import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test, waitUntil } from '@drownek/plugwright';

const state = async () => JSON.parse(await readFile(join(process.env.SERVER_DIR,'plugins/ArcFarms/data/state.json'),'utf8')).mines.lab_mine;

if (process.env.MINE_LIFT_TEST === 'true') for (const scenario of ['injured_miner', 'convoy']) test(`${scenario} travels with the player in the real lift to another floor`, async ({player,server,signal}) => {
  await player.makeOp();
  await player.teleport(707.5,64,8.5);
  const point=(x,y,z)=>player.bot.entity.position.clone().set(x,y,z);
  const reaches=async(predicate,message,timeout=45000)=>{
    await waitUntil(async()=>predicate(await state()),{signal,timeout,message});
    return state();
  };
  const walk=async(destination)=>{
    if(player.bot.entity.position.distanceTo(destination)<0.6) return;
    try {
      await player.bot.lookAt(destination.offset(0,1.5,0),true);
      player.bot.setControlState('forward',true);
      await waitUntil(()=>player.bot.entity.position.distanceTo(destination)<0.7,{signal,timeout:15000,message:`Walk to ${destination}`});
    } finally {player.bot.clearControlStates();}
  };
  const initial=await reaches(s=>s?.phase==='MINING','Prepared mine starts automatically after server restart');
  await waitUntil(async()=>{
    if ((await state())?.incident?.type===scenario.toUpperCase()) return true;
    player.chat(`/arcfarms admin worksite mine lab_mine incident ${scenario}`);
    return false;
  },{signal,timeout:60000,interval:2000,message:'Event starts after preceding room restoration'});
  const active=await state();
  const placement=active.incident.scenarioPlacement;
  assert.equal(placement.floorId,'bottom');
  assert.equal(placement.destinationFloorId,'top');
  await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5),{signal,timeout:30000,message:'Rescue entrance appears'});
  await player.bot.activateEntity(Object.values(player.bot.entities).find(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5));
  await waitUntil(()=>player.bot.entity.position.y>200,{signal,timeout:10000,message:'Enter rescue chamber'});
  const origin=placement.origin;
  if (scenario==='convoy') {
    await player.giveItem('iron_pickaxe',1);
    await player.bot.equip(player.bot.inventory.items().find(item=>item.name==='iron_pickaxe'),'hand');
  }
  const firstCount=scenario==='convoy'?2:1;
  for(let action=0;action<=firstCount;action++) {
    const stage=action<firstCount?0:1;
    const current=await reaches(s=>s.objective?.key?.objectiveId===`scenario_${stage}`,'Event exposes the next physical stage');
    const target=current.objective.targets.find(t=>t.status==='AVAILABLE').position;
    await walk(point(origin.x+8.5,target.y,player.bot.entity.position.z));
    await walk(point(origin.x+8.5,target.y,target.z+0.5));
    await walk(point(target.x+(target.x<origin.x+8?1.5:-0.5),target.y,target.z+0.5));
    if(scenario==='injured_miner') await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='villager'),{signal,timeout:5000,message:'The injured miner is an actual visible villager'});
    const block=player.bot.blockAt(point(target.x,target.y,target.z));
    if(scenario==='convoy'&&stage===0) await player.bot.dig(block,true);
    else await player.bot.activateBlock(block);
    await reaches(s=>stage===0?s.incident?.progress===action+1:s.objective?.targets.some(t=>t.status==='LEASED'),'Complete preparation then accept the physical passenger or cart');
  }
  const feetY=origin.y+2;
  await walk(point(origin.x+8.5,feetY,player.bot.entity.position.z));
  if(scenario==='convoy') {
    // Follow the actual cart along the full chamber route; no progress command or player teleport.
    for(let step=0;step<19;step++) {
      if(player.bot.entity.position.y<100) break;
      await walk(point(origin.x+8.5,feetY,origin.z+3.5+step));
      await reaches(s=>s.incident?.scenarioStep>step,'Cart advances only with its nearby escort');
    }
  } else {
    await walk(point(origin.x+8.5,feetY,origin.z+3.5));
    const exit=Object.values(player.bot.entities).find(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5);
    assert.ok(exit,'Return marker is reachable');
    await player.bot.activateEntity(exit);
  }
  await waitUntil(()=>player.bot.entity.position.y<100,{signal,timeout:10000,message:'Leave chamber with passenger or escorted cart'});
  assert.equal((await state()).incident.progress,firstCount,'Returning to the source floor does not finish delivery');
  player.chat('/minelift');
  await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='interaction'&&Math.abs(e.position.y-64)<0.1&&Math.abs(e.position.x-715.5)<3),{signal,timeout:15000,message:'Call the real cabin to the bottom floor'});
  await waitUntil(()=>{
    if(player.messageBuffer.some(message=>message.includes('phase=DOCKED floor=1'))) return true;
    player.chat('/minelift status');
    return false;
  },{signal,timeout:10000,interval:500,message:'Cabin fully docks before boarding'});
  player.chat('/minelift 1');
  await waitUntil(()=>player.bot.entity.position.y>=79&&player.bot.entity.position.y<100,{signal,timeout:20000,message:'Ride the real lift to the top floor'});
  await waitUntil(()=>{
    if(player.messageBuffer.some(message=>message.includes('phase=DOCKED floor=0')&&message.includes('riders=0 recovery=0'))) return true;
    player.chat('/minelift status');
    return false;
  },{signal,timeout:10000,interval:500,message:'Server confirms the passenger is unloaded and durable return is cleared'});
  assert.ok(player.bot.entity.position.distanceTo(point(707.5,80,8.5))<1.5,'Passenger actually arrives on the landing');
  await walk(point(708.5,80,8.5));
  const completed=await reaches(s=>s.phase==='MINING'&&s.incident==null,'Deliver the passenger or escorted cart on the destination floor');
  assert.equal(completed.orderId,initial.orderId);
  assert.equal(completed.mined,initial.mined);
});

