import { test, expect } from '@drownek/plugwright';

test('worksite menu opens through the live command executor', async ({ player }) => {
  player.chat('/arcfarms');
  await player.gui({ title: /Choose an activity|Выберите занятие/ });
});

test('admin help is exposed through the live command executor', async ({ player }) => {
  await player.makeOp();
  player.chat('/arcfarms help');
  await expect(player).toHaveReceivedMessage(/Farm • \/arcfarms/);
});
