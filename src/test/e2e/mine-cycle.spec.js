import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { test, expect, waitUntil, waitForStable } from '@drownek/plugwright';

const snapshot = async () => JSON.parse(await readFile(
  join(process.env.SERVER_DIR, 'plugins/ArcFarms/data/state.json'), 'utf8',
));
const mine = async () => (await snapshot()).mines.old_shafts;

test('mine expedition repairs three incidents, carries ore and extracts one durable reward', async ({ player, server, signal }) => {
  await player.makeOp();
  const playerId = player.bot.player.uuid;
  const position = (x, y, z) => player.bot.entity.position.clone().set(x, y, z);
  const ironCount = () => player.bot.inventory.items().filter(item => item.name === 'iron_ingot')
    .reduce((sum, item) => sum + item.count, 0);
  const reaches = async (predicate, message) => {
    try {
      await waitUntil(async () => predicate(await mine()), { signal, timeout: 15000, message });
    } catch (error) {
      console.error('Persisted mine at failure:', JSON.stringify(await mine()));
      throw error;
    }
    return mine();
  };
  const use = async target => {
    const blockPosition = position(target.x, target.y, target.z);
    await waitUntil(() => player.bot.blockAt(blockPosition)?.name !== 'air' && player.bot.blockAt(blockPosition), { signal });
    await player.bot.activateBlock(player.bot.blockAt(blockPosition));
  };
  const approach = async target => {
    await player.teleport(target.x + 0.5, target.y + 1, target.z + 0.5);
    await waitUntil(() => player.bot.entity.position.distanceTo(position(target.x + 0.5, target.y + 1, target.z + 0.5)) < 1, { signal });
  };
  const walk = async destination => {
    try {
      await player.bot.lookAt(destination.offset(0, 1.5, 0), true);
      player.bot.setControlState('forward', true);
      await waitUntil(() => player.bot.entity.position.distanceTo(destination) < 0.7, {
        signal, timeout: 10000, message: `Miner must walk to ${destination}`,
      });
    } finally {
      player.bot.clearControlStates();
    }
  };

  const marker = `mine-sync-${randomUUID()}`;
  for (const command of [
    'minecraft:forceload add 288 0 320 16',
    // The connected raised stone line is the real indexed extraction path.
    'minecraft:fill 300 -60 0 312 -60 0 minecraft:stone',
    ...[300, 303, 306, 309].map(x => `minecraft:setblock ${x} -60 8 minecraft:iron_ore`),
    ...[300, 303, 306, 309].map(x => `minecraft:setblock ${x} -60 4 minecraft:iron_bars`),
    `minecraft:experience set ${player.username} 0 levels`,
    `minecraft:experience set ${player.username} 0 points`,
    `minecraft:tellraw ${player.username} {"text":"${marker}"}`,
  ]) server.execute(command);
  await expect(player).toHaveReceivedMessage(marker);
  await player.teleport(306.5, -60, 6.5);
  await player.giveItem('iron_pickaxe', 1);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'iron_pickaxe'), 'hand');
  player.chat('/arcfarms admin worksite mine old_shafts reindex start');
  await expect(player).toHaveReceivedMessage('Index rebuild started for mine:old_shafts.');
  player.chat('/arcfarms admin worksite mine old_shafts reindex tick');
  await expect(player).toHaveReceivedMessage(/Index mine:old_shafts:.*blocks,.*targets/);
  await use({ x: 306, y: -60, z: 8 });
  const started = await reaches(s => s?.sequence === 1 && s.prospected === 1, 'Inspecting indexed ore must prospect the first sample');
  assert.deepEqual(started.incidentSchedule, ['CAVE_IN', 'GAS_LEAK', 'TRACK_DAMAGE']);

  const cave = await reaches(s => s.incident?.type === 'CAVE_IN', 'Cave-in must interrupt the expedition');
  const support = cave.objective.targets.find(target => target.status === 'AVAILABLE');
  await approach(support.position);
  await use(support.position);
  await reaches(s => Object.values(s.incident?.serviceLeases ?? {}).includes(playerId), 'Support click must issue a leased kit');
  await player.rejoin();
  await reaches(s => s.incident?.type === 'CAVE_IN' && Object.keys(s.incident.serviceLeases).length === 0, 'Disconnect must release the support kit');
  assert.equal((await mine()).incident.progress, 0);
  await use(support.position);
  await reaches(s => Object.values(s.incident?.serviceLeases ?? {}).includes(playerId), 'Released support kit must be obtainable again');
  await use(support.position);
  await reaches(s => s.incidentCursor >= 1, 'Second support click must consume the kit and fix the cave-in');

  const gas = await reaches(s => s.incident?.type === 'GAS_LEAK', 'Gas leak must follow the cave-in');
  await approach(gas.objective.targets[1].position);
  await use(gas.objective.targets[1].position);
  await waitForStable(async () => (await mine()).incident?.progress === 0, {
    signal, duration: 500, message: 'Wrong vent order must not advance the gas repair',
  });
  for (let index = 0; index < 2; index++) {
    await approach(gas.objective.targets[index].position);
    await use(gas.objective.targets[index].position);
    await reaches(s => index === 1 ? s.incidentCursor >= 2 : s.incident?.progress === 1, 'Vents must advance in the marked order');
  }

  await reaches(s => s.incident?.type === 'TRACK_DAMAGE', 'Third mandatory incident must damage the track');
  for (let fixed = 0; fixed < 2; fixed++) {
    const target = (await mine()).objective.targets.find(candidate => candidate.status === 'AVAILABLE');
    await approach(target.position);
    await use(target.position);
    await reaches(s => Object.values(s.incident?.serviceLeases ?? {}).includes(playerId), 'Track click must issue the real repair kit');
    await use(target.position);
    await reaches(s => fixed === 1 ? s.incidentCursor === 3 : s.incident?.progress === 1, 'Distinct track anchors must consume kits and advance repair');
  }
  await reaches(s => s.phase === 'MINING', 'Incident recovery must restore the original mining phase');
  for (let mined = 0; mined < 2; mined++) {
    const target = (await mine()).objective.targets.find(candidate => candidate.status === 'AVAILABLE');
    await player.teleport(target.position.x + 0.5, -60, target.position.z - 1.5);
    const blockPosition = position(target.position.x, target.position.y, target.position.z);
    await waitUntil(() => player.bot.blockAt(blockPosition)?.name === 'iron_ore', { signal });
    await player.bot.dig(player.bot.blockAt(blockPosition));
    await reaches(s => s.mined === mined + 1, 'Real pickaxe break must journal and count the ore');
  }
  const loading = await reaches(s => s.phase === 'LOADING', 'Mining quota must create ore crates');
  const crate = loading.objective.targets.find(target => target.status === 'AVAILABLE' && target.position.z === 0);
  assert.ok(crate, 'Loading must offer an indexed crate on the stone path');
  await approach(crate.position);
  await use(crate.position);
  await reaches(s => s.objective.targets.some(target => target.id === crate.id && target.status === 'LEASED'), 'Ore crate must be leased before transport');
  await walk(position(312.5, -59, 0.5));
  await reaches(s => s.phase === 'EXTRACTION' && s.loaded === 1, 'Walking the crate to the cart must consume it and start extraction');
  // The straight thirteen-block path has six two-block native route steps.
  for (let step = 1; step <= 6; step++) {
    await walk(position(312.5 - step * 2, -59, 0.5));
    await reaches(s => s.routeIndex >= step, 'Walking beside the cart must advance each physical checkpoint');
  }
  const done = await reaches(s => s.phase === 'COOLDOWN', 'Final extraction checkpoint must complete the expedition');
  assert.equal(done.routeIndex, 6);
  assert.equal(done.incidentCursor, 3);
  // Base 220 XP / four ingots; ten work units and three incidents produce 135%.
  await waitUntil(() => player.bot.experience.points === 297 && ironCount() === 5, {
    signal, message: 'Extraction must grant exactly 297 XP and five iron ingots',
  });
  const completed = await snapshot();
  assert.equal(completed.stats[playerId].completedShifts.MINE, 1);
  assert.equal(completed.claimedFarmRewardSequences[`mine_old_shafts:${playerId}`], 1);
  assert.deepEqual(completed.pendingFarmRewards, []);
  await walk(position(302.5, -59, 0.5));
  await walk(position(300.5, -59, 0.5));
  await waitForStable(() => player.bot.experience.points === 297 && ironCount() === 5, { signal, duration: 500 });
  await player.rejoin();
  await waitUntil(() => player.bot.experience.points === 297 && ironCount() === 5, { signal });
  assert.equal((await snapshot()).stats[playerId].completedShifts.MINE, 1);
});
