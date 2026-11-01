#!/bin/bash
# Compiles :core main + tests with kotlinc 1.9.24 and runs them with a minimal JUnit-compatible runner.
set -e
export PATH=/home/claude/tools/kotlinc/bin:$PATH
ROOT=/home/claude/LiveCaption/core
OUT=/home/claude/work/core-out; rm -rf $OUT; mkdir -p $OUT/main $OUT/test
kotlinc -nowarn -jvm-target 17 $(find $ROOT/src/main -name '*.kt') -d $OUT/main 2>&1 | grep -v "^warning" || true
kotlinc -nowarn -jvm-target 17 -cp $OUT/main:/home/claude/tools/shim-out $(find $ROOT/src/test -name '*.kt') -d $OUT/test 2>&1 | grep -v "^warning" || true
java -cp $OUT/test:$OUT/main:/home/claude/tools/shim-out:/home/claude/tools/kotlinc/lib/kotlin-stdlib.jar:/home/claude/tools/kotlinc/lib/kotlinx-coroutines-core-jvm.jar RunnerKt $OUT/test
