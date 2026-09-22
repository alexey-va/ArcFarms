// QA-only, fixed-size client-side effect pool for the inline-six preview.
const dieselEffectTextures = typeof DIESEL_EFFECT_ASSETS_AVAILABLE !== 'undefined' && DIESEL_EFFECT_ASSETS_AVAILABLE && typeof THREE !== 'undefined' ? {
  flame: new THREE.TextureLoader().load('diesel-effects/flame.png'),
  smoke: new THREE.TextureLoader().load('diesel-effects/big_smoke_0.png'),
} : null;

function dieselRampIntegratedMillis(elapsedMillis) {
  const t = Math.max(0, elapsedMillis);
  const u = Math.min(t / 6000, 1);
  return 6000 * (.28 * u + .72 * (u ** 3 - .5 * u ** 4)) + Math.max(0, t - 6000);
}

function dieselRampPhase(elapsedMillis) {
  return Math.PI * 2 + Math.PI * 2 / 3000 * dieselRampIntegratedMillis(elapsedMillis);
}

function dieselRampSpeed(elapsedMillis) {
  const u = Math.min(Math.max(elapsedMillis, 0) / 6000, 1);
  return .28 + .72 * (3 * u ** 2 - 2 * u ** 3);
}

function createDieselEffects(root, definition) {
  if (!dieselEffectTextures || !definition || !Array.isArray(definition.combustion) || definition.combustion.length !== 6 ||
      !Array.isArray(definition.firingOrder) || definition.firingOrder.length !== 6 ||
      !Array.isArray(definition.exhaust) || definition.exhaust.length !== 3 ||
      !definition.combustion.every((point) => Array.isArray(point) && point.length === 3 && point.every(Number.isFinite)) ||
      !definition.exhaust.every(Number.isFinite)) return null;

  const cycleMillis = Number(definition.cycleMillis);
  const ignitionMillis = Number(definition.ignitionMillis);
  const ignitionWindowMillis = Number(definition.ignitionWindowMillis);
  const exhaustMillis = Number(definition.exhaustMillis);
  if (![cycleMillis, ignitionMillis, ignitionWindowMillis, exhaustMillis].every(Number.isFinite) ||
      cycleMillis <= 0 || ignitionMillis <= 0 || ignitionWindowMillis <= 0 || exhaustMillis <= 0 ||
      new Set(definition.firingOrder).size !== 6 ||
      definition.firingOrder.some((cylinder) => !Number.isInteger(cylinder) || cylinder < 0 || cylinder >= 6)) return null;

  const makeSprite = (texture, color) => {
    const material = new THREE.SpriteMaterial({
      map: texture, color, transparent: true, opacity: 0, depthWrite: false,
    });
    const sprite = new THREE.Sprite(material);
    sprite.visible = false;
    root.add(sprite);
    return sprite;
  };
  const flames = definition.combustion.map((point, cylinder) => ({
    at: new THREE.Vector3(...point),
    sprite: makeSprite(dieselEffectTextures.flame, 0xff9a45),
    ignitedAt: null,
    cylinder,
  }));
  const smokePuffs = Array.from({ length: 8 }, () => ({
    sprite: makeSprite(dieselEffectTextures.smoke, 0xc4c8c9),
    startedAt: null,
  }));
  const exhaustAt = new THREE.Vector3(...definition.exhaust);
  let nextExhaustAt = 0;
  let exhaustPoolCursor = 0;
  function hide(sprite) {
    sprite.visible = false;
    sprite.material.opacity = 0;
  }

  function reset() {
    flames.forEach((flame) => { flame.ignitedAt = null; hide(flame.sprite); });
    smokePuffs.forEach((puff) => { puff.startedAt = null; hide(puff.sprite); });
    nextExhaustAt = 0;
    exhaustPoolCursor = 0;
  }

  function tick(engineElapsedMillis, cyclePositionMillis) {
    if (!Number.isFinite(engineElapsedMillis) || !Number.isFinite(cyclePositionMillis)) {
      reset();
      return;
    }
    const position = ((cyclePositionMillis % cycleMillis) + cycleMillis) % cycleMillis;
    const order = Math.floor(position / ignitionMillis);
    const eventAgeMillis = position - order * ignitionMillis;
    const cylinder = definition.firingOrder[order];
    // Derive only the current beat from phase; a delayed frame never replays missed fires.
    flames.forEach((flame) => { flame.ignitedAt = null; hide(flame.sprite); });
    if (cylinder !== undefined && eventAgeMillis < ignitionWindowMillis) {
      flames[cylinder].ignitedAt = engineElapsedMillis - eventAgeMillis;
    }

    flames.forEach(({ at, sprite, ignitedAt, cylinder }) => {
      const age = ignitedAt === null ? Infinity : engineElapsedMillis - ignitedAt;
      if (age < 0 || age >= ignitionWindowMillis) { hide(sprite); return; }
      const progress = age / ignitionWindowMillis;
      const flicker = .92 + .08 * Math.sin(engineElapsedMillis * .075 + cylinder * 1.7);
      sprite.visible = true;
      sprite.position.set(at.x, at.y + progress * .035, at.z);
      sprite.scale.set(.22 + progress * .035, .30 + progress * .08, 1);
      sprite.material.opacity = (1 - progress) * flicker;
    });

    if (engineElapsedMillis >= nextExhaustAt) {
      const puff = smokePuffs[exhaustPoolCursor];
      puff.startedAt = engineElapsedMillis;
      exhaustPoolCursor = (exhaustPoolCursor + 1) % smokePuffs.length;
      nextExhaustAt = engineElapsedMillis + exhaustMillis / dieselRampSpeed(engineElapsedMillis);
    }
    smokePuffs.forEach(({ sprite, startedAt }) => {
      const age = startedAt === null ? Infinity : engineElapsedMillis - startedAt;
      if (age < 0 || age >= 2400) { hide(sprite); return; }
      const progress = age / 2400;
      sprite.visible = true;
      sprite.position.set(exhaustAt.x, exhaustAt.y + 1.2 * progress, exhaustAt.z);
      sprite.scale.set(.18 + .58 * progress, .18 + .68 * progress, 1);
      sprite.material.opacity = .46 * (1 - progress);
    });
  }

  function dispose() {
    reset();
    [...flames.map(({ sprite }) => sprite), ...smokePuffs.map(({ sprite }) => sprite)].forEach((sprite) => {
      root.remove(sprite);
      sprite.material.dispose();
    });
  }

  return { cycleMillis, tick, reset, dispose };
}

if (typeof process !== 'undefined' && process.argv[1]?.endsWith('diesel-effects.js')) {
  const assert = require('node:assert/strict');
  assert.equal(dieselRampIntegratedMillis(0), 0);
  assert.equal(dieselRampIntegratedMillis(6000), 3840);
  assert.equal(dieselRampIntegratedMillis(7000), 4840);
  assert.equal(dieselRampSpeed(0), .28);
  assert.equal(dieselRampSpeed(6000), 1);
  assert.equal(dieselRampSpeed(7000), 1);
  assert.ok(Math.abs(dieselRampPhase(6000) - (Math.PI * 2 + Math.PI * 2 * 1.28)) < 1e-12);
}
