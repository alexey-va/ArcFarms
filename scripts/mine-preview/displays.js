// QA only: same cuboid transforms as the packet renderer, using Atelier's pinned vanilla assets.
// The schematic contains blocks only; these assemblies remain owned by ArcFarms.
let expeditionDisplays = null;
let expeditionMovingParts = [];
let expeditionDemo = false;
let expeditionFrame = null;
const demoRotationAxis = new THREE.Vector3(0, 0, 1);
function poseExpeditionPart(mesh, part, phase) {
  const angle = !part.moving || part.motion === 'press' ? 0
    : part.motion === 'lever' ? Math.sin(phase / 2) * .5 : phase;
  const center = new THREE.Vector3(...part.center);
  if (part.moving && part.motion === 'press') center.y -= (1 - Math.cos(phase)) * .5;
  else if (part.moving) {
    const pivot = new THREE.Vector3(...part.pivot);
    center.sub(pivot).applyAxisAngle(demoRotationAxis, angle).add(pivot);
  }
  const rotation = new THREE.Quaternion().setFromAxisAngle(demoRotationAxis, part.angle + angle);
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
demoNote.textContent = 'Движение деталей: колёса, рычаги и пресс. Звуки, частицы и перенос груза проверяются в Minecraft.';
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
        const duration = part.motion === 'press' ? 2400 : part.motion === 'lever' ? 4500 : 12000;
        const elapsed = (now - started) % (part.motion === 'rotate' ? duration : duration + 1600);
        poseExpeditionPart(mesh, part, Math.min(1, elapsed / duration) * Math.PI * 2);
      }
      render();
      lastFrame = now;
    }
    expeditionFrame = requestAnimationFrame(animate);
  };
  expeditionFrame = requestAnimationFrame(animate);
};
