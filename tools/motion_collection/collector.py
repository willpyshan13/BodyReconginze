#!/usr/bin/env python3
"""Local-only motion collection service. Data are stored outside the source tree by default."""
import argparse
import hashlib
import json
import mimetypes
import os
import random
import re
import shutil
import tempfile
import threading
import uuid
import zipfile
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

HERE = Path(__file__).resolve().parent
MODEL = HERE.parents[1] / 'demo-android/src/main/assets/rtmpose_t.onnx'
ACTIONS = {'squat': '深蹲', 'push_up': '俯卧撑', 'arm_raise': '双臂上举', 'other': '其他行为'}
JOINTS = ['nose', 'left_eye', 'right_eye', 'left_ear', 'right_ear', 'left_shoulder',
          'right_shoulder', 'left_elbow', 'right_elbow', 'left_wrist', 'right_wrist',
          'left_hip', 'right_hip', 'left_knee', 'right_knee', 'left_ankle', 'right_ankle']
LOCK = threading.RLock()
MAX_UPLOAD = 500 * 1024 * 1024


def now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


class Dataset:
    def __init__(self, root):
        self.root = Path(root).expanduser().resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        for name in ('videos', 'poses', 'exports'):
            (self.root / name).mkdir(exist_ok=True)
        self.index = self.root / 'samples.json'
        self.model_hash = None

    def read(self):
        if not self.index.exists():
            return {'version': 1, 'samples': []}
        data = json.loads(self.index.read_text(encoding='utf-8'))
        if data.get('version') != 1 or not isinstance(data.get('samples'), list):
            raise ValueError('数据目录格式不受支持')
        return data

    def write(self, data):
        tmp = self.index.with_suffix('.tmp')
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
        os.replace(tmp, self.index)

    def get(self, sample_id):
        if not re.fullmatch(r'[0-9a-f]{32}', sample_id):
            raise ValueError('无效的样本编号')
        data = self.read()
        sample = next((s for s in data['samples'] if s['id'] == sample_id), None)
        if sample is None:
            raise ValueError('找不到样本')
        return data, sample

    def import_video(self, source, name, action, person, angle, tempo):
        import cv2
        if action not in ACTIONS or not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', person or ''):
            raise ValueError('请选择动作并填写有效的拍摄者编号（字母、数字、下划线或短横线）')
        if angle not in ('front', 'side', 'oblique') or tempo not in ('normal', 'slow', 'fast'):
            raise ValueError('拍摄角度或节奏无效')
        extension = Path(name).suffix.lower()
        if extension not in ('.mp4', '.mov', '.webm', '.m4v'):
            raise ValueError('支持 MP4、MOV、M4V 和 WebM 视频')
        sample_id = uuid.uuid4().hex
        target = self.root / 'videos' / (sample_id + extension)
        shutil.copyfile(source, target)
        try:
            capture = cv2.VideoCapture(str(target))
            ok, frame = capture.read()
            fps = float(capture.get(cv2.CAP_PROP_FPS))
            total = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
            capture.release()
            if not ok or frame is None or not 0 < fps < 1000 or total <= 0:
                raise ValueError('无法读取视频时长或画面，请尝试转换为标准 MP4')
            duration = total / fps
            sample = {'id': sample_id, 'original_name': Path(name).name[:200], 'file': target.name,
                      'sha256': sha256(target), 'created_at': now(), 'person': person,
                      'action': action, 'angle': angle, 'tempo': tempo, 'duration_seconds': duration,
                      'width': int(frame.shape[1]), 'height': int(frame.shape[0]), 'fps': fps,
                      'annotation': None, 'pose': None}
            with LOCK:
                data = self.read()
                data['samples'].append(sample)
                self.write(data)
            return sample
        except Exception:
            target.unlink(missing_ok=True)
            raise

    def annotate(self, sample_id, payload):
        with LOCK:
            data, sample = self.get(sample_id)
            action = payload.get('action')
            start, end = float(payload['start']), float(payload['end'])
            import math
            if action not in ACTIONS or not math.isfinite(start) or not math.isfinite(end) or start < 0 or end <= start or end > sample['duration_seconds'] + 0.05:
                raise ValueError('动作或时间范围无效：起点须早于终点，且不能超过视频时长')
            count = payload.get('count')
            if count not in ('', None):
                if not str(count).isdigit() or int(count) > 10000:
                    raise ValueError('完整次数须为非负整数')
                count = int(count)
            else:
                count = None
            if not isinstance(payload.get('reviewed'), bool):
                raise ValueError('请明确标注是否已人工检查')
            sample['annotation'] = {'action': action, 'start_seconds': start, 'end_seconds': end,
                                    'count': count, 'note': str(payload.get('note', ''))[:500],
                                    'reviewed': bool(payload.get('reviewed')), 'updated_at': now(), 'source': 'human'}
            self.write(data)
            return sample

    def extract(self, sample_id):
        import cv2
        import numpy as np
        import onnxruntime as ort
        with LOCK:
            _, sample = self.get(sample_id)
            if not sample['annotation']:
                raise ValueError('请先保存人工标注')
            video = self.root / 'videos' / sample['file']
            annotation = sample['annotation'].copy()
        if not MODEL.is_file():
            raise ValueError('缺少项目内置的 RTMPose 模型')
        model_hash = sha256(MODEL)
        session = ort.InferenceSession(str(MODEL), providers=['CPUExecutionProvider'])
        inp = session.get_inputs()[0].name
        capture = cv2.VideoCapture(str(video))
        fps = sample['fps']
        target_step = max(1, round(fps / 5.0))
        temp = self.root / 'poses' / (sample_id + '.jsonl.tmp')
        dest = self.root / 'poses' / (sample_id + '.jsonl')
        count = missing = index = 0
        last_timestamp = -1.0
        try:
            with temp.open('w', encoding='utf-8') as output:
                while True:
                    ok, frame = capture.read()
                    if not ok:
                        break
                    timestamp = float(capture.get(cv2.CAP_PROP_POS_MSEC)) / 1000.0
                    if not np.isfinite(timestamp) or timestamp <= last_timestamp:
                        timestamp = index / fps
                    if timestamp <= last_timestamp:
                        raise ValueError('视频时间戳不连续，无法可靠提取')
                    last_timestamp = timestamp
                    if timestamp > annotation['end_seconds'] + 1 / fps:
                        break
                    if index % target_step == 0 and annotation['start_seconds'] <= timestamp <= annotation['end_seconds']:
                        height, width = frame.shape[:2]
                        scale_w = max(width * 1.25, height * 1.25 * 192 / 256)
                        scale_h = scale_w * 256 / 192
                        left, top = (width - scale_w) / 2, (height - scale_h) / 2
                        transform = np.array([[192 / scale_w, 0, -left * 192 / scale_w],
                                              [0, 256 / scale_h, -top * 256 / scale_h]], dtype=np.float32)
                        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                        crop = cv2.warpAffine(rgb, transform, (192, 256), flags=cv2.INTER_LINEAR,
                                              borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
                        tensor = ((crop.astype(np.float32) - [123.675, 116.28, 103.53]) /
                                  [58.395, 57.12, 57.375]).astype(np.float32).transpose(2, 0, 1)[None]
                        xs, ys = session.run(None, {inp: tensor})[:2]
                        if xs.shape != (1, 17, 384) or ys.shape != (1, 17, 512):
                            raise ValueError('模型输出格式与预期不一致')
                        points = []
                        for joint in range(17):
                            xb, yb = int(np.argmax(xs[0, joint])), int(np.argmax(ys[0, joint]))
                            x = xb / 2 / 192 * scale_w + left
                            y = yb / 2 / 256 * scale_h + top
                            score = float(min(xs[0, joint, xb], ys[0, joint, yb]))
                            visible = bool(np.isfinite(score) and score >= .3 and 0 <= x < width and 0 <= y < height)
                            if not visible:
                                missing += 1
                            points.append({'name': JOINTS[joint], 'x': round(x / width, 6) if visible else None,
                                           'y': round(y / height, 6) if visible else None,
                                           'score': round(score, 6) if np.isfinite(score) else None, 'visible': visible})
                        output.write(json.dumps({'frame_index': index, 'timestamp_seconds': round(timestamp, 6),
                                                 'keypoints': points}, ensure_ascii=False, allow_nan=False) + '\n')
                        count += 1
                    index += 1
            if not count:
                raise ValueError('标注区间没有可提取的视频帧')
            if last_timestamp + 2 / fps < annotation['end_seconds']:
                raise ValueError('视频解码提前中断，未覆盖标注片段')
            with LOCK:
                data, latest = self.get(sample_id)
                if latest['annotation'] != annotation:
                    raise ValueError('标注在提取期间已修改，请重新提取')
                os.replace(temp, dest)
                latest['pose'] = {'file': dest.name, 'frame_count': count, 'missing_keypoints': missing,
                                  'sha256': sha256(dest),
                                  'sample_interval_frames': target_step, 'timestamp_basis': 'opencv_pos_msec_with_frame_fps_fallback',
                                  'coordinate_system': 'normalized_image_xy_top_left', 'keypoint_layout': 'COCO-17',
                                  'model': 'RTMPose-t BODY7', 'model_sha256': model_hash,
                                  'input': 'whole_frame_single_person_1.25_padding_192x256',
                                  'visibility_threshold': .3, 'opencv_version': cv2.__version__,
                                  'onnxruntime_version': ort.__version__, 'created_at': now(),
                                  'annotation_updated_at': annotation['updated_at']}
                self.write(data)
            return latest['pose']
        finally:
            capture.release()
            temp.unlink(missing_ok=True)

    def checks(self, sample):
        problems = []
        video = self.root / 'videos' / sample['file']
        if not video.is_file() or sha256(video) != sample['sha256']:
            problems.append('原视频缺失或内容已改变')
        annotation = sample.get('annotation')
        if not annotation:
            problems.append('缺少人工标注')
        elif not annotation.get('reviewed'):
            problems.append('标注待人工确认')
        pose = sample.get('pose')
        if not pose or not (self.root / 'poses' / pose['file']).is_file():
            problems.append('未提取关键点')
        elif not pose.get('frame_count') or sha256(self.root / 'poses' / pose['file']) != pose.get('sha256'):
            problems.append('关键点数据缺失或已改变，请重新提取')
        elif annotation and pose.get('annotation_updated_at') != annotation.get('updated_at'):
            problems.append('标注已更改，请重新提取关键点')
        elif pose.get('missing_keypoints', 0):
            problems.append('含缺失关键点，请检查')
        return problems

    def export(self, include_video=False, seed=42):
        with LOCK:
            samples = self.read()['samples']
            if not samples:
                raise ValueError('没有可导出的样本')
            # Missing landmarks are retained explicitly and require the user's review; they are not fabricated.
            eligible = [s for s in samples if not [p for p in self.checks(s) if p != '含缺失关键点，请检查']]
            if not eligible:
                raise ValueError('没有可导出的样本：请先确认标注并提取关键点')
            identities = {}
            for sample in eligible:
                previous = identities.setdefault(sample['sha256'], sample['person'])
                if previous != sample['person']:
                    raise ValueError('同一视频被分配给不同拍摄者，无法可靠划分数据集')
            people = sorted({s['person'] for s in eligible})
            if len(people) < 3:
                raise ValueError('至少需要 3 位不同拍摄者，才能按人划分训练、验证和测试集合')
            random.Random(seed).shuffle(people)
            n_test = max(1, round(len(people) * .2))
            n_val = max(1, round(len(people) * .2))
            split = {person: ('test' if i < n_test else 'validation' if i < n_test + n_val else 'train')
                     for i, person in enumerate(people)}
            manifest = {'schema_version': 1, 'created_at': now(), 'seed': seed, 'split_by': 'person',
                        'actions': ACTIONS, 'samples': [], 'note': '训练数据，不是训练好的模型；不报告模型准确率。'}
            classes = {s['annotation']['action'] for s in eligible}
            warnings = []
            for group in ('train', 'validation', 'test'):
                present = {s['annotation']['action'] for s in eligible if split[s['person']] == group}
                absent = sorted(classes - present)
                if absent:
                    warnings.append(f'{group} 集缺少类别：' + '、'.join(ACTIONS[x] for x in absent))
            manifest['warnings'] = warnings
            name = 'motion-dataset-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6] + '.zip'
            target = self.root / 'exports' / name
            temp = target.with_suffix('.zip.tmp')
            try:
                with zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_DEFLATED) as z:
                    for sample in eligible:
                        item = dict(sample)
                        item['split'] = split[sample['person']]
                        item['checks'] = self.checks(sample)
                        item['video_included'] = bool(include_video)
                        manifest['samples'].append(item)
                        z.write(self.root / 'poses' / sample['pose']['file'], 'poses/' + sample['pose']['file'])
                        if include_video:
                            z.write(self.root / 'videos' / sample['file'], 'videos/' + sample['file'])
                    z.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2))
                os.replace(temp, target)
            finally:
                temp.unlink(missing_ok=True)
            return {'filename': name, 'path': str(target), 'sample_count': len(eligible),
                    'excluded_count': len(samples) - len(eligible), 'people_count': len(people), 'warnings': warnings}


