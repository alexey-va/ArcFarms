#!/usr/bin/env node
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { makeParts } from './machine.mjs';
import { CYCLE_SECONDS, LOG_BOTTOM, LOG_HEIGHT, SCENE_SCALE, sampleCycle } from './motion.mjs';

const EPSILON = 1e-7;
const bounds = part => ({
  min: part.center.map((value, axis) => value - part.size[axis] / 2),
  max: part.center.map((value, axis) => value + part.size[axis] / 2),
});

// Check actual exposed, equally oriented faces; buried intersections and shared edges are harmless.
function exposedCoplanarFaces(parts) {
  const boxes = parts.filter(part => part.motion === 'fixed'
    && !part.shape && !(part.rotation ?? []).some(value => Math.abs(value) > EPSILON))
    .map(part => ({ ...part, ...bounds(part) }));
  const result = [];
  for (let a = 0; a < boxes.length; a++) for (let b = a + 1; b < boxes.length; b++) {
    const first = boxes[a], second = boxes[b];
    for (let axis = 0; axis < 3; axis++) for (const side of [-1, 1]) {
      const face = side < 0 ? 'min' : 'max';
      const plane = first[face][axis];
      if (axis === 1 && side < 0 && plane <= EPSILON) continue; // Buried in the captured room floor.
      if (Math.abs(plane - second[face][axis]) > EPSILON) continue;
      const other = [0, 1, 2].filter(value => value !== axis);
      const overlap = other.map(value => [Math.max(first.min[value], second.min[value]), Math.min(first.max[value], second.max[value])]);
      if (overlap.some(([lo, hi]) => hi - lo <= EPSILON)) continue;
      let exposed = false;
      for (const u of [.2, .5, .8]) for (const v of [.2, .5, .8]) {
        const point = [];
        point[axis] = plane + side * 1e-5;
        point[other[0]] = overlap[0][0] + (overlap[0][1] - overlap[0][0]) * u;
        point[other[1]] = overlap[1][0] + (overlap[1][1] - overlap[1][0]) * v;
        if (!boxes.some(box => point.every((value, dimension) => value > box.min[dimension] + EPSILON && value < box.max[dimension] - EPSILON))) exposed = true;
      }
      if (exposed) result.push(`${first.name}/${second.name}: axis ${axis}, plane ${plane}`);
    }
  }
  return result;
}

// Reproduce the reported table defect: a wooden top and the broad metal plate shared y=1.11.
const originalTable = [
  { name: 'metal', center: [.74, 1.055, 0], size: [1.24, .11, 2.75], motion: 'fixed' },
  { name: 'wood', center: [.82, 1.085, 0], size: [.96, .05, 2.7], motion: 'fixed' },
];
assert.ok(exposedCoplanarFaces(originalTable).length > 0, 'detector must reproduce the original z-fighting');

const parts = makeParts().map(part => ({ ...part,
  center: part.center.map(value => value * SCENE_SCALE), size: part.size.map(value => value * SCENE_SCALE),
}));
assert.deepEqual(exposedCoplanarFaces(parts), [], 'fixed exposed surfaces must not fight for depth');
for (const part of parts) {
  assert.ok([...part.center, ...part.size].every(Number.isFinite), `non-finite geometry: ${part.name}`);
  assert.ok(part.size.every(value => value > 0), `non-positive geometry: ${part.name}`);
}
const top = part => part.center[1] + part.size[1] / 2;
const table = parts.find(part => part.name === 'table-right');
const arbor = parts.find(part => part.name === 'arbor');
assert.ok(arbor.center[1] < top(table), 'the circular saw spindle belongs below the table');
for (const part of parts.filter(part => part.motion === 'arbor')) {
  assert.ok(part.center[1] + Math.hypot(part.size[1], part.size[2]) / 2 < top(table),
    `${part.name} must stay below the table throughout its full rotation`);
}
assert.ok(Math.abs(top(table) - LOG_BOTTOM) < EPSILON, 'stock must touch the table');
for (const part of parts.filter(part => ['timber-bolster', 'outfeed-top'].includes(part.name))) {
  assert.ok(Math.abs(top(part) - LOG_BOTTOM) < EPSILON, `${part.name} must support the stock without a gap`);
}
const nativeModels = JSON.parse(readFileSync(new URL('../../src/main/resources/sawmill/cedar-machine.json', import.meta.url), 'utf8')).nativeModels;
const blade = parts.find(part => part.name === 'blade-rotor');
assert.ok(blade?.itemModel === nativeModels.blade.itemModel, 'the saw must use the complete rigid native blade model');
assert.ok(Math.max(...nativeModels.blade.boxes.map(box => blade.center[1] + box.max[1] * blade.size[1])) > LOG_BOTTOM + LOG_HEIGHT,
  'the solid disc must reach through the entire stock');
for (const pass of [0, 1]) {
let previousCut = Infinity;
for (let time = 0; time <= CYCLE_SECONDS; time += .025) {
  const state = sampleCycle(time, pass);
  assert.ok(Object.values(state).filter(value => typeof value === 'number').every(Number.isFinite), `invalid pose at ${time}`);
  assert.ok(state.cutThreshold <= previousCut + EPSILON, 'sawn material must not grow back during return');
  previousCut = state.cutThreshold;
}
const finished = sampleCycle(CYCLE_SECONDS, pass);
assert.ok(finished.finished && !finished.contact, 'the stopped cycle must not keep cutting');
assert.notEqual(finished.outputZ, finished.carriageZ, 'the offcut must remain on the outfeed during carriage return');
}
const runtime = JSON.parse(readFileSync(new URL('../../build/reports/sawmill-preview/runtime-export.json', import.meta.url), 'utf8'));
const final = new Map(runtime.passes[1].at(-1)[8].map(pose => [runtime.parts[pose[0]].id, pose]));
const stock = final.get('timber-3-sawn');
assert.equal(stock[1], 0, 'the second pass must leave the carriage empty');
const plank = final.get('timber-board-3'), cant = final.get('timber-final-3');
assert.equal(plank[1], 1);assert.equal(cant[1], 1);
assert.ok(Math.abs(plank[3] - plank[5] / 2 - (LOG_BOTTOM + .175 * SCENE_SCALE)) < 1e-5, 'second board must touch the first output');
const cantMinimumX = Math.min(...runtime.nativeModels.sawn.boxes.map(box=>box.min[0]));
assert.ok(Math.abs(cant[3] + cantMinimumX * cant[5] - (plank[3] + plank[5] / 2)) < 1e-5, 'cut-face-up cant must touch the second board');
assert.ok(Math.abs(cant[4]-plank[4]) > .25, 'staggered board and cant ends must remain visibly separate');
console.log(`Sawmill checks passed: ${parts.length} parts, ${CYCLE_SECONDS}s cycle, original z-fighting reproduced and current fixed faces clear.`);
