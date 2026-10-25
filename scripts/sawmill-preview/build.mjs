#!/usr/bin/env node
// Preview only. Reuses Location Atelier's exact vanilla block baker and renderer.
import { readFile, writeFile, mkdir, rm } from 'node:fs/promises';
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
for (const part of parts) material(part.block);
for (const state of ['minecraft:spruce_log[axis=z]', 'minecraft:stripped_spruce_log[axis=z]', 'minecraft:spruce_planks']) material(state);
const views = SAWMILL.views.map(view => ({ ...view, camera: local(view.camera), target: local(view.target) }));
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
  motion.replace(/\bexport /g, ''), machine.replace(/\bexport /g, ''), viewer,
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
  source: 'Sanitized live native block snapshot; display animation is authored concept, not runtime export.',
  snapshotSha256: createHash('sha256').update(snapshotText).digest('hex'),
  staticBlockCount: snapshot.blocks.length, previewMaskedProps: hidden.length,
  cycleSeconds: CYCLE_DURATION_FROM_SOURCE(motion), nativeVersion: snapshot.minecraftVersion,
  renderer: 'Location Atelier vanilla block model + texture baker, Three 0.128.0',
  limitations: ['No server writes', 'No Minecraft native client acceptance', 'No resource-pack or native-particle/audio simulation'],
}, null, 2) + '\n');
console.log(`Animated preview: ${join(out, 'index.html')} (${hidden.length} interior props masked only in preview)`);

function CYCLE_DURATION_FROM_SOURCE(source) {
  return Number(source.match(/CYCLE_SECONDS\s*=\s*([\d.]+)/)?.[1]);
}
