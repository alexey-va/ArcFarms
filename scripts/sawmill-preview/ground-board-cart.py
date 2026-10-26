#!/usr/bin/env python3
"""Check exported board-cart contacts and the representative internal route."""
import hashlib
import itertools
import json
import math
import subprocess
from pathlib import Path

project = Path(__file__).resolve().parents[2]
preview = project / 'build/reports/sawmill-preview'
out = preview / 'grounding'
fixture = out / 'ItemsAdder'
source = project / 'scripts/sawmill-preview'
export_path = preview / 'lumber-export.json'
export = json.loads(export_path.read_text())
manifest = json.loads((source / 'native-models/models.manifest.json').read_text())
skill = Path.home() / '.codex/skills/itemsadder-item-display-grounding'
cases = []
for second, prefix, support_prefix in [(109, 'timber-', 'board-cart-bolster-'), (117, 'stored-timber-', 'storage-bearer-')]:
    frame = export['frames'][round(second * 10)]
    surface = max(part['center'][1] + part['size'][1] / 2 for part in frame['parts'] if part['id'].startswith(support_prefix))
    parts = [part for part in frame['parts'] if part['id'].startswith(prefix) and part.get('itemModel')]
    assert len(parts) == 10
    for part in parts:
        support = surface + ((.175 + .28) * 1.7 if 'final-' in part['id'] else 0)
        report = out / f'boards-{second}-{part["id"]}.json'
        command = ['python3', '-B', str(skill / 'scripts/analyze_itemsadder_display.py'),
                   '--itemsadder-root', str(fixture), '--resource-pack', str(fixture / 'contents/arc_farms/resourcepack'),
                   '--item-id', part['itemModel'], '--context', 'none',
                   '--entity-position=' + ','.join(map(str, part['center'])),
                   '--scale=' + ','.join(map(str, part['size'])),
                   '--left-quaternion=' + ','.join(map(str, part['rotation'])),
                   '--surface-y', str(support), '--output', str(report)]
        subprocess.run(command, check=True, capture_output=True)
        result = json.loads(report.read_text())
        residual = result['contact']['current_min_residual']
        assert result['status'] == 'grounded' and abs(residual) <= 1e-4, (second, part['id'], residual)
        cases.append({'time': second, 'part': part['id'], 'surfaceY': support,
                      'status': result['status'], 'residual': residual,
                      'modelSha256': result['model_provenance'][0]['sha256'], 'report': str(report)})

# This route is straight: all exported rotations keep boxes axis aligned.
# Full captured cells are a conservative solid oracle; no foliage exemption.
capture = json.loads((source / 'lumber-area.snapshot.json').read_text())
cells = {tuple(block[:3]): block[3] for block in capture['blocks']}
overlay = json.loads((source / 'lumber-live-readback.json').read_text())
for position in overlay['air']:
    cells.pop(tuple(position), None)

def transformed_boxes(part):
    x, y, z, w = part['rotation']
    norm = x*x + y*y + z*z + w*w
    rotation = [[1-2*(y*y+z*z)/norm, 2*(x*y-w*z)/norm, 2*(x*z+w*y)/norm],
                [2*(x*y+w*z)/norm, 1-2*(x*x+z*z)/norm, 2*(y*z-w*x)/norm],
                [2*(x*z-w*y)/norm, 2*(y*z+w*x)/norm, 1-2*(x*x+y*y)/norm]]
    boxes = manifest['models'][part['itemModel'].rsplit('_', 1)[1]]['boxes'] if part.get('itemModel') else [{'min': [-.5]*3, 'max': [.5]*3}]
    for box in boxes:
        points = [[part['center'][axis] + sum(rotation[axis][j]*point[j]*part['size'][j] for j in range(3)) for axis in range(3)]
                  for point in itertools.product(*zip(box['min'], box['max']))]
        yield [min(point[axis] for point in points) for axis in range(3)], [max(point[axis] for point in points) for axis in range(3)]

contacts = []
checked = 0
for frame in export['frames'][1090:1171]:
    for part in frame['parts']:
        if not part['visible'] or not part['id'].startswith(('board-cart-', 'timber-', 'stored-', 'storage-bearer-')):
            continue
        for lower, upper in transformed_boxes(part):
            checked += 1
            for position in itertools.product(*(range(math.floor(lower[axis] + 1e-4), math.ceil(upper[axis] - 1e-4)) for axis in range(3))):
                block = cells.get(position, 'minecraft:air')
                if block not in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'):
                    contacts.append({'time': frame['t'], 'part': part['id'], 'position': position, 'block': block})
summary = {'scope': 'Exact native ItemDisplay contacts and conservative captured-block route; not a current native-client observation.',
           'compiledExportSha256': hashlib.sha256(export_path.read_bytes()).hexdigest(),
           'cases': cases, 'route': {'seconds': [109, 117], 'frames': 81, 'modelBoxesChecked': checked,
                                    'solidContacts': contacts, 'usesFullCapturedCells': True,
                                    'captureSha256': capture['sourceSha256'], 'liveOverlay': overlay['verifiedAt']}}
(out / 'board-cart-grounding-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
assert not contacts, contacts[:8]
print(f'Grounded {len(cases)} native board placements; {checked} boxes across81 exported frames clear captured blocks.')
