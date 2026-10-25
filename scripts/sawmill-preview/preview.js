// Runs inside the existing Atelier page after its renderer, before texture load completes.
const millRoot = new THREE.Group();
millRoot.position.set(...SAWMILL.origin.map((value, index) => value - SAWMILL_CAPTURE.origin[index]));
world.add(millRoot);
const millParts = [];
let millLeverHitbox = null, millLeverHovered = false;
let millTime = 0, millPlaying = false;
let millSpeed = 1, millLastFrame = null, millAsBuilt = false;
const query = new URLSearchParams(location.search);
if (query.has('t')) millTime = Math.max(0, Math.min(CYCLE_SECONDS, Number(query.get('t')) || 0));
if (query.get('paused') === '1') millPlaying = false;

const cubeModel = block => createVanillaMesh({
  materials: [{ block: 'minecraft:air' }, { block }], blocks: [[0, 0, 0, 1]], get: () => 0,
}, Infinity, false, false, null, Infinity);
const quat = rotation => new THREE.Quaternion().setFromEuler(new THREE.Euler(...rotation));
function placePart(object, center, size, rotation = new THREE.Quaternion()) {
  object.scale.set(...size); object.quaternion.copy(rotation);
  object.position.copy(new THREE.Vector3(...size).multiplyScalar(-.5).applyQuaternion(rotation).add(new THREE.Vector3(...center)));
}
function nativeItemMesh(itemModel) {
  const descriptor = NATIVE_ITEM_FACES[itemModel];
  const batches = new Map();
  for (const face of descriptor.faces) {
    if (!batches.has(face.texture)) batches.set(face.texture, { positions: [], normals: [], uv: [] });
    const batch = batches.get(face.texture), vertices = face.positions;
    const a = new THREE.Vector3(...vertices[0]), b = new THREE.Vector3(...vertices[1]), c = new THREE.Vector3(...vertices[2]);
    const outward = b.sub(a).cross(c.sub(a)).dot(new THREE.Vector3(...face.normal)) >= 0;
    for (const index of outward ? [0,1,2,0,2,3] : [0,2,1,0,3,2]) {
      batch.positions.push(...vertices[index]);batch.normals.push(...face.normal);batch.uv.push(...face.uv[index]);
    }
  }
  const group = new THREE.Group();
  for (const [texture, batch] of batches) {
    if (!vanillaTextures.has(texture)) throw Error(`Missing native item PNG: ${texture}`);
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(batch.positions, 3));
    geometry.setAttribute('normal', new THREE.Float32BufferAttribute(batch.normals, 3));
    geometry.setAttribute('uv', new THREE.Float32BufferAttribute(batch.uv, 2));
    const material = new THREE.MeshStandardMaterial({ map: vanillaTextures.get(texture), roughness: .96, side: THREE.FrontSide });
    const mesh = new THREE.Mesh(geometry, material);mesh.castShadow = true;mesh.receiveShadow = true;group.add(mesh);
  }
  return group;
}
function buildMill() {
  if (millParts.length) return;
  for (const part of runtime.parts) {
    const object = part.itemModel ? nativeItemMesh(part.itemModel) : cubeModel(part.block);
    object.traverse(child => { if (child.isMesh) child.userData.millLever = part.control === true; });
    millRoot.add(object);millParts.push({part,object});
  }
  millLeverHitbox = new THREE.Mesh(new THREE.BoxGeometry(...runtime.leverSize),new THREE.MeshBasicMaterial({visible:false}));
  millLeverHitbox.position.set(...runtime.leverCenter);millLeverHitbox.userData.millHitbox=true;millRoot.add(millLeverHitbox);
}
const originalBlockBuilder = buildMesh;
buildMesh = function() {
  const original = model;
  if (!millAsBuilt) {
    const hidden = (x, y, z, state) => SAWMILL.previewMask([x, y, z].map((v, i) => v + SAWMILL_CAPTURE.origin[i]), state);
    const masked = new Set(original.blocks.filter(([x, y, z, id]) => hidden(x, y, z, original.materials[id].block)).map(([x, y, z]) => `${x},${y},${z}`));
    model = { ...original, blocks: original.blocks.filter(([x, y, z]) => !masked.has(`${x},${y},${z}`)),
      get: (x, y, z) => masked.has(`${x},${y},${z}`) ? 0 : original.get(x, y, z) };
  }
  originalBlockBuilder(); model = original;
  buildMill(); poseMill(millTime); millRoot.visible = !millAsBuilt;
};

