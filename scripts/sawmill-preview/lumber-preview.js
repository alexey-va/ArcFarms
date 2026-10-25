// The full round consumes Kotlin-exported state/poses; only camera and playback controls live here.
if (query.get('mode') === 'lumber') {
  const lumberRoot = new THREE.Group(); world.add(lumberRoot);
  const lumberParts = new Map();
  let lumberPlaying = false, lumberTime = Number(query.get('t') || 0), lumberLast = null;
  let lumberBeta = query.get('beta') !== '0', treeMasked = null;
  const treeCells = new Set([...LUMBER.fixture.logs.filter(p => p[1] >= LUMBER.fixture.root[1] + 2), ...LUMBER.fixture.leaves]
    .map(p => p.slice(0,3).map((n,i)=>n-SAWMILL_CAPTURE.origin[i]).join(',')));
  const labels = {IDLE:'Станок пуст · круг завершён',CHOPPING:'Срубить выбранное дерево',FALLING:'Падение дерева',DELIMBING:'Снять три ветви',LOADING_CART:'Загрузить это бревно',HAULING:'Телега к пилораме',LOADING_SAWMILL:'Затянуть бревно на подающий стол',SAWING:'Два распила',COLLECTING_BOARDS:'Забрать готовый штабель',DELIVERING:'Отнести на лесосклад'};
  const phaseHint = {IDLE:'Новая заготовка появляется только после выбора и рубки следующего дерева.',CHOPPING:'Пять ударов выданным топором по настоящему стволу.',FALLING:'Сохраняются два блока пня; исходные блоки дерева вращаются вокруг среза.',DELIMBING:'Три отдельные цели снимают ветви и крону этого дерева.',LOADING_CART:'Подойти к стволу и нажать: одно бревно переходит на телегу.',HAULING:'Три секунды бревно укладывается на телегу. Затем игрок берёт ручку и ведёт её вперёд.',LOADING_SAWMILL:'Передаётся тот же идентификатор заготовки. Последний кадр совпадает с пятью raw-секциями станка.',SAWING:'Каждый проход требует отдельного нажатия рычага; между ними станок выключен.',COLLECTING_BOARDS:'Сохраняются все пятнадцать деталей готового штабеля.',DELIVERING:'Штабель несут на открытую площадку существующего лесосклада. Наград и валют в beta нет.'};
  panel.innerHTML = `<h2>Полный рабочий круг</h2>
    <p class="sawmill-note">Один ствол · три ветви · телега · два распила · лесосклад</p>
    <div class="sawmill-play-row"><button id="millPlay" class="primary">Смотреть</button><button id="millReplay">С начала</button></div>
    <div class="sawmill-time"><label for="millTimeline">Время</label><output id="millClock"></output></div>
    <input id="millTimeline" type="range" min="0" max="170" step=".05" value="0" aria-label="Время полного рабочего круга">
    <div class="field"><label for="millSpeed">Скорость</label><select id="millSpeed"><option value=".25">0,25×</option><option value=".5">0,5×</option><option value="1" selected>1×</option><option value="2">2×</option></select></div>
    <div class="field"><label for="lumberBeat">Этап</label><select id="lumberBeat">${LUMBER.beats.map(b=>`<option value="${b.at}">${b.at.toFixed(1)} с · ${labels[b.phase.split(':')[0]]}${b.phase.startsWith('SAWING')?' · '+(Number(b.phase.split(':')[1])+1):''}</option>`).join('')}</select></div>
    <h3 class="phase-title" id="millPhase"></h3><p class="phase-detail" id="millPhaseDetail"></p>
    <div class="sawmill-views">${[...LUMBER_VIEWS,...SAWMILL.views].map(v=>`<button data-mill-view="${v.id}">${v.label}</button>`).join('')}</div>
    <div class="field"><label for="lumberPrivate">Зритель без права beta</label><input id="lumberPrivate" type="checkbox"></div>
    <div class="field"><label for="millDust">Опилки у пропила</label><input id="millDust" type="checkbox" checked></div>
    <div class="sawmill-separator"><p class="sawmill-note">Пакетные позы и переходы экспортированы из Kotlin. Маршрут — проверяемая постановочная последовательность, управление и коллизии клиента здесь не симулируются.</p><p class="sawmill-note">Профиль ствола округляется при подъёме: приближение beta, длина 7,48 м сохраняется. Пять моделей станка прежние.</p><p class="sawmill-note" id="millStatus"></p></div>`;
  $('lumberPrivate').checked = !lumberBeta;
  $('sceneSubtitle').textContent = 'Закрытая beta · полный цикл лесоруба';
  const previousBuilder = buildMesh;
  buildMesh = function() {
    const original = model;
    if (treeMasked && lumberBeta) model = {...original,blocks:original.blocks.filter(p=>!treeCells.has(p.slice(0,3).join(','))),
      get:(x,y,z)=>treeCells.has(`${x},${y},${z}`)?0:original.get(x,y,z)};
    previousBuilder(); model = original; millRoot.visible = lumberBeta; lumberRoot.visible = lumberBeta;
  };
  function transform(object, part, next, alpha) {
    const q = new THREE.Quaternion(...part.rotation).normalize().slerp(new THREE.Quaternion(...next.rotation).normalize(),alpha);
    const size = part.size.map((n,i)=>n+(next.size[i]-n)*alpha);
    const center = part.center.map((n,i)=>n+(next.center[i]-n)*alpha-SAWMILL_CAPTURE.origin[i]);
    if (part.itemModel) {object.position.set(...center);object.scale.set(...size);object.quaternion.copy(q);}
    else {
      const shift = p=>new THREE.Vector3(...p.size).multiplyScalar(-.5).applyQuaternion(new THREE.Quaternion(...p.rotation)).add(new THREE.Vector3(...p.center));
      object.position.copy(shift(part).lerp(shift(next),alpha).sub(new THREE.Vector3(...SAWMILL_CAPTURE.origin)));
      object.scale.set(...size);object.quaternion.copy(q);
    }
  }
  poseMill = function(seconds) {
    lumberTime = Math.max(0,Math.min(LUMBER.seconds,seconds));
    const index=Math.min(LUMBER.frames.length-1,Math.floor(lumberTime*10+1e-6));
    const a=LUMBER.frames[index],b=LUMBER.frames[Math.min(index+1,LUMBER.frames.length-1)],alpha=lumberTime*10-index;
    const next=new Map(b.parts.map(p=>[p.id,p]));
    for(const object of lumberParts.values())object.visible=false;
    for(const p of a.parts){
      if(!lumberParts.has(p.id)){const object=p.itemModel?nativeItemMesh(p.itemModel):cubeModel(p.block);if(p.branchTarget)object.traverse(mesh=>{if(mesh.material?.emissive){mesh.material=mesh.material.clone();mesh.material.emissive.set('#ffc857');mesh.material.emissiveIntensity=.32;}});lumberRoot.add(object);lumberParts.set(p.id,object);}
      const object=lumberParts.get(p.id);transform(object,p,next.get(p.id)||p,alpha);object.visible=!!p.visible;
    }
    for(let i=0;i<millParts.length;i++){
      const {part,object}=millParts[i],p=a.machine[i],n=b.machine[i];
      const from={center:p.slice(1,4).map((v,j)=>v+SAWMILL.origin[j]),size:p.slice(4,7),rotation:p.slice(7,11),itemModel:part.itemModel};
      const to={center:n.slice(1,4).map((v,j)=>v+SAWMILL.origin[j]),size:n.slice(4,7),rotation:n.slice(7,11),itemModel:part.itemModel};
      transform(object,from,to,alpha);
      // Machine objects are children of its translated local origin.
      object.position.sub(millRoot.position);
      object.visible=p[0]&&(part.motion!=='sawdust'||$('millDust').checked);
    }
    millRoot.visible=lumberBeta;lumberRoot.visible=lumberBeta;
    $('lumberBeat').value = [...LUMBER.beats].reverse().find(beat=>beat.at<=lumberTime)?.at;
    $('millClock').textContent=`${lumberTime.toFixed(1)} / ${LUMBER.seconds} с`;
    $('millTimeline').value=lumberTime;
    $('millPhase').textContent=lumberBeta?labels[a.phase]:'Обычный мир';
    $('millPhaseDetail').textContent=lumberBeta?phaseHint[a.phase]:'Оригинальное дерево видно. В этой демонстрации скрыты все private-дисплеи; серверная проверка permission тестируется отдельно.';
    $('millStatus').textContent=`Круг ${a.round} · ветви ${a.branches}/3 · распилы ${a.passes}/2 · ${a.running?'рычаг ON':'рычаг OFF'}`;
    $('millFrameLabel').textContent=lumberBeta?`${labels[a.phase]} · ${lumberTime.toFixed(1)} с`:'Без права beta · исходный мир';
    const newMask=a.maskTree&&lumberBeta;
    if(treeMasked!==newMask){treeMasked=newMask;buildMesh();}
    renderer.shadowMap.needsUpdate=true;
  };
  setMillPlaying = function(value){millPlaying=false;lumberPlaying=value;lumberLast=null;$('millPlay').textContent=value?'Пауза':'Смотреть';$('millPlay').setAttribute('aria-pressed',String(value));};
  leverAtPointer=()=>false;hoverLever=()=>{};
  $('millPlay').onclick=()=>{if(lumberTime>=LUMBER.seconds)lumberTime=0;setMillPlaying(!lumberPlaying);};
  $('millReplay').onclick=()=>{setMillPlaying(false);poseMill(0);render();};
  $('millTimeline').oninput=()=>{setMillPlaying(false);poseMill(Number($('millTimeline').value));render();};
  $('lumberBeat').onchange=()=>{setMillPlaying(false);poseMill(Number($('lumberBeat').value));render();};
  $('millSpeed').onchange=()=>{millSpeed=Number($('millSpeed').value);lumberLast=null;};
  $('lumberPrivate').onchange=()=>{lumberBeta=!$('lumberPrivate').checked;poseMill(lumberTime);render();};
  $('millDust').onchange=()=>{poseMill(lumberTime);render();};
  for(const button of document.querySelectorAll('[data-mill-view]'))button.onclick=()=>chooseMillView(button.dataset.millView);
  document.addEventListener('keydown',e=>{if(e.code==='Space'&&!['INPUT','SELECT','BUTTON','TEXTAREA'].includes(e.target.tagName)){e.preventDefault();e.stopImmediatePropagation();setMillPlaying(!lumberPlaying);}},true);
  millTime=lumberTime; if(!query.has('view')) query.set('view','forest');
  function animateLumber(now){requestAnimationFrame(animateLumber);if(!window.atelierReady||document.hidden||!lumberPlaying){lumberLast=null;return;}
    if(lumberLast!==null)lumberTime=Math.min(LUMBER.seconds,lumberTime+Math.min((now-lumberLast)/1000,.1)*millSpeed);lumberLast=now;
    poseMill(lumberTime);render();if(lumberTime>=LUMBER.seconds)setMillPlaying(false);
  }
  requestAnimationFrame(animateLumber);
}
