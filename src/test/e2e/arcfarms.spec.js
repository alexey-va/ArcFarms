import { test, expect } from '@drownek/plugwright';

test('worksite menu opens and routes a disabled farm to exact zone feedback', async ({ player }) => {
  player.chat('/arcfarms');
  const main = await player.gui({ title: /Choose an activity|Выберите занятие/ });
  const farm = main.locator((item) => item.slot === 2);
  await expect(farm).toHaveLore('Access to this worksite is locked.');
  await farm.click();
  await player.gui({ title: /Choose an activity|Выберите занятие/ });
});

test('admin help is exposed through the live command executor', async ({ player }) => {
  await player.makeOp();
  player.chat('/arcfarms help');
  await expect(player).toHaveReceivedMessage(/Farm • \/arcfarms/);
});