if (process.env.MINE_LIFT_TEST === 'true') test('runaway cart requires timed switches, a real cart escort and a lift trip', async ({player,server,signal}) => {
  await player.makeOp();
  await player.teleport(707.5,64,8.5);
  const point=(x,y,z)=>player.bot.entity.position.clone().set(x,y,z);
  const reaches=async(predicate,message,timeout=45000)=>{
    await waitUntil(async()=>predicate(await state()),{signal,timeout,message});
    return state();
  };
  const walk=async(destination)=>{
    if(player.bot.entity.position.distanceTo(destination)<0.6) return;
    try {
      await player.bot.lookAt(destination.offset(0,1.5,0),true);
      player.bot.setControlState('forward',true);
      await waitUntil(()=>player.bot.entity.position.distanceTo(destination)<0.7,{signal,timeout:15000,message:`Walk to ${destination}`});
    } finally {player.bot.clearControlStates();}
  };
  const initial=await reaches(s=>s?.phase==='MINING','Prepared mine starts automatically after server restart');
  await waitUntil(async()=>{
    if ((await state())?.incident?.type==='RUNAWAY_CART') return true;
    player.chat('/arcfarms admin worksite mine lab_mine incident runaway_cart');
    return false;
  },{signal,timeout:60000,interval:2000,message:'Runaway cart starts after preceding room restoration'});
  const active=await state();
  const placement=active.incident.scenarioPlacement;
  assert.equal(placement.floorId,'bottom');
  assert.equal(placement.destinationFloorId,'top');
  await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5),{signal,timeout:30000,message:'Runaway cart entrance appears'});
  await player.bot.activateEntity(Object.values(player.bot.entities).find(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5));
  await waitUntil(()=>player.bot.entity.position.y>200,{signal,timeout:10000,message:'Enter runaway cart chamber'});

  const origin=placement.origin;
  const walkToPad=async target=>{
    await walk(point(origin.x+8.5,target.y,player.bot.entity.position.z));
    await walk(point(origin.x+8.5,target.y,target.z+0.5));
    await walk(point(target.x+(target.x<origin.x+8?1.5:-0.5),target.y,target.z+0.5));
  };
  const steer=async(stageTarget,minStep,maxStep,progress)=>{
    await walkToPad(stageTarget);
    await waitUntil(async()=>{
      const current=await state();
      return current.incident?.scenarioStep>=minStep&&current.incident?.scenarioStep<=maxStep;
    },{signal,timeout:12000,message:`Click switch during the ${maxStep} step gate`});
    await player.bot.activateBlock(player.bot.blockAt(point(stageTarget.x,stageTarget.y,stageTarget.z)));
    await reaches(s=>s.incident?.progress===progress,`Switch click at gate ${maxStep} advances progress`);
  };
  let current=await reaches(s=>s.objective?.key?.objectiveId==='scenario_0','Switch stage appears');
  await steer(current.objective.targets.find(t=>t.status==='AVAILABLE').position,3,5,1);
  current=await reaches(s=>s.objective?.key?.objectiveId==='scenario_0'&&s.objective.targets.some(t=>t.status==='AVAILABLE'),'Second switch target appears');
  await steer(current.objective.targets.find(t=>t.status==='AVAILABLE').position,10,12,2);

  current=await reaches(s=>s.objective?.key?.objectiveId==='scenario_1','Brake stage appears');
  const brake=current.objective.targets.find(t=>t.status==='AVAILABLE').position;
  await walkToPad(brake);
  await player.bot.activateBlock(player.bot.blockAt(point(brake.x,brake.y,brake.z)));
  await reaches(s=>s.objective?.key?.objectiveId==='scenario_2','Cart escort stage appears');
  const escort=(await state()).objective.targets.find(t=>t.status==='AVAILABLE').position;
  await walkToPad(escort);
  await player.bot.activateBlock(player.bot.blockAt(point(escort.x,escort.y,escort.z)));
  await reaches(s=>s.objective?.targets.some(t=>t.status==='LEASED'),'Escort target must be leased');
  await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='item_display'),{signal,timeout:10000,message:'Escort stage must show the real cart display'});
  const feetY=origin.y+2;
  for(let step=(await state()).incident.scenarioStep;step<19;step++) {
    if(player.bot.entity.position.y<100) break;
    await walk(point(origin.x+8.5,feetY,origin.z+3.5+step));
    await reaches(s=>s.incident?.scenarioStep>step,`Cart advances through escort step ${step}`);
  }
  await waitUntil(()=>player.bot.entity.position.y<100,{signal,timeout:10000,message:'Completed cart escort returns the player to the bottom landing'});
  player.chat('/minelift');
  await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='interaction'&&Math.abs(e.position.y-64)<0.1&&Math.abs(e.position.x-715.5)<3),{signal,timeout:15000,message:'Call the real cabin to the bottom floor'});
  await waitUntil(()=>{
    if(player.messageBuffer.some(message=>message.includes('phase=DOCKED floor=1'))) return true;
    player.chat('/minelift status');
    return false;
  },{signal,timeout:10000,interval:500,message:'Cabin docks before boarding'});
  player.chat('/minelift 1');
  await waitUntil(()=>player.bot.entity.position.y>=79&&player.bot.entity.position.y<100,{signal,timeout:20000,message:'Ride the real lift to the top floor'});
  await waitUntil(()=>{
    if(player.messageBuffer.some(message=>message.includes('phase=DOCKED floor=0')&&message.includes('riders=0 recovery=0'))) return true;
    player.chat('/minelift status');
    return false;
  },{signal,timeout:10000,interval:500,message:'Top landing must be fully docked before delivery'});
  await walk(point(708.5,80,8.5));
  const completed=await reaches(s=>s.phase==='MINING'&&s.incident==null,'Runaway cart resolves after lift delivery');
  assert.equal(completed.mined,initial.mined);
});

