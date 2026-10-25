// One classic headrig pass. The remaining log keeps its sawn face during return.
export const CYCLE_SECONDS = 25;
export const CUT_PLANES = [.27];
export const LOG_LENGTH = 4.4;
export const LOG_BOTTOM = 1.27;
export const LOG_HEIGHT = .72;
export const PHASES = [
  { at: 0, label: 'Пуск пилы', description: 'Ременная передача разгоняет один большой пильный диск.' },
  { at: 1.2, label: 'Подача каретки', description: 'Бревно закреплено на поперечных опорах. Каретка плавно набирает ход.' },
  { at: 5.5, label: 'Продольный рез', description: 'Диск снимает один боковой горбыль. Опилки уходят вниз у режущей кромки.' },
  { at: 14.0, label: 'Снятие горбыля', description: 'Торец вышел за диск. Горбыль ложится свежим срезом вверх на выходной стол.' },
  { at: 16.4, label: 'Обратный ход', description: 'Оставшееся бревно отведено от диска и возвращается на каретке.' },
  { at: 23.0, label: 'Один проход готов', description: 'На столе — один горбыль. На каретке — то же бревно с ровной спиленной стороной.' },
];
export const clamp01 = value => Math.max(0, Math.min(1, value));
export const smooth = value => { const t = clamp01(value); return t * t * (3 - 2 * t); };
export const mix = (a, b, t) => a + (b - a) * t;
export function feedEase(value) {
  const t = clamp01(value), edge = .12, area = 1 - edge;
  if (t < edge) return t * t / (2 * edge * area);
  if (t > 1 - edge) return 1 - (1 - t) * (1 - t) / (2 * edge * area);
  return (t - edge / 2) / area;
}
export function sampleCycle(seconds) {
  const t = Math.max(0, Math.min(CYCLE_SECONDS, seconds));
  const forward = feedEase((t - 1.2) / 12.6);
  const forwardZ = mix(-5.8, 3.6, forward);
  const retreat = smooth((t - 16.4) / 6.6);
  const carriageZ = t < 16.4 ? forwardZ : mix(3.6, -5.8, retreat);
  const lateral = -.46 * smooth((t - 14.2) / 1.5);
  const split = smooth((t - 14.1) / 2.1);
  const speed = smooth(t / 1.5) * (1 - smooth((t - 23) / 2));
  const primitive = x => x * x * x - .5 * x * x * x * x;
  const poweredTime = t < 1.5 ? 1.5 * primitive(t / 1.5)
    : t <= 23 ? t - .75 : 22.25 + Math.min(t - 23, 2) - 2 * primitive(clamp01((t - 23) / 2));
  const angle = -poweredTime * Math.PI * 4.4;
  const contact = t < 14 && forwardZ + LOG_LENGTH / 2 > -.97 && forwardZ - LOG_LENGTH / 2 < .97 && speed > .1;
  const cutThreshold = Math.max(-LOG_LENGTH / 2, Math.min(LOG_LENGTH / 2, -forwardZ));
  return { t, stockZ: carriageZ, forwardZ, carriageZ, lateral, split, speed, angle, cutThreshold,
    outputZ: t < 14 ? forwardZ : 3.6, contact,
    phase: PHASES.filter(phase => t >= phase.at).at(-1), finished: t >= CYCLE_SECONDS };
}
export function splitLogInterval(z0, z1, cutThreshold) {
  return { raw: [z0, Math.min(z1, cutThreshold)], cut: [Math.max(z0, cutThreshold), z1] };
}
