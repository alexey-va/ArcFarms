# Real Paper mechanics tests

Run `./gradlew plugwrightTest` with Java 25. The suite downloads Paper 1.21.11,
Node 22.14.0 and Plugwright 2.0.4, then starts a disposable server under
`build/plugwright` on 127.0.0.1:25565. Run local Paper suites sequentially.

The inventory presentation is selected explicitly because Mineflayer does not
drive native Paper dialogs. Tests assert the activity menu and the real admin
help route.

The farm scenario performs preparation with a hoe, planting, mandatory weed
care, harvesting, carrying a crate on foot, and mounted food delivery. It
checks persisted phase transitions, consumed crops, one completion, the bakery
contract's exact persisted experience entitlement, and no repeated reward after rejoining. The fixture
uses the first common contract, one crop and a short route; growth is accelerated
with a world command. Reward settings and difficulty scaling are unchanged.
Its receiving point uses the normal saved-point override, so procedural care
placement cannot move the delivery destination away from the test map.
Mineflayer lacks horse physics, so the mounted part sends normal client vehicle
movement packets while Paper validates the rider and route.

The legacy lumber scenario and its reward expectations are retired. The new
permission-gated Cedar Sawmill beta uses focused domain, lifecycle, permission
and packet-scene checks. Its complete native-player journey remains a separate
acceptance step after activation; a source preview is not server E2E evidence.

Mine and mine-lift E2E scenarios are removed while those mechanics are unfinished.
The disposable E2E configuration has no mine zones. Production mine code and
unit/integration coverage are unchanged.

Networking is disabled. Other incident variants and cross-server state are not
covered by this suite. These disposable quotas do not change production rates:
vault and tokens remain unchanged; the tests assert XP and item rewards in
their own units without assigning a coin value to either.
Existing unit and Redis integration tests retain their coverage. GitHub Actions
runs E2E separately and uploads runner/Paper logs on every outcome.
