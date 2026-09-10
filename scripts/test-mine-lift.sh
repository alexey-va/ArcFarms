#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

MINE_LIFT_PREPARE=true TEST_FILES=mine-lift-prepare TEST_TIMEOUT=120000 ./gradlew plugwrightTest --console=plain
MINE_LIFT_TEST=true TEST_FILES=mine-lift-scenarios TEST_TIMEOUT=180000 ./gradlew plugwrightTest -x plugwrightClean --console=plain
