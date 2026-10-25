// The native runtime resource is the only machine geometry source.
import { readFileSync } from 'node:fs';
const document = JSON.parse(readFileSync(new URL('../../src/main/resources/sawmill/cedar-machine.json', import.meta.url), 'utf8'));
export const SAWMILL = {
  origin: document.origin,
  views: document.views,
  previewMask(point, state) {
    const [x, y, z] = point;
    return x >= 183 && x <= 196 && y >= 73 && y <= 78 && z >= 167 && z <= 191
      && /^minecraft:(rail|spruce_slab|spruce_stairs|spruce_log|iron_chain|stonecutter)(?:\[|$)/.test(state);
  },
};
export function makeParts() { return document.parts; }
