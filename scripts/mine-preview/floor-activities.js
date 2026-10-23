// Replays exported canonical Kotlin poses; no second machine geometry or motion model lives here.
if (recipe.floorActivityFrames) {
  const recording = recipe.floorActivityFrames;
  demoPanel.hidden = true;
  const panel = document.createElement('div');
  panel.className = 'section';
  const button = document.createElement('button');
  button.textContent = 'Проиграть цикл';
  const timeline = document.createElement('input');
  timeline.type = 'range'; timeline.min = '0'; timeline.max = String(recording.duration); timeline.step = '100'; timeline.value = '0';
  timeline.setAttribute('aria-label', 'Момент цикла');
  const label = document.createElement('p'); label.className = 'subtle';
  panel.append(button, timeline, label);
  document.querySelector('aside').prepend(panel);
  let playing = false, started = 0, elapsed = 0;
  let references = null;
  const referenceToggle = document.createElement('input'); referenceToggle.type = 'checkbox'; referenceToggle.checked = true;
  const referenceLabel = document.createElement('label'); referenceLabel.append(referenceToggle, ' Силуэт игрока 1,8 м · шкала');
  const controlToggle = document.createElement('input'); controlToggle.type = 'checkbox';
  const controlLabel = document.createElement('label'); controlLabel.append(controlToggle, ' Показать реальные хитбоксы');
  panel.append(referenceLabel, controlLabel);
  function rebuildReferences() {
    if (references) { world.remove(references); disposeVanillaMesh(references); }
    references = new THREE.Group();
    if (recipe.playerScaleReference && referenceToggle.checked) {
      const person = new THREE.Group(); person.position.set(...recipe.playerScaleReference.at);
      const material = new THREE.MeshStandardMaterial({color:0xd9d2b9,roughness:.95});
      for (const [position,size] of [
        [[-.12,.375,0],[.2,.75,.24]],[[.12,.375,0],[.2,.75,.24]],
        [[0,1.08,0],[.36,.66,.24]],[[0,1.605,0],[.39,.39,.39]],
        [[-.25,1.08,0],[.14,.66,.22]],[[.25,1.08,0],[.14,.66,.22]],
      ]) {
        const body = new THREE.Mesh(new THREE.BoxGeometry(...size),material); body.position.set(...position); person.add(body);
      }
      references.add(person);
    }
    if (controlToggle.checked) {
      for (const [role,center] of Object.entries(recipe.floorActivityControls ?? {})) {
        const geometry = new THREE.EdgesGeometry(new THREE.BoxGeometry(1.15,1.1,1.15));
        const box = new THREE.LineSegments(geometry,new THREE.LineBasicMaterial({color:0xffbf4e}));
        box.position.set(center[0]+11.5,center[1]+2,center[2]+8.5); box.userData.role=role; references.add(box);
      }
    }
    world.add(references);
  }
  referenceToggle.onchange = controlToggle.onchange = () => { rebuildReferences(); render(); };
  const pose = (time) => {
    const sample = Math.min(recording.frames.length - 1, Math.floor(time / recording.cadence));
    const a = recording.frames[sample], b = recording.frames[Math.min(sample + 1, recording.frames.length - 1)];
    const u = Math.min(1, Math.max(0, (time - a.time) / recording.cadence));
    expeditionDisplays?.children.forEach((assembly, index) => {
      const part = a.parts[index], next = b.parts[index], mesh = assembly.children[0];
      if (!part || !next || !mesh) return;
      const center = new THREE.Vector3(...part.center).lerp(new THREE.Vector3(...next.center), u);
      const size = new THREE.Vector3(...part.size).lerp(new THREE.Vector3(...next.size), u);
      const rotation = new THREE.Quaternion(...part.quaternion).slerp(new THREE.Quaternion(...next.quaternion), u);
      mesh.position.copy(size.clone().multiplyScalar(-.5).applyQuaternion(rotation).add(center));
      mesh.quaternion.copy(rotation); mesh.scale.copy(size); mesh.visible = part.visible;
    });
    label.textContent = `${(time / 1000).toFixed(1)} с · этап ${a.progress} · экспорт 10 Hz · звук/управление проверяются в клиенте`;
    timeline.value = String(time);
    render();
  };
  timeline.oninput = () => { playing = false; button.textContent = 'Проиграть цикл'; elapsed = Number(timeline.value); pose(elapsed); };
  button.onclick = () => {
    playing = !playing; button.textContent = playing ? 'Пауза' : 'Проиграть цикл';
    if (playing) {
      if (elapsed >= recording.duration) elapsed = 0;
      started = performance.now() - elapsed; requestAnimationFrame(frame);
    }
  };
  function frame(now) {
    if (!playing) return;
    elapsed = Math.min(recording.duration, now - started); pose(elapsed);
    if (elapsed >= recording.duration) { playing = false; button.textContent = 'Проиграть цикл'; }
    else requestAnimationFrame(frame);
  }
  const buildWithFloorActivity = buildMesh;
  buildMesh = function () { buildWithFloorActivity(); rebuildReferences(); pose(elapsed); };
  // loadScene calls this wrapper after textures and model are ready on first load.
  if (window.atelierReady) buildMesh();
}
