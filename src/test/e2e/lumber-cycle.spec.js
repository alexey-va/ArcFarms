import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { test, expect, waitUntil, waitForStable } from '@drownek/plugwright';

async function snapshot() {
  return JSON.parse(await readFile(join(process.env.SERVER_DIR, 'plugins/ArcFarms/data/state.json'), 'utf8'));
}

async function lumber() {
  return (await snapshot()).lumbermills.communal_lumbermill;
}

async function reaches(predicate, message, signal) {
  try {
    await waitUntil(async () => predicate(await lumber()), { signal, timeout: 15000, message });
  } catch (error) {
    console.error('Persisted lumber at failure:', JSON.stringify(await lumber()));
    throw error;
  }
  return lumber();
}

async function command(server, player, commands) {
  const marker = `lumber-sync-${randomUUID()}`;
  for (const cmd of commands) server.execute(cmd);
  server.execute(`minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

async function walk(player, destination, signal) {
  try {
    await player.bot.lookAt(destination.offset(0, 1.5, 0), true);
    player.bot.setControlState('forward', true);
    await waitUntil(() => player.bot.entity.position.distanceTo(destination) < 1, {
      signal, timeout: 15000, message: `Player must carry the load to ${destination}`,
    });
  } finally {
    player.bot.clearControlStates();
  }
}

test('lumber shift resolves three incidents, restores a dropped lease and rewards dispatch once', async ({ player, server, signal }) => {
  await player.makeOp();
  const playerId = player.bot.player.uuid;
  const position = (x, y, z) => player.bot.entity.position.clone().set(x, y, z);
  const logCount = () => player.bot.inventory.items().filter(item => item.name === 'oak_log')
    .reduce((sum, item) => sum + item.count, 0);
  const use = async (x, y, z) => {
    const target = position(x, y, z);
    await waitUntil(() => player.bot.blockAt(target), { signal });
    await player.bot.activateBlock(player.bot.blockAt(target));
  };
  const fell = async target => {
    await waitUntil(() => player.bot.blockAt(target)?.name === 'oak_log', {
      signal, message: 'Client must receive the indexed log after teleport',
    });
    await player.bot.dig(player.bot.blockAt(target));
  };
  const targetEntity = target => Object.values(player.bot.entities).find(entity =>
    entity.name === 'interaction' && entity.position.distanceTo(
      position(target.position.x + 0.5, target.position.y, target.position.z + 0.5),
    ) < 0.3);

  await command(server, player, [
    'minecraft:forceload add 192 -16 240 16',
    ...[200, 204, 208, 212].map(x => `minecraft:setblock ${x} -60 2 minecraft:oak_log`),
    'minecraft:setblock 224 -60 0 minecraft:stonecutter',
    'minecraft:setblock 224 -60 2 minecraft:grindstone[face=floor]',
    'minecraft:setblock 224 -60 4 minecraft:smithing_table',
    'minecraft:setblock 224 -60 6 minecraft:loom',
    `minecraft:experience set ${player.username} 0 levels`,
    `minecraft:experience set ${player.username} 0 points`,
  ]);
  await player.teleport(198.5, -60, 2.5);
  await player.giveItem('iron_axe', 1);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'iron_axe'), 'hand');
  player.chat('/arcfarms admin worksite lumber communal_lumbermill reindex start');
  await expect(player).toHaveReceivedMessage('Index rebuild started for lumber:communal_lumbermill.');
  // One explicit tick covers this bounded map; the regular ticker may already have completed it.
  player.chat('/arcfarms admin worksite lumber communal_lumbermill reindex tick');
  await expect(player).toHaveReceivedMessage(/Index lumber:communal_lumbermill:.*blocks,.*targets/);
  await fell(position(200, -60, 2));
  const first = await reaches(s => s?.phase === 'FELLING' && s.felled === 1, 'First indexed log must start the shift', signal);
  assert.equal(first.sequence, 1);
  assert.deepEqual(first.incidentSchedule, ['SAW_JAM', 'CONVEYOR_BREAKDOWN', 'RUSH_ORDER']);
  const remaining = first.objective.targets.find(target => target.status === 'AVAILABLE');
  await player.teleport(remaining.position.x - 1.5, -60, remaining.position.z + 0.5);
  await fell(position(remaining.position.x, remaining.position.y, remaining.position.z));
  const skid = await reaches(s => s.phase === 'SKIDDING', 'Felling quota must create physical bundles', signal);
  assert.equal(skid.felled, 2);
  const bundle = skid.objective.targets.find(target => target.status === 'AVAILABLE');
  await player.teleport(bundle.position.x + 1.5, bundle.position.y, bundle.position.z + 0.5);
  await waitUntil(() => targetEntity(bundle), { signal });
  await player.bot.activateEntity(targetEntity(bundle));
  await reaches(s => s.objective.targets.some(target => target.id === bundle.id && target.status === 'LEASED'), 'Bundle must be leased before disconnect', signal);
  await player.rejoin();
  const recovered = await reaches(s => s.objective.targets.some(target => target.id === bundle.id && target.status === 'AVAILABLE'), 'Disconnect must release the bundle without credit', signal);
  assert.equal(recovered.skidded, 0);
  await waitUntil(() => targetEntity(bundle), { signal });
  await player.bot.activateEntity(targetEntity(bundle));
  await reaches(s => s.objective.targets.some(target => target.id === bundle.id && target.status === 'LEASED'), 'Recovered bundle must be pickable again', signal);
  await walk(player, position(225.5, -60, -1), signal);

  await reaches(s => s.incident?.type === 'SAW_JAM', 'First mandatory workshop incident must interrupt sawing', signal);
  await player.teleport(226, -60, 2);
  await use(224, -60, 2);
  await waitForStable(async () => (await lumber()).incident?.progress === 0, {
    signal, duration: 500, message: 'Out-of-order switch must not clear the jam',
  });
  for (const [index, z] of [0, 2, 4].entries()) {
    await use(224, -60, z);
    await reaches(s => index === 2 ? s.incidentCursor >= 1 : s.incident?.progress === index + 1, 'Jam switches must advance in order', signal);
  }

  await reaches(s => s.incident?.type === 'CONVEYOR_BREAKDOWN', 'Second mandatory incident must start', signal);
  for (let repaired = 0; repaired < 2; repaired++) {
    const target = (await lumber()).objective.targets.find(candidate => candidate.status === 'AVAILABLE');
    await player.teleport(target.position.x + 1.5, target.position.y, target.position.z + 0.5);
    await use(target.position.x, target.position.y - 1, target.position.z);
    await reaches(s => repaired === 1 ? s.incidentCursor >= 2 : s.incident?.progress === 1, 'Real repair-kit interaction must fix each distinct anchor', signal);
  }
  assert.equal(player.bot.inventory.items().filter(item => item.name === 'iron_nugget').length, 0);

  await reaches(s => s.phase === 'SAWING', 'Workshop repairs must resume the saved foreground phase', signal);
  await player.teleport(226, -60, 1);
  await use(224, -60, 0);
  await reaches(s => s.sawCuts === 1, 'Left saw control must make the first cut', signal);
  await player.bot.waitForTicks(8);
  await use(224, -60, 0);
  await waitForStable(async () => (await lumber()).sawCuts === 1, {
    signal, duration: 500, message: 'Repeating the left control must not count as another cut',
  });
  await use(224, -60, 2);
  const stacking = await reaches(s => s.phase === 'STACKING' && s.rushOrder != null, 'Alternating saw controls must reach the scheduled rush order', signal);
  assert.equal(stacking.incidentCursor, 3);
  assert.equal(stacking.rushOrder.bonusAvailable, true);
  const pallet = stacking.objective.targets.find(target => target.status === 'AVAILABLE');
  await player.teleport(226, -60, 4);
  await use(224, -60, 4);
  await walk(player, position(pallet.position.x + 1.5, pallet.position.y, pallet.position.z + 0.5), signal);
  await waitUntil(() => targetEntity(pallet), { signal });
  await player.bot.activateEntity(targetEntity(pallet));
  await reaches(s => s.phase === 'DISPATCH' && s.rushOrder.bonusEarned, 'Placed plank must complete the rush order before dispatch', signal);
  await player.teleport(226, -60, 6);
  const logsBeforeReward = logCount();
  await use(224, -60, 6);
  await reaches(s => s.phase === 'COOLDOWN' && s.dispatched, 'Dispatch must complete the shift', signal);
  // Base 180 XP / 6 logs, six work units and three incidents give the normal 133% multiplier.
  await waitUntil(() => player.bot.experience.points === 239 && logCount() === logsBeforeReward + 7, {
    signal, timeout: 15000, message: 'Dispatch must grant exactly 239 XP and seven bonus logs',
  });
  const completed = await snapshot();
  assert.equal(completed.stats[playerId].completedShifts.LUMBER, 1);
  assert.equal(completed.claimedFarmRewardSequences[`lumber_communal_lumbermill:${playerId}`], 1);
  assert.deepEqual(completed.pendingFarmRewards, []);
  await use(224, -60, 6);
  await waitForStable(() => player.bot.experience.points === 239 && logCount() === logsBeforeReward + 7, {
    signal, duration: 500, message: 'Repeating dispatch must not grant another reward',
  });
  await player.rejoin();
  await waitUntil(() => player.bot.experience.points === 239 && logCount() === logsBeforeReward + 7, { signal });
  assert.equal(logCount(), logsBeforeReward + 7);
  assert.equal((await snapshot()).stats[playerId].completedShifts.LUMBER, 1);
});
