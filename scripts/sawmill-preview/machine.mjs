// Preview-only classic circular headrig, authored from native block display cuboids.
export const SAWMILL = {
  origin: [188.5, 73, 180.5],
  views: [
    { id: 'overview', label: 'Весь цикл', camera: [195.2, 76.2, 188.2], target: [188.5, 74.2, 180.8], fov: 67 },
    { id: 'operator', label: 'Глазами игрока', camera: [193.4, 74.62, 183.7], target: [188.6, 74.5, 180.5], fov: 66 },
    { id: 'infeed', label: 'Подача', camera: [192.5, 74.62, 174.2], target: [188.4, 74.4, 178.7], fov: 70 },
    { id: 'drive', label: 'Диск и передача', camera: [192.4, 74.62, 180.8], target: [188.8, 74.3, 180.1], fov: 68 },
    { id: 'output', label: 'Выход горбыля', camera: [193.5, 74.62, 188.8], target: [188.7, 74.2, 184.6], fov: 68 },
  ],
  previewMask(point, state) {
    const [x, y, z] = point;
    return x >= 183 && x <= 196 && y >= 73 && y <= 78 && z >= 167 && z <= 191
      && /^minecraft:(rail|spruce_slab|spruce_stairs|spruce_log|iron_chain|stonecutter)(?:\[|$)/.test(state);
  },
};
export function makeParts() {
  const parts = [];
  const add = (name, block, center, size, motion = 'fixed', extra = {}) => parts.push({ name, block: `minecraft:${block}`, center, size, motion, ...extra });
  const iron = 'gray_concrete', edge = 'polished_deepslate', steel = 'smooth_stone', bronze = 'waxed_exposed_copper';
  add('cast-foot', edge, [.90, .13, 0], [1.12, .26, 2.85]);
  add('cast-base', iron, [.83, .63, 0], [1.02, .98, 2.37]);
  add('body-shoulder', iron, [.85, 1.07, 0], [1.08, .22, 2.55]);
  // A true underside, not a second coplanar top. The blade slot is x=.235.. .32.
  add('table-underlay', edge, [.86, 1.18, 0], [1.08, .08, 2.75]);
  add('table-right', 'stripped_spruce_log[axis=z]', [.82, 1.245, 0], [.96, .05, 2.7]);
  for (const z of [-1.15, 1.15]) add('cast-end-band', edge, [.86, .68, z], [1.02, .91, .12]);
  add('inspection-cover', edge, [1.36, .69, 0], [.035, .5, 1.0]);
  add('maker-plate', bronze, [1.3925, .77, 0], [.015, .16, .40]);
  const pivot = [.27, 1.18, 0], radius = 1.04, rows = 41;
  for (let i = 0; i < rows; i++) {
    const bottom = -radius + i * radius * 2 / rows, top = bottom + radius * 2 / rows;
    const middle=(bottom+top)/2;
    const extent = Math.sqrt(Math.max(0, radius * radius - middle*middle));
    if (extent > .001) add('blade-disc', 'light_gray_concrete', [.27, 1.18 + (bottom + top) / 2, 0], [.045, top - bottom, extent * 2], 'blade', { pivot });
  }
  for (let i = 0; i < 28; i++) {
    const a = i * Math.PI * 2 / 28;
    add('saw-tooth', steel, [.27, 1.18 + 1.035 * Math.cos(a), 1.035 * Math.sin(a)], [.055, .14, .075], 'blade', { pivot, rotation: [a - .27, 0, 0] });
  }
  add('arbor', edge, [1.01, 1.18, 0], [1.78, .10, .10], 'arbor');
  for (const x of [.34, .73, 1.29]) add('arbor-collar', bronze, [x, 1.18, 0], [.12, .12, .12], 'arbor');
  add('arbor-bearing', iron, [1.52, 1.08, 0], [.37, .47, .49]);
  add('bearing-bronze', bronze, [1.73, 1.18, 0], [.055, .29, .29]);
  for (const pulley of [{ y: 1.18, z: 0, r: .33 }, { y: .48, z: -1.84, r: .22 }]) {
    const pp = [1.89, pulley.y, pulley.z];
    add('belt-pulley',edge,pp,[1,1,1],'pulley',{pivot:pp,ratio:.33/pulley.r,shape:'annulus',outerRadius:pulley.r+.045,innerRadius:pulley.r-.045,depth:.24});
    for(let i=0;i<3;i++){const a=i*Math.PI/3;add('pulley-spoke',edge,pp,[.13,pulley.r*1.8,.075],'pulley',{pivot:pp,ratio:.33/pulley.r,rotation:[a,0,0]});}
    add('pulley-hub', bronze, pp, [.30, .18, .18], 'pulley', { pivot: pp, ratio: .33 / pulley.r });
  }
  const beltTilt = Math.atan2(-1.84, -.70);
  for (const side of [-1, 1]) add('drive-belt', 'black_concrete', [1.89, .83 + side * .255, -.92 - side * .096], [.20, 1.98, .045], 'fixed', { rotation: [beltTilt, 0, 0] });
  add('motor-foot', edge, [1.89, .115, -1.84], [1.12, .23, 1.20]);
  add('motor-body', iron, [1.58, .47, -1.84], [.88, .59, .82]);
  for (let i = 0; i < 6; i++) add('motor-fin', edge, [1.22 + i * .12, .49, -1.84], [.05, .66, .88]);
  add('motor-end', bronze, [1.07, .47, -1.84], [.10, .38, .54]);
  for (const x of [-1.13, -.13]) add('carriage-rail', edge, [x, .13, -1.4], [.085, .09, 15.0]);
  for (let z = -8.6; z < 6.2; z += 1.25) add('rail-sleeper', 'dark_oak_planks', [-.63, .05, z], [1.9, .1, .22]);
  for (const x of [-1.12, -.13]) add('carriage-side', iron, [x, .48, 0], [.18, .28, 3.18], 'carriage');
  for (const z of [-1.30, 1.30]) {
    add('carriage-axle', edge, [-.63, .29, z], [1.27, .13, .13], 'carriage');
    for (const x of [-1.15, -.13]) {
      add('carriage-wheel', edge, [x, .29, z], [.16, .31, .31], 'carriage-wheel');
      add('wheel-pin', bronze, [x + .10, .29, z], [.055, .105, .105], 'carriage');
    }
    add('headblock-base', edge, [-.62, .855, z], [1.32, .39, .40], 'carriage');
    add('timber-bolster', 'stripped_dark_oak_log[axis=x]', [-.225, 1.16, z], [.92, .22, .36], 'headblock');
    add('log-dog', iron, [-.65, 1.35, z], [.17, .64, .18], 'headblock');
    add('dog-tooth', steel, [-.54, 1.55, z], [.16, .06, .12], 'headblock');
  }
  for (const x of [.54, 1.75]) {
    add('outfeed-beam', 'stripped_spruce_log[axis=z]', [x, 1.07, 4.1], [.22, .30, 5.4]);
    for (const z of [1.7, 4, 6.5]) add('outfeed-leg', iron, [x, .51, z], [.18, 1.02, .27]);
  }
  for (let i = 0; i < 11; i++) add('outfeed-top', 'spruce_planks', [1.13, 1.23, 1.6 + i * .50], [1.44, .08, .43]);
  add('outfeed-end-stop', edge, [1.13, 1.34, 6.85], [1.46, .22, .10]);
  add('control-plinth', iron, [2.45, .54, 1.08], [.44, 1.08, .46]);
  add('control-cap', bronze, [2.45, 1.12, 1.08], [.48, .1, .50]);
  add('control-lever', edge, [2.45, 1.42, 1.08], [.085, .54, .085], 'lever', { pivot: [2.45, 1.15, 1.08] });
  add('lever-handle', 'dark_oak_log[axis=x]', [2.45, 1.69, 1.08], [.28, .10, .12], 'lever', { pivot: [2.45, 1.15, 1.08] });
  return parts;
}
