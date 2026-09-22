// The same authored part IDs and locale descriptions used by the in-game guide.
if (recipe.inspectionCatalog) {
  const hint = document.createElement('div');
  hint.style.cssText = 'position:absolute;left:50%;bottom:94px;transform:translateX(-50%);width:min(360px,80%);padding:14px 18px;background:#0f171ef2;color:#b8c4c8;border:1px solid #516561;border-radius:8px;pointer-events:none;z-index:5;box-shadow:0 8px 24px #0004;';
  hint.hidden = true;
  hint.setAttribute('role', 'status');
  hint.setAttribute('aria-live', 'polite');
  const title = document.createElement('strong');
  title.style.cssText = 'display:block;color:#f2fff7;margin-bottom:6px;font-size:16px;';
  const description = document.createElement('span');
  description.style.cssText = 'font-size:13px;line-height:1.45;';
  hint.append(title, description);
  $('viewport').append(hint);
  const toggle = document.createElement('button');
  toggle.textContent = 'Гид по двигателю';
  toggle.setAttribute('aria-pressed', 'true');
  const note = document.createElement('p');
  note.className = 'subtle';
  note.textContent = 'Наведите указатель на деталь. В Minecraft — наведите прицел; подсказки используют настройки ARC.';
  demoPanel.append(toggle, note);
  const pointer = new THREE.Vector2();
  const ray = new THREE.Raycaster();
  let inside = false, enabled = true, selected = null, lastCheck = 0;
  function inspect(now = performance.now()) {
    if (!enabled || !inside || !expeditionDisplays || now - lastCheck < 100) return;
    lastCheck = now;
    ray.setFromCamera(pointer, camera);
    expeditionDisplays.updateMatrixWorld(true);
    const hit = ray.intersectObjects(expeditionDisplays.children, true).find(entry => {
      if (entry.object.isSprite) return false; // Combustion and smoke do not obstruct solid-part selection.
      for (let current = entry.object; current; current = current.parent) if (!current.visible) return false;
      return true;
    });
    let part = hit?.object;
    while (part && !Object.prototype.hasOwnProperty.call(part.userData, 'engineInspection')) part = part.parent;
    let key = part?.userData.engineInspection ?? null;
    // The nearest solid world geometry remains an occluder in the preview too.
    if (key && mesh && ray.intersectObject(mesh, true).some(block => block.distance < hit.distance - .001)) key = null;
    if (key === selected) return;
    selected = key;
    const entry = recipe.inspectionCatalog[key];
    hint.hidden = !entry;
    title.textContent = entry?.title ?? '';
    description.textContent = entry?.description ?? '';
  }
  function clear() { inside = false; selected = null; lastCheck = 0; hint.hidden = true; }
  function point(event) {
    if (event.buttons) { clear(); return; }
    const bounds = renderer.domElement.getBoundingClientRect();
    pointer.set((event.clientX - bounds.left) / bounds.width * 2 - 1, 1 - (event.clientY - bounds.top) / bounds.height * 2);
    inside = true;
    inspect();
  }
  renderer.domElement.addEventListener('pointermove', point);
  renderer.domElement.addEventListener('pointerup', point);
  renderer.domElement.addEventListener('pointerleave', clear);
  renderer.domElement.addEventListener('pointerdown', clear);
  toggle.onclick = () => { enabled = !enabled; toggle.setAttribute('aria-pressed', String(enabled)); clear(); };
  controls.addEventListener('change', clear);
  // Piggyback on the existing presentation frame so moving parts stay selectable under a still pointer.
  const renderWithInspection = render;
  render = function () { renderWithInspection(); inspect(); };
}
