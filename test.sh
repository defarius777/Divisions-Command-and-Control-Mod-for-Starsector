#!/bin/bash
# DivisionAI Headless Tactical C2 & Campaign Test Runner
set -e
cd "$(dirname "$0")"

./build.sh

JDK=toolchain/jdk17/bin
CP="mod/jars/DivisionAI.jar;lib/starfarer.api.jar;lib/log4j-1.2.9.jar;lib/LazyLib.jar;lib/lwjgl.jar;lib/lwjgl_util.jar;lib/LunaLib.jar;${STARSECTOR_DIR:-../Starsector}/starsector-core/starfarer_obf.jar;${STARSECTOR_DIR:-../Starsector}/starsector-core/fs.common_obf.jar"

mkdir -p build/test-classes
"$JDK/javac.exe" -encoding UTF-8 -cp "$CP" -d build/test-classes test/divisionai/sim/*.java
"$JDK/java.exe" -cp "$CP;build/test-classes" divisionai.sim.DivisionAITestSuite
