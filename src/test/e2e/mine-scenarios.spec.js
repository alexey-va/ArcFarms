import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test, waitUntil } from '@drownek/plugwright';

const state = async () => {
  try {
    return JSON.parse(await readFile(join(process.env.SERVER_DIR, 'plugins/ArcFarms/data/state.json'), 'utf8')).mines.lab_mine;
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
};

for (const scenario of [
  {id:'cave_in', actions:['break','break','interact'], firstStage:2},
  {id:'power_failure', actions:['break','interact','interact'], firstStage:1},
  {id:'fungal_bloom', actions:['break','break','break'], firstStage:2},
  {id:'root_invasion', actions:['break','break','break'], firstStage:2},
  {id:'flooding', actions:['interact','interact','sustain'], firstStage:2},
  {id:'gas_leak', actions:['interact','interact','sustain'], firstStage:2},
  {id:'lava_breach', actions:['carry','carry','interact','interact'], firstStage:2},
  {id:'ancient_door', actions:['carry','interact'], firstStage:1},
  {id:'old_warehouse', actions:['carry','carry','interact'], firstStage:2},
  {id:'drill_trial', actions:['carry','sustain','sustain','break'], stages:[0,1,1,2]},
  {id:'creature_nest', actions:['combat','combat','combat','break'], firstStage:3},
  {id:'bat_swarm', actions:['herd','interact'], stages:[0,1], progress:[2,3]},
]) test(`mine ${scenario.id}: physical stages, automatic HUD and ordinary order resume`, async ({ player, server, signal }) => {
  await player.makeOp();
  const point = (x,y,z) => player.bot.entity.position.clone().set(x,y,z);
  server.execute('minecraft:forceload add 688 -16 736 32');
  server.execute('minecraft:fill 698 63 -2 714 63 14 minecraft:stone');
  server.execute('minecraft:setblock 700 64 0 minecraft:coal_ore');
  await player.teleport(707.5,64,8.5);
  // Fixture blocks are created after bootstrap; index the new terrain once before exercising automatic start.
  player.chat('/arcfarms admin worksite mine lab_mine reindex start');
  await player.giveItem('iron_pickaxe',1);
  await player.bot.equip(player.bot.inventory.items().find(item=>item.name==='iron_pickaxe'),'hand');
  if(scenario.id==='creature_nest') {
    server.execute('minecraft:difficulty normal');
    for(const item of ['diamond_sword','iron_helmet','iron_chestplate','iron_leggings','iron_boots']) await player.giveItem(item,1);
    for(const [name,slot] of [['iron_helmet','head'],['iron_chestplate','torso'],['iron_leggings','legs'],['iron_boots','feet']]) await player.bot.equip(player.bot.inventory.items().find(item=>item.name===name),slot);
  }
  const reaches = async (predicate, message) => {
    await waitUntil(async()=>predicate(await state()), {signal, timeout:45000, message});
    return state();
  };
  const initial = await reaches(s=>s?.phase==='MINING','Entering the worksite must start a normal order automatically');
  const inventoryCounts=()=>player.bot.inventory.items().map(item=>[item.name,item.count]).sort();
  const initialInventory=inventoryCounts();
  const initialExperience={...player.bot.experience};
  await waitUntil(()=>player.bot.bossBars.some(bar=>bar.title.toString().includes('0/100')) && Boolean(player.bot.scoreboard.sidebar), {signal,timeout:10000,message:'Bossbar and sidebar must appear automatically on entry'});
  assert.equal(initial.mined,0);
  assert.ok(initial.objective == null,'Ordinary coal must not use a highlighted target pool');
  const walk = async destination => {
    try {
      await player.bot.lookAt(destination.offset(0,1.5,0),true);
      player.bot.setControlState('forward',true);
      await waitUntil(()=>player.bot.entity.position.distanceTo(destination)<0.75,{signal,timeout:12000,message:`Walk to ${destination}`});
    } finally { player.bot.clearControlStates(); }
  };
  player.chat(`/arcfarms admin worksite mine lab_mine incident ${scenario.id}`);
  await waitUntil(async()=>{
    if ((await state())?.incident?.type===scenario.id.toUpperCase()) return true;
    player.chat(`/arcfarms admin worksite mine lab_mine incident ${scenario.id}`);
    return false;
  },{signal,timeout:60000,interval:2000,message:'The event must start after the preceding room is restored and preflight succeeds'});
  const started=await state();
  assert.ok(started.incident.scenarioPlacement);
  await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<4),{signal,timeout:45000,message:'A visible clickable entrance must appear'});
  const gate=Object.values(player.bot.entities).find(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<4);
  await player.bot.activateEntity(gate);
  await waitUntil(()=>player.bot.entity.position.y>200,{signal,timeout:15000,message:'Clicking the entrance must enter the prepared room'});
  const origin=started.incident.scenarioPlacement.origin;
  for (let expected=1;expected<=scenario.actions.length;expected++) {
    const current=await reaches(s=>s.objective?.key?.objectiveId===`scenario_${scenario.stages?.[expected-1] ?? (expected<=scenario.firstStage?0:1)}` && s.objective.targets.some(t=>t.status==='AVAILABLE'),'A stage must expose physical targets');
    if(scenario.actions[expected-1]==='combat') {
      await player.bot.equip(player.bot.inventory.items().find(item=>item.name==='diamond_sword'),'hand');
      await walk(point(origin.x+8.5,origin.y+2,origin.z+12.5));
      try {
        await waitUntil(async()=>{
          if((await state())?.incident?.progress>=expected) return true;
          const enemy=Object.values(player.bot.entities).filter(e=>e.name==='husk').sort((a,b)=>a.position.distanceTo(player.bot.entity.position)-b.position.distanceTo(player.bot.entity.position))[0];
          if(!enemy) return false;
          await player.bot.lookAt(enemy.position.offset(0,1.2,0),true);
          const near=enemy.position.distanceTo(player.bot.entity.position)<2.8;
          player.bot.setControlState('forward',!near);
          if(near) player.bot.attack(enemy);
          return false;
        },{signal,timeout:30000,interval:700,message:'Defeat a real hostile creature with normal melee attacks'});
      } finally {player.bot.clearControlStates();}
      continue;
    }
    if(scenario.id==='creature_nest') await player.bot.equip(player.bot.inventory.items().find(item=>item.name==='iron_pickaxe'),'hand');
    const target=current.objective.targets.find(t=>t.status==='AVAILABLE');
    const p=target.position;
    await walk(point(origin.x+8.5,p.y,player.bot.entity.position.z));
    await walk(point(origin.x+8.5,p.y,p.z+0.5));
    await walk(point(p.x+(p.x<origin.x+8?1.5:-0.5),p.y,p.z+0.5));
    const block=player.bot.blockAt(point(p.x,p.y,p.z));
    assert.ok(block&&block.name!=='air');
    if (scenario.actions[expected-1]==='break') await player.bot.dig(block,true);
    else await player.bot.activateBlock(block);
    if (scenario.actions[expected-1]==='carry') {
      await reaches(s=>s.objective?.targets.some(t=>t.status==='LEASED'),'Pickup must lease the physical cargo');
      await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='item_display'&&e.position.distanceTo(player.bot.entity.position)<1.6),{signal,timeout:5000,message:'Leased cargo must be visibly carried beside the player'});
      await walk(point(origin.x+8.5,p.y,player.bot.entity.position.z));
      await walk(point(origin.x+8.5,p.y,origin.z+3.5));
    }
    if(expected<scenario.actions.length) {
      try { await reaches(s=>s.incident?.progress===(scenario.progress?.[expected-1]??expected),'Each physical target contributes once'); }
      catch(error) {
        if(scenario.id==='bat_swarm') console.error('Bat positions at failure:',Object.values(player.bot.entities).filter(e=>e.name==='bat').map(e=>({position:e.position,velocity:e.velocity})), 'player:',player.bot.entity.position);
        throw error;
      }
    }
  }
  const resumed=await reaches(s=>s.phase==='MINING'&&s.incident==null,'Resolving the incident resumes the same coal order');
  assert.equal(resumed.orderId,initial.orderId);
  assert.equal(resumed.mined,0);
  await waitUntil(()=>player.bot.entity.position.y<100,{signal,timeout:15000,message:'Room cleanup must return the player before restoring blocks'});
  assert.equal(player.bot.inventory.items().some(item=>['cobblestone','oak_planks'].includes(item.name)),false,'Temporary targets must not become resource drops');
  assert.deepEqual(inventoryCounts(),initialInventory,'An incident must not grant items or leak block/mob drops');
  assert.deepEqual(player.bot.experience,initialExperience,'An incident must not grant XP separately from the ordinary order');
  if(scenario.id==='creature_nest') server.execute('minecraft:difficulty peaceful');
});
