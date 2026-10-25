#!/usr/bin/env python3
"""Verify the unchanged native raw model at the new ground/cart handoff contacts."""
import json, subprocess
from pathlib import Path
project=Path(__file__).resolve().parents[2]
out=project/'build/reports/sawmill-preview/grounding'
fixture=out/'ItemsAdder'
frames=json.loads((out.parent/'lumber-export.json').read_text())['frames']
skill=Path.home()/'.codex/skills/itemsadder-item-display-grounding'
reports=[]
for second in [17,20,30,50,60,72]:
 frame=frames[round(second*10)]
 part=next(p for p in frame['parts'] if p['id']=='timber-2-raw')
 surface=73.0 if second==17 else frame['cart']['y']+.30
 path=out/f'lumber-raw-{second}.json'
 command=['python3','-B',str(skill/'scripts/analyze_itemsadder_display.py'),
  '--itemsadder-root',str(fixture),'--resource-pack',str(fixture/'contents/arc_farms/resourcepack'),
  '--item-id',part['itemModel'],'--context','none',
  '--entity-position='+','.join(map(str,part['center'])),
  '--scale='+','.join(map(str,part['size'])),
  '--left-quaternion='+','.join(map(str,part['rotation'])),
  '--surface-y',str(surface),'--output',str(path)]
 subprocess.run(command,check=True,capture_output=True)
 result=json.loads(path.read_text()); residual=result['contact']['current_min_residual']
 assert result['status']=='grounded' and abs(residual)<=1e-4,(second,result['status'],residual)
 reports.append({'time':second,'support':'original forest floor' if second==17 else 'authored cart bolster top',
   'surfaceY':surface,'center':part['center'],'scale':part['size'],'rotation':part['rotation'],
   'status':result['status'],'residual':residual,'modelSha256':result['model_provenance'][0]['sha256'],'report':str(path)})
(out/'lumber-grounding-summary.json').write_text(json.dumps({'scope':'Exact native model and source-export poses; not a native-client observation','cases':reports},indent=2)+'\n')
subprocess.run(['python3','-B',str(skill/'scripts/render_itemsadder_display.py'),str(out/'lumber-raw-20.json'),'--output',str(out/'lumber-raw-cart.html'),'--title','Existing raw stock on lumber cart'],check=True,capture_output=True)
print(f'Grounded {len(reports)} full-length stock poses: every residual <=1e-4; existing native model unchanged.')
