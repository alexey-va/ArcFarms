import importlib.util,json,os,re
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
ops_root=Path(os.environ['RUSCRAFTING_OPS_ROOT']).resolve() if os.environ.get('RUSCRAFTING_OPS_ROOT') else next(
    candidate for candidate in (repo.parent/'ruscrafting-ops', repo.parent.parent/'ruscrafting-ops')
    if (candidate/'scripts/location-atelier/engine.mjs').exists()
)
ops=ops_root/'scripts/location-atelier'
spec=importlib.util.spec_from_file_location('sponge',ops/'sponge.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
blob=(ops/'examples/compact-mine/live-source-20260914/server.schem').read_bytes(); sc=m.read_schematic(blob)
sx,sy,sz=sc['size']; ox,oy,oz=(0,64,0)
# Keep exact block state but classify by base material.
def base(s): return s.split(':',1)[1].split('[',1)[0]
cells=sc['cells']
def cell(x,y,z):
    if not (0<=x<sx and 0<=y-oy<sy and 0<=z<sz): return 'minecraft:air'
    return cells[((y-oy)*sz+z)*sx+x]
# Bukkit isSolid approximation: NEST floor geometry here is dominated by full rock/brick blocks.
non_solid_re=re.compile(r'^(air|cave_air|void_air|water|lava|.*_air|.*_button|.*_pressure_plate|.*_rail|.*_fence|.*_wall|.*_stairs|.*_slab|.*_trapdoor|.*_door|.*_sign|.*_banner|.*_torch|.*_lantern|.*_carpet|.*_vine|.*_leaves|.*_sapling|.*_flower|.*_mushroom|.*_coral|.*_glass_pane|.*_grate|.*_bars)$')
def solid(s): return not non_solid_re.match(base(s)) and base(s) not in {'water_cauldron','lava_cauldron','chest','barrel','furnace','grindstone','spawner','bedrock'}
geo={'stone','cobblestone','deepslate','tuff','andesite','diorite','granite','calcite','smooth_stone','stone_bricks','mossy_stone_bricks','deepslate_bricks','deepslate_tiles','gravel'}
def geological(s): return base(s) in geo
D=json.loads((repo/'src/main/resources/mine/workings/lateral-working.layout.json').read_text())
B={(p['side'],p['up'],p['forward']):p['block'] for p in D['blocks']}
walk0=[(p['side'],p['up'],p['forward']) for p in D['walkable']]
exc0=[(p['side'],p['up'],p['forward']) for p in D['excavation']]
sup0=[(p['side'],p['up'],p['forward']) for p in D['supports']]
rails0=[(p['side'],p['up'],p['forward']) for p in D['rails']]
ext0=[(p['side'],p['up'],p['forward']) for p in D['extensionRubble']]
gap0=[(p['side'],p['up'],p['forward']) for p in D['trackDamageGaps']]
frames0=[[(p['side'],p['up'],p['forward']) for p in frame] for frame in D['supportFrames']]
stations0={k:(v['side'],v['up'],v['forward']) for k,v in D['stations'].items()}
fixtures0=[(p['side'],p['up'],p['forward']) for p in D.get('fixtures',[])]
def pos(anchor,dir,p):
    side,up,f=p; x,z=((side,f) if dir==0 else (-f,side) if dir==1 else (-side,-f) if dir==2 else (f,-side))
    return (anchor[0]+x,anchor[1]+up,anchor[2]+z)
def along(anchor,dir,p):
    x,z=p[0],p[2]
    return z-anchor[2] if dir==0 else anchor[0]-x if dir==1 else anchor[2]-z if dir==2 else x-anchor[0]
# target material projection and planner predicates, exactly matching current Kotlin logic.
def plan_local(typ):
    def project(p):
      side,up,f=p; raw=B[p]
      if p in fixtures0 and not (typ=='TUNNEL_DRIVE' and p in set(exc0)):
        return raw
      if 1<=up<=3 and f<=2:return 'minecraft:air'
      if typ=='ORE_WORKSHOP' or not 1<=up<=3:return raw
      if typ=='TUNNEL_DRIVE' and f>=4:return 'minecraft:stone'
      return 'minecraft:air' if abs(side)<=1 and f<=13 else 'minecraft:stone'
    supportpts={tuple((q['side'],q['up'],q['forward'])) for fr in D['supportFrames'] for q in fr}
    included={p for p in B if (1<=p[1]<=3 and abs(p[0])<=1) or (p[2]>=3 and 0<=p[1]<=4 and abs(p[0])<=1) or (typ=='TUNNEL_DRIVE' and p in supportpts)}
    out={p:project(p) for p in B if p in included}
    exc=set(exc0) if typ=='TUNNEL_DRIVE' else set()
    supports=set(sup0) if typ=='TUNNEL_DRIVE' else set()
    rails=set(rails0) if typ in ('RAIL_EXTENSION','TRACK_DAMAGE') else set()
    rubble=set(ext0 if typ=='RAIL_EXTENSION' else gap0 if typ=='TRACK_DAMAGE' else [])
    stations=set(stations0.values()) if typ=='ORE_WORKSHOP' else set()
    fixtures=(set(fixtures0)|supportpts)-({p for p in fixtures0 if typ=='TUNNEL_DRIVE' and p in set(exc0)})
    entry={(p['side'],p['up'],p['forward']) for p in D['blocks'] if p['up'] in range(1,4) and p['forward']<=2}
    walk={p for p in set(walk0)|entry if abs(p[0])<=1}
    walk={p for p in walk if p[2]<=13}
    walk-=set(rails0) if typ in ('RAIL_EXTENSION','TRACK_DAMAGE') else set()
    walk-=stations|fixtures
    shell={p for p,v in out.items() if v!='minecraft:air' and p not in walk and p not in rails and p not in rubble and p not in exc and p not in stations and p not in fixtures}
    return out,walk,shell,exc,rails,rubble,stations,fixtures

types=['TUNNEL_DRIVE','RAIL_EXTENSION','TRACK_DAMAGE','ORE_WORKSHOP']
plans={t:plan_local(t) for t in types}
# NEST anchors by floor; include loaded full schematic only, then service's ±1 floor filter.
floorY=[110,96,82]
anchors={y:[] for y in floorY}
for y in floorY:
  for z in range(sz):
    for x in range(sx):
      s=cell(x,y,z)
      if solid(s) and not solid(cell(x,y+1,z)) and not solid(cell(x,y+2,z)):
        anchors[y].append((x,y,z))
print('anchors', {y:len(v) for y,v in anchors.items()})
# Service exits from live config: one exit at 28.5 block 28, y floor+1, z43 block43.
exits=[(28,111,43),(28,97,43),(28,83,43)]
bounds=(0,87,64,139,0,87)
def eval_plan(typ,a,d):
    out,walk,shell,exc,rails,rubble,stations,fixtures=plans[typ]
    def world(p): return pos(a,d,p)
    # Service bounds/lift clearance over the complete journal footprint.
    for p in out:
      q=world(p); x,y,z=q
      if not (bounds[0]<=x<=bounds[1] and bounds[2]<=y<=bounds[3] and bounds[4]<=z<=bounds[5]): return 'outside_region'
      if any(abs(y-ey)<=5 and abs(x-ex)<=6 and abs(z-ez)<=6 for ex,ey,ez in exits): return 'lift_clearance'
    for p in shell:
      if not geological(cell(*world(p))): return 'shell_not_geological'
    for p in fixtures:
      if not geological(cell(*world(p))): return 'fixture_not_geological'
    for p in walk:
      s=cell(*world(p)); dep=p[2]
      if dep<=2:
        if base(s)!='air': return 'entry_not_clear'
      elif not geological(s): return 'new_volume_not_geological'
    for p in rails:
      s=cell(*world(p))
      if p[2]<=2:
        if base(s)!='air': return 'track_entry_not_clear'
      elif not geological(s): return 'track_volume_not_geological'
    for p in stations:
      if not geological(cell(*world(p))): return 'station_not_geological'
    if typ=='TUNNEL_DRIVE':
      for p in exc:
        if not geological(cell(*world(p))): return 'excavation_not_geological'
    surface=world((0,0,-1))
    if not geological(cell(*surface)): return 'surface_not_geological'
    for up in (1,2,3):
      x,y,z=world((0,up,-1));
      if base(cell(x,y,z))!='air': return 'surface_not_clear'
    return None
report={'schema':1,'source':'compact-mine-live-20260914/server.schem','world':'rc_atelier_compact_mine','bounds':{'min':[0,64,0],'max':[87,139,87]},'floors':{}}
for typ in types:
  print('\n',typ)
  report['floors'][typ]={}
  for fy,aa in anchors.items():
    counts={}; valid=[]
    for a in aa:
      # service floor filter: candidate anchor y matches corresponding exit-1; exact floor bucket
      ey={110:111,96:97,82:83}[fy]
      if abs(ey-1-a[1])>1: continue
      for d in range(4):
        reason=eval_plan(typ,a,d); counts[reason]=counts.get(reason,0)+1
        if reason is None: valid.append((a,d))
    print(fy,'considered',sum(counts.values()),'valid',len(valid),'reasons',counts,'sample',valid[:2])
    report['floors'][typ][str(fy)]={'considered':sum(counts.values()),'valid':len(valid),'reasons':{'accepted' if k is None else k:v for k,v in counts.items()},'samples':[{'anchor':list(a),'direction':d} for a,d in valid[:3]]}
(repo/'docs/evidence').mkdir(parents=True, exist_ok=True)
(repo/'docs/evidence/mine-working-feasibility-20260919.json').write_text(json.dumps(report, indent=2, sort_keys=True)+'\n')
