#!/bin/bash
# DivisionAI Headless Tactical C2 & Campaign Test Runner
set -e
cd "$(dirname "$0")"

./build.sh

JDK=toolchain/jdk17/bin
STARSECTOR_CORE="${STARSECTOR_CORE:-}"
if [ -z "$STARSECTOR_CORE" ] && [ -n "${STARSECTOR_DIR:-}" ]; then
    STARSECTOR_CORE="$STARSECTOR_DIR/starsector-core"
fi
if [ -z "$STARSECTOR_CORE" ] && [ -f "lib/starsector_core_path.txt" ]; then
    STARSECTOR_CORE="$(tr -d '
\n' < lib/starsector_core_path.txt)"
fi
EXTRA_CP=""
LIB_PATH_ARG=""
if [ -n "$STARSECTOR_CORE" ] && [ -d "$STARSECTOR_CORE" ]; then
    EXTRA_CP=";$STARSECTOR_CORE;$STARSECTOR_CORE/*"
    LIB_PATH_ARG="-Djava.library.path=$STARSECTOR_CORE/native/windows"
fi
CP="build;build/DivisionAI.jar;mod/jars/DivisionAI.jar;lib/starfarer.api.jar;lib/log4j-1.2.9.jar;lib/LazyLib.jar;lib/lwjgl.jar;lib/lwjgl_util.jar;lib/LunaLib.jar${EXTRA_CP}"

mkdir -p build/test-classes
"$JDK/javac.exe" -encoding UTF-8 --add-exports java.base/jdk.internal.reflect=ALL-UNNAMED -cp "$CP" -d build/test-classes test/divisionai/sim/*.java
"$JDK/java.exe" -Xverify:none --add-exports java.base/jdk.internal.reflect=ALL-UNNAMED ${LIB_PATH_ARG:+"$LIB_PATH_ARG"} -cp "$CP;build/test-classes" divisionai.sim.DivisionAITestSuite
