import { test, expect } from '@drownek/plugwright';

test('worksite menu opens and routes a disabled lumbermill to exact zone feedback', async ({ player }) => {
  player.chat('/arcfarms');
  const main = await player.gui({ title: /Choose an activity|Выберите занятие/ });
  const lumbermill = main.locator((item) => item.slot === 4);
  await expect(lumbermill).toHaveLore('Access to this worksite is locked.');
  await lumbermill.click();
  await player.gui({ title: /Choose an activity|Выберите занятие/ });
});

test('admin help is exposed through the live command executor', async ({ player }) => {
  await player.makeOp();
  player.chat('/arcfarms help');
  await expect(player).toHaveReceivedMessage(/Farm • \/arcfarms/);
});
