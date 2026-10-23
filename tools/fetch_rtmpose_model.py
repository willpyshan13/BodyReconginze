"""Restore the exact official ONNX model used by the Android comparison build."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

root = Path(__file__).resolve().parent.parent
source = json.loads((root / 'assets/rtmpose/source.json').read_text())
with urllib.request.urlopen(source['url'], timeout=60) as response:
    archive = response.read()
with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
    names = [name for name in bundle.namelist() if name.endswith('/end2end.onnx')]
    if len(names) != 1:
        raise ValueError('Expected exactly one end2end.onnx model')
    model = bundle.read(names[0])
if hashlib.sha256(model).hexdigest() != source['sha256']:
    raise ValueError('Official model SHA256 does not match pinned version')
target = root / 'demo-android/src/main/assets/rtmpose_t.onnx'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(model)
print(f'Model verified and saved: {target}')
