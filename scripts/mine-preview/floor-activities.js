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
    if (playing) { started = performance.now() - elapsed; requestAnimationFrame(frame); }
  };
  function frame(now) {
    if (!playing) return;
    elapsed = Math.min(recording.duration, now - started); pose(elapsed);
    if (elapsed >= recording.duration) { playing = false; button.textContent = 'Проиграть цикл'; elapsed = 0; }
    else requestAnimationFrame(frame);
  }
  const buildWithFloorActivity = buildMesh;
  buildMesh = function () { buildWithFloorActivity(); pose(elapsed); };
  // loadScene calls this wrapper after textures and model are ready on first load.
  if (window.atelierReady) buildMesh();
}
