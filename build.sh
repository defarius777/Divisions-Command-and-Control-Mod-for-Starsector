#!/bin/bash
# DivisionAI build script — compiles src/ and packages mod/jars/DivisionAI.jar
# Toolchain: Temurin JDK 17 (matches game's Zulu 17 runtime)
set -e
cd "$(dirname "$0")"

JDK=toolchain/jdk17/bin
CP="lib/starfarer.api.jar;lib/log4j-1.2.9.jar;lib/LazyLib.jar;lib/lwjgl.jar;lib/lwjgl_util.jar;lib/LunaLib.jar"

rm -rf build/divisionai build/sources.txt
mkdir -p build mod/jars mod/data/config

find src -name '*.java' > build/sources.txt
"$JDK/javac.exe" -encoding UTF-8 -source 17 -target 17 -cp "$CP" -d build @build/sources.txt

"$JDK/jar.exe" cf build/DivisionAI_out.jar -C build divisionai
cp build/DivisionAI_out.jar build/DivisionAI.jar 2>/dev/null || true
cp build/DivisionAI_out.jar mod/jars/DivisionAI.jar 2>/dev/null || true
rm -f build/DivisionAI_out.jar 2>/dev/null || true
if [ -n "${STARSECTOR_DIR:-}" ] && [ -d "$STARSECTOR_DIR/mods/DivisionAI" ]; then
    cp -r mod/* "$STARSECTOR_DIR/mods/DivisionAI/" 2>/dev/null || true
    cp build/DivisionAI.jar "$STARSECTOR_DIR/mods/DivisionAI/jars/DivisionAI.jar" 2>/dev/null || true
fi
echo "BUILD OK -> mod/jars/DivisionAI.jar"
