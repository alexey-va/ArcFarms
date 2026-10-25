// Runs inside the existing Atelier page after its renderer, before texture load completes.
const millRoot = new THREE.Group();
millRoot.position.set(...SAWMILL.origin.map((value, index) => value - SAWMILL_CAPTURE.origin[index]));
world.add(millRoot);
const millParts = [], millStock = [], millChips = [];
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
const logOutline = [[-.205,0],[.205,0],[.48,.17],[.48,.55],[.205,LOG_HEIGHT],[-.205,LOG_HEIGHT],[-.48,.55],[-.48,.17]];
function clipTimber(polygon, plane, keepLeft) {
  const result = [];
  for (let i = 0; i < polygon.length; i++) {
    const a = polygon[i], b = polygon[(i + 1) % polygon.length];
    const aIn = keepLeft ? a[0] <= plane : a[0] >= plane;
    const bIn = keepLeft ? b[0] <= plane : b[0] >= plane;
    if (aIn) result.push(a);
    if (aIn !== bIn) { const t = (plane - a[0]) / (b[0] - a[0]); result.push([plane, mix(a[1], b[1], t)]); }
  }
  return result;
}
function timberMesh(polygon, centered = false) {
  const nativeLog = VANILLA_ASSETS.blocks['minecraft:spruce_log[axis=z]'];
  const nativeCut = VANILLA_ASSETS.blocks['minecraft:stripped_spruce_log[axis=z]'];
  const bark = nativeLog.quads.find(face => Math.abs(face.normal[2]) < .5).texture;
  const end = nativeLog.quads.find(face => Math.abs(face.normal[2]) > .5).texture;
  const cut = nativeCut.quads.find(face => Math.abs(face.normal[2]) < .5).texture;
  const materials = [bark, cut, end, end].map(name => new THREE.MeshStandardMaterial({ map: vanillaTextures.get(name), roughness: .96, side: THREE.DoubleSide }));
  const positions = [], uvs = [], groups = [];
  const vertex = ([x,y],z) => [x - (centered ? .3925 : 0), y - (centered ? LOG_HEIGHT/2 : 0), z];
  function triangle(a,b,c,uv,material) {
    const start = positions.length / 3; positions.push(...a,...b,...c); uvs.push(...uv.flat()); groups.push([start,3,material]);
  }
  for (let i = 1; i < polygon.length - 1; i++) {
    const a=polygon[0],b=polygon[i],c=polygon[i+1];
    const uv = [a,b,c].map(([x,y])=>[(x+.48)/.96,y/LOG_HEIGHT]);
    triangle(vertex(a,1),vertex(b,1),vertex(c,1),uv,2);
    triangle(vertex(c,0),vertex(b,0),vertex(a,0),[uv[2],uv[1],uv[0]],3);
  }
  for (let i=0;i<polygon.length;i++) {
    const a=polygon[i],b=polygon[(i+1)%polygon.length];
    const isCut = Math.abs(a[0]-b[0])<1e-6 && (Math.abs(a[0]-.235)<1e-6 || Math.abs(a[0]-.305)<1e-6);
    const u0=i/polygon.length,u1=(i+1)/polygon.length;
    triangle(vertex(a,0),vertex(b,0),vertex(b,1),[[u0,0],[u1,0],[u1,1]],isCut?1:0);
    triangle(vertex(a,0),vertex(b,1),vertex(a,1),[[u0,0],[u1,1],[u0,1]],isCut?1:0);
  }
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));
  geometry.setAttribute('uv',new THREE.Float32BufferAttribute(uvs,2));
  groups.forEach(group=>geometry.addGroup(...group)); geometry.computeVertexNormals();
  const object=new THREE.Mesh(geometry,materials);object.castShadow=true;object.receiveShadow=true;return object;
}
function annularPulley(part) {
  const shape=new THREE.Shape(),hole=new THREE.Path(),count=32;
  for(let i=0;i<=count;i++){const a=i/count*Math.PI*2;const f=i===0?'moveTo':'lineTo';shape[f](Math.cos(a)*part.outerRadius,Math.sin(a)*part.outerRadius);}
  for(let i=count;i>=0;i--){const a=i/count*Math.PI*2;const f=i===count?'moveTo':'lineTo';hole[f](Math.cos(a)*part.innerRadius,Math.sin(a)*part.innerRadius);}
  shape.holes.push(hole);
  const geometry=new THREE.ExtrudeGeometry(shape,{depth:part.depth,bevelEnabled:false,steps:1,curveSegments:count});
  geometry.translate(0,0,-part.depth/2);geometry.rotateY(Math.PI/2);
  const model=VANILLA_ASSETS.blocks[part.block];
  const object=new THREE.Mesh(geometry,new THREE.MeshStandardMaterial({map:vanillaTextures.get(model.quads[0].texture),roughness:.82}));
  object.castShadow=true;object.receiveShadow=true;const group=new THREE.Group();group.add(object);return group;
}
function buildMill() {
  if (millParts.length) return;
  for (const part of makeParts()) {
    const object = part.shape==='annulus'?annularPulley(part):cubeModel(part.block);
    object.traverse(child => { if (child.isMesh) child.userData.millLever = part.motion === 'lever'; });
    millRoot.add(object); millParts.push({ part, object });
  }
  millLeverHitbox = new THREE.Mesh(new THREE.BoxGeometry(.56,.78,.55),new THREE.MeshBasicMaterial({visible:false}));
  millLeverHitbox.position.set(2.45,1.43,1.20);millLeverHitbox.userData.millHitbox=true;millRoot.add(millLeverHitbox);
  // Five consecutive native log sections keep the bark texel scale close to one block.
  for (let section = 0; section < 5; section++) {
    const raw = timberMesh(logOutline), remaining = timberMesh(clipTimber(logOutline,.235,true));
    const slab = timberMesh(clipTimber(logOutline,.305,false),true);
    millRoot.add(raw,remaining,slab); millStock.push({section,raw,remaining,slab});
  }
  for (let i = 0; i < 36; i++) {
    const object = cubeModel('minecraft:spruce_planks'); millRoot.add(object); millChips.push(object);
  }
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
  for (const { part, object } of millParts) {
    const center = [...part.center];
    let rotation = quat(part.rotation ?? [0, 0, 0]);
    if (part.motion === 'blade' || part.motion === 'pulley') {
      const turn = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), state.angle * (part.ratio ?? 1));
      const at = new THREE.Vector3(...center).sub(new THREE.Vector3(...part.pivot)).applyQuaternion(turn).add(new THREE.Vector3(...part.pivot));
      center.splice(0, 3, at.x, at.y, at.z); rotation.premultiply(turn);
    }
    if (part.motion === 'arbor') rotation = quat([state.angle,0,0]);
    if (part.motion.startsWith('carriage') || part.motion === 'headblock') {
      center[2] += state.carriageZ;
      if (part.motion === 'headblock') center[0] += state.lateral;
      if (part.motion === 'carriage-wheel') rotation = quat([(state.carriageZ + 5.8) / .155, 0, 0]);
    }
    if (part.motion === 'lever') {
      const turn = quat([smooth(state.t / .7) * .7, 0, 0]);
      const at = new THREE.Vector3(...center).sub(new THREE.Vector3(...part.pivot)).applyQuaternion(turn).add(new THREE.Vector3(...part.pivot));
      center.splice(0, 3, at.x, at.y, at.z); rotation = turn;
    }
    if(part.shape==='annulus'){object.position.set(...center);object.quaternion.copy(rotation);}else placePart(object, center, part.size, rotation);
  }
  for (const { section, raw, remaining, slab } of millStock) {
    const z0 = -LOG_LENGTH / 2 + section * LOG_LENGTH / 5;
    const z1 = z0 + LOG_LENGTH / 5;
    const spans = splitLogInterval(z0, z1, state.cutThreshold);
    const rawLength = spans.raw[1] - spans.raw[0];
    raw.visible = rawLength > .0001;
    if (raw.visible) {raw.scale.set(1,1,rawLength);raw.position.set(state.lateral,LOG_BOTTOM,state.stockZ+spans.raw[0]);raw.material[2].visible=Math.abs(spans.raw[1]-LOG_LENGTH/2)<1e-6;raw.material[3].visible=Math.abs(spans.raw[0]+LOG_LENGTH/2)<1e-6;}
    const cutLength = spans.cut[1] - spans.cut[0];
    remaining.visible=slab.visible=cutLength>.0001;
    if(remaining.visible){
      remaining.scale.set(1,1,cutLength);remaining.position.set(state.lateral,LOG_BOTTOM,state.stockZ+spans.cut[0]);
      for(const piece of [remaining,slab]){piece.material[2].visible=Math.abs(spans.cut[1]-LOG_LENGTH/2)<1e-6;piece.material[3].visible=Math.abs(spans.cut[0]+LOG_LENGTH/2)<1e-6;}
      const tilt=-Math.PI/2*state.split;
      const points=clipTimber(logOutline,.305,false).map(([x,y])=>new THREE.Vector3(x-.3925,y-LOG_HEIGHT/2,0).applyAxisAngle(new THREE.Vector3(0,0,1),tilt));
      const minY=Math.min(...points.map(point=>point.y));
      const grounded=1.27-minY;
      slab.scale.set(1,1,cutLength);slab.rotation.z=tilt;
      slab.position.set(.3925+state.split*.74,mix(LOG_BOTTOM+LOG_HEIGHT/2,grounded,state.split),state.outputZ+spans.cut[0]);
    }
  }
  for (let index = 0; index < millChips.length; index++) {
    const object = millChips[index];
    const cycle = .72, offset = index * cycle / millChips.length;
    const birth = Math.floor((seconds - offset) / cycle) * cycle + offset;
    const age = seconds - birth, atBirth = sampleCycle(Math.max(0, birth));
    object.visible = !millAsBuilt && $('millDust').checked && birth >= 0 && age < .48 && atBirth.contact;
    if (!object.visible) continue;
    const seed = Math.sin(index * 73.7 + Math.floor(birth * 30) * 2.1) * .5 + .5;
    const x = .27 + age * (seed-.5)*.55;
    const y = LOG_BOTTOM + .09 - age * (1.2 + seed * .6) - 3.8 * age * age;
    const z = -.85 - age * (.3 + seed * .5);
    const size = .022 + seed * .018;
    placePart(object, [x, Math.max(.1, y), z], [size, size * .5, size * 1.5], quat([age * 8, seed * 7, age * 5]));
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
  <div class="sawmill-separator"><p class="sawmill-note">Перетаскивание — поворот камеры. Колесо — приближение. Пробел — пауза.</p><p class="sawmill-note">Постройка — снимок живого мира. Механизм — локальное превью. На сервере ничего не меняется.</p><p class="sawmill-note" id="millStatus"></p></div>`;
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
  return !first || first.object.userData.millLever || first.distance>=leverHit.distance-.02;
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
