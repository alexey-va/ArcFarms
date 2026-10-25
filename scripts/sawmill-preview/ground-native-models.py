#!/usr/bin/env python3
"""Reproduce exact native ItemDisplay placement using the required grounding skill."""
import argparse
import hashlib
import json
import math
import shutil
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--client-jar', type=Path, required=True)
parser.add_argument('--skill', type=Path, default=Path.home() / '.codex/skills/itemsadder-item-display-grounding')
parser.add_argument('--export', type=Path)
args = parser.parse_args()
project = Path(__file__).resolve().parents[2]
out = project / 'build/reports/sawmill-preview/grounding'
out.mkdir(parents=True, exist_ok=True)
native = project / 'scripts/sawmill-preview/native-models'
manifest = json.loads((native / 'models.manifest.json').read_text())
kinds = list(manifest['models'])
export = json.loads((args.export or out.parent / 'runtime-export.json').read_text())
scene_scale = export.get('sceneScale', 1)
root = out / 'ItemsAdder'
content = root / 'contents/arc_farms'
(content / 'configs').mkdir(parents=True, exist_ok=True)
(root / 'storage').mkdir(parents=True, exist_ok=True)
shutil.copy(native / 'items.yml', content / 'configs/cedar_sawmill.yml')
(root / 'storage/items_ids_cache.yml').write_text('PAPER:\n' + ''.join(
    f'  arc_farms:cedar_sawmill_{kind}: {271001 + index}\n' for index, kind in enumerate(kinds)))
pack = content / 'resourcepack'
models = pack / 'assets/arc_farms/models/sawmill'
models.mkdir(parents=True, exist_ok=True)
for kind in kinds:
    shutil.copy(native / f'cedar_sawmill_{kind}.json', models / f'cedar_sawmill_{kind}.json')
with zipfile.ZipFile(args.client_jar) as client:
    textures = {texture for kind in kinds for texture in json.loads((native / f'cedar_sawmill_{kind}.json').read_text())['textures'].values()}
    for texture in textures:
        namespace, texture_path = texture.split(':', 1)
        relative = f'assets/{namespace}/textures/{texture_path}.png'
        destination = pack / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(client.read(relative))

cases = [(0, 'timber-1-raw'), (10, 'timber-1-raw'), (10, 'timber-3-sawn'),
         (16.2, 'timber-3-sawn'), (21, 'timber-1-sawn'), (25, 'timber-1-sawn'),
         (16.2, 'timber-2-slab'), (25, 'timber-2-slab'),
         (0, 'blade-rotor'), (5.55, 'blade-rotor'), (25, 'blade-rotor'),
         (5.55, 'pulley-upper'), (25, 'pulley-upper'), (5.55, 'pulley-lower'), (25, 'pulley-lower')]

def sampled_pose(time, index):
    key = math.floor(time * 10 + 1e-7)
    a = next(pose for pose in export['frames'][key][8] if pose[0] == index)
    alpha = time * 10 - key
    if alpha < 1e-7:
        return a
    b = next(pose for pose in export['frames'][key + 1][8] if pose[0] == index)
    qa, qb = a[8:12], b[8:12]
    if sum(x * y for x, y in zip(qa, qb)) < 0:
        qb = [-x for x in qb]
    # All requested non-keyframe contacts are exact midpoints, where normalized lerp equals slerp.
    assert abs(alpha - .5) < 1e-7
    q = [(x + y) / 2 for x, y in zip(qa, qb)]
    length = math.sqrt(sum(x * x for x in q))
    return a[:2] + [(x + y) / 2 for x, y in zip(a[2:8], b[2:8])] + [x / length for x in q]

