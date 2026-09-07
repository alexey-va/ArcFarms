# Real Paper mechanics tests

Run `./gradlew plugwrightTest` with Java 25. The suite downloads Paper 1.21.11,
Node 22.14.0 and Plugwright 2.0.4, then starts a disposable server under
`build/plugwright` on 127.0.0.1:25565. Run local Paper suites sequentially.

The inventory presentation is selected explicitly because Mineflayer does not
drive native Paper dialogs. Tests assert the activity menu, locked mine entry
lore, the rejected click remaining in that menu, and the real admin help route.

The farm scenario performs preparation with a hoe, planting, mandatory weed
care, harvesting, carrying a crate on foot, and mounted food delivery. It
checks persisted phase transitions, consumed crops, one completion, the bakery
contract's 161 experience reward, and no repeated reward after rejoining. The fixture
uses the first common contract, one crop and a short route; growth is accelerated
with a world command. Reward settings and difficulty scaling are unchanged.
Its receiving point uses the normal saved-point override, so procedural care
placement cannot move the delivery destination away from the test map.
Mineflayer lacks horse physics, so the mounted part sends normal client vehicle
movement packets while Paper validates the rider and route.

The lumber scenario indexes actual logs, fells them, picks up a bundle, rejoins
to release its lease, carries it into the station, fixes a saw jam in switch
order, repairs two conveyor anchors, alternates saw controls, places a plank,
completes the rush order and dispatches the load. It checks both rejected
out-of-order/repeated controls and the durable reward claim after rejoining.
Two logs, one bundle, two cuts and one plank keep the map small; all three
mandatory incidents remain. Unchanged reward settings with the normal 133%
difficulty multiplier produce 239 XP and seven reward logs.

Networking and the mine are disabled in this fixture. Other incident variants,
the complete mine journey and cross-server state are not covered by this suite.
Existing unit and Redis integration tests retain their coverage. GitHub Actions
runs E2E separately and uploads runner/Paper logs on every outcome.
