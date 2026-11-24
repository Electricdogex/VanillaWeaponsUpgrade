#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
mvn --version
mvn --batch-mode --no-transfer-progress clean package
test -f target/VanillaWeaponsUpgrade-1.1.0.jar
echo "Built: target/VanillaWeaponsUpgrade-1.1.0.jar"
