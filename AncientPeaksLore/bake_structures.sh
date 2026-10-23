#!/bin/sh
# Copies the structure folder into the mod so the shipped jar carries them, then builds.
#   ./bake_structures.sh [folder]     (default: ~/Desktop/Schematics/ancient_peaks)
cd "$(dirname "$0")" || exit 1
SRC="${1:-$HOME/Desktop/Schematics/ancient_peaks}"
DST=src/main/resources/data/ancientpeaks_lore/structures
rm -rf "$DST" && mkdir -p "$DST" && (cd "$SRC" && find . -name '*.nbt' | while read f; do mkdir -p "$OLDPWD/$DST/$(dirname "$f")"; cp "$f" "$OLDPWD/$DST/$f"; done)
echo "baked $(find "$DST" -name '*.nbt' | wc -l) structures"
./gradlew --no-daemon build -q
