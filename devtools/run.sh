#!/bin/zsh
# usage: devtools/run.sh <module> <tag> [args...]  — build + runClient for a version module (e.g. mc1_21_11), wait WAIT
# seconds (default 40) after the window appears, screenshot it to /tmp/<tag>.png, print errors, leave the game running
# (KEEP=1) or close it. JOPTS="-D..." passes JVM options to the game. Only this checkout's game is touched (matched by working directory).
M=$1; T=$2; shift 2
cd ~/Documents/HoneycrispEco/Graft
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
RUN=$PWD/$M/run
mkdir -p $RUN
mine() { for p in $(pgrep -f "java.*KnotClient"); do [ "$(lsof -a -p $p -d cwd -Fn 2>/dev/null | grep ^n | cut -c2-)" = "$RUN" ] && echo $p; done; }
for p in $(mine); do kill $p; done; while [ -n "$(mine)" ]; do sleep 1; done
ARGS=""; [ $# -gt 0 ] && ARGS="--args=$*"
(JAVA_TOOL_OPTIONS="-Dgraft.noFocus=true $JOPTS" timeout ${TIMEOUT:-300} ./gradlew --no-daemon :${M}:runClient $ARGS --console=plain > /tmp/$T.log 2>&1 &)
for i in $(seq 1 80); do sleep 3; [ -n "$(mine)" ] && break; grep -q "BUILD FAILED" /tmp/$T.log && break; done
sleep ${WAIT:-40}
P=$(mine | head -1); ID=$([ -n "$P" ] && ~/Documents/HoneycrispEco/GraftShaders/devtools/win $P); [ -n "$ID" ] && screencapture -x -o -l $ID /tmp/$T.png && echo "shot: /tmp/$T.png"
grep -iE "error|exception|Caused by|crash|Missing uniform|Couldn't" /tmp/$T.log | grep -v "^\s*at \|Realms\|SignedJWT\|certificates\|profile key\|HttpException" | cut -c1-260 | head -${NLINES:-15}
[ -z "$KEEP" ] && for p in $(mine); do kill $p; done
