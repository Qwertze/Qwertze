#!/bin/bash
# Usage: ./run.sh 1.21.4|1.21.11   (Git Bash; needs the addon's Gradle/Loom caches populated)
set -e
V=$1
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"          # addon repo root
G=~/.gradle/caches/modules-2/files-2.1
MC=$(ls "$ROOT"/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/$V-loom*/*.jar | grep -v sources | head -1)
# Mojang library versions each game version ships with (taken from Loom's resolved classpaths)
case $V in 1.21.4) DFU=8.0.16; AUTH=6.0.57; LOG=1.5.10;; *) DFU=9.0.19; AUTH=7.0.61; LOG=1.6.11;; esac
mkdir -p "$HERE/out"
{
  echo -n '"'"$(cygpath -m "$MC")"
  # highest version of each library artifact (one jar per group:artifact)
  for ad in $(ls -d $G/com.mojang/*/ $G/it.unimi.dsi/*/ $G/com.google.guava/*/ $G/com.google.code.gson/*/ $G/org.apache.logging.log4j/*/ \
      $G/org.slf4j/slf4j-api/ $G/org.apache.commons/*/ $G/commons-io/*/ $G/commons-codec/*/ $G/io.netty/*/ $G/org.joml/*/ $G/org.jspecify/*/ \
      $G/com.ibm.icu/*/ $G/net.java.dev.jna/*/ $G/org.lwjgl/*/ | grep -v datafixerupper); do
    ver=$(ls $ad | sort -V | tail -1)
    case $ad in */authlib/) ver=$AUTH;; */logging/) ver=$LOG;; esac
    jar=$(find $ad/$ver -name '*.jar' ! -name '*sources*' ! -name '*linux*' ! -name '*osx*' | head -1)
    [ -n "$jar" ] && echo -n ";$(cygpath -m "$jar")"
  done
  jar=$(find $G/com.mojang/datafixerupper/$DFU -name '*.jar' ! -name '*sources*' | head -1)
  echo -n ";$(cygpath -m "$jar")"'"'
} > "$HERE/out/cp.txt"
CPARG="-cp $(cat "$HERE/out/cp.txt")"
echo "$CPARG" > "$HERE/out/javac.args"; echo " \"$(cygpath -m "$HERE/GenBlockItems.java")\" -d \"$(cygpath -m "$HERE/out")\"" >> "$HERE/out/javac.args"
javac @"$HERE/out/javac.args"
OUT="$ROOT/stash-manager-server/src/main/resources/block-items/$V.json"
mkdir -p "$(dirname "$OUT")"
echo "-cp \"$(cygpath -m "$HERE/out");$(cat "$HERE/out/cp.txt" | tr -d '"')\" GenBlockItems $V \"$(cygpath -m "$HERE/rules.json")\" \"$(cygpath -m "$OUT")\"" > "$HERE/out/java.args"
# run from out/ so the game's logs/ folder lands there
cd "$HERE/out" && java @"$HERE/out/java.args"
