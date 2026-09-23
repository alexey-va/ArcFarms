// QA only: same cuboid transforms as the packet renderer, using Atelier's pinned vanilla assets.
// The schematic contains blocks only; these assemblies remain owned by ArcFarms.
let expeditionDisplays = null;
let expeditionMovingParts = [];
let dieselEffectPools = [];
let expeditionDemo = false;
let expeditionFrame = null;
let expeditionDemoStartedAt = 0;
let dieselIgnitionAt = null;
const dieselDrivenModels = new Set(['factory_crusher', 'factory_conveyor', 'roller_table', 'furnace']);
const demoRotationAxis = new THREE.Vector3(0, 0, 1);
const dieselMotions = new Set(Array.from({ length: 6 }, (_, i) => [`diesel_piston_${i}`, `diesel_rod_${i}`, `diesel_valve_inlet_${i}`, `diesel_valve_exhaust_${i}`, `diesel_spring_inlet_${i}`, `diesel_spring_exhaust_${i}`]).flat());
function beltPose(part, phase) {
  const straight = 7.6, radius = .28, arc = Math.PI * radius;
  let distance = (((phase + part.angle) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2)) / (Math.PI * 2) * (straight * 2 + arc * 2);
  const at = new THREE.Vector3(...part.pivot);
  if (distance < straight) return [at.add(new THREE.Vector3(-straight / 2 + distance, radius, 0)), 0];
  distance -= straight;
  if (distance < arc) { const a = distance / radius; return [at.add(new THREE.Vector3(straight / 2 + Math.sin(a) * radius, Math.cos(a) * radius, 0)), -a]; }
  distance -= arc;
  if (distance < straight) return [at.add(new THREE.Vector3(straight / 2 - distance, -radius, 0)), -Math.PI];
  const a = (distance - straight) / radius;
  return [at.add(new THREE.Vector3(-straight / 2 - Math.sin(a) * radius, -Math.cos(a) * radius, 0)), -Math.PI - a];
}
function leverPose(part, phase) {
  const throwAngle = part.moving ? (1 - Math.cos(phase)) * Math.PI / 4 : 0;
  const horizontalArm = Math.abs(part.center[0] - part.pivot[0]) > Math.abs(part.center[1] - part.pivot[1]);
  if (horizontalArm) return { axis: new THREE.Vector3(0, 1, 0), angle: throwAngle };
  return {
    axis: new THREE.Vector3(1, 0, 0),
    angle: throwAngle * (part.pivot[2] < 0 ? -1 : 1),
  };
}
function poseExpeditionPart(mesh, part, phase) {
  const angle = !part.moving || ['press', 'feed', 'processed'].includes(part.motion) ? 0
    : part.motion === 'diesel_cam' ? phase / 2 : part.motion === 'counter_rotate' ? -phase : phase;
  const lever = part.motion === 'lever' ? leverPose(part, phase) : null;
  const axis = part.motion === 'axle' ? new THREE.Vector3(1, 0, 0) : demoRotationAxis;
  let center = new THREE.Vector3(...part.center);
  let rotation = lever
    ? new THREE.Quaternion().setFromAxisAngle(lever.axis, lever.angle)
      .multiply(new THREE.Quaternion().setFromAxisAngle(demoRotationAxis, part.angle))
    : new THREE.Quaternion().setFromAxisAngle(axis, part.angle + angle);
  if ((part.motion.startsWith('diesel_valve_') || part.motion.startsWith('diesel_spring_')) && dieselMotions.has(part.motion)) {
    const cylinder = Number(part.motion.slice(-1));
    const theta = phase / 2 - ([0, 2, 1, 4, 5, 3][cylinder] * Math.PI / 3 +
      (part.motion.includes('_inlet_') ? 5 : 3) * Math.PI / 4);
    const springFactor = part.motion.startsWith('diesel_spring_') ? Math.max(0, Math.min(1, (part.center[1] - 5.59) / .37)) : 1;
    center.y -= springFactor * Math.max(0, .22 * Math.cos(theta) + .21 * Math.abs(Math.cos(theta)) + .09 * Math.abs(Math.sin(theta)) - .316);
    rotation.setFromAxisAngle(demoRotationAxis, part.angle);
  } else if (dieselMotions.has(part.motion)) {
    // Exact slider-crank geometry shared with MineDieselGeneratorMotion.
    const offsets = [0, 2, 4, 4, 2, 0];
    const theta = phase + offsets[Number(part.motion.slice(-1))] * Math.PI / 3;
    const x = -.45 * Math.sin(theta), y = .45 * Math.cos(theta);
    const rise = Math.sqrt(2.5 * 2.5 - x * x);
    if (part.motion.startsWith('diesel_piston_')) {
      center.add(new THREE.Vector3(...part.pivot)).add(new THREE.Vector3(0, y + rise, 0));
      rotation.setFromAxisAngle(demoRotationAxis, part.angle);
    } else {
      const tilt = Math.atan2(x, rise);
      center.applyAxisAngle(demoRotationAxis, tilt).add(new THREE.Vector3(...part.pivot)).add(new THREE.Vector3(x / 2, y + rise / 2, 0));
      rotation.setFromAxisAngle(demoRotationAxis, part.angle + tilt);
    }
  } else if (part.motion === 'belt') {
    const [at, tangent] = beltPose(part, phase); center.add(at);
    rotation = new THREE.Quaternion().setFromAxisAngle(demoRotationAxis, tangent);
  } else if (part.motion === 'cargo') {
    const cycle = ((phase + part.angle) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2) / (Math.PI * 2);
    center.set(part.pivot[0] - 3.8 + cycle * 7.6 + part.center[0], part.pivot[1] + .58, part.pivot[2] + part.center[2]);
    rotation.identity();
  } else if (part.moving && part.motion === 'press') center.y -= (1 - Math.cos(phase)) * .5;
  else if (part.moving && part.motion === 'feed') center.y -= ((phase / (Math.PI * 2) + part.angle) % 1) * 2.8;
  else if (part.moving && part.motion === 'lever') {
    const pivot = new THREE.Vector3(...part.pivot);
    center.sub(pivot).applyAxisAngle(lever.axis, lever.angle).add(pivot);
  }
  else if (part.moving && part.motion !== 'processed') {
    const pivot = new THREE.Vector3(...part.pivot);
    center.sub(pivot).applyAxisAngle(axis, angle).add(pivot);
  }
  mesh.visible = !part.idleHidden || expeditionDemo;
  // The client renderer hides a completed load while resetting its transform.
  // Keep the same disappearance in the preview instead of showing a return trip.
  if (expeditionDemo && ['feed', 'cargo'].includes(part.motion)) {
    const cycle = part.motion === 'feed'
      ? ((phase / (Math.PI * 2) + part.angle) % 1 + 1) % 1
      : ((phase + part.angle) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2) / (Math.PI * 2);
    mesh.visible = cycle >= .04 && cycle <= .96;
  }
  mesh.quaternion.copy(rotation);
  mesh.scale.set(...part.size);
  mesh.position.copy(new THREE.Vector3(...part.size).multiplyScalar(-.5).applyQuaternion(rotation).add(center));
}
const buildBlockScene = buildMesh;
buildMesh = function () {
  buildBlockScene();
  dieselEffectPools.forEach((pool) => pool.dispose());
  dieselEffectPools = [];
  if (expeditionDisplays) { world.remove(expeditionDisplays); disposeVanillaMesh(expeditionDisplays); }
  expeditionDisplays = new THREE.Group();
  expeditionMovingParts = [];
  for (const assembly of recipe.displayAssemblies ?? []) {
    if (assembly.at[1] > cutHeight || assembly.at[2] > cutDepth) continue;
    const root = new THREE.Group();
    root.position.set(...assembly.at);
    root.scale.setScalar(assembly.scale ?? 1);
    root.rotation.y = (assembly.yaw ?? 0) * Math.PI / 180;
    for (const part of recipe.displayModels[assembly.model]) {
      const compiled = { materials: [{ block: 'minecraft:air' }, { block: part.block }], blocks: [[0, 0, 0, 1]], get: () => 0 };
      const mesh = createVanillaMesh(compiled, Infinity, lightMode === 'clay', false, null, Infinity);
      if (assembly.model === 'factory_diesel_generator') mesh.userData.engineInspection = part.inspection ?? null;
      poseExpeditionPart(mesh, part, 0);
      root.add(mesh);
      if (part.moving) expeditionMovingParts.push({ mesh, part, model: assembly.model });
    }
    if (assembly.model === 'factory_diesel_generator') {
      const effects = createDieselEffects(root, recipe.dieselEffects);
      if (effects) dieselEffectPools.push(effects);
    }
    expeditionDisplays.add(root);
  }
  world.add(expeditionDisplays);
  render();
};
const demoPanel = document.createElement('div');
demoPanel.className = 'section';
const demoToggle = document.createElement('button');
demoToggle.textContent = 'Демо механизмов';
demoToggle.setAttribute('aria-pressed', 'false');
const dieselStartButton = document.createElement('button');
const hasDieselGenerator = (recipe.displayAssemblies ?? []).some(({ model }) => model === 'factory_diesel_generator');
dieselStartButton.hidden = !hasDieselGenerator;
const dieselStatus = document.createElement('p');
dieselStatus.className = 'subtle';
dieselStatus.hidden = !hasDieselGenerator;
const demoNote = document.createElement('p');
demoNote.className = 'subtle';
demoNote.textContent = 'Рычаги один раз переводятся при включении демо и остаются включёнными до выключения. Порядок заданий, поездка на платформе, звук и перенос груза проверяются в Minecraft.';
function refreshDieselControls(now = performance.now()) {
  const rampElapsed = dieselIgnitionAt === null ? null : now - dieselIgnitionAt;
  dieselStartButton.textContent = rampElapsed === null ? 'Пуск двигателя'
    : rampElapsed < 6000 ? 'Запуск двигателя…' : 'Двигатель работает';
  dieselStartButton.disabled = dieselIgnitionAt !== null;
  if (rampElapsed === null) {
    dieselStatus.textContent = 'Двигатель остановлен';
  } else {
    dieselStatus.textContent = rampElapsed < 6000
      ? `Дизель набирает обороты · ${Math.round(dieselRampSpeed(rampElapsed) * 100)}%`
      : 'Дизель работает';
  }
}
demoPanel.append(demoToggle, dieselStartButton, dieselStatus, demoNote);
document.querySelector('aside').prepend(demoPanel);
function setExpeditionDemo(enabled, now = performance.now()) {
  if (enabled === expeditionDemo) return;
  expeditionDemo = enabled;
  demoToggle.setAttribute('aria-pressed', String(expeditionDemo));
  if (expeditionFrame !== null) cancelAnimationFrame(expeditionFrame);
  expeditionFrame = null;
  if (!expeditionDemo) {
    for (const { mesh, part } of expeditionMovingParts) poseExpeditionPart(mesh, part, 0);
    dieselEffectPools.forEach((pool) => pool.reset());
    dieselIgnitionAt = null;
    refreshDieselControls(now);
    render();
    return;
  }
  expeditionDemoStartedAt = now;
  let lastFrame = -Infinity;
  const animate = now => {
    if (!expeditionDemo) return;
    if (now - lastFrame >= 1000 / 30) {
      const demoElapsed = now - expeditionDemoStartedAt;
      const dieselElapsed = dieselIgnitionAt === null ? null : now - dieselIgnitionAt;
      const dieselReady = dieselElapsed !== null && dieselElapsed >= 6000;
      const dieselPhase = dieselElapsed !== null && dieselElapsed >= 0
        ? dieselRampPhase(dieselElapsed)
        : 0;
      for (const { mesh, part, model } of expeditionMovingParts) {
        const diesel = model === 'factory_diesel_generator';
        if (hasDieselGenerator && !diesel && dieselDrivenModels.has(model) && !dieselReady) {
          poseExpeditionPart(mesh, part, 0);
          continue;
        }
        if (diesel && (dieselElapsed === null || dieselElapsed < 0)) {
          poseExpeditionPart(mesh, part, dieselPhase);
          continue;
        }
        if (part.motion === 'lever') {
          const phase = model === 'factory_route_gate'
            ? 0
            : Math.PI * Math.min(1, demoElapsed / 480);
          poseExpeditionPart(mesh, part, phase);
          continue;
        }
        const duration = diesel ? 6000 : part.motion === 'press' ? 2400 : ['belt', 'cargo'].includes(part.motion) ? 6000 : 3000;
        const elapsed = demoElapsed % (['press', 'lever'].includes(part.motion) ? duration + 1600 : duration);
        poseExpeditionPart(mesh, part, diesel ? dieselPhase : Math.min(1, elapsed / duration) * Math.PI * 2);
      }
      if (dieselElapsed !== null && dieselElapsed >= 0) {
        dieselEffectPools.forEach((pool) => {
          const dieselCyclePosition = ((dieselPhase / (Math.PI * 4)) * pool.cycleMillis) % pool.cycleMillis;
          pool.tick(dieselElapsed, dieselCyclePosition);
        });
      } else dieselEffectPools.forEach((pool) => pool.reset());
      refreshDieselControls(now);
      render();
      lastFrame = now;
    }
    expeditionFrame = requestAnimationFrame(animate);
  };
  expeditionFrame = requestAnimationFrame(animate);
}
demoToggle.onclick = () => setExpeditionDemo(!expeditionDemo);
dieselStartButton.onclick = () => {
  const now = performance.now();
  if (!hasDieselGenerator || dieselIgnitionAt !== null) return;
  if (!expeditionDemo) setExpeditionDemo(true, now);
  dieselIgnitionAt = now;
  refreshDieselControls(now);
};
refreshDieselControls();
