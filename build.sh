#!/bin/bash
# DivisionAI build script — compiles src/ and packages mod/jars/DivisionAI.jar
# Toolchain: Temurin JDK 17 (matches game's Zulu 17 runtime)
set -e
cd "$(dirname "$0")"

JDK=toolchain/jdk17/bin
CP="lib/starfarer.api.jar;lib/log4j-1.2.9.jar;lib/LazyLib.jar;lib/lwjgl.jar;lib/lwjgl_util.jar"

rm -rf build
mkdir -p build mod/jars mod/data/config

find src -name '*.java' > build/sources.txt
"$JDK/javac.exe" -encoding UTF-8 -source 17 -target 17 -cp "$CP" -d build @build/sources.txt

"$JDK/jar.exe" cf mod/jars/DivisionAI.jar -C build divisionai
if [ -d "${STARSECTOR_DIR:-../Starsector}/mods/DivisionAI" ]; then
    cp -r mod/* "${STARSECTOR_DIR:-../Starsector}/mods/DivisionAI/"
fi
echo "BUILD OK -> mod/jars/DivisionAI.jar"
