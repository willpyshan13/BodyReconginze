import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import cv2
import numpy as np

from collector import Dataset, MODEL, export_rule


class CollectionWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.data = Dataset(self.root / 'data')

    def tearDown(self):
        self.tmp.cleanup()

    def video(self, suffix):
        path = self.root / f'{suffix}.mp4'
        writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*'mp4v'), 10, (160, 256))
        self.assertTrue(writer.isOpened())
        for i in range(10):
            frame = np.full((256, 160, 3), 200, dtype=np.uint8)
            cv2.rectangle(frame, (50 + i, 35), (110 + i, 230), (40 + suffix * 20, 50, 80), -1)
            writer.write(frame)
        writer.release()
        return path

    def imported(self, number, person=None):
        return self.data.import_video(self.video(number), f'{number}.mp4', 'squat',
                                      person or f'P{number}', 'side', 'normal')

    def annotate(self, sample, **kwargs):
        payload = {'action': 'squat', 'start': 0, 'end': .8, 'count': 2, 'note': '', 'reviewed': True}
        payload.update(kwargs)
        return self.data.annotate(sample['id'], payload)

    def test_annotation_validation_and_reliable_person_split(self):
        samples = [self.imported(i) for i in (1, 2, 3)]
        with self.assertRaises(ValueError): self.annotate(samples[0], start=.8, end=.1)
        with self.assertRaises(ValueError): self.annotate(samples[0], end=100)
        with self.assertRaises(ValueError): self.annotate(samples[0], reviewed='false')
        with self.assertRaises(ValueError): self.data.export()
        self.assertTrue(MODEL.exists())
        for sample in samples:
            self.annotate(sample)
            pose = self.data.extract(sample['id'])
            self.assertGreater(pose['frame_count'], 0)
            self.assertEqual('COCO-17', pose['keypoint_layout'])
            frames = [json.loads(x) for x in (self.data.root / 'poses' / pose['file']).read_text().splitlines()]
            self.assertEqual(17, len(frames[0]['keypoints']))
            self.assertTrue(all(a['timestamp_seconds'] < b['timestamp_seconds'] for a,b in zip(frames, frames[1:])))
            self.assertTrue(all(p['visible'] or (p['x'] is None and p['y'] is None) for f in frames for p in f['keypoints']))
        result = self.data.export()
        with zipfile.ZipFile(result['path']) as archive:
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual({'train', 'validation', 'test'}, {s['split'] for s in manifest['samples']})
            self.assertEqual(3, len(manifest['samples']))
            self.assertFalse(any(n.startswith('videos/') for n in archive.namelist()))
            self.assertEqual(3, sum(n.startswith('poses/') for n in archive.namelist()))
        second = self.data.export(include_video=True)
        with zipfile.ZipFile(second['path']) as archive:
            self.assertEqual(3, sum(n.startswith('videos/') for n in archive.namelist()))
        self.annotate(samples[0], note='修改过标签')
        _, changed = self.data.get(samples[0]['id'])
        self.assertIn('标注已更改，请重新提取关键点', self.data.checks(changed))
        with self.assertRaisesRegex(ValueError, '至少需要 3 位'):
            self.data.export()
        duplicate = self.data.import_video(self.root / '2.mp4', 'duplicate.mp4', 'squat', 'P-other', 'side', 'normal')
        self.annotate(duplicate)
        self.data.extract(duplicate['id'])
        with self.assertRaisesRegex(ValueError, '同一视频被分配给不同拍摄者'):
            self.data.export()

    def test_unknown_person_and_unreadable_video_are_rejected(self):
        path = self.video(1)
        with self.assertRaises(ValueError): self.data.import_video(path, 'x.mp4', 'squat', '', 'side', 'normal')
        invalid = self.root / 'broken.mp4'; invalid.write_bytes(b'not video')
        with self.assertRaises(ValueError): self.data.import_video(invalid, 'broken.mp4', 'squat', 'P1', 'side', 'normal')
        self.assertEqual([], self.data.read()['samples'])

    def test_manual_rule_validated_and_cannot_overwrite(self):
        path = self.root / 'rule.json'
        result = json.loads(export_rule('squat', 90, 160, path).read_text())
        self.assertEqual([11, 13, 15], result['keypoints']['left'])
        self.assertEqual('manual_configuration', result['source'])
        with self.assertRaises(ValueError): export_rule('squat', 90, 160, path)
        with self.assertRaises(ValueError): export_rule('squat', 160, 90, self.root / 'bad.json')


if __name__ == '__main__':
    unittest.main()
