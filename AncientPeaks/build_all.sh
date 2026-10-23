#!/bin/sh
# Regenerates the data files (biomes, surface rules, gateways, structures/) and builds build/libs/ancientpeaks-<version>.jar
cd "$(dirname "$0")" || exit 1
[ -d /opt/jdk-17.0.20.1+1 ] && export JAVA_HOME=/opt/jdk-17.0.20.1+1
rm -rf src/main/resources/data src/main/resources/assets src/main/resources/trees
python3 gen_data.py && python3 gen_gateway.py && python3 gen_structures.py || exit 1
./gradlew --no-daemon build -q 2>&1 | grep -E "error|FAIL" -A3 | head -20
