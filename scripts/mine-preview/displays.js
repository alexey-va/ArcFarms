// QA only: same cuboid transforms as the packet renderer, using Atelier's pinned vanilla assets.
// The schematic contains blocks only; these assemblies remain owned by ArcFarms.
let expeditionDisplays = null;
let expeditionMovingParts = [];
let expeditionDemo = false;
let expeditionFrame = null;
const demoRotationAxis = new THREE.Vector3(0, 0, 1);
const dieselMotions = new Set(Array.from({ length: 6 }, (_, i) => [`diesel_piston_${i}`, `diesel_rod_${i}`]).flat());
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
function poseExpeditionPart(mesh, part, phase) {
  const angle = !part.moving || ['press', 'feed', 'processed'].includes(part.motion) ? 0
    : part.motion === 'lever' ? Math.sin(phase / 2) * .5 : part.motion === 'counter_rotate' ? -phase : phase;
  const axis = part.motion === 'axle' ? new THREE.Vector3(1, 0, 0) : demoRotationAxis;
  let center = new THREE.Vector3(...part.center);
  let rotation = new THREE.Quaternion().setFromAxisAngle(axis, part.angle + angle);
  if (dieselMotions.has(part.motion)) {
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
      poseExpeditionPart(mesh, part, 0);
      root.add(mesh);
      if (part.moving) expeditionMovingParts.push({ mesh, part });
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
const demoNote = document.createElement('p');
demoNote.className = 'subtle';
demoNote.textContent = 'Демонстрация движения механизмов. Порядок заданий, поездка на платформе, звук и перенос груза проверяются в Minecraft.';
demoPanel.append(demoToggle, demoNote);
document.querySelector('aside').prepend(demoPanel);
demoToggle.onclick = () => {
  expeditionDemo = !expeditionDemo;
  demoToggle.setAttribute('aria-pressed', String(expeditionDemo));
  if (expeditionFrame !== null) cancelAnimationFrame(expeditionFrame);
  expeditionFrame = null;
  if (!expeditionDemo) {
    for (const { mesh, part } of expeditionMovingParts) poseExpeditionPart(mesh, part, 0);
    render();
    return;
  }
  const started = performance.now();
  let lastFrame = -Infinity;
  const animate = now => {
    if (now - lastFrame >= 1000 / 30) {
      for (const { mesh, part } of expeditionMovingParts) {
        const duration = part.motion === 'press' ? 2400 : part.motion === 'lever' ? 4500 : ['belt', 'cargo'].includes(part.motion) ? 6000 : 3000;
        const elapsed = (now - started) % (['press', 'lever'].includes(part.motion) ? duration + 1600 : duration);
        poseExpeditionPart(mesh, part, Math.min(1, elapsed / duration) * Math.PI * 2);
      }
      render();
      lastFrame = now;
    }
    expeditionFrame = requestAnimationFrame(animate);
  };
  expeditionFrame = requestAnimationFrame(animate);
};
