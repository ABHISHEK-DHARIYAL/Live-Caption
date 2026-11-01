#!/bin/bash
# Type-checks every Android source that does not need Compose, using real android.jar 35 + real sherpa-onnx classes,
# and small API stubs for Room/DataStore/WorkManager (stubs check Kotlin types only, not SQL or generated code).
export PATH=/home/claude/tools/kotlinc/bin:$PATH
SRC=/home/claude/LiveCaption/app/src/main/java/com/clearcap
OUT=/home/claude/work/app-out; rm -rf $OUT; mkdir -p $OUT
CP=/home/claude/tools/android-35.jar:/home/claude/aar/classes.jar:/home/claude/work/core-out/main:/home/claude/tools/kotlinc/lib/kotlinx-coroutines-core-jvm.jar
FILES="$(find $SRC/capture $SRC/asr $SRC/models $SRC/session $SRC/data $SRC/work -name '*.kt' ! -name Untar.kt) $SRC/App.kt $(ls /home/claude/tools/stubs/*.kt /home/claude/tools/stubs2/*.kt)"
kotlinc -nowarn -jvm-target 17 -cp $CP $FILES -d $OUT 2>&1 | grep -v "^warning"
echo "exit-marker: files=$(echo $FILES | wc -w) classes=$(find $OUT -name '*.class' | wc -l)"
