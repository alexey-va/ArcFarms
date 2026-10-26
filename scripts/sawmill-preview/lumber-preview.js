// The full round consumes Kotlin-exported state/poses; only camera and playback controls live here.
if (query.get('mode') === 'lumber') {
  const lumberRoot = new THREE.Group(); world.add(lumberRoot);
  const lumberParts = new Map();
  let lumberPlaying = false, lumberTime = Number(query.get('t') || 0), lumberLast = null;
  let lumberBeta = query.get('beta') !== '0', treeMasked = null;
  const treeCells = new Set([...LUMBER.fixture.logs.filter(p => p[1] >= LUMBER.fixture.root[1] + 2), ...LUMBER.fixture.leaves]
    .map(p => p.slice(0,3).map((n,i)=>n-SAWMILL_CAPTURE.origin[i]).join(',')));
  const wholeTreeCells = new Set([...LUMBER.fixture.logs,...LUMBER.fixture.leaves]
    .map(p => p.slice(0,3).map((n,i)=>n-SAWMILL_CAPTURE.origin[i]).join(',')));
  const eventEpisode = query.has('incident');
  const labels = {IDLE:'Доски на складе',CHOPPING:'Сруби дерево',FALLING:'Дерево падает',DELIMBING:'Обрежь ветви',LOADING_CART:'Возьми бревно',HAULING:'Доставь бревно',LOADING_SAWMILL:'Загрузка станка',SAWING:'Распили бревно',COLLECTING_BOARDS:'Доски готовы',CRANE_TRANSFER:'Кран переносит доски',FEED_JAM:'Прокрути ворот',SAWDUST_SMOLDER:'Потуши опилки'};
  const phaseHint = {IDLE:'Выбери следующее дерево',CHOPPING:'ЛКМ топором',FALLING:'Отойди в сторону',DELIMBING:'ЛКМ по подсвеченной ветви',LOADING_CART:'ПКМ по бревну',HAULING:'Войди в золотую зону',LOADING_SAWMILL:'Подожди',SAWING:'ПКМ по рычагу',COLLECTING_BOARDS:'Дождись крана',CRANE_TRANSFER:'Дождись разгрузки',FEED_JAM:'ПКМ по рукояти',SAWDUST_SMOLDER:'Направь струю на огонь'};
  panel.innerHTML = `<h2>${eventEpisode?'Событие лесопилки':'Полный рабочий круг'}</h2>
    <p class="sawmill-note">${eventEpisode?'Препятствие · действие игрока · продолжение работы':'Дерево · поводок · распил · кран · склад в цеху'}</p>
    <div class="sawmill-play-row"><a href="?mode=lumber&incident=feed-jam&view=crank&paused=1">Ворот</a><a href="?mode=lumber&incident=sawdust-smolder&view=smolder&paused=1">Опилки</a><a href="?mode=lumber&view=forest&paused=1">Рабочий круг</a></div>
    <div class="sawmill-play-row"><button id="millPlay" class="primary">Смотреть</button><button id="millReplay">С начала</button></div>
    <div class="sawmill-time"><label for="millTimeline">Время</label><output id="millClock"></output></div>
    <input id="millTimeline" type="range" min="0" max="${LUMBER.seconds}" step=".05" value="0" aria-label="Время полного рабочего круга">
    <div class="field"><label for="millSpeed">Скорость</label><select id="millSpeed"><option value=".25">0,25×</option><option value=".5">0,5×</option><option value="1" selected>1×</option><option value="2">2×</option></select></div>
    <div class="field"><label for="lumberBeat">Этап</label><select id="lumberBeat">${LUMBER.beats.map(b=>`<option value="${b.at}">${b.at.toFixed(1)} с · ${labels[b.phase.split(':')[0]]}${b.phase.startsWith('SAWING:')?' · '+(Number(b.phase.split(':')[1])+1):''}</option>`).join('')}</select></div>
    <h3 class="phase-title" id="millPhase"></h3><p class="phase-detail" id="millPhaseDetail"></p>
    <div class="sawmill-views">${[...LUMBER_VIEWS,...SAWMILL.views].map(v=>`<button data-mill-view="${v.id}">${v.label}</button>`).join('')}</div>
    <div class="field"><label for="lumberPrivate">Зритель без права beta</label><input id="lumberPrivate" type="checkbox"></div>
    <div class="field"><label for="millDust">Опилки у пропила</label><input id="millDust" type="checkbox" checked></div>
    <div class="sawmill-separator"><p class="sawmill-note">${eventEpisode?'Позы и переходы — из Kotlin. Круги считает общий worksite tracker, попадания шланга — WorksiteWaterJet. Частицы и контур подсветки здесь условные; нативный клиент проверяется отдельно.':'Пакетные позы и переходы экспортированы из Kotlin. Путь игрока задан для просмотра; за ним работает настоящий Kotlin follower. Контур подсветки и изгиб нативного поводка в Minecraft проверяются отдельно.'}</p><p class="sawmill-note">${eventEpisode?'Прежний станок и пять моделей. Новые предметы — только vanilla-дисплеи; блоки мира не меняются.':'Профиль ствола округляется при подборе: приближение beta, длина 7,48 м сохраняется. Пять моделей станка прежние.'}</p><p class="sawmill-note" id="millStatus"></p></div>`;
  $('lumberPrivate').checked = !lumberBeta;
  $('sceneSubtitle').textContent = eventEpisode?'Закрытая beta · события лесопилки':'Закрытая beta · полный цикл лесоруба';
  const previousBuilder = buildMesh;
  buildMesh = function() {
    const original = model;
    const hidden = treeMasked==='whole'?wholeTreeCells:treeCells;
    if (treeMasked && lumberBeta) model = {...original,blocks:original.blocks.filter(p=>!hidden.has(p.slice(0,3).join(','))),
      get:(x,y,z)=>hidden.has(`${x},${y},${z}`)?0:original.get(x,y,z)};
    previousBuilder(); model = original; millRoot.visible = lumberBeta; lumberRoot.visible = lumberBeta;
  };
  const playerViews = ['haul-forward','haul-back','crank-player'];
  let lumberCameraMode = playerViews.includes(query.get('view')) ? query.get('view') : null;
  const normalChooseMillView = chooseMillView;
  chooseMillView = function(id) {lumberCameraMode=playerViews.includes(id)?id:null;normalChooseMillView(id);};
  const carrierFigure=new THREE.Group();world.add(carrierFigure);
  for(const [size,at,color] of [[[.5,.75,.28],[0,1.05,0],'#467d85'],[[.45,.45,.45],[0,1.65,0],'#b58f6c'],[[.18,.7,.22],[-.13,.35,0],'#364b5e'],[[.18,.7,.22],[.13,.35,0],'#364b5e']]){
    const mesh=new THREE.Mesh(new THREE.BoxGeometry(...size),new THREE.MeshStandardMaterial({color,roughness:1}));mesh.position.set(...at);carrierFigure.add(mesh);
  }
  const ropeGroup=new THREE.Group();lumberRoot.add(ropeGroup);
  const ropeGeometry=new THREE.CylinderGeometry(.025,.025,1,4),ropeMaterial=new THREE.MeshStandardMaterial({color:'#86613a',roughness:1});
  const ropeSegments=Array.from({length:20},()=>{const mesh=new THREE.Mesh(ropeGeometry,ropeMaterial);ropeGroup.add(mesh);return mesh;});
  // Native particle sprites are client-owned. These small markers preview timing and aim only.
  const incidentParticles = new THREE.Group(); lumberRoot.add(incidentParticles);
  const particleShape = new THREE.SphereGeometry(1, 5, 4);
  const particlePool = Array.from({length:32}, () => {
    const mesh = new THREE.Mesh(particleShape, new THREE.MeshBasicMaterial({transparent:true,depthWrite:false}));
    incidentParticles.add(mesh); return mesh;
  });
  function poseIncidentParticles(a, seconds) {
    particlePool.forEach(p=>p.visible=false); let count=0;
    const dot=(at,color,size,opacity)=>{const p=particlePool[count++];if(!p)return;p.visible=true;p.position.copy(at).sub(new THREE.Vector3(...SAWMILL_CAPTURE.origin));p.scale.setScalar(size);p.material.color.set(color);p.material.opacity=opacity;};
    for (const p of a.parts.filter(p=>p.id.startsWith('incident-smolder-')&&p.visible)) {
      const seed=Number(p.id.split('-').pop()),phase=(seconds*1.2+seed*.3)%1;
      dot(new THREE.Vector3(p.center[0]-.12,p.center[1]+.21+phase*.18,p.center[2]),'#ffaf32',.055,1);
      dot(new THREE.Vector3(p.center[0]+.12,p.center[1]+.25+(1-phase)*.14,p.center[2]+.08),'#f47b22',.045,1);
      dot(new THREE.Vector3(p.center[0]+phase*.12,p.center[1]+.4+phase*.5,p.center[2]),'#8c8983',.065+phase*.08,.45*(1-phase));
    }
    if (a.sprayAt&&a.eventActor) {
      const eye=new THREE.Vector3(a.eventActor.x,a.eventActor.y+1.62,a.eventActor.z);
      const direction=new THREE.Vector3(a.sprayAt.x,a.sprayAt.y,a.sprayAt.z).sub(eye).normalize();
      for(let i=0;i<=10;i++)dot(eye.clone().addScaledVector(direction,.45+i*.3),'#7ddbf4',.035,.85);
    }
  }
  function poseCarrier(a,b,alpha){
    const player=a.logCarrier||a.boardCarrier||a.eventActor,other=b.logCarrier||b.boardCarrier||b.eventActor||player;
    carrierFigure.visible=!!player;ropeGroup.visible=!!(a.logCarrier&&a.leash);
    if(!player)return;
    const at=new THREE.Vector3(player.x+(other.x-player.x)*alpha,player.y+(other.y-player.y)*alpha,player.z+(other.z-player.z)*alpha);
    const yaw=(player.yaw+(((other.yaw-player.yaw+540)%360)-180)*alpha)*Math.PI/180,forward=new THREE.Vector3(-Math.sin(yaw),0,Math.cos(yaw));
    carrierFigure.position.copy(at).sub(new THREE.Vector3(...SAWMILL_CAPTURE.origin));carrierFigure.rotation.y=-yaw;
    if(a.leash){
      const from=new THREE.Vector3(a.leash.x,a.leash.y+.35,a.leash.z),to=at.clone().add(new THREE.Vector3(Math.cos(yaw)*.35,1.1,Math.sin(yaw)*.35));
      const point=t=>from.clone().lerp(to,t).add(new THREE.Vector3(0,-Math.sin(Math.PI*t)*.22,0)).sub(new THREE.Vector3(...SAWMILL_CAPTURE.origin));
      ropeSegments.forEach((mesh,i)=>{const p=point(i/20),q=point((i+1)/20),delta=q.clone().sub(p);mesh.position.copy(p).add(q).multiplyScalar(.5);mesh.scale.set(1,delta.length(),1);mesh.quaternion.setFromUnitVectors(new THREE.Vector3(0,1,0),delta.normalize());});
    }
    if(lumberCameraMode){
      const eye=at.clone().add(new THREE.Vector3(0,1.62,0)).sub(new THREE.Vector3(...SAWMILL_CAPTURE.origin));
      camera.position.copy(eye);controls.target.copy(eye).add(forward.multiplyScalar(lumberCameraMode==='haul-back'?-6:8));
      if(lumberCameraMode==='haul-back')controls.target.y-=1.0;
      if(lumberCameraMode==='crank-player')controls.target.copy(eye).add(forward.multiplyScalar(1.35/8)).add(new THREE.Vector3(0,-.73,0));
      camera.lookAt(controls.target);controls.update();carrierFigure.visible=false;
    }
  }
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
    // Scene rebuilds reapply poseMill(millTime), including whole-tree to upper-tree mask changes.
    millTime = lumberTime = Math.max(0,Math.min(LUMBER.seconds,seconds));
    const index=Math.min(LUMBER.frames.length-1,Math.floor(lumberTime*10+1e-6));
    const a=LUMBER.frames[index],b=LUMBER.frames[Math.min(index+1,LUMBER.frames.length-1)],alpha=lumberTime*10-index;
    const next=new Map(b.parts.map(p=>[p.id,p]));
    poseCarrier(a,b,alpha);
    poseIncidentParticles(a,lumberTime);
    for(const object of lumberParts.values())object.visible=false;
    for(const p of a.parts){
      if(!lumberParts.has(p.id)){const object=p.itemModel?nativeItemMesh(p.itemModel):cubeModel(p.block);lumberRoot.add(object);lumberParts.set(p.id,object);}
      const object=lumberParts.get(p.id);transform(object,p,next.get(p.id)||p,alpha);object.visible=!!p.visible;
      if(object.userData.glowing!==!!p.glowing){object.traverse(mesh=>{if(mesh.material?.emissive){mesh.material=mesh.material.clone();mesh.material.emissive.set(p.glowing?'#ffc857':'#000000');mesh.material.emissiveIntensity=p.glowing ? .65 : 0;}});object.userData.glowing=!!p.glowing;}
    }
    for(let i=0;i<millParts.length;i++){
      const {part,object}=millParts[i],p=a.machine[i],n=b.machine[i];
      const from={center:p.slice(1,4).map((v,j)=>v+SAWMILL.origin[j]),size:p.slice(4,7),rotation:p.slice(7,11),itemModel:part.itemModel};
      const to={center:n.slice(1,4).map((v,j)=>v+SAWMILL.origin[j]),size:n.slice(4,7),rotation:n.slice(7,11),itemModel:part.itemModel};
      transform(object,from,to,alpha);
      // Machine objects are children of its translated local origin.
      object.position.sub(millRoot.position);
      object.visible=p[0]&&(part.motion!=='sawdust'||$('millDust').checked);
      const glowing=a.machineGlowing.includes(part.id);
      if(object.userData.glowing!==glowing){object.traverse(mesh=>{if(mesh.material?.emissive){mesh.material=mesh.material.clone();mesh.material.emissive.set(glowing?'#ffc857':'#000000');mesh.material.emissiveIntensity=glowing?.65:0;}});object.userData.glowing=glowing;}
    }
    millRoot.visible=lumberBeta;lumberRoot.visible=lumberBeta;
    $('lumberBeat').value = [...LUMBER.beats].reverse().find(beat=>beat.at<=lumberTime)?.at;
    $('millClock').textContent=`${lumberTime.toFixed(1)} / ${LUMBER.seconds} с`;
    $('millTimeline').value=lumberTime;
    const loadingBoards=a.phase==='DELIVERING'&&!a.boardCartLoaded;
    const title=loadingBoards?'Грузим доски':labels[a.incident||a.phase];
    const hint=a.incident==='FEED_JAM'&&a.crankHeld?'Иди вокруг ворота':a.incident?phaseHint[a.incident]:loadingBoards?'Подожди':a.phase==='SAWING'&&a.running?'Идёт распил':phaseHint[a.phase];
    $('millPhase').textContent=lumberBeta?title:'Обычный мир';
    $('millPhaseDetail').textContent=lumberBeta?hint:'Исходное дерево · без private-дисплеев';
    $('millStatus').textContent=a.incident==='FEED_JAM'?`Полные обороты ${a.crankTurns}/2`:a.incident==='SAWDUST_SMOLDER'?`Потушено очагов ${a.hotspotsCompleted}/3`:`Круг ${a.round} · ветви ${a.branches}/3 · распилы ${a.passes}/2 · ${a.running?'рычаг ON':'рычаг OFF'}`;
    $('millFrameLabel').textContent=lumberBeta?`${title} · ${lumberTime.toFixed(1)} с`:'Без права beta · исходный мир';
    const newMask=a.maskTree&&lumberBeta?(a.maskStump?'whole':'upper'):null;
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
  millTime=lumberTime; if(!query.has('view')) query.set('view',eventEpisode?(query.get('incident')==='feed-jam'?'crank':'smolder'):'forest');
  function animateLumber(now){requestAnimationFrame(animateLumber);if(!window.atelierReady||document.hidden||!lumberPlaying){lumberLast=null;return;}
    if(lumberLast!==null)lumberTime=Math.min(LUMBER.seconds,lumberTime+Math.min((now-lumberLast)/1000,.1)*millSpeed);lumberLast=now;
    poseMill(lumberTime);render();if(lumberTime>=LUMBER.seconds)setMillPlaying(false);
  }
  requestAnimationFrame(animateLumber);
}
