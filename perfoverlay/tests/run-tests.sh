#!/bin/bash
# Host test of the app frame rate's dump parsing (LayerFrames.java, plain Java, no android.*).
#   perfoverlay/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/rabbitr1/src/android/prebuilts/jdk/jdk21/linux-x86/bin}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
"$JDK/javac" -encoding UTF-8 -d "$T" "$HERE/src/cat/kenny/r1/perfoverlay/LayerFrames.java" \
	"$HERE/tests/LayerFramesTest.java"
"$JDK/java" -cp "$T" cat.kenny.r1.perfoverlay.LayerFramesTest