def make_handler(dataset):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def send_json(self, obj, status=200):
            data = json.dumps(obj, ensure_ascii=False, allow_nan=False).encode('utf-8')
            self.send_response(status); self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data))); self.send_header('Cache-Control', 'no-store')
            self.end_headers(); self.wfile.write(data)

        def safe_request(self):
            host = self.headers.get('Host', '').split(':')[0].lower()
            origin = self.headers.get('Origin')
            if host not in ('127.0.0.1', 'localhost'):
                raise ValueError('仅允许本机访问')
            if origin and urlsplit(origin).netloc != self.headers.get('Host'):
                raise ValueError('不允许来自其他网站的请求')

        def do_GET(self):
            try:
                self.safe_request()
                path = urlsplit(self.path).path
                if path == '/api/samples':
                    with LOCK:
                        data = dataset.read()
                        for s in data['samples']:
                            s['checks'] = dataset.checks(s)
                    return self.send_json({'samples': data['samples'], 'data_dir': str(dataset.root)})
                if path.startswith('/video/'):
                    _, sample = dataset.get(path.rsplit('/', 1)[-1])
                    file = dataset.root / 'videos' / sample['file']
                    # Range responses allow the browser to seek imported MP4/WebM videos.
                    size = file.stat().st_size
                    start, end = 0, size - 1
                    header = self.headers.get('Range')
                    if header:
                        match = re.fullmatch(r'bytes=(\d+)-(\d*)', header)
                        if not match:
                            self.send_error(416); return
                        start = int(match.group(1)); end = min(int(match.group(2)) if match.group(2) else end, end)
                        if start > end:
                            self.send_error(416); return
                    self.send_response(206 if header else 200)
                    self.send_header('Content-Type', mimetypes.guess_type(file.name)[0] or 'application/octet-stream')
                    self.send_header('Accept-Ranges', 'bytes'); self.send_header('Content-Length', str(end-start+1))
                    if header: self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                    self.end_headers()
                    with file.open('rb') as f:
                        f.seek(start); remaining = end-start+1
                        while remaining:
                            chunk = f.read(min(1024*1024, remaining))
                            if not chunk: break
                            self.wfile.write(chunk); remaining -= len(chunk)
                    return
                if path not in ('/', '/app.js', '/style.css'):
                    self.send_error(404); return
                file = HERE / ('index.html' if path == '/' else path[1:])
                content = file.read_bytes()
                self.send_response(200); self.send_header('Content-Type', mimetypes.guess_type(file.name)[0] + '; charset=utf-8')
                self.send_header('Content-Length', str(len(content))); self.send_header('Cache-Control', 'no-store')
                self.end_headers(); self.wfile.write(content)
            except (BrokenPipeError, ConnectionResetError):
                pass  # Browser may cancel a video request while seeking or leaving the page.
            except (ValueError, OSError) as e:
                self.send_json({'error': str(e)}, 400)

        def do_POST(self):
            temp = None
            try:
                self.safe_request()
                path = urlsplit(self.path).path
                length = int(self.headers.get('Content-Length', '0'))
                if length <= 0 or length > MAX_UPLOAD:
                    raise ValueError('请求为空或文件超过 500 MB')
                if path == '/api/import':
                    params = parse_qs(urlsplit(self.path).query)
                    with tempfile.NamedTemporaryFile(dir=dataset.root, delete=False) as f:
                        temp = Path(f.name); remaining = length
                        while remaining:
                            chunk = self.rfile.read(min(1024*1024, remaining))
                            if not chunk: raise ValueError('上传中断')
                            f.write(chunk); remaining -= len(chunk)
                    result = dataset.import_video(temp, params.get('name', [''])[0], params.get('action', [''])[0],
                                                  params.get('person', [''])[0], params.get('angle', [''])[0], params.get('tempo', [''])[0])
                else:
                    if length > 100_000: raise ValueError('请求过大')
                    payload = json.loads(self.rfile.read(length))
                    if path == '/api/annotate': result = dataset.annotate(payload['id'], payload)
                    elif path == '/api/extract': result = dataset.extract(payload['id'])
                    elif path == '/api/export': result = dataset.export(bool(payload.get('include_video')), 42)
                    else: self.send_error(404); return
                self.send_json(result)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except (ValueError, KeyError, OSError, ImportError, json.JSONDecodeError) as e:
                self.send_json({'error': str(e)}, 400)
            except Exception as e:
                self.send_json({'error': f'处理失败：{e}'}, 500)
            finally:
                if temp: temp.unlink(missing_ok=True)
    return Handler


