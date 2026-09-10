import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test, waitUntil } from '@drownek/plugwright';

if(process.env.MINE_AUTO_TEST==='true') test('ordinary coal mining automatically starts the scheduled cave-in at halfway',async({player,server,signal})=>{
  await player.makeOp();
  server.execute('minecraft:forceload add 688 -16 736 32');
  server.execute('minecraft:fill 698 63 -2 714 63 14 minecraft:stone');
  server.execute('minecraft:fill 700 64 0 709 64 9 minecraft:coal_ore');
  await player.teleport(700.5,65,0.5);
  player.chat('/arcfarms admin worksite mine lab_mine reindex start');
  await player.giveItem('iron_pickaxe',1);
  await player.bot.equip(player.bot.inventory.items().find(i=>i.name==='iron_pickaxe'),'hand');
  const state=async()=>{
    try {return JSON.parse(await readFile(join(process.env.SERVER_DIR,'plugins/ArcFarms/data/state.json'),'utf8')).mines.lab_mine;}
    catch(error) {if(error.code==='ENOENT') return {}; throw error;}
  };
  await waitUntil(async()=>(await state()).phase==='MINING',{signal,timeout:20000,message:'Ordinary order starts without a start command'});
  for(let i=0;i<50;i++){
    const x=700+i%10,z=Math.floor(i/10);
    await player.teleport(x+.5,65,z+.5);
    const at=player.bot.entity.position.clone().set(x,64,z);
    await waitUntil(()=>player.bot.blockAt(at)?.name==='coal_ore',{signal,timeout:5000,message:'Ordinary ore is visible'});
    await player.bot.dig(player.bot.blockAt(at),true);
    await waitUntil(async()=>(await state()).mined===i+1,{signal,timeout:5000,message:'Every ordinary ore block counts exactly once'});
  }
  await waitUntil(async()=>(await state()).incident?.type==='CAVE_IN',{signal,timeout:20000,message:'Scheduled incident starts automatically at 50 of 100 ore'});
  const interrupted=await state();
  assert.equal(interrupted.mined,50);
  assert.equal(interrupted.phase,'INCIDENT');
  assert.ok(interrupted.incident.scenarioPlacement);
  assert.ok(player.bot.scoreboard.sidebar,'The worksite sidebar remains visible during the interruption');
});