function poseMill(seconds) {
  const state = sampleCycle(seconds);
  // Match the server's10Hz transforms and two-tick display interpolation.
  const firstFrame = runtime.frames[state.index], nextFrame = runtime.frames[Math.min(state.index + 1, runtime.frames.length - 1)];
  const initial = runtime.frames[0][8], first = new Map(firstFrame[8].map(pose => [pose[0],pose]));
  const next = new Map(nextFrame[8].map(pose => [pose[0],pose]));
  for (let index = 0; index < millParts.length; index++) {
    const { part, object } = millParts[index];
    const from = first.get(index) ?? initial[index], to = next.get(index) ?? from;
    object.visible = !!from[1] && (part.motion !== 'sawdust' || $('millDust').checked);
    const center = from.slice(2,5).map((value,axis) => value + (to[2+axis]-value)*state.alpha);
    const size = from.slice(5,8).map((value,axis) => value + (to[5+axis]-value)*state.alpha);
    const rotation = new THREE.Quaternion(...from.slice(8,12)).normalize().slerp(new THREE.Quaternion(...to.slice(8,12)).normalize(),state.alpha);
    if (part.itemModel) { object.position.set(...center);object.scale.set(...size);object.quaternion.copy(rotation); }
    else {
      // Native Display interpolation lerps the transmitted translation, not the
      // reconstructed geometric center. Keep this exact for shaft/lever cuboids too.
      const translation = pose => new THREE.Vector3(...pose.slice(5,8)).multiplyScalar(-.5)
        .applyQuaternion(new THREE.Quaternion(...pose.slice(8,12)).normalize()).add(new THREE.Vector3(...pose.slice(2,5)));
      object.position.copy(translation(from).lerp(translation(to),state.alpha));
      object.scale.set(...size);object.quaternion.copy(rotation);
    }
  }
  const kerf = state.contact ? 'Есть контакт с древесиной' : state.finished ? 'Цикл завершён' : 'Без контакта';
  $('millClock').textContent = `${state.t.toFixed(1)} / ${CYCLE_SECONDS.toFixed(0)} с`;
  $('millTimeline').value = state.t;
  $('millPhase').textContent = state.phase.label;
  $('millPhaseDetail').textContent = state.phase.description;
  $('millFrameLabel').textContent = millAsBuilt ? 'Исходный интерьер · снимок живого мира' : state.t===0&&!millPlaying ? 'Нажмите рычаг на станке, чтобы запустить пилу' : `${state.phase.label} · ${kerf}`;
  for (const button of document.querySelectorAll('[data-beat]')) button.setAttribute('aria-current', String(Number(button.dataset.beat) === state.phase.at));
  $('millStatus').textContent = `Подача: ${(state.forwardZ + 5.8).toFixed(2)} м · ${state.contact ? 'рез' : 'холостой ход'} · ${millAsBuilt ? 'исходный интерьер' : 'дисплеи'}`;
  renderer.shadowMap.needsUpdate = true;
}

