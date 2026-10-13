#!/bin/bash
# Startup time and idle memory of a built launcher (see docs/PERFORMANCE.md).
#
#   ./gradlew :launcher-app:installDist
#   tools/perf/measure.sh launcher-app/build/install/shardlight-client [runs]
#
# Runs the app RUNS times (default 5) under a virtual display with the real JVM flags, each time with a fresh data
# folder, and prints the median startup time (JVM start until Home is drawn), heap and resident memory after 8 s idle.
# The app needs -Dshardlight.measure support (StartupMeasure.java). Linux only (needs xvfb-run and /proc).
set -euo pipefail
dir="$1"; runs="${2:-5}"
out=$(mktemp -d)
for i in $(seq 1 "$runs"); do
  xvfb-run -a -s "-screen 0 1400x900x24" java -Xss2m ${JAVA_GC:--XX:+UseG1GC} -Dprism.lcdtext=false --enable-native-access=ALL-UNNAMED \
    -Dshardlight.measure="$out/run$i.json" -cp "$dir/lib/*" dev.shardlight.client.app.Main --data-dir "$out/data$i" >"$out/log$i.txt" 2>&1 || true
  [ -s "$out/run$i.json" ] || { echo "run $i produced no result; log:"; tail -20 "$out/log$i.txt"; exit 1; }
done
python3 -I - "$out" "$runs" <<'PY'
import json, statistics, sys
out, runs = sys.argv[1], int(sys.argv[2])
rows = [json.load(open(f"{out}/run{i}.json")) for i in range(1, runs + 1)]
for k, label in (("startupMs", "startup to Home (ms)"), ("heapMb", "heap after idle (MB)"), ("rssMb", "resident memory after idle (MB)")):
    v = [r[k] for r in rows]
    print(f"{label:34s} median {statistics.median(v):8.0f}   runs {v}")
PY
