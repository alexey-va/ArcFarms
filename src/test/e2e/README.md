# Real Paper mechanics tests

Run `./gradlew plugwrightTest` with Java 25. The suite downloads Paper 1.21.11,
Node 22.14.0 and Plugwright 2.0.4, then starts a disposable server under
`build/plugwright` on 127.0.0.1:25565. Run local Paper suites sequentially.

The inventory presentation is selected explicitly because Mineflayer does not
drive native Paper dialogs. Tests assert the activity menu, locked lumbermill entry
lore, the rejected click remaining in that menu, and the real admin help route.

The farm scenario performs preparation with a hoe, planting, mandatory weed
care, harvesting, carrying a crate on foot, and mounted food delivery. It
checks persisted phase transitions, consumed crops, one completion, the default
100 experience reward, and no repeated reward after rejoining. The fixture
uses one crop and a short route; growth is accelerated with a world command.
Mineflayer lacks horse physics, so the mounted part sends normal client vehicle
movement packets while Paper validates the rider and route.

Networking, the lumbermill, and the mine are disabled in this fixture. Their
complete journeys and cross-server state are not covered by this suite.
Existing unit and Redis integration tests retain their coverage. GitHub Actions
runs E2E separately and uploads runner/Paper logs on every outcome.