if (process.env.MINE_LIFT_TEST === 'true') test('lift breakdown locks maintenance during repair and resumes real lift travel', async ({player,server,signal}) => {
  await player.makeOp();
  await player.teleport(707.5,64,8.5);
  const point=(x,y,z)=>player.bot.entity.position.clone().set(x,y,z);
  const reaches=async(predicate,message,timeout=45000)=>{
    await waitUntil(async()=>predicate(await state()),{signal,timeout,message});
    return state();
  };
  const walk=async(destination)=>{
    if(player.bot.entity.position.distanceTo(destination)<0.6) return;
    try {
      await player.bot.lookAt(destination.offset(0,1.5,0),true);
      player.bot.setControlState('forward',true);
      await waitUntil(()=>player.bot.entity.position.distanceTo(destination)<0.7,{signal,timeout:15000,message:`Walk to ${destination}`});
    } finally {player.bot.clearControlStates();}
  };
  await reaches(s=>s?.phase==='MINING','Prepared mine starts automatically after server restart');
  await waitUntil(async()=>{
    if ((await state())?.incident?.type==='LIFT_BREAKDOWN') return true;
    player.chat('/arcfarms admin worksite mine lab_mine incident lift_breakdown');
    return false;
  },{signal,timeout:60000,interval:2000,message:'Lift breakdown starts after preceding room restoration'});
  const active=await state();
  const placement=active.incident.scenarioPlacement;
  assert.equal(placement.floorId,'bottom');
  assert.equal(placement.destinationFloorId,'bottom');
  await waitUntil(()=>{
    if(player.messageBuffer.some(message=>message.includes('unavailable')||message.includes('Unavailable'))) return true;
    player.chat('/minelift');
    return false;
  },{signal,timeout:10000,interval:500,message:'Maintenance must reject a new lift call'});
  await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5),{signal,timeout:30000,message:'Lift breakdown entrance appears'});
  await player.bot.activateEntity(Object.values(player.bot.entities).find(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5));
  await waitUntil(()=>player.bot.entity.position.y>200,{signal,timeout:10000,message:'Enter lift breakdown chamber'});
  const origin=placement.origin;
  const walkToPad=async target=>{
    await walk(point(origin.x+8.5,target.y,player.bot.entity.position.z));
    await walk(point(origin.x+8.5,target.y,target.z+0.5));
    await walk(point(target.x+(target.x<origin.x+8?1.5:-0.5),target.y,target.z+0.5));
  };
  let current=await reaches(s=>s.objective?.key?.objectiveId==='scenario_0','Exit repair stage appears');
  let target=current.objective.targets.find(t=>t.status==='AVAILABLE').position;
  await walkToPad(target);
  await player.bot.activateBlock(player.bot.blockAt(point(target.x,target.y,target.z)));
  current=await reaches(s=>s.objective?.key?.objectiveId==='scenario_1','Parts carry stage appears');
  target=current.objective.targets.find(t=>t.status==='AVAILABLE').position;
  await walkToPad(target);
  await player.bot.activateBlock(player.bot.blockAt(point(target.x,target.y,target.z)));
  await reaches(s=>s.objective?.targets.some(t=>t.status==='LEASED'),'Repair part must be leased');
  await walk(point(origin.x+8.5,origin.y+2,player.bot.entity.position.z));
  await walk(point(origin.x+8.5,origin.y+2,origin.z+3.5));
  current=await reaches(s=>s.objective?.key?.objectiveId==='scenario_2','Ordered lift test stage appears');
  target=current.objective.targets.find(t=>t.status==='AVAILABLE').position;
  await walkToPad(target);
  await player.bot.activateBlock(player.bot.blockAt(point(target.x,target.y,target.z)));
  await reaches(s=>s.phase==='MINING'&&s.incident==null,'Lift repair restores the mine order');
  await waitUntil(()=>player.bot.entity.position.y<100,{signal,timeout:10000,message:'Maintenance cleanup returns the player to the bottom landing'});
  await waitUntil(()=>{
    if(player.messageBuffer.some(message=>message.includes('phase=DOCKED floor=1'))) return true;
    player.chat('/minelift');
    player.chat('/minelift status');
    return false;
  },{signal,timeout:20000,interval:1000,message:'Maintenance must release before the cabin docks at bottom'});
  player.chat('/minelift 1');
  await waitUntil(()=>player.bot.entity.position.y>=79&&player.bot.entity.position.y<100,{signal,timeout:20000,message:'Repaired lift must carry the player to the top floor'});
});

