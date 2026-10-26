#!/usr/bin/env python3
"""Exact existing-item contacts plus swept cargo/crane bounds against the maintained world capture."""
import hashlib
import itertools
import json
import math
import subprocess
from pathlib import Path

project = Path(__file__).resolve().parents[2]
preview = project / 'build/reports/sawmill-preview'
out = preview / 'grounding'
source = project / 'scripts/sawmill-preview'
export_path = preview / 'lumber-export.json'
export = json.loads(export_path.read_text())
runtime = json.loads((preview / 'runtime-export.json').read_text())
manifest = json.loads((source / 'native-models/models.manifest.json').read_text())
capture = json.loads((source / 'lumber-area.snapshot.json').read_text())
overlay = json.loads((source / 'lumber-live-readback.json').read_text())
cells = {tuple(p[:3]): p[3] for p in capture['blocks']}
for p in overlay['air']:
    cells.pop(tuple(p), None)


def boxes(part):
    x, y, z, w = part['rotation']
    n = x*x+y*y+z*z+w*w
    matrix = [[1-2*(y*y+z*z)/n, 2*(x*y-w*z)/n, 2*(x*z+w*y)/n],
              [2*(x*y+w*z)/n, 1-2*(x*x+z*z)/n, 2*(y*z-w*x)/n],
              [2*(x*z-w*y)/n, 2*(y*z+w*x)/n, 1-2*(x*x+y*y)/n]]
    shapes = manifest['models'][part['itemModel'].rsplit('_', 1)[1]]['boxes'] if part.get('itemModel') else [{'min': [-.5]*3, 'max': [.5]*3}]
    for shape in shapes:
        points = [[part['center'][a] + sum(matrix[a][j]*p[j]*part['size'][j] for j in range(3)) for a in range(3)]
                  for p in itertools.product(*zip(shape['min'], shape['max']))]
        yield [min(p[a] for p in points) for a in range(3)], [max(p[a] for p in points) for a in range(3)]


def overlaps(a, b):
    return all(min(a[1][i], b[1][i]) - max(a[0][i], b[0][i]) > 1e-4 for i in range(3))


machine = []
tables = []
for pose in runtime['passes'][1][-1][8]:
    part = runtime['parts'][pose[0]]
    if not pose[1] or part['motion'].startswith('timber-'):
        continue
    item = {'id': part['id'], 'center': [pose[2+i] + runtime['origin'][i] for i in range(3)],
            'size': pose[5:8], 'rotation': pose[8:12], 'itemModel': part.get('itemModel')}
    machine.extend((part['id'], box) for box in boxes(item))
    if part['id'].startswith('outfeed-'):
        tables.append(item)

contacts = []
checked = 0


def captured_contacts(part_id, box, time):
    global checked
    checked += 1
    lower, upper = box
    for p in itertools.product(*(range(math.floor(lower[a]+1e-4), math.ceil(upper[a]-1e-4)) for a in range(3))):
        block = cells.get(p, 'minecraft:air')
        if block not in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'):
            contacts.append({'time': time, 'part': part_id, 'position': p, 'block': block})


for part in tables:
    for box in boxes(part):
        captured_contacts(part['id'], box, 'static-west-table')

frames = [f for f in export['frames'] if f['phase'] == 'CRANE_TRANSFER' or any(p['id'].startswith('stored-timber-') for p in f['parts'])]
previous = {}
for frame in frames:
    current = {}
    crane_boxes = [(p['id'], box) for p in frame['parts'] if p['id'].startswith('crane-') for box in boxes(p)]
    for part in frame['parts']:
        if not part['visible'] or not part['id'].startswith(('timber-', 'crane-', 'stored-', 'storage-bearer-')):
            continue
        shapes = list(boxes(part))
        current[part['id']] = shapes
        for index, box in enumerate(shapes):
            prior = previous.get(part['id'], shapes)[index]
            # Fixed-orientation translations have an exact swept AABB between the exported endpoints.
            swept = ([min(box[0][a], prior[0][a]) for a in range(3)], [max(box[1][a], prior[1][a]) for a in range(3)])
            captured_contacts(part['id'], swept, frame['t'])
            if part['id'].startswith('timber-'):
                for machine_id, machine_box in machine:
                    if overlaps(swept, machine_box):
                        contacts.append({'time': frame['t'], 'part': part['id'], 'machine': machine_id})
                for prop_id, prop_box in crane_boxes:
                    if overlaps(box, prop_box):
                        contacts.append({'time': frame['t'], 'part': part['id'], 'crane': prop_id})
    previous = current

skill = Path.home() / '.codex/skills/itemsadder-item-display-grounding'
items = out / 'ItemsAdder'
contact_frames = [frames[0], next(f for f in frames if f['phase'] == 'CRANE_TRANSFER' and f['phaseAge'] == 60),
                  next(f for f in frames if f['phase'] == 'IDLE')]
cases = []
for frame in contact_frames:
    prefix = 'stored-timber-' if frame['phase'] == 'IDLE' else 'timber-'
    support = max(p['center'][1]+p['size'][1]/2 for p in frame['parts']
                  if p['id'].startswith('storage-bearer-' if frame['phase'] == 'IDLE' else 'crane-sling-'))
    for part in frame['parts']:
        if not part['id'].startswith(prefix) or not part.get('itemModel'):
            continue
        surface = support + ((.175+.28)*1.7 if 'final-' in part['id'] else 0)
        report = out / f'crane-{frame["t"]}-{part["id"]}.json'
        command = ['python3', '-B', str(skill / 'scripts/analyze_itemsadder_display.py'),
                   '--itemsadder-root', str(items), '--resource-pack', str(items / 'contents/arc_farms/resourcepack'),
                   '--item-id', part['itemModel'], '--context', 'none',
                   '--entity-position='+','.join(map(str, part['center'])), '--scale='+','.join(map(str, part['size'])),
                   '--left-quaternion='+','.join(map(str, part['rotation'])), '--surface-y', str(surface), '--output', str(report)]
        subprocess.run(command, check=True, capture_output=True)
        result = json.loads(report.read_text())
        residual = result['contact']['current_min_residual']
        assert result['status'] == 'grounded' and abs(residual) <= 1e-4, (part['id'], residual)
        cases.append({'time': frame['t'], 'part': part['id'], 'surfaceY': surface, 'residual': residual,
                      'modelSha256': result['model_provenance'][0]['sha256'], 'report': str(report)})

summary = {'scope': 'Exact native cut-face-up pieces, west output, suspended slings and internal warehouse. Capture is not current client acceptance.',
           'compiledExportSha256': hashlib.sha256(export_path.read_bytes()).hexdigest(),
           'captureSha256': capture['sourceSha256'], 'liveOverlay': overlay['verifiedAt'],
           'cases': cases, 'sweep': {'frames': len(frames), 'boxes': checked, 'contacts': contacts,
                                    'oracle': 'Conservative full captured cells and exact static machine model bounds'}}
(out / 'crane-grounding-summary.json').write_text(json.dumps(summary, indent=2)+'\n')
assert not contacts, contacts[:12]
print(f'Crane: {len(cases)} grounded native placements; {len(frames)} frames/{checked} swept boxes; no contacts.')