def export_rule(action, low, high, output):
    import math
    config = {'squat': ('深蹲', [11, 13, 15], [12, 14, 16]),
              'push_up': ('俯卧撑', [5, 7, 9], [6, 8, 10]),
              'arm_raise': ('双臂上举', [7, 5, 11], [8, 6, 12])}.get(action)
    if not config or not math.isfinite(low) or not math.isfinite(high) or not 0 <= low < high <= 180:
        raise ValueError('请选择支持的动作，且角度必须满足 0 ≤ 低阈值 < 高阈值 ≤ 180')
    result = {'schema_version': 1, 'source': 'manual_configuration', 'keypoint_layout': 'COCO-17',
              'action': action, 'name_zh': config[0], 'keypoints': {'left': config[1], 'right': config[2]},
              'low_angle_degrees': low, 'high_angle_degrees': high,
              'note': '人工填写的演示规则，不是训练模型；Android 需实现对应规则执行器并验证阈值。'}
    target = Path(output).expanduser().resolve()
    if target.exists(): raise ValueError('目标文件已存在，请指定新的输出路径')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return target


def main():
    parser = argparse.ArgumentParser(description='Local motion collection studio')
    parser.add_argument('--data-dir', default=str(HERE / 'data'), help='directory for local videos, annotations, poses and exports')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--rule-action', choices=('squat', 'push_up', 'arm_raise'))
    parser.add_argument('--low-angle', type=float)
    parser.add_argument('--high-angle', type=float)
    parser.add_argument('--rule-output')
    args = parser.parse_args()
    if args.rule_action or args.low_angle is not None or args.high_angle is not None or args.rule_output:
        if not args.rule_action or args.low_angle is None or args.high_angle is None or not args.rule_output:
            parser.error('导出规则时需要 --rule-action、--low-angle、--high-angle、--rule-output')
        try:
            print('动作规则已保存：', export_rule(args.rule_action, args.low_angle, args.high_angle, args.rule_output))
        except ValueError as exc:
            parser.error(str(exc))
        return
    dataset = Dataset(args.data_dir)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), make_handler(dataset))
    print(f'动作采集工作台：http://127.0.0.1:{server.server_port}/', flush=True)
    print(f'本地数据目录：{dataset.root}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
