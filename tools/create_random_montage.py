"""Create a reproducible 60-second randomized pose montage timeline."""
import json
import random
from pathlib import Path

root = Path(__file__).resolve().parent.parent
rng = random.Random(20261020)
names = ['squat', 'arm_raise', 'bicep_curl', 'push_up', 'lateral_raise']
fps, total = 24, 60 * 24
segments = []
remaining = total
previous = None
while remaining:
    choices = [name for name in names if name != previous]
    action = rng.choice(choices)
    row = names.index(action)
    # Vary the repetitions and hold times; retain hard cuts for unambiguous poses.
    for repetition in range(rng.randint(1, 3)):
        for column in (0, 1, 2):
            if not remaining:
                break
            length = min(remaining, rng.randint(9, 24) if column != 1 else rng.randint(12, 32))
            segments.append(dict(action=action, row=row, column=column, frames=length))
            remaining -= length
        if not remaining:
            break
    if remaining and rng.random() < .5:
        length = min(remaining, rng.randint(12, 35))
        segments.append(dict(action='rest', row=rng.choice([0, 1, 2, 4]), column=0, frames=length))
        remaining -= length
    previous = action
plan = dict(fps=fps, width=640, height=720, duration_seconds=60, seed=20261020, segments=segments)
(root / 'assets/random_montage_timeline.json').write_text(json.dumps(plan, indent=2) + '\n')
print(f'{len(segments)} pose segments, {sum(s["frames"] for s in segments)} frames, {total / fps:.0f} seconds')