summary, meshes = [], {}
for time, part_id in cases:
    index = next(i for i, part in enumerate(export['parts']) if part['id'] == part_id)
    part = export['parts'][index]
    pose = sampled_pose(time, index)
    assert pose[1], f'Invisible contact case {time}/{part_id}'
    position = [pose[2 + axis] + export['origin'][axis] for axis in range(3)]
    stem = f'{part_id}-{time:g}'
    report = out / f'{stem}.json'
    rotor = part_id in ['blade-rotor', 'pulley-upper', 'pulley-lower']
    plane = (f'blade_collar:x:max:{export["origin"][0] + .2975 * scene_scale}' if part_id == 'blade-rotor' else f'axial_bearing:x:min:{export["origin"][0] + 1.74 * scene_scale}')
    contact_args = ['--contact-plane', plane] if rotor else ['--surface-y', str(export['origin'][1] + export['logBottom'])]
    command = ['python3', '-B', str(args.skill / 'scripts/analyze_itemsadder_display.py'),
               '--itemsadder-root', str(root), '--resource-pack', str(pack),
               '--item-id', part['itemModel'], '--context', 'none',
               '--scale=' + ','.join(map(str, pose[5:8])),
               '--left-quaternion=' + ','.join(map(str, pose[8:12])),
               '--translation=' + ','.join(map(str, pose[2:5])),
               '--entity-position=' + ','.join(map(str, export['origin'])),
               *contact_args, '--output', str(report)]
    subprocess.run(command, check=True)
    result = json.loads(report.read_text())
    residual = max((abs(plane['current_residual']) for plane in result['contact_planes']), default=abs(result['contact'].get('current_min_residual', 0)))
    assert result['status'] in ['grounded', 'contacted'] and abs(residual) <= 1e-4, (stem, result['status'], residual)
    summary.append({'time': time, 'part': part_id, 'item': part['itemModel'], 'position': position, 'entityPosition': export['origin'], 'translation': pose[2:5],
                    'scale': pose[5:8], 'leftRotation': pose[8:12], 'context': 'NONE',
                    'support': plane if rotor else 'outfeed-top' if part_id.endswith('slab') else 'timber-bolster pair',
                    'surfaceY': None if rotor else export['origin'][1] + export['logBottom'], 'status': result['status'],
                    'currentResidual': residual, 'postAdjustmentResidual': result['contact']['post_adjustment_min_residual'],
                    'modelSha256': result['model_provenance'][0]['sha256'], 'command': command})
    if part['itemModel'] not in meshes:
        subprocess.run(['python3', '-B', str(args.skill / 'scripts/render_itemsadder_display.py'),
                        str(report), '--output', str(out / f'{stem}.html'), '--title', part['itemModel']], check=True)
        meshes[part['itemModel']] = {'modelSha256': result['model_provenance'][0]['sha256'], 'faces': [
            {'positions': face['positions_item_display_base'], 'uv': face['uv_normalized'], 'texture': face['texture'],
             'normal': {'up': [0, 1, 0], 'down': [0, -1, 0], 'east': [-1, 0, 0], 'west': [1, 0, 0],
                        'north': [0, 0, 1], 'south': [0, 0, -1]}[face['face']]}
            for face in result['render']['faces']], 'textures': result['render']['textures']}

# Every supported raw/sawn and settled slab pose uses the same exact model bounds.
# Turning offcuts are intentionally in transfer; their underside must clear the receiving table.
manifest = json.loads((native / 'models.manifest.json').read_text())
minimum_clearance = float('inf')
for frame in export['frames']:
    for pose in frame[8]:
        part = export['parts'][pose[0]]
        if not pose[1] or not part.get('itemModel'):
            continue
        kind = part['itemModel'].rsplit('_', 1)[1]
        if kind not in ['raw', 'sawn', 'slab']:
            continue
        qx, qy, qz, qw = pose[8:12]
        # These models only have the authored semantic slab rotation around Z.
        assert abs(qx) < 1e-7 and abs(qy) < 1e-7
        norm = qz * qz + qw * qw
        sine, cosine = 2 * qz * qw / norm, 1 - 2 * qz * qz / norm
        transformed_min = min(sine * x * pose[5] + cosine * y * pose[6] for box in manifest['models'][kind]['boxes']
                              for x in [box['min'][0], box['max'][0]] for y in [box['min'][1], box['max'][1]])
        clearance = pose[3] + transformed_min - export['logBottom']
        minimum_clearance = min(minimum_clearance, clearance)
        assert clearance >= -1e-4, (frame[0], part['id'], clearance)
        if kind != 'slab' or frame[0] >= 16.2:
            assert abs(clearance) <= 1e-4, (frame[0], part['id'], clearance)

(out / 'grounding-summary.json').write_text(json.dumps({
    'scope': 'Offline exact model and compiled runtime poses; active client pack is not yet verified.',
    'nativeItemDisplayBaseY': 180, 'assetBakedY': 180,
    'clientJarSha256': hashlib.sha256(args.client_jar.read_bytes()).hexdigest(),
    'supportEvidence': f'Canonical local supports scaled by {scene_scale}: stockY={export["logBottom"]}; blade collar rearX={.2975 * scene_scale}; bearing/motor frontX={1.74 * scene_scale}. Entity origin{export["origin"]}; pose centers are native Display translations.',
    'sampledFrames': len(export['frames']), 'minimumTransferClearance': minimum_clearance, 'cases': summary,
}, ensure_ascii=False, indent=2) + '\n')
(out / 'native-item-faces.json').write_text(json.dumps(meshes, separators=(',', ':')))
print(f'Grounded {len(summary)} representative placements; {len(export["frames"])} sampled frames clear the support planes. Residual <=1e-4.')
