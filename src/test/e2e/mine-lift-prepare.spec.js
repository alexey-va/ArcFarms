import assert from 'node:assert/strict';
import { test, waitUntil } from '@drownek/plugwright';

if (process.env.MINE_LIFT_PREPARE === 'true') test('prepare the surveyed two-floor mine lift world', async ({ player, server, signal }) => {
  await player.makeOp();
  const marker = `mine-lift-prepared-${Date.now()}`;
  server.execute('minecraft:forceload add 688 -16 736 32');
  for (const y of [63, 79]) {
    server.execute(`minecraft:fill 698 ${y} 6 711 ${y} 11 minecraft:stone`);
    server.execute(`minecraft:fill 698 ${y + 1} 6 711 ${y + 2} 11 minecraft:air`);
  }
  server.execute('minecraft:fill 714 64 7 716 83 9 minecraft:air');
  server.execute('minecraft:setblock 700 64 0 minecraft:coal_ore');
  server.execute(`minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await waitUntil(() => player.messageBuffer.some(message => message.includes(marker)), { signal });

  await player.teleport(707.5, 64, 8.5);
  await waitUntil(() => player.bot.blockAt(player.bot.entity.position.clone().set(707, 63, 8))?.name === 'stone', { signal });
  assert.equal(player.bot.blockAt(player.bot.entity.position.clone().set(715, 70, 8))?.name, 'air');
});