const panel = document.createElement('section');
panel.className = 'section sawmill-controls';
panel.innerHTML = `
  <h2>Рабочий цикл</h2>
  <p class="sawmill-note">Рычаг на станке запускает и ставит цикл на паузу.</p>
  <div class="sawmill-play-row"><button id="millPlay" class="primary">Пауза</button><button id="millReplay">С начала</button></div>
  <div class="sawmill-time"><label for="millTimeline">Время</label><output id="millClock">0 / 25 с</output></div>
  <input id="millTimeline" type="range" min="0" max="25" value="0" step=".01" aria-label="Время рабочего цикла">
  <div class="field"><label for="millSpeed">Скорость</label><select id="millSpeed"><option value=".25">0,25×</option><option value=".5">0,5×</option><option value="1" selected>1×</option></select></div>
  <h3 class="phase-title" id="millPhase"></h3><p class="phase-detail" id="millPhaseDetail"></p>
  <div class="sawmill-views">${SAWMILL.views.map(view => `<button data-mill-view="${view.id}">${view.label}</button>`).join('')}</div>
  <div class="field"><label for="millDust">Опилки у пропила</label><input id="millDust" type="checkbox" checked></div>
  <div class="field"><label for="millOriginal">Исходный интерьер</label><input id="millOriginal" type="checkbox"></div>
  <div class="sawmill-beats" aria-label="Этапы цикла">${PHASES.map(phase => `<button data-beat="${phase.at}"><span>${phase.at.toFixed(1)}</span><span>${phase.label}</span></button>`).join('')}</div>
  <div class="sawmill-separator"><p class="sawmill-note">Перетаскивание — поворот камеры. Колесо — приближение. Пробел — пауза.</p><p class="sawmill-note">Постройка — снимок живого мира. Геометрия и кадры — из серверного кода. Свет в этом превью условный.</p><p class="sawmill-note" id="millStatus"></p></div>`;
document.querySelector('aside').prepend(panel);
const frameLabel = document.createElement('div'); frameLabel.className = 'sawmill-frame'; frameLabel.id = 'millFrameLabel';
document.querySelector('.workspace').append(frameLabel);
document.querySelector('.brand').lastChild.textContent = 'RUSCRAFTING';
document.querySelector('.brand').childNodes[1].textContent = 'Кедровый двор ';
$('sceneSubtitle').textContent = 'Классическая круговая пила · один проход';

