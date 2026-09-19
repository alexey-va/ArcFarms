#!/usr/bin/env node

import fs, {existsSync} from "node:fs";
import path from "node:path";
import {pathToFileURL} from "node:url";

// Keep the resource compiler aligned with the ops Atelier CLI. The environment
// override lets CI point at its checked-out ops repository; the sibling path is
// the normal RusCrafting workspace layout.
const root = path.resolve(new URL("..", import.meta.url).pathname);
const opsRoot = process.env.RUSCRAFTING_OPS_ROOT
  ? path.resolve(process.env.RUSCRAFTING_OPS_ROOT)
  : [path.resolve(root, "../ruscrafting-ops"), path.resolve(root, "../../ruscrafting-ops")]
    .find((candidate) => existsSync(path.join(candidate, "scripts/location-atelier/engine.mjs"))) ??
    path.resolve(root, "../ruscrafting-ops");
const {compile: canonicalCompile} = await import(pathToFileURL(
  path.join(opsRoot, "scripts/location-atelier/engine.mjs"),
).href);

const sourcePath = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(root, "src/main/resources/mine/workings/lateral-working.atelier.json");
const outDir = process.argv[3]
  ? path.resolve(process.argv[3])
  : path.join(root, "src/main/resources/mine/workings");
const source = JSON.parse(fs.readFileSync(sourcePath, "utf8"));
const work = source.working;
const [width, height, length] = source.size;
const canonical = canonicalCompile(source);
const [originX, originY, originZ] = source.origin ?? [0, 0, 0];
// The canonical export omits air cells; retain them in the layout so the
// journal can restore a corridor that was carved by the working.
const blockList = [];
for (let y = 0; y < height; y += 1) for (let z = 0; z < length; z += 1) for (let x = 0; x < width; x += 1) {
  const materialId = canonical.get(x, y, z);
  blockList.push({
    side: x - originX,
    up: y - originY,
    forward: z - originZ,
    block: materialId ? canonical.materials[materialId].block : "minecraft:air",
  });
}
const key = (x, y, z) => `${x},${y},${z}`;
const blocks = new Map(blockList.map((entry) => [key(entry.side, entry.up, entry.forward), entry]));

const rel = (side, up, forward) => ({side, up, forward});
const range = (from, to) => Array.from({length: to - from + 1}, (_, index) => from + index);
const list = (values) => values.map(([side, up, forward]) => rel(side, up, forward));
const corridorAir = [];
for (const side of range(work.corridor.minSide, work.corridor.maxSide))
  for (const up of range(work.corridor.minUp, work.corridor.maxUp))
    for (const forward of range(work.corridor.from, work.corridor.to)) corridorAir.push(rel(side, up, forward));
const workshopAir = [];
if (work.workshop) {
  for (const side of range(work.workshop.minSide + 1, work.workshop.maxSide - 1))
    for (const up of range(work.workshop.minUp, work.workshop.maxUp))
      for (const forward of range(work.workshop.from + 1, work.workshop.to - 1)) workshopAir.push(rel(side, up, forward));
}
const doorwayAir = (work.doorways ?? []).flatMap((forward) =>
  range(work.corridor.minSide, work.corridor.maxSide).flatMap((side) =>
    range(work.corridor.minUp, work.corridor.maxUp).map((up) => rel(side, up, forward))));
const walkable = [...corridorAir, ...workshopAir, ...doorwayAir];
const excavation = [];
for (const forward of range(work.excavation.from, work.excavation.to))
  for (const up of range(1, 3))
    for (const side of range(-1, 1)) excavation.push(rel(side, up, forward));
const supports = work.supports.map((forward) => rel(0, 4, forward));
const supportFrame = work.supportFrame ?? {minSide: -1, maxSide: 1, up: 4};
const supportFrames = work.supports.map((forward) =>
  range(supportFrame.minSide, supportFrame.maxSide).map((side) => rel(side, supportFrame.up, forward)));
const rails = range(work.rails.from, work.rails.to).map((forward) => rel(0, 1, forward));
const cartRoute = range(work.rails.from, work.rails.to).map((forward) => rel(0, 1, forward));
const extensionRubble = work.extensionRubble.map((forward) => rel(0, 1, forward));
const trackDamageGaps = work.trackDamageGaps.map((forward) => rel(0, 1, forward));
const fixtures = list(work.fixtures ?? []);
const shell = [...blocks.values()]
  .filter((entry) => entry.block !== "minecraft:air" && !walkable.some((p) => p.side === entry.side && p.up === entry.up && p.forward === entry.forward))
  .map(({side, up, forward}) => rel(side, up, forward));
const stations = Object.fromEntries(Object.entries(work.stations).map(([name, [side, up, forward]]) => [name, rel(side, up, forward)]));
const compiled = {
  version: source.version,
  title: source.title,
  dimensions: {width, height, length},
  entrance: rel(0, 0, 0),
  blocks: [...blocks.values()],
  shell,
  walkable,
  excavation,
  supports,
  supportFrames,
  rails,
  cartRoute,
  extensionRubble,
  trackDamageGaps,
  fixtures,
  stations,
};
const blocksOutput = {
  version: source.version,
  title: source.title,
  size: source.size,
  origin: source.origin,
  palette: source.palette,
  blocks: canonical.blocks.map(([x, y, z, materialId]) => [x, y, z, canonical.materials[materialId].block]),
};
const metadataOutput = {...compiled, blocks: undefined};
fs.mkdirSync(outDir, {recursive: true});
fs.writeFileSync(path.join(outDir, "lateral-working.layout.json"), `${JSON.stringify({...compiled, compiler: canonical.report}, null, 2)}\n`);
fs.writeFileSync(path.join(outDir, "lateral-working.blocks.json"), `${JSON.stringify(blocksOutput, null, 2)}\n`);
fs.writeFileSync(path.join(outDir, "lateral-working.metadata.json"), `${JSON.stringify(metadataOutput, null, 2)}\n`);
console.log(`generated ${blocks.size} blocks via canonical Atelier compiler, shell=${shell.length}, walkable=${walkable.length}, output=${outDir}`);
