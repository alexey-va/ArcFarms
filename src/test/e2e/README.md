# Real Paper menu tests

Run `./gradlew plugwrightTest` with Java 25. The suite downloads Paper 1.21.11,
Node 22.14.0 and Plugwright 2.0.4, then starts a disposable server under
`build/plugwright` on 127.0.0.1:25565. Run local Paper suites sequentially.

The inventory presentation is selected explicitly because Mineflayer does not
drive native Paper dialogs. Tests assert the activity menu, locked farm entry
lore, the rejected click remaining in that menu, and the real admin help route.
The fixture disables networking and the selected worksite; it does not test
farm completion, rewards or cross-server state. Existing unit and Redis
integration tests retain their coverage. GitHub Actions runs E2E separately
and uploads runner/Paper logs on every outcome.