function setMillPlaying(value) {
  millPlaying = value; millLastFrame = null;
  $('millPlay').textContent = value ? 'Пауза' : millTime >= CYCLE_SECONDS ? 'Повторить' : millTime===0 ? 'Пуск' : 'Продолжить';
  $('millPlay').setAttribute('aria-pressed', String(value));
}
function chooseMillView(id) {
  setProjectView(id);
  for (const button of document.querySelectorAll('[data-mill-view]')) button.setAttribute('aria-pressed', String(button.dataset.millView === id));
}
$('millPlay').onclick = () => { if (millTime >= CYCLE_SECONDS) millTime = 0; setMillPlaying(!millPlaying); };
$('millReplay').onclick = () => { millTime = 0; setMillPlaying(true); poseMill(0); render(); };
$('millTimeline').oninput = () => { setMillPlaying(false); millTime = Number($('millTimeline').value); poseMill(millTime); render(); };
$('millSpeed').onchange = () => { millSpeed = Number($('millSpeed').value); millLastFrame = null; };
$('millDust').onchange = () => { poseMill(millTime); render(); };
$('millOriginal').onchange = () => { millAsBuilt = $('millOriginal').checked; buildMesh(); render(); };
for (const button of document.querySelectorAll('[data-mill-view]')) button.onclick = () => chooseMillView(button.dataset.millView);
for (const button of document.querySelectorAll('[data-beat]')) button.onclick = () => {
  millTime = Number(button.dataset.beat); setMillPlaying(false); poseMill(millTime); render();
};
document.addEventListener('keydown', event => {
  if (event.code === 'Space' && !['INPUT', 'SELECT', 'BUTTON', 'TEXTAREA'].includes(event.target.tagName)) {
    event.preventDefault(); if (millTime >= CYCLE_SECONDS) millTime = 0; setMillPlaying(!millPlaying);
  }
});
document.addEventListener('visibilitychange', () => { millLastFrame = null; });
// One physical lever, one existing animation loop. Camera drags never activate it.
const millRaycaster = new THREE.Raycaster(), millPointer = new THREE.Vector2();
let millPointerDown = null;
function leverAtPointer(event) {
  if (millAsBuilt || !millLeverHitbox || !millRoot.visible) return false;
  const bounds=renderer.domElement.getBoundingClientRect();
  millPointer.set((event.clientX-bounds.left)/bounds.width*2-1,-(event.clientY-bounds.top)/bounds.height*2+1);
  world.updateMatrixWorld(true);millRaycaster.setFromCamera(millPointer,camera);
  const leverHit=millRaycaster.intersectObject(millLeverHitbox,false)[0];
  if(!leverHit)return false;
  const visible=object=>{for(let node=object;node;node=node.parent)if(!node.visible)return false;return true;};
  const first=millRaycaster.intersectObjects(world.children,true).find(hit=>!hit.object.userData.millHitbox&&hit.object.isMesh&&visible(hit.object));
  return !!first?.object.userData.millLever;
}
function hoverLever(value) {
  if(value===millLeverHovered)return;millLeverHovered=value;
  renderer.domElement.style.cursor=value?'pointer':'grab';
  for(const {part,object} of millParts)if(part.motion==='lever')object.traverse(child=>{
    if(child.material?.emissive){child.material.emissive.set(value?'#5a3911':'#000000');child.material.emissiveIntensity=value?.45:0;}
  });
  $('millFrameLabel').textContent=value?(millPlaying?'Рычаг · пауза':'Рычаг · пуск'):millTime===0?'Нажмите рычаг на станке, чтобы запустить пилу':sampleCycle(millTime).phase.label;
  render();
}
renderer.domElement.addEventListener('pointerdown',event=>{if(event.button===0)millPointerDown={x:event.clientX,y:event.clientY};});
renderer.domElement.addEventListener('pointermove',event=>hoverLever(leverAtPointer(event)));
renderer.domElement.addEventListener('pointerleave',()=>hoverLever(false));
renderer.domElement.addEventListener('pointercancel',()=>{millPointerDown=null;});
renderer.domElement.addEventListener('pointerup',event=>{
  const start=millPointerDown;millPointerDown=null;
  if(event.button!==0||!start||Math.hypot(event.clientX-start.x,event.clientY-start.y)>6||!leverAtPointer(event))return;
  if(millTime>=CYCLE_SECONDS)millTime=0;
  setMillPlaying(!millPlaying);poseMill(millTime);render();
});
const previousSceneLoader = loadScene;
loadScene = function(next) {
  previousSceneLoader(next);
  visualMode = 'plain'; $('visualStyle').value = 'plain'; updateLighting();
  // Existing ceiling remains intact; brighter indoor fill is a preview lighting choice.
  hemi.intensity = .72; hemi.color.set('#d2dce1');hemi.groundColor.set('#786b58');
  sun.intensity = 1.28; sun.color.set('#f1dfc0');sun.position.set(65,16,16);sun.target.position.set(8,3,16);
  sun.shadow.mapSize.set(1024, 1024);
  Object.assign(sun.shadow.camera, { left: -22, right: 22, top: 22, bottom: -22 });
  sun.shadow.camera.updateProjectionMatrix();
  $('annotations').checked = false;
  chooseMillView(query.get('view') || 'overview');
  poseMill(millTime); setMillPlaying(millPlaying); render();
  document.body.dataset.previewReady = 'true';
};
let millRenderStamp = 0;
function millAnimate(now) {
  requestAnimationFrame(millAnimate);
  if (!window.atelierReady || document.hidden || !millPlaying) { millLastFrame = null; return; }
  if (millLastFrame !== null) millTime = Math.min(CYCLE_SECONDS, millTime + Math.min((now - millLastFrame) / 1000, .10) * millSpeed);
  millLastFrame = now;
  if (now - millRenderStamp < 1000 / 30) return;
  millRenderStamp = now;
  poseMill(millTime); render();
  if (millTime >= CYCLE_SECONDS) setMillPlaying(false);
}
requestAnimationFrame(millAnimate);
