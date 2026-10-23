"""Restore the official pinned MoveNet Lightning TFLite model for offline Android inference."""
import hashlib
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parent.parent
source = json.loads((root / 'assets/movenet/source.json').read_text())
with urllib.request.urlopen(source['url'], timeout=90) as response:
    model = response.read()
if model[4:8] != b'TFL3' or hashlib.sha256(model).hexdigest() != source['sha256']:
    raise ValueError('Downloaded file does not match the pinned MoveNet TFLite model')
target = root / 'demo-android/src/main/assets/movenet_lightning.tflite'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(model)
print(f'Model verified and saved: {target}')
