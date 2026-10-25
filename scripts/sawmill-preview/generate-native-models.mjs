#!/usr/bin/env node
// Native model meshes and exact contact vertices. Rigid rotors are single ItemDisplays.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const own = dirname(fileURLToPath(import.meta.url));
const out = join(own, 'native-models');
await mkdir(out, { recursive: true });
const geometryPath = resolve(own, '../../src/main/resources/sawmill/cedar-machine.json');
const geometry = JSON.parse(await readFile(geometryPath, 'utf8'));
const round = x => Number(x.toFixed(7));
const width = y => .205 + .275 * Math.min(1, Math.min(y, .72 - y) / .17);
const definitions = {};
for (const kind of ['raw', 'sawn', 'slab']) {
  const origin = [kind === 'slab' ? .3925 : 0, .36, 0];
  const rows = [];
  for (let row = 0; row < 24; row++) {
    const bottom = row * .03, top = (row + 1) * .03;
    const half = Math.min(width(bottom), width(top));
    const left = kind === 'slab' ? .305 : -half;
    const right = kind === 'sawn' ? Math.min(half, .235) : half;
    if (right > left + 1e-6) rows.push({ left, right, bottom, top });
  }
  // ItemDisplay NONE has native Y+180. Bake its inverse into the asset once;
  // every runtime placement remains level with identity entity rotations.
  const modelPoint = (x, y, z) => [x, y, z].map((n, axis) => round(8 + (n - origin[axis]) * 16 * (axis === 1 ? 1 : -1)));
  const tex = (texture, uv) => ({ texture: `#${texture}`, uv: uv.map(round) });
  const elements = rows.map((row, index) => {
    const { left, right, bottom, top } = row;
    const endUV = [(left + .48) / .96 * 16, (1 - top / .72) * 16, (right + .48) / .96 * 16, (1 - bottom / .72) * 16];
    // Vanilla spruce grain runs along texture V: turn it onto the log's long Z axis.
    const sideUV = [(1 - top / .72) * 16, 0, (1 - bottom / .72) * 16, 16];
    const faces = {
      north: tex('end', endUV), south: tex('end', endUV),
      west: { ...tex(kind === 'slab' ? 'cut' : 'bark', sideUV), rotation: 90 },
      east: { ...tex(kind === 'sawn' && right === .235 ? 'cut' : 'bark', sideUV), rotation: 90 },
    };
    // Adjacent wider rows cover the whole horizontal face. Omit those faces entirely.
    const covers = neighbor => neighbor && neighbor.left <= left && neighbor.right >= right;
    if (!covers(rows[index - 1])) faces.down = tex('bark', [(left + .48) / .96 * 16, 0, (right + .48) / .96 * 16, 16]);
    if (!covers(rows[index + 1])) faces.up = tex('bark', [(left + .48) / .96 * 16, 0, (right + .48) / .96 * 16, 16]);
    const bakedFaces = {};
    const opposite = { north: 'south', south: 'north', east: 'west', west: 'east', up: 'up', down: 'down' };
    for (const [side, face] of Object.entries(faces)) bakedFaces[opposite[side]] = side === 'up' || side === 'down' ? { ...face, rotation: 180 } : face;
    return { from: modelPoint(right, bottom, .5), to: modelPoint(left, top, -.5), faces: bakedFaces };
  });
  const model = {
    credit: 'ArcFarms Cedar sawmill: native sliced spruce timber, generated from approved cross-section.',
    ambientocclusion: false,
    textures: { bark: 'minecraft:block/spruce_log', end: 'minecraft:block/spruce_log_top', cut: 'minecraft:block/stripped_spruce_log', particle: 'minecraft:block/spruce_log' },
    display: { fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] } },
    elements,
  };
  const name = `cedar_sawmill_${kind}`;
  const text = JSON.stringify(model, null, 2) + '\n';
  await writeFile(join(out, `${name}.json`), text);
  definitions[kind] = {
    itemModel: `arc_farms:${name}`, file: `${name}.json`,
    sha256: createHash('sha256').update(text).digest('hex'), origin,
    // Relative model coordinates, in blocks; this is the exact runtime contact hull.
    boxes: rows.map(({ left, right, bottom, top }) => ({
      min: [left - origin[0], bottom - origin[1], -.5].map(round),
      max: [right - origin[0], top - origin[1], .5].map(round),
    })),
  };
}
for (const kind of ['blade', 'pulley']) {
  const authored = geometry.rotorBlueprints[kind];
  const pivot = authored[0].pivot;
  const shapes = authored.map(part => ({
    y: part.center[1] - pivot[1], z: part.center[2] - pivot[2],
    halfY: part.size[1] / 2, halfZ: part.size[2] / 2, depth: part.size[0],
    angle: part.rotation?.[0] ?? 0, block: part.block,
  }));
  // The blade's center hub and tooth roots share the exact collar contact depth.
  if (kind === 'blade') shapes.push({ y: 0, z: 0, halfY: .09, halfZ: .09, depth: .055, angle: 0, block: 'minecraft:light_gray_concrete' });
  const step = kind === 'blade' ? .025 : .015;
  const extent = kind === 'blade' ? 1.125 : .375;
  const rows = Math.round(extent * 2 / step);
  const boxes = [], active = new Map();
  const at = (y, z) => {
    let result = null;
    for (const shape of shapes) {
      const dy = y - shape.y, dz = z - shape.z, c = Math.cos(shape.angle), s = Math.sin(shape.angle);
      if (Math.abs(c * dy + s * dz) <= shape.halfY + 1e-9 && Math.abs(-s * dy + c * dz) <= shape.halfZ + 1e-9
        && (!result || shape.depth > result.depth)) result = shape;
    }
    return result;
  };
  for (let row = 0; row < rows; row++) {
    const y0 = -extent + row * step, present = new Set();
    let column = 0;
    while (column < rows) {
      const shape = at(y0 + step / 2, -extent + (column + .5) * step);
      if (!shape) { column++; continue; }
      const start = column, signature = `${shape.block}|${shape.depth}`;
      while (++column < rows) {
        const next = at(y0 + step / 2, -extent + (column + .5) * step);
        if (!next || `${next.block}|${next.depth}` !== signature) break;
      }
      const key = `${start}|${column}|${signature}`;
      let box = active.get(key);
      if (box && Math.abs(box.max[1] - y0) < 1e-8) box.max[1] = round(y0 + step);
      else {
        box = { min: [-shape.depth / 2, round(y0), round(-extent + start * step)],
          max: [shape.depth / 2, round(y0 + step), round(-extent + column * step)], block: shape.block };
        boxes.push(box); active.set(key, box);
      }
      present.add(key);
    }
    for (const key of active.keys()) if (!present.has(key)) active.delete(key);
  }
  const textures = {}, textureKey = block => {
    const resource = block.replace('minecraft:', 'minecraft:block/').replace('waxed_exposed_copper', 'exposed_copper');
    const existing = Object.entries(textures).find(([, value]) => value === resource);
    if (existing) return existing[0];
    const key = `m${Object.keys(textures).length}`; textures[key] = resource; return key;
  };
  const direction = { west: [0, -1], east: [0, 1], down: [1, -1], up: [1, 1], north: [2, -1], south: [2, 1] };
  const opposite = { north: 'south', south: 'north', east: 'west', west: 'east', up: 'up', down: 'down' };
  const elements = boxes.map(box => {
    const faces = {};
    for (const [face, [axis, sign]] of Object.entries(direction)) {
      const plane = (sign < 0 ? box.min : box.max)[axis], others = [0, 1, 2].filter(i => i !== axis);
      const covered = boxes.some(other => other !== box
        && Math.abs((sign < 0 ? other.max : other.min)[axis] - plane) < 1e-8
        && others.every(i => other.min[i] <= box.min[i] + 1e-8 && other.max[i] >= box.max[i] - 1e-8));
      if (covered) continue;
      const uv = axis === 0 ? [(box.min[2] + extent) / (2 * extent) * 16, (extent - box.max[1]) / (2 * extent) * 16,
        (box.max[2] + extent) / (2 * extent) * 16, (extent - box.min[1]) / (2 * extent) * 16] : [0, 0, 16, 16];
      faces[opposite[face]] = { texture: `#${textureKey(box.block)}`, uv: uv.map(round), ...(axis === 1 ? { rotation: 180 } : {}) };
    }
    const toModel = point => point.map((n, axis) => round(8 + 16 * n * (axis === 1 ? 1 : -1)));
    return { from: toModel([box.max[0], box.min[1], box.max[2]]),
      to: toModel([box.min[0], box.max[1], box.min[2]]), faces };
  });
  const model = { credit: 'ArcFarms Cedar sawmill rigid rotor: rasterized union of the authored native cuboids.',
    ambientocclusion: false, textures: { ...textures, particle: Object.values(textures)[0] },
    display: { fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] } }, elements };
  const name = `cedar_sawmill_${kind}`, text = JSON.stringify(model, null, 2) + '\n';
  await writeFile(join(out, `${name}.json`), text);
  definitions[kind] = { itemModel: `arc_farms:${name}`, file: `${name}.json`,
    sha256: createHash('sha256').update(text).digest('hex'), origin: [0, 0, 0],
    boxes: boxes.map(({ min, max }) => ({ min, max })) };
}
const names = { raw: 'Бревно пилорамы', sawn: 'Бревно с продольным спилом', slab: 'Горбыль пилорамы', blade: 'Пильный диск пилорамы', pulley: 'Шкив пилорамы' };
await writeFile(join(out, 'items.yml'), `info:\n  namespace: arc_farms\nitems:\n${Object.keys(definitions).map((kind, index) => `  cedar_sawmill_${kind}:\n    display_name: ${names[kind]}\n    resource:\n      material: PAPER\n      model_id: ${271001 + index}\n      generate: false\n      model_path: sawmill/cedar_sawmill_${kind}\n`).join('')}`);
await writeFile(join(out, 'models.manifest.json'), JSON.stringify({ context: 'NONE', neutralModelOrigin: [8, 8, 8], nativeBaseRotation: [0, 180, 0], bakedAssetRotation: [0, 180, 0], models: definitions }, null, 2) + '\n');
delete geometry.timberModels;
geometry.nativeModels = definitions;
await writeFile(geometryPath, JSON.stringify(geometry, null, 2) + '\n');
console.log(`Generated${Object.keys(definitions).length} native models with exact contact boxes.`);
