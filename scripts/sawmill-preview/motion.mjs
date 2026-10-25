// Inspection adapters read compiled runtime poses; no independent choreography.
import { readFileSync } from 'node:fs';
const runtime = JSON.parse(readFileSync(new URL('../../build/reports/sawmill-preview/runtime-export.json', import.meta.url), 'utf8'));
export const CYCLE_SECONDS = runtime.cycleSeconds;
export const SCENE_SCALE = runtime.sceneScale;
export const CUT_PLANES = [.27 * SCENE_SCALE];
export const LOG_LENGTH = runtime.logLength;
export const LOG_BOTTOM = runtime.logBottom;
export const LOG_HEIGHT = runtime.logHeight;
export const PHASES = [
  { at: 0, label: 'Пуск пилы', description: 'Ременная передача разгоняет один большой пильный диск.' },
  { at: 1.2, label: 'Подача каретки', description: 'Бревно закреплено на поперечных опорах. Каретка плавно набирает ход.' },
  { at: 5.5, label: 'Продольный рез', description: 'Диск снимает текущую деталь. Опилки уходят вниз у режущей кромки.' },
  { at: 14.0, label: 'Перекладка деталей', description: 'Торец вышел за диск. Снятая деталь ложится на выходной стол.' },
  { at: 16.4, label: 'Выход и возврат', description: 'Оставшееся бревно отведено от диска и возвращается на каретке.' },
  { at: 23.0, label: 'Проход завершён', description: 'Пила останавливается. Рычаг возвращается в готовность к следующему нажатию.' },
];

export function sampleCycle(seconds, passIndex = 0) {
  const t = Math.max(0, Math.min(CYCLE_SECONDS, seconds));
  const frames = runtime.passes[passIndex];
  const index = Math.min(frames.length - 1, Math.floor(t / runtime.sampleSeconds + 1e-7));
  const first = frames[index], next = frames[Math.min(index + 1, frames.length - 1)];
  const alpha = Math.max(0, Math.min(1, (t - first[0]) / runtime.sampleSeconds));
  const scalar = key => first[key] + (next[key] - first[key]) * alpha;
  return { t, forwardZ: scalar(1), carriageZ: scalar(2), stockZ: scalar(2), lateral: scalar(3),
    cutThreshold: scalar(4), outputZ: scalar(5), split: scalar(6), contact: !!first[7],
    phase: PHASES.filter(phase => t >= phase.at).at(-1), finished: t >= CYCLE_SECONDS, index, alpha, passIndex };
}
