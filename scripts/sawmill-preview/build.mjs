#!/usr/bin/env node
// Source-driven runtime preview. Reuses Location Atelier and the native grounding face exporter.
import { readFile, writeFile, mkdir, rm, readdir } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { SAWMILL, makeParts } from './machine.mjs';

const own = dirname(fileURLToPath(import.meta.url));
const project = resolve(own, '../..');
const options = Object.fromEntries(process.argv.slice(2).map(arg => {
  const separator = arg.indexOf('=');
  if (!arg.startsWith('--') || separator < 0) throw Error('Use --atelier=/path, --out=/path or --client-jar=/path');
  return [arg.slice(2, separator), arg.slice(separator + 1)];
}));
const atelier = resolve(options.atelier ?? join(project, '../ruscrafting-ops/scripts/location-atelier'));
const out = resolve(options.out ?? join(project, 'build/reports/sawmill-preview'));
const clientJar = resolve(options['client-jar'] ?? join(project, '../artifacts/.surface-cache/minecraft-1.21.11-client-ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd.jar'));
const runtimeText = await readFile(resolve(options['runtime-export'] ?? join(project, 'build/reports/sawmill-preview/runtime-export.json')), 'utf8');
const runtime = JSON.parse(runtimeText);
const nativeFaces = JSON.parse(await readFile(join(project, 'build/reports/sawmill-preview/grounding/native-item-faces.json'), 'utf8'));
for (const descriptor of Object.values(runtime.nativeModels)) {
  const modelBytes = await readFile(join(own, 'native-models', descriptor.file));
  if (createHash('sha256').update(modelBytes).digest('hex') !== descriptor.sha256
    || nativeFaces[descriptor.itemModel]?.modelSha256 !== descriptor.sha256) {
    throw Error(`Stale native model export: ${descriptor.itemModel}; regenerate models, compiled export and grounding faces.`);
  }
}
const classes = join(project, 'build/classes/kotlin/main/ru/ruscrafting/farms/paper/lumber/sawmill');
const sourceClassSha256 = {};
for (const filename of (await readdir(classes)).filter(name => /^CedarSawmill(?:Geometry|Animation)(?:\$|\.)/.test(name)).sort()) {
  sourceClassSha256[filename] = createHash('sha256').update(await readFile(join(classes, filename))).digest('hex');
}
const snapshotText = await readFile(join(own, 'room.snapshot.json'), 'utf8');
const snapshot = JSON.parse(snapshotText);
if (snapshot.missingCells !== 0) throw Error('The captured room contains missing cells');
const [min, max] = [snapshot.bounds.min, snapshot.bounds.max];
const local = point => point.map((n, i) => n - min[i]);
const palette = {};
const materials = new Map();
const material = state => {
  if (!materials.has(state)) {
    const key = `m${materials.size}`;
    materials.set(state, key);
    palette[key] = { block: state, color: '#877b63', name: state.replace('minecraft:', '') };
  }
  return materials.get(state);
};
const operations = snapshot.blocks.map(([x, y, z, state]) => ({
  op: 'box', from: local([x, y, z]), to: local([x, y, z]), material: material(state),
}));
const parts = makeParts();
if (JSON.stringify(parts) !== JSON.stringify(runtime.parts)) throw Error('Runtime geometry changed; rerun the compiled Kotlin export.');
for (const part of parts) if (part.block) material(part.block);
for (const state of ['minecraft:spruce_log[axis=z]', 'minecraft:stripped_spruce_log[axis=z]', 'minecraft:spruce_planks', 'minecraft:light_gray_concrete']) material(state);
// Step back with the larger footprint while preserving the authored player eye height.
const views = SAWMILL.views.map(view => ({ ...view,
  camera: local(view.camera.map((value, axis) => axis === 1 ? value
    : SAWMILL.origin[axis] + (value - SAWMILL.origin[axis]) * runtime.sceneScale)),
  target: local(view.target.map((value, axis) => SAWMILL.origin[axis]
    + (value - SAWMILL.origin[axis]) * runtime.sceneScale)),
}));
const recipe = {
  version: 1, seed: 251026, title: 'Кедровый двор · рабочая пилорама',
  subtitle: 'Один полный цикл в существующем цехе',
  concept: 'Классическая круговая пилорама: закреплённое бревно проходит один диск, один горбыль остаётся на столе, остаток возвращается с сохранённой спиленной стороной.',
  size: max.map((n, i) => n - min[i] + 1), palette, operations,
  preview: { defaultView: 'overview', views },
  lighting: { targetLevel: 8, samples: [] },
};
await mkdir(out, { recursive: true });
const recipePath = join(out, 'background.atelier.json');
await writeFile(recipePath, JSON.stringify(recipe));
const compiled = spawnSync(process.execPath, [join(atelier, 'cli.mjs'), recipePath, '--out', out, '--client-jar', clientJar], { stdio: 'inherit' });
if (compiled.status !== 0) process.exit(compiled.status ?? 1);
const [motion, machine, viewer, css] = await Promise.all([
  readFile(join(own, 'motion.mjs'), 'utf8'), readFile(join(own, 'machine.mjs'), 'utf8'),
  readFile(join(own, 'preview.js'), 'utf8'), readFile(join(own, 'preview.css'), 'utf8'),
]);
const inline = value => JSON.stringify(value).replace(/</g, '\\u003c');
const script = [
  `const SAWMILL_CAPTURE = ${inline({ world: snapshot.world, capturedAt: snapshot.capturedAt, origin: min, sha256: createHash('sha256').update(snapshotText).digest('hex') })};`,
  `const runtime = ${runtimeText};`,
  `const NATIVE_ITEM_FACES = ${inline(nativeFaces)};`,
  `const SAWMILL = {origin:${inline(SAWMILL.origin)},views:${inline(SAWMILL.views)},${SAWMILL.previewMask.toString()}};`,
  motion.replace(/^import .*\n/gm, '').replace(/^const runtime = .*\n/gm, '').replace(/\bexport /g, ''), viewer,
].join('\n');
let html = await readFile(join(out, 'index.html'), 'utf8');
html = html.replace('</head>', `<style>${css}</style></head>`);
html = html.replace('</script></body>', `${script}\n</script></body>`);
html = html.replace('<title>Ателье локаций · RusCrafting</title>', '<title>Пилорама · Кедровый двор</title>');
await writeFile(join(out, 'index.html'), html);
// No world-import artifact is part of this animation preview.
await rm(join(out, 'scene.schem'));
const hidden = snapshot.blocks.filter(([x, y, z, state]) => SAWMILL.previewMask([x, y, z], state));
await writeFile(join(out, 'preview-mask.json'), JSON.stringify({
  purpose: 'Only the browser preview hides these props. No world mutation.', count: hidden.length, blocks: hidden,
}, null, 2) + '\n');
await writeFile(join(out, 'preview-manifest.json'), JSON.stringify({
  schema: 1, world: snapshot.world, capturedAt: snapshot.capturedAt,
  source: 'CedarSawmillGeometry resource + compiled CedarSawmillAnimation sample at10Hz; native ItemsAdder model faces include the nativeY180 base.',
  runtimeExportSha256: createHash('sha256').update(runtimeText).digest('hex'),
  sourceClassSha256,
  nativeModels: Object.fromEntries(Object.entries(runtime.nativeModels).map(([kind, model]) => [kind, model.sha256])),
  partCount: runtime.parts.length,
  sceneScale: runtime.sceneScale,
  snapshotSha256: createHash('sha256').update(snapshotText).digest('hex'),
  staticBlockCount: snapshot.blocks.length, previewMaskedProps: hidden.length,
  cycleSeconds: runtime.cycleSeconds, nativeVersion: snapshot.minecraftVersion,
  renderer: 'Location Atelier vanilla block model + texture baker, Three 0.128.0',
  limitations: ['No server writes', 'No Minecraft native client acceptance', 'Workshop lighting is an offline approximation', 'Native model loading, packet delivery and sound require live validation'],
}, null, 2) + '\n');
console.log(`Animated preview: ${join(out, 'index.html')} (${hidden.length} interior props masked only in preview)`);
