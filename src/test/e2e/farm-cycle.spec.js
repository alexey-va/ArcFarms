import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { test, expect, waitUntil } from '@drownek/plugwright';

async function state() {
  try {
    return JSON.parse(await readFile(join(process.env.SERVER_DIR, 'plugins/ArcFarms/data/state.json'), 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return {};
    throw error;
  }
}

async function phase(expected, signal) {
  try {
    await waitUntil(async () => (await state()).farms?.communal_farm?.phase === expected, {
      signal, timeout: 20000, message: `Farm must durably enter ${expected}`,
    });
  } catch (error) {
    console.error('Persisted farm at failed phase:', JSON.stringify((await state()).farms?.communal_farm));
    throw error;
  }
  return (await state()).farms.communal_farm;
}

async function command(server, player, commands) {
  const marker = `farm-sync-${randomUUID()}`;
  for (const cmd of commands) server.execute(cmd);
  server.execute(`minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

test('ordinary farm work reaches mounted delivery, rewards once and survives rejoin', async ({ player, server, signal }) => {
  const playerId = player.bot.player.uuid;
  await command(server, player, [
    'minecraft:gamerule randomTickSpeed 0',
    'minecraft:forceload add 96 -16 144 16',
    'minecraft:setblock 100 -61 0 minecraft:farmland[moisture=7]',
    `minecraft:experience set ${player.username} 0 levels`,
    `minecraft:experience set ${player.username} 0 points`,
  ]);
  await player.giveItem('iron_hoe', 1);
  await player.giveItem('wheat_seeds', 1);
  await player.teleport(100.5, -60, 2.5);
  const started = await phase('PREPARATION', signal);
  assert.equal(started.sequence, 1);
  assert.deepEqual(started.preparationPatch.map(({ x, y, z }) => [x, y, z]), [[100, -61, 0]]);

  const soilPosition = player.bot.entity.position.clone().set(100, -61, 0);
  await waitUntil(() => player.bot.blockAt(soilPosition)?.name === 'dirt', { signal });
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'iron_hoe'), 'hand');
  await player.bot.activateBlock(player.bot.blockAt(soilPosition));
  await phase('PLANTING', signal);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'wheat_seeds'), 'hand');
  await player.bot.activateBlock(player.bot.blockAt(soilPosition));
  const care = await phase('CARE', signal);
  assert.equal(care.careType, 'WEEDS');
  assert.equal(care.careTargets.length, 1);
  const weedPosition = care.careTargets[0].position;
  const weed = () => Object.values(player.bot.entities).find(entity => entity.name === 'interaction' &&
    entity.position.distanceTo(soilPosition.clone().set(weedPosition.x, weedPosition.y, weedPosition.z)) < 0.5);
  await waitUntil(weed, { signal });
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'iron_hoe'), 'hand');
  await player.bot.activateEntity(weed());
  await phase('HARVESTING', signal);
  assert.equal(player.bot.inventory.items().find(item => item.name === 'wheat_seeds')?.count, 1);

  // Grow the planted crop in the fixture; the player still performs the actual harvest.
  await command(server, player, ['minecraft:setblock 100 -60 0 minecraft:wheat[age=7]']);
  const cropPosition = soilPosition.offset(0, 1, 0);
  await waitUntil(() => player.bot.blockAt(cropPosition)?.getProperties().age === '7', { signal });
  await player.bot.dig(player.bot.blockAt(cropPosition));
  const delivery = await phase('DELIVERY', signal);
  assert.equal(delivery.progress.WHEAT, 1);
  assert.equal(player.bot.inventory.items().filter(item => item.name === 'wheat').length, 0);

  // Approach the actual spawned crate. Its persistent identity selects the fixture entity only.
  const carrying = `farm-carrying-${randomUUID()}`;
  await command(server, player, [
    `minecraft:execute as @e[type=minecraft:interaction,nbt={BukkitValues:{"arcfarms:farm_delivery_role":"GROUND_INTERACTION"}},limit=1] at @s run tp ${player.username} ~ ~ ~`,
  ]);
  await waitUntil(async () => {
    server.execute(`minecraft:execute if entity @e[type=minecraft:item_display,nbt={BukkitValues:{"arcfarms:farm_delivery_role":"CARRIED_DISPLAY"}}] run tellraw ${player.username} {"text":"${carrying}"}`);
    return player.messageBuffer.some(message => message.includes(carrying));
  }, { signal, timeout: 15000 });
  try {
    const receiving = soilPosition.clone().set(116.5, -60, 0.5);
    await player.bot.lookAt(receiving.offset(0, 1.5, 0), true);
    player.bot.setControlState('forward', true);
    await waitUntil(() => player.bot.entity.position.distanceTo(receiving) < 1.5, { signal, timeout: 15000 });
  } finally {
    player.bot.clearControlStates();
  }
  await command(server, player, [`minecraft:data get entity ${player.username} Pos`]);
  const route = await phase('INCIDENT', signal);
  assert.equal(route.incidentType, 'FOOD_DELIVERY');
  assert.deepEqual(route.deliveredCrates, [0]);
  await player.teleport(120.5, -60, 2.5);
  await waitUntil(() => Object.values(player.bot.entities).some(entity => entity.name === 'horse'), { signal });
  const horse = Object.values(player.bot.entities).find(entity => entity.name === 'horse');
  await player.bot.mount(horse);
  await waitUntil(() => player.bot.vehicle?.id === horse.id, { signal });
  const physicsEnabled = player.bot.physicsEnabled;
  try {
    // Mineflayer has no horse physics. Send the same mounted movement packets as
    // a client, in 0.2-block steps; Paper owns passenger, route and completion checks.
    player.bot.physicsEnabled = false;
    let x = horse.position.x;
    await waitUntil(async () => {
      if ((await state()).farms?.communal_farm?.phase === 'COOLDOWN') return true;
      x = Math.min(x + 0.2, 136.5);
      player.bot._client.write('vehicle_move', { x, y: -60, z: 0.5, yaw: -90, pitch: 0, onGround: true });
      return false;
    }, { signal, interval: 50, timeout: 20000, message: 'Mounted client movement must complete the food route' });
  } finally {
    player.bot.physicsEnabled = physicsEnabled;
    player.bot.clearControlStates();
  }
  await waitUntil(() => player.bot.experience.points === 100, { signal, timeout: 15000 });
  const completed = await state();
  assert.equal(completed.farms.communal_farm.outcome, 'COMPLETED');
  assert.equal(completed.stats[playerId].completedShifts.FARM, 1);
  assert.equal(completed.claimedFarmRewardSequences[`communal_farm:${playerId}`], 1);
  assert.deepEqual(completed.pendingFarmRewards, []);
  await player.rejoin();
  await command(server, player, [`minecraft:tellraw ${player.username} {"text":"farm-rejoined"}`]);
  assert.equal(player.bot.experience.points, 100);
  assert.equal((await state()).stats[playerId].completedShifts.FARM, 1);
});