if (process.env.MINE_LIFT_TEST === 'true') test('lift breakdown timeout restores ordinary mining without contribution or reward', async ({player,server,signal}) => {
  const LIFT_BREAKDOWN_TIMEOUT=180000;
  const snapshot=async()=>JSON.parse(await readFile(join(process.env.SERVER_DIR,'plugins/ArcFarms/data/state.json'),'utf8'));
  await player.makeOp();
  await player.teleport(707.5,64,8.5);
  const initial=await snapshot();
  const initialMine=initial.mines.lab_mine;
  const playerId=player.bot.player.uuid;
  await waitUntil(async()=>((await state())?.phase==='MINING'),{signal,timeout:30000,message:'Prepared mine starts ordinary mining before breakdown timeout'});
  await waitUntil(async()=>{
    if ((await state())?.incident?.type==='LIFT_BREAKDOWN') return true;
    player.chat('/arcfarms admin worksite mine lab_mine incident lift_breakdown');
    return false;
  },{signal,timeout:60000,interval:2000,message:'Lift breakdown starts for timeout recovery'});
  const started=await state();
  assert.equal(started.incident.scenarioPlacement.floorId,'bottom');
  await waitUntil(()=>Object.values(player.bot.entities).some(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5),{signal,timeout:30000,message:'Breakdown chamber entrance appears'});
  await player.bot.activateEntity(Object.values(player.bot.entities).find(e=>e.name==='interaction'&&e.position.distanceTo(player.bot.entity.position)<1.5));
  await waitUntil(()=>player.bot.entity.position.y>200,{signal,timeout:10000,message:'Enter breakdown chamber without repairing it'});
  await waitUntil(async()=>{
    const current=await state();
    return current.phase==='MINING'&&current.incident==null;
  },{signal,timeout:LIFT_BREAKDOWN_TIMEOUT,message:'Unattended breakdown must persistently restore ordinary mining'});
  await waitUntil(()=>player.bot.entity.position.y<100,{signal,timeout:15000,message:'Automatic breakdown recovery returns the player to the landing'});
  await waitUntil(()=>{
    if(player.messageBuffer.some(message=>message.includes('phase=DOCKED floor=1')&&message.includes('riders=0 recovery=0'))) return true;
    player.chat('/minelift');
    player.chat('/minelift status');
    return false;
  },{signal,timeout:15000,interval:500,message:'Lift must be released and docked after timeout recovery'});
  const completed=await snapshot();
  const completedMine=completed.mines.lab_mine;
  assert.equal(completedMine.phase,'MINING');
  assert.equal(completedMine.orderId,initialMine.orderId);
  assert.equal(completedMine.mined,initialMine.mined);
  assert.deepEqual(completedMine.contributors,initialMine.contributors);
  assert.equal(completed.stats[playerId]?.completedShifts?.MINE ?? 0,initial.stats[playerId]?.completedShifts?.MINE ?? 0);
  assert.deepEqual(completed.pendingFarmRewards,initial.pendingFarmRewards);
});
