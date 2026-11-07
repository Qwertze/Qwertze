#!/bin/bash
# Usage: ./run.sh [versions...]   (Git Bash; default "1.21.4 1.21.11"; needs the addon's Gradle/Loom caches populated)
# Dumps MapColor from each version's Mojang-mapped game jar, checks that the versions match, and writes
# src/main/resources/web/map-palette.json.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"          # addon repo root
G=~/.gradle/caches/modules-2/files-2.1
VERSIONS="${*:-1.21.4 1.21.11}"
mkdir -p "$HERE/out"
DUMPS=()
for V in $VERSIONS; do
  MC=$(ls "$ROOT"/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/$V-loom*/*.jar | grep -v sources | head -1)
  [ -n "$MC" ] || { echo "no Loom-cached game jar for $V (build the addon for it once)"; exit 1; }
  # The same classpath as ../block-items (the game's colour lookup pulls in Util, which needs the Mojang libraries).
  case $V in 1.21.4) DFU=8.0.16; AUTH=6.0.57; LOG=1.5.10;; *) DFU=9.0.19; AUTH=7.0.61; LOG=1.6.11;; esac
  CP="$(cygpath -m "$MC")"
  for ad in $(ls -d $G/com.mojang/*/ $G/it.unimi.dsi/*/ $G/com.google.guava/*/ $G/com.google.code.gson/*/ $G/org.apache.logging.log4j/*/ \
      $G/org.slf4j/slf4j-api/ $G/org.apache.commons/*/ $G/commons-io/*/ $G/commons-codec/*/ $G/io.netty/*/ $G/org.joml/*/ $G/org.jspecify/*/ \
      $G/com.ibm.icu/*/ | grep -v datafixerupper); do
    ver=$(ls $ad | sort -V | tail -1)
    case $ad in */authlib/) ver=$AUTH;; */logging/) ver=$LOG;; esac
    jar=$(find $ad/$ver -name '*.jar' ! -name '*sources*' ! -name '*linux*' ! -name '*osx*' | head -1)
    [ -n "$jar" ] && CP="$CP;$(cygpath -m "$jar")"
  done
  jar=$(find $G/com.mojang/datafixerupper/$DFU -name '*.jar' ! -name '*sources*' | head -1)
  CP="$CP;$(cygpath -m "$jar")"
  mkdir -p "$HERE/out/$V"
  javac -cp "$CP" -d "$(cygpath -m "$HERE/out/$V")" "$(cygpath -m "$HERE/GenMapColors.java")"
  # run from out/ so anything the game writes lands there
  (cd "$HERE/out" && java -cp "$(cygpath -m "$HERE/out/$V");$CP" GenMapColors dump "$V" "$(cygpath -m "$HERE/out/$V.txt")")
  DUMPS+=("$(cygpath -m "$HERE/out/$V.txt")")
done
OUT="$ROOT/stash-manager-server/src/main/resources/web/map-palette.json"
V0=$(echo $VERSIONS | cut -d' ' -f1)
java -cp "$(cygpath -m "$HERE/out/$V0")" GenMapColors merge "$(cygpath -m "$OUT")" "${DUMPS[@]}"
