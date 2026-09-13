#!/usr/bin/env bash
# Soak one http-servers backend: build the harness, start SoakServer detached under a continuous
# flight recording and native-memory tracking, sample its RSS and native memory, drive it with
# SoakLoad for the duration, dump the recording, stop it, post-process the recording, and write
# summary.md with a verdict.
#
#   soak.sh <backend-factory> <duration-seconds> [connections] [heapMb]
#
# Results land in http-servers-soak/build/soak/runs/<backend>-<timestamp>/:
#   server.csv       the server's PORT= line, then one JVM sample per 30 s (see SoakServer): heap, GC,
#                    threads, descriptors, the LEAK: and SEVERE counters, and the whole-run
#                    jdk.VirtualThreadSubmitFailed and jdk.VirtualThreadPinned counts (JfrCounters)
#   server.log       the server's stderr (JUL console output, LEAK: reports, stack traces)
#   rss.csv          epoch,rss_kb of the server process every 30 s, and once more just before it is stopped
#   nmt.log          jcmd VM.native_memory output under timestamps: the baseline at start, then a
#                    summary.diff at the end of the warm-up, every SOAK_NMT_INTERVAL_SECONDS, and once
#                    when the load has finished
#   nmt.csv          one row per summary.diff: the totals, the committed size and diff of each category in
#                    NMT_CATEGORIES below, and last the gated total: committed less the transient
#                    categories in NMT_TRANSIENT (Arena Chunk, and Tracing — the recorder's own buffers)
#   jfr-repo/        the recording's disk repository (the ring's chunks) while the server runs; the JVM
#                    removes it on a clean exit, and it is under build/ either way
#   soak-final.jfr   the recording dumped with path-to-gc-roots=true before the server is stopped
#   soak-exit.jfr    the same recording as the JVM dumps it on exit (dumponexit); the fallback
#   jfr-summary.txt  'jfr summary' of the recording the report was built from
#   jfr-report.md    JfrReport's sections (a)-(g), also appended to summary.md; jfr-metrics.properties
#                    is the same pass as key=value pairs for the verdict
#   load.log         the load generator's periodic summaries and final report
#   summary.md       the verdict and the numbers behind it
#
# Verdict knobs, read from the environment (defaults in parentheses):
#   SOAK_RSS_SLOPE_KIB_PER_HOUR (2048)  RSS growth allowed after warm-up, KiB per hour ...
#   SOAK_RSS_NOISE_FLOOR_KIB    (8192)  ... or this much over the whole fitted window, whichever is larger;
#                                       the least-squares slope of the gated native-memory total (nmt.csv's
#                                       last column) over every summary.diff from the end of the warm-up to
#                                       the end of the load is held to the same two numbers
#   SOAK_THREAD_MARGIN          (16)    live threads allowed above the post-warm-up baseline
#   SOAK_FD_MARGIN              (64)    open descriptors allowed above the post-warm-up baseline
#   SOAK_SEVERE_MARGIN          (0)     SEVERE records allowed beyond the ones the load provokes
#   SOAK_WARMUP_SECONDS         (max(60, duration / 10))  samples this close to the first are warm-up
# Recording knobs:
#   SOAK_JFR_MAXSIZE            (512m)  the on-disk ring the recording is kept to; each dump is at most this
#   SOAK_JFR_DUMP_TIMEOUT_SECONDS (600) how long the path-to-gc-roots dump may take before it is abandoned
#   SOAK_NMT_INTERVAL_SECONDS   (1800)  how often VM.native_memory summary.diff is taken during the load
set -euo pipefail

usage() {
  echo "usage: $0 <backend-factory> <duration-seconds> [connections=32] [heapMb=256]" >&2
  echo "  backend-factory: JDKHttpServerBuilderFactory | JettyServerBuilderFactory | FusionAuthBuilderFactory | HelidonBuilderFactory | NettyBuilderFactory" >&2
  echo "  environment: SOAK_RSS_SLOPE_KIB_PER_HOUR=2048 SOAK_RSS_NOISE_FLOOR_KIB=8192 SOAK_THREAD_MARGIN=16 SOAK_FD_MARGIN=64" >&2
  echo "               SOAK_SEVERE_MARGIN=0 SOAK_WARMUP_SECONDS=max(60, duration/10)" >&2
  echo "               SOAK_JFR_MAXSIZE=512m SOAK_JFR_DUMP_TIMEOUT_SECONDS=600 SOAK_NMT_INTERVAL_SECONDS=1800" >&2
  exit 2
}

[ $# -ge 2 ] || usage
FACTORY=$1
DURATION=$2
CONNECTIONS=${3:-32}
HEAP_MB=${4:-256}
case "$DURATION$CONNECTIONS$HEAP_MB" in *[!0-9]*) usage ;; esac

# Verdict thresholds; each can be overridden from the environment.
RSS_SLOPE_LIMIT_KIB_PER_HOUR=${SOAK_RSS_SLOPE_KIB_PER_HOUR:-2048}   # 2 MiB per hour ...
RSS_NOISE_FLOOR_KIB=${SOAK_RSS_NOISE_FLOOR_KIB:-8192}               # ... or 8 MiB over the whole window, whichever is larger
THREAD_MARGIN=${SOAK_THREAD_MARGIN:-16}
FD_MARGIN=${SOAK_FD_MARGIN:-64}
SEVERE_MARGIN=${SOAK_SEVERE_MARGIN:-0}
WARMUP_SECONDS=$((DURATION / 10))
[ "$WARMUP_SECONDS" -ge 60 ] || WARMUP_SECONDS=60
WARMUP_SECONDS=${SOAK_WARMUP_SECONDS:-$WARMUP_SECONDS}
JFR_MAXSIZE=${SOAK_JFR_MAXSIZE:-512m}
JFR_DUMP_TIMEOUT_SECONDS=${SOAK_JFR_DUMP_TIMEOUT_SECONDS:-600}
NMT_INTERVAL_SECONDS=${SOAK_NMT_INTERVAL_SECONDS:-1800}
case "$SEVERE_MARGIN$WARMUP_SECONDS$JFR_DUMP_TIMEOUT_SECONDS$NMT_INTERVAL_SECONDS" in *[!0-9]*) usage ;; esac
case "$JFR_MAXSIZE" in ''|*[!0-9kKmMgG]*) usage ;; esac
[ "$NMT_INTERVAL_SECONDS" -gt 0 ] || usage
SAMPLE_SECONDS=30
JCMD_BOUND_SECONDS=60
MODULE=software.sava.http_servers.soak
SERVER_MAIN=$MODULE/software.sava.http_servers.soak.SoakServer
LOAD_MAIN=$MODULE/software.sava.http_servers.soak.SoakLoad
REPORT_MAIN=$MODULE/software.sava.http_servers.soak.JfrReport

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$ROOT"
JFC=$SCRIPT_DIR/soak.jfc
[ -s "$JFC" ] || { echo "missing recording settings: $JFC" >&2; exit 1; }

SERVER_PID=""
SAMPLER_PID=""
LOAD_PID=""

# stop_pid <pid> <signal> <label> [bound-seconds=30]: signals the process and waits up to the bound
# for it to exit, then kills it.
stop_pid() {
  local pid=$1 signal=$2 label=$3 bound=${4:-30} waited=0
  [ -n "$pid" ] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  kill "-$signal" "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt $((bound * 2)) ]; do
    sleep 0.5
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "$label (pid $pid) ignored SIG$signal for $bound s, killing" >&2
    kill -KILL "$pid" 2>/dev/null || true
  fi
}

# run_bounded <bound-seconds> <label> <command...>: runs the command, killing it at the bound.
# Returns the command's status, or 124 when it was killed.
run_bounded() {
  local bound=$1 label=$2 waited=0 pid
  shift 2
  "$@" &
  pid=$!
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$bound" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "$label did not finish within $bound s, abandoning it" >&2
    kill -TERM "$pid" 2>/dev/null || true
    sleep 1
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    return 124
  fi
  wait "$pid"
}

cleanup() {
  stop_pid "$LOAD_PID" TERM "load generator"
  stop_pid "$SAMPLER_PID" TERM "rss sampler"
  # SIGTERM runs the server's shutdown hook and the recording's dumponexit, so an interrupted run
  # still leaves soak-exit.jfr behind.
  stop_pid "$SERVER_PID" TERM "server" 120
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "building http-servers-soak"
./gradlew :http-servers-soak:build :http-servers-soak:soakModulePath -q
MODULE_PATH=$(cat http-servers-soak/build/soak/module-path.txt)
JAVA=java
if [ -s http-servers-soak/build/soak/java.txt ]; then
  JAVA=$(cat http-servers-soak/build/soak/java.txt)
fi
# jcmd and jfr sit beside the launcher the module was compiled for, never a PATH JDK of another version.
JAVA_BIN=$(cd "$(dirname "$(command -v "$JAVA")")" && pwd)
JCMD=$JAVA_BIN/jcmd
JFR=$JAVA_BIN/jfr
for tool in "$JCMD" "$JFR"; do
  [ -x "$tool" ] || { echo "missing JDK tool beside $JAVA: $tool" >&2; exit 1; }
done

BACKEND=${FACTORY%BuilderFactory}
BACKEND=${BACKEND%HttpServer}
BACKEND=${BACKEND%Server}
BACKEND=$(printf '%s' "$BACKEND" | tr '[:upper:]' '[:lower:]')
STAMP=$(date +%Y%m%d-%H%M%S)
RUN_DIR=$ROOT/http-servers-soak/build/soak/runs/$BACKEND-$STAMP
mkdir -p "$RUN_DIR"
echo "run directory: $RUN_DIR"

# The recording: the module's soak.jfc (default.jfc plus the tuned events), a disk ring of
# JFR_MAXSIZE whose chunk repository is under the run directory rather than $TMPDIR (a killed
# JVM leaves it behind, and under build/ it is 'clean'-able), dumped by the JVM on exit and by
# jcmd before the stop. NMT summary is what VM.native_memory and the jdk.NativeMemoryUsage*
# events read.
HEAP_FLAG="-Xmx${HEAP_MB}m"
JFR_REPO=$RUN_DIR/jfr-repo
JFR_FLAG="-XX:StartFlightRecording:name=soak,settings=$JFC,disk=true,maxsize=$JFR_MAXSIZE,dumponexit=true,filename=$RUN_DIR/soak-exit.jfr"
"$JAVA" "$HEAP_FLAG" \
  -Dio.netty.leakDetection.level=paranoid \
  -Dio.netty.leakDetection.targetRecords=8 \
  -XX:+HeapDumpOnOutOfMemoryError "-XX:HeapDumpPath=$RUN_DIR" \
  -XX:NativeMemoryTracking=summary \
  "-XX:FlightRecorderOptions:repository=$JFR_REPO" \
  "$JFR_FLAG" \
  -p "$MODULE_PATH" -m "$SERVER_MAIN" "$FACTORY" 0 "$SAMPLE_SECONDS" \
  > "$RUN_DIR/server.csv" 2> "$RUN_DIR/server.log" &
SERVER_PID=$!
echo "$SERVER_PID" > "$RUN_DIR/server.pid"

# fail_early <reason>: the run ended before the load could start. An unattended wrapper collects
# summary.md, so the reason is written there as a FAIL with the tail of server.log, not only to
# stderr; exit 1 runs the cleanup trap.
fail_early() {
  local reason=$1
  echo "$reason; see $RUN_DIR/server.log" >&2
  tail -n 40 "$RUN_DIR/server.log" >&2 2>/dev/null || true
  {
    echo "# Soak summary: $FACTORY"
    echo
    echo "- backend: $FACTORY ($BACKEND)"
    echo "- duration: ${DURATION} s, connections: $CONNECTIONS, heap flag: $HEAP_FLAG"
    echo "- run directory: $RUN_DIR"
    echo "- the load never started"
    echo
    echo "## server.log (last 40 lines)"
    echo
    echo '```'
    tail -n 40 "$RUN_DIR/server.log" 2>/dev/null || true
    echo '```'
    echo
    echo "## Verdict"
    echo
    echo "FAIL: $reason"
  } > "$RUN_DIR/summary.md"
  echo "summary: $RUN_DIR/summary.md"
  exit 1
}

PORT=""
waited=0
while [ "$waited" -lt 240 ]; do
  line=$(grep -m1 '^PORT=' "$RUN_DIR/server.csv" 2>/dev/null || true)
  if [ -n "$line" ]; then
    PORT=${line#PORT=}
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    fail_early "server exited before printing PORT="
  fi
  sleep 0.5
  waited=$((waited + 1))
done
if [ -z "$PORT" ]; then
  fail_early "server did not print PORT= within 120 s"
fi
echo "server pid $SERVER_PID ($FACTORY) on port $PORT, heap $HEAP_FLAG, recording ring $JFR_MAXSIZE, NMT summary"

sample_rss() {
  local rss
  rss=$(ps -o rss= -p "$SERVER_PID" 2>/dev/null | tr -d ' ' || true)
  if [ -n "$rss" ]; then
    echo "$(date +%s),$rss" >> "$RUN_DIR/rss.csv"
  fi
}

# ---- native memory ----
NMT_LOG=$RUN_DIR/nmt.log
NMT_CSV=$RUN_DIR/nmt.csv
# The categories broken out into nmt.csv: the ones that hold a backend's own growth (heap, class
# metadata, thread stacks, JIT code, GC structures, direct buffers under Other) and the two that
# move by megabytes between samples with nothing leaked — Arena Chunk (the JVM's transient arenas,
# freed and refilled by compilation and class loading) and Tracing (the recorder's own buffers,
# which grow with JFR's activity, not the server's). Those two are NMT_TRANSIENT: they are
# subtracted from the total committed to give the last column, the series the verdict fits.
NMT_CATEGORIES='Java Heap|Class|Thread|Code|GC|Metaspace|Internal|Other|Arena Chunk|Tracing'
NMT_TRANSIENT='Arena Chunk|Tracing'
NMT_HEADER="epoch_s,label,elapsed_s,total_reserved_kb,total_committed_kb,total_committed_diff_kb"
IFS='|' read -r -a nmt_categories <<< "$NMT_CATEGORIES"
for category in "${nmt_categories[@]}"; do
  column=$(printf '%s' "$category" | tr '[:upper:] ' '[:lower:]_')
  NMT_HEADER="$NMT_HEADER,${column}_committed_kb,${column}_diff_kb"
done
NMT_HEADER="$NMT_HEADER,gated_committed_kb"
echo "$NMT_HEADER" > "$NMT_CSV"

# nmt_row <epoch> <label> <elapsed>: reads one 'VM.native_memory summary.diff' on stdin and prints its
# nmt.csv row — 'Total: reserved=NKB [+/-MKB], committed=NKB [+/-MKB]' and, for each category of
# interest, 'committed=NKB [+/-MKB]' from its '- <name> (reserved=..., committed=...)' line, then the
# gated total. A diff is printed by NMT only when it is non-zero, and a category under 1 KB is
# omitted, so both default to 0.
nmt_row() {
  awk -v epoch="$1" -v label="$2" -v elapsed="$3" -v categories="$NMT_CATEGORIES" -v transient="$NMT_TRANSIENT" '
    function committed(line) {
      C = 0; D = 0
      if (match(line, /committed=[0-9]+KB( [+-][0-9]+KB)?/)) {
        field = substr(line, RSTART + 10, RLENGTH - 10)
        C = field + 0
        if (match(field, / [+-][0-9]+KB/)) D = substr(field, RSTART + 1, RLENGTH - 3) + 0
      }
    }
    /^Total:/ {
      if (match($0, /reserved=[0-9]+KB/)) total_r = substr($0, RSTART + 9, RLENGTH - 11) + 0
      committed($0); total_c = C; total_d = D
    }
    /^- / {
      name = $0; sub(/^-[ ]+/, "", name); sub(/ \(reserved=.*/, "", name)
      committed($0); cat_c[name] = C; cat_d[name] = D
    }
    END {
      printf "%s,%s,%s,%d,%d,%d", epoch, label, elapsed, total_r + 0, total_c + 0, total_d + 0
      n = split(categories, cats, "|")
      for (i = 1; i <= n; i++) printf ",%d,%d", cat_c[cats[i]] + 0, cat_d[cats[i]] + 0
      gated = total_c + 0
      t = split(transient, skip, "|")
      for (i = 1; i <= t; i++) gated -= cat_c[skip[i]] + 0
      printf ",%d\n", gated
    }'
}

# nmt_diff <label>: appends a timestamped 'VM.native_memory summary.diff' to nmt.log and its row to
# nmt.csv. A failed jcmd is logged and leaves no row, so the verdict sees it as a missing sample.
nmt_diff() {
  local label=$1 now output status=0
  now=$(date +%s)
  output=$(run_bounded "$JCMD_BOUND_SECONDS" "jcmd VM.native_memory summary.diff ($label)" "$JCMD" "$SERVER_PID" VM.native_memory summary.diff 2>&1) || status=$?
  {
    echo "=== $(date -r "$now" '+%Y-%m-%d %H:%M:%S') $label (elapsed $((now - NMT_START)) s, jcmd exit $status) ==="
    echo "$output"
    echo
  } >> "$NMT_LOG"
  if [ "$status" -eq 0 ] && printf '%s\n' "$output" | grep -q '^Total:'; then
    printf '%s\n' "$output" | nmt_row "$now" "$label" "$((now - NMT_START))" >> "$NMT_CSV"
  else
    echo "VM.native_memory summary.diff ($label) failed (exit $status); see $NMT_LOG" >&2
  fi
}

NMT_START=$(date +%s)
NMT_BASELINE_STATUS=0
nmt_baseline=$(run_bounded "$JCMD_BOUND_SECONDS" "jcmd VM.native_memory baseline" "$JCMD" "$SERVER_PID" VM.native_memory baseline 2>&1) || NMT_BASELINE_STATUS=$?
{
  echo "=== $(date -r "$NMT_START" '+%Y-%m-%d %H:%M:%S') baseline (jcmd exit $NMT_BASELINE_STATUS) ==="
  echo "$nmt_baseline"
  echo
} > "$NMT_LOG"
[ "$NMT_BASELINE_STATUS" -eq 0 ] || echo "VM.native_memory baseline failed (exit $NMT_BASELINE_STATUS); see $NMT_LOG" >&2
# The baseline's own summary.diff is all zeros; it records the absolute sizes the diffs are against.
nmt_diff baseline

# The sampler: RSS every SAMPLE_SECONDS, a native-memory diff once the warm-up has elapsed and then
# every NMT_INTERVAL_SECONDS, until the server exits or it is stopped.
(
  elapsed=0
  warmup_taken=""
  next_periodic=$NMT_INTERVAL_SECONDS
  while kill -0 "$SERVER_PID" 2>/dev/null; do
    sample_rss
    if [ -z "$warmup_taken" ] && [ "$elapsed" -ge "$WARMUP_SECONDS" ]; then
      nmt_diff warmup
      warmup_taken=1
    fi
    if [ "$elapsed" -ge "$next_periodic" ]; then
      nmt_diff periodic
      next_periodic=$((next_periodic + NMT_INTERVAL_SECONDS))
    fi
    i=0
    while [ "$i" -lt "$SAMPLE_SECONDS" ] && kill -0 "$SERVER_PID" 2>/dev/null; do
      sleep 1
      i=$((i + 1))
    done
    elapsed=$((elapsed + SAMPLE_SECONDS))
  done
) &
SAMPLER_PID=$!

echo "load: $CONNECTIONS connections for $DURATION s (warm-up $WARMUP_SECONDS s)"
STARTED_AT=$(date '+%Y-%m-%d %H:%M:%S')
# The load runs to its own deadline unless the server dies first: the load generator has no
# liveness check on its target, so a server that exits mid-run is noticed here, the load stopped
# and the reason recorded, rather than the failure surfacing when the scheduled end arrives.
SERVER_DIED=""
set +e
"$JAVA" -p "$MODULE_PATH" -m "$LOAD_MAIN" 127.0.0.1 "$PORT" "$DURATION" "$CONNECTIONS" "$FACTORY" > "$RUN_DIR/load.log" 2>&1 &
LOAD_PID=$!
while kill -0 "$LOAD_PID" 2>/dev/null; do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    SERVER_DIED="the server (pid $SERVER_PID) exited $(($(date +%s) - NMT_START)) s into the run, before the load finished; the load was stopped"
    echo "$SERVER_DIED" >&2
    stop_pid "$LOAD_PID" TERM "load generator"
    break
  fi
  sleep 1
done
wait "$LOAD_PID"
LOAD_EXIT=$?
set -e
LOAD_PID=""
FINISHED_AT=$(date '+%Y-%m-%d %H:%M:%S')
echo "load exit $LOAD_EXIT"

# The periodic sampler is stopped first so the last readings are the ones taken at the end of the
# load, not up to a sampling interval before it.
stop_pid "$SAMPLER_PID" TERM "rss sampler"
SAMPLER_PID=""
sample_rss
nmt_diff final

# The recording is dumped with the paths to GC roots before the server is stopped: the dump walks
# the heap from the sampled old objects, which the exit dump does not. Bounded, because that walk
# pauses the JVM and the exit dump is the fallback.
JFR_FINAL=$RUN_DIR/soak-final.jfr
JFR_EXIT=$RUN_DIR/soak-exit.jfr
echo "dumping the recording with path-to-gc-roots=true (bound $JFR_DUMP_TIMEOUT_SECONDS s)"
set +e
run_bounded "$JFR_DUMP_TIMEOUT_SECONDS" "jcmd JFR.dump" "$JCMD" "$SERVER_PID" JFR.dump name=soak "filename=$JFR_FINAL" path-to-gc-roots=true > "$RUN_DIR/jfr-dump.log" 2>&1
JFR_DUMP_EXIT=$?
set -e
stop_pid "$SERVER_PID" TERM "server" 120
set +e
wait "$SERVER_PID" 2>/dev/null
SERVER_EXIT=$?
set -e
SERVER_PID=""

# ---- recording post-processing ----
JFR_FILE=""
JFR_SOURCE=""
if [ -s "$JFR_FINAL" ]; then
  JFR_FILE=$JFR_FINAL
  JFR_SOURCE="jcmd JFR.dump with path-to-gc-roots=true"
elif [ -s "$JFR_EXIT" ]; then
  JFR_FILE=$JFR_EXIT
  JFR_SOURCE="the JVM's dumponexit (the JFR.dump before the stop failed, so no paths to GC roots)"
fi
JFR_SUMMARY_EXIT=-1
REPORT_EXIT=-1
METRICS_FILE=$RUN_DIR/jfr-metrics.properties
REPORT_FILE=$RUN_DIR/jfr-report.md
if [ -n "$JFR_FILE" ]; then
  set +e
  "$JFR" summary "$JFR_FILE" > "$RUN_DIR/jfr-summary.txt" 2>&1
  JFR_SUMMARY_EXIT=$?
  "$JAVA" -p "$MODULE_PATH" -m "$REPORT_MAIN" "$JFR_FILE" "$REPORT_FILE" "$NMT_CSV" "$METRICS_FILE" > "$RUN_DIR/jfr-report.log" 2>&1
  REPORT_EXIT=$?
  set -e
  echo "jfr summary exit $JFR_SUMMARY_EXIT, JfrReport exit $REPORT_EXIT"
fi
metric() {
  local value
  value=$(sed -n "s/^$1=//p" "$METRICS_FILE" 2>/dev/null | head -n 1 || true)
  printf '%s\n' "${value:-$2}"
}
SUBMIT_FAILED=$(metric submit_failed 0)
PINNED=$(metric pinned 0)
PINNED_MAX_MS=$(metric pinned_max_ms 0)
OLD_OBJECT_SAMPLES=$(metric old_object_samples 0)
OLD_OBJECT_TOP_TYPES=$(metric old_object_top_types '')
DOMINANT_FLAG=$(metric dominant_thread_flag 0)
DOMINANT_THREAD=$(metric dominant_thread '')
DOMINANT_SHARE=$(metric dominant_thread_share_pct 0)
EXECUTION_SAMPLES=$(metric execution_samples 0)
JFR_EVENTS=$(metric events_total 0)
case "$SUBMIT_FAILED$PINNED$OLD_OBJECT_SAMPLES$DOMINANT_FLAG$DOMINANT_SHARE$EXECUTION_SAMPLES$JFR_EVENTS" in *[!0-9]*) REPORT_EXIT=99 ;; esac

# ---- numbers ----
RSS_FILE=$RUN_DIR/rss.csv
CSV_FILE=$RUN_DIR/server.csv
LOG_FILE=$RUN_DIR/server.log
LOAD_FILE=$RUN_DIR/load.log
touch "$RSS_FILE"

# Warm-up is a span of time: every sample within WARMUP_SECONDS of the first one is dropped
# before the least-squares fit, so the heap-commit ramp never enters it. Fewer than three
# surviving samples cannot be fitted and the slope is reported as not measured.
rss_stats=$(awk -F, -v floor="$RSS_NOISE_FLOOR_KIB" -v limit="$RSS_SLOPE_LIMIT_KIB_PER_HOUR" -v warmup="$WARMUP_SECONDS" '
  /^[0-9]/ { n++; x[n] = $1; y[n] = $2; if (n == 1 || $2 > max) max = $2 }
  END {
    if (n == 0) { print "0 0 0 0 0 0 n/a 0 0"; exit }
    first = 1
    while (first <= n && x[first] < x[1] + warmup) first++
    dropped = first - 1; m = n - dropped
    if (m < 3) { printf "%d %d %d %d %d %d n/a %d n/a\n", n, y[1], y[n], max, dropped, m, (m > 0 ? x[n] - x[first] : 0); exit }
    x0 = x[first]; sx = 0; sy = 0; sxx = 0; sxy = 0
    for (i = first; i <= n; i++) { dx = x[i] - x0; sx += dx; sy += y[i]; sxx += dx * dx; sxy += dx * y[i] }
    den = m * sxx - sx * sx; window = x[n] - x0
    if (den == 0) slope = "n/a"; else slope = sprintf("%.1f", (m * sxy - sx * sy) / den * 3600)
    applied = limit; hours = window / 3600
    if (hours > 0 && floor / hours > applied) applied = floor / hours
    printf "%d %d %d %d %d %d %s %d %.0f\n", n, y[1], y[n], max, dropped, m, slope, window, applied
  }' "$RSS_FILE")
read -r RSS_N RSS_FIRST RSS_LAST RSS_MAX RSS_DROPPED RSS_FIT_N RSS_SLOPE RSS_WINDOW RSS_LIMIT <<< "$rss_stats"

# The same time window gives the thread and descriptor baseline: the first sample taken after
# warm-up, i.e. under load, never the pre-load sample.
csv_stats=$(awk -F, -v warmup="$WARMUP_SECONDS" '
  /^[0-9]/ { n++; t[n] = $1; heap[n] = $2; thr[n] = $6; peak = $7; fds[n] = $8; leaks = $9; severe = $10
             submit_failed = $11; pinned = $12
             if (n == 1 || $2 > hmax) hmax = $2; if (n == 1 || $8 > fmax) fmax = $8 }
  END {
    if (n == 0) { print "0 0 0 0 0 0 0 0 0 0 0 0 -1 -1 0 0 0 0"; exit }
    first = 1
    while (first <= n && t[first] < t[1] + warmup) first++
    dropped = first - 1; post = n - dropped
    if (post > 0) { tb = thr[first]; fb = fds[first] } else { tb = -1; fb = -1 }
    printf "%d %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d\n", n, heap[1], heap[n], hmax, thr[1], thr[n], peak, fds[1], fds[n], fmax, leaks, severe, tb, fb, dropped, post, submit_failed + 0, pinned + 0
  }' "$CSV_FILE")
read -r CSV_N HEAP_FIRST HEAP_LAST HEAP_MAX THR_FIRST THR_LAST THR_PEAK FD_FIRST FD_LAST FD_MAX LEAKS SEVERE THR_BASE FD_BASE CSV_DROPPED CSV_POST RUN_SUBMIT_FAILED RUN_PINNED <<< "$csv_stats"

# Native memory: the total committed and the gated total (nmt.csv's last column: committed less
# Arena Chunk and Tracing) at the baseline, at the end of the warm-up and at the end of the load;
# the check is a least-squares fit of the gated total over every summary.diff row from the end
# of the warm-up (warmup, periodic, final), the same fit and the same limit as the RSS check —
# two rows give the two-point slope, one row cannot be fitted and is reported as not measured.
# The total itself is not gated: Arena Chunk moves by megabytes between consecutive samples with
# nothing leaked, and Tracing is the recorder's own buffers, which grow with JFR's activity.
nmt_stats=$(awk -F, -v floor="$RSS_NOISE_FLOOR_KIB" -v limit="$RSS_SLOPE_LIMIT_KIB_PER_HOUR" '
  NR > 1 && $2 == "baseline" && bc == "" { bc = $5; bg = $NF }
  NR > 1 && $2 == "warmup" && wc == "" { wc = $5; wg = $NF }
  NR > 1 && $2 == "final" { fc = $5; fg = $NF }
  NR > 1 { rows++ }
  NR > 1 && $2 != "baseline" { m++; x[m] = $1; y[m] = $NF }
  END {
    slope = "n/a"; applied = "n/a"; growth = "n/a"; window = (m > 0 ? x[m] - x[1] : 0)
    if (m >= 2 && window > 0) {
      x0 = x[1]; sx = 0; sy = 0; sxx = 0; sxy = 0
      for (i = 1; i <= m; i++) { dx = x[i] - x0; sx += dx; sy += y[i]; sxx += dx * dx; sxy += dx * y[i] }
      den = m * sxx - sx * sx
      if (den != 0) {
        hours = window / 3600
        slope = sprintf("%.1f", (m * sxy - sx * sy) / den * 3600)
        growth = sprintf("%.0f", slope * hours)
        applied = limit; if (floor / hours > applied) applied = floor / hours
        applied = sprintf("%.0f", applied)
      }
    }
    printf "%d %s %s %s %s %s %s %d %s %d %s %s\n", rows + 0, (bc == "" ? "n/a" : bc), (wc == "" ? "n/a" : wc), (fc == "" ? "n/a" : fc),
      (bg == "" ? "n/a" : bg), (wg == "" ? "n/a" : wg), (fg == "" ? "n/a" : fg), m + 0, slope, window, applied, growth
  }' "$NMT_CSV")
read -r NMT_ROWS NMT_BASE_C NMT_WARM_C NMT_FINAL_C NMT_BASE_G NMT_WARM_G NMT_FINAL_G NMT_FIT_N NMT_SLOPE NMT_WINDOW NMT_LIMIT NMT_GROWTH <<< "$nmt_stats"

LEAK_LINES=$(grep -c 'LEAK:' "$LOG_FILE" || true)
OOM_LINES=$(grep -c 'OutOfMemoryError' "$LOG_FILE" || true)
HPROF_COUNT=$(find "$RUN_DIR" -maxdepth 1 -name '*.hprof' | wc -l | tr -d ' ')
ESCAPED_LINES=$(grep -c 'Exception in thread' "$LOAD_FILE" || true)
LOAD_REPORT=$(sed -n '/^=== SoakLoad final report ===/,$p' "$LOAD_FILE")
[ -n "$LOAD_REPORT" ] || LOAD_REPORT="(no final report in load.log; last lines follow)
$(tail -n 20 "$LOAD_FILE")"

# The load generator's own progress line: responses read per profile, the floor it applied,
# and the abuse cases it skipped; a missing line or a zero is a starved run.
PROGRESS=$(grep -m1 '^progress:' "$LOAD_FILE" || true)
progress_field() {
  local value
  value=$(printf '%s\n' "$PROGRESS" | sed -n "s/.*[[:space:]]$1=\([0-9][0-9]*\).*/\1/p")
  printf '%s\n' "${value:-0}"
}
last_counter() {
  local value
  value=$(grep -o "$1=[0-9]*" "$LOAD_FILE" | tail -n 1 | cut -d= -f2 || true)
  printf '%s\n' "${value:-0}"
}
REQ_CLIENT=$(progress_field client)
REQ_PIPELINER=$(progress_field pipeliner)
REQ_ABUSER=$(progress_field abuser)
REQ_CHURNER=$(progress_field churner)
REQ_TOTAL=$(progress_field total)
REQ_FLOOR=$(progress_field floor)
SKIPPED_CASES=$(progress_field skipped_cases)
# SEVERE records the load provokes by design: one per GET /fail, and up to one per upload abandoned
# mid-body, whether by a reset or a half-close — the JDK backend's handler fails on either (the body
# read ends in 'Connection reset' or 'connection closed before all data received'), Netty logs the
# reset or drops it silently — so the expected count is a ceiling and a shortfall is not counted
# against the run.
FAIL_OPS=$(last_counter 'op:fail')
ABORT_RST=$(last_counter 'case:abort_mid_body:rst')
ABORT_FIN=$(last_counter 'case:abort_mid_body:fin')
SEVERE_EXPECTED=$((FAIL_OPS + ABORT_RST + ABORT_FIN))
SEVERE_UNEXPECTED=$((SEVERE - SEVERE_EXPECTED))
[ "$SEVERE_UNEXPECTED" -ge 0 ] || SEVERE_UNEXPECTED=0

# ---- verdict ----
REASONS=""
NOTES=""
[ -z "$SERVER_DIED" ] || REASONS="$REASONS; $SERVER_DIED"
[ "$LOAD_EXIT" -eq 0 ] || REASONS="$REASONS; load generator exit $LOAD_EXIT"
if [ -n "$SERVER_DIED" ]; then
  # a stopped load generator writes no final report, so its progress cannot be judged
  NOTES="$NOTES; the load generator was stopped, so load.log has no final report or progress line"
else
  [ -n "$PROGRESS" ] || REASONS="$REASONS; no progress line in load.log"
  for profile in client pipeliner abuser churner; do
    [ "$(progress_field "$profile")" -gt 0 ] || REASONS="$REASONS; the $profile profile read no response"
  done
fi
[ "$ESCAPED_LINES" -eq 0 ] || REASONS="$REASONS; $ESCAPED_LINES 'Exception in thread' line(s) in load.log"
[ "$LEAKS" -eq 0 ] || REASONS="$REASONS; $LEAKS LEAK reports counted by the server"
[ "$LEAK_LINES" -eq 0 ] || REASONS="$REASONS; $LEAK_LINES 'LEAK:' lines in server.log"
[ "$OOM_LINES" -eq 0 ] || REASONS="$REASONS; $OOM_LINES 'OutOfMemoryError' lines in server.log"
[ "$HPROF_COUNT" -eq 0 ] || REASONS="$REASONS; $HPROF_COUNT heap dump(s) written"
[ "$SEVERE_UNEXPECTED" -le "$SEVERE_MARGIN" ] || REASONS="$REASONS; $SEVERE_UNEXPECTED SEVERE record(s) beyond the $SEVERE_EXPECTED the load provoked (margin $SEVERE_MARGIN)"
[ "$CSV_N" -ge 2 ] || REASONS="$REASONS; only $CSV_N JVM sample(s)"
if [ "$CSV_POST" -ge 2 ]; then
  [ "$THR_LAST" -le $((THR_BASE + THREAD_MARGIN)) ] || REASONS="$REASONS; live threads grew from $THR_BASE (post-warm-up) to $THR_LAST (margin $THREAD_MARGIN)"
  [ "$FD_LAST" -le $((FD_BASE + FD_MARGIN)) ] || REASONS="$REASONS; open fds grew from $FD_BASE (post-warm-up) to $FD_LAST (margin $FD_MARGIN)"
else
  NOTES="$NOTES; threads and fds not measured ($CSV_POST JVM sample(s) after the $WARMUP_SECONDS s warm-up, need 2)"
fi
if [ "$RSS_SLOPE" = "n/a" ]; then
  NOTES="$NOTES; RSS slope not measured ($RSS_FIT_N RSS sample(s) after the $WARMUP_SECONDS s warm-up, need 3)"
elif ! awk -v s="$RSS_SLOPE" -v l="$RSS_LIMIT" 'BEGIN { exit !(s <= l) }'; then
  REASONS="$REASONS; RSS slope $RSS_SLOPE KiB/h exceeds $RSS_LIMIT KiB/h"
fi
if [ "$NMT_SLOPE" = "n/a" ]; then
  NOTES="$NOTES; native-memory slope not measured ($NMT_FIT_N summary.diff row(s) after the warm-up in nmt.csv, need 2 over a non-zero window)"
elif ! awk -v s="$NMT_SLOPE" -v l="$NMT_LIMIT" 'BEGIN { exit !(s <= l) }'; then
  REASONS="$REASONS; gated native memory (committed less Arena Chunk and Tracing) slope $NMT_SLOPE KiB/h exceeds $NMT_LIMIT KiB/h over the $NMT_WINDOW s after warm-up ($NMT_GROWTH KiB fitted over the window, $NMT_FIT_N rows)"
fi
# The submit-failure gate reads the server's own whole-run counter (server.csv, from a
# RecordingStream over the whole run), not the recording, which is a disk ring that on a long
# run holds only its last hours; the recording's count is reported beside it.
[ "$RUN_SUBMIT_FAILED" -eq 0 ] || REASONS="$REASONS; $RUN_SUBMIT_FAILED jdk.VirtualThreadSubmitFailed event(s) over the run (server.csv)"
if [ -z "$JFR_FILE" ]; then
  REASONS="$REASONS; no flight recording was written (JFR.dump exit $JFR_DUMP_EXIT, no soak-exit.jfr)"
elif [ "$REPORT_EXIT" -ne 0 ]; then
  REASONS="$REASONS; JfrReport failed (exit $REPORT_EXIT) on $JFR_FILE; see jfr-report.log"
else
  [ "$SUBMIT_FAILED" -le "$RUN_SUBMIT_FAILED" ] || REASONS="$REASONS; $SUBMIT_FAILED jdk.VirtualThreadSubmitFailed event(s) in the recording, more than the run counter's $RUN_SUBMIT_FAILED"
  [ "$JFR_DUMP_EXIT" -eq 0 ] || NOTES="$NOTES; JFR.dump before the stop exited $JFR_DUMP_EXIT (124 = abandoned at $JFR_DUMP_TIMEOUT_SECONDS s), the report is from soak-exit.jfr"
  [ "$JFR_SUMMARY_EXIT" -eq 0 ] || NOTES="$NOTES; jfr summary exited $JFR_SUMMARY_EXIT"
  NOTES="$NOTES; report only: $PINNED virtual thread(s) pinned (longest $PINNED_MAX_MS ms), old-object top types [$OLD_OBJECT_TOP_TYPES]"
  [ "$DOMINANT_FLAG" -eq 0 ] || NOTES="$NOTES; report only: one thread dominates the execution samples ($DOMINANT_THREAD, $DOMINANT_SHARE %)"
fi
if [ -z "$REASONS" ]; then
  VERDICT="PASS"
else
  VERDICT="FAIL: ${REASONS#; }"
fi

SUMMARY=$RUN_DIR/summary.md
{
  echo "# Soak summary: $FACTORY"
  echo
  echo "- backend: $FACTORY ($BACKEND)"
  echo "- duration: ${DURATION} s, connections: $CONNECTIONS, heap flag: $HEAP_FLAG"
  echo "- recording: $(basename "$JFC"), ring $JFR_MAXSIZE, NMT summary with a diff every $NMT_INTERVAL_SECONDS s"
  echo "- started: $STARTED_AT, finished: $FINISHED_AT"
  echo "- run directory: $RUN_DIR"
  echo "- server exit status after SIGTERM: $SERVER_EXIT"
  echo "- warm-up: samples within $WARMUP_SECONDS s of the first are dropped: $RSS_DROPPED of $RSS_N RSS samples, $CSV_DROPPED of $CSV_N JVM samples"
  echo
  echo "## Server process RSS (rss.csv, $RSS_N samples every $SAMPLE_SECONDS s plus one at the end)"
  echo
  echo "- RSS KiB first/last/max: $RSS_FIRST / $RSS_LAST / $RSS_MAX"
  echo "- RSS slope after warm-up: $RSS_SLOPE KiB/h (least-squares fit over the $RSS_FIT_N samples after the warm-up, window $RSS_WINDOW s)"
  if [ "$RSS_LIMIT" = "n/a" ]; then
    echo "- RSS slope limit: not applied (the slope was not measured); it would be max($RSS_SLOPE_LIMIT_KIB_PER_HOUR KiB/h, $RSS_NOISE_FLOOR_KIB KiB over the fitted window)"
  else
    echo "- RSS slope limit applied: $RSS_LIMIT KiB/h = max($RSS_SLOPE_LIMIT_KIB_PER_HOUR KiB/h, $RSS_NOISE_FLOOR_KIB KiB over the fitted window)"
  fi
  echo
  echo "## JVM samples (server.csv, $CSV_N samples every $SAMPLE_SECONDS s, $CSV_POST after the warm-up)"
  echo
  echo "- heap used bytes first/last/max: $HEAP_FIRST / $HEAP_LAST / $HEAP_MAX"
  echo "- live threads first/last/peak: $THR_FIRST / $THR_LAST / $THR_PEAK (post-warm-up baseline $THR_BASE, margin $THREAD_MARGIN; -1 = no post-warm-up sample)"
  echo "- open fds first/last/max: $FD_FIRST / $FD_LAST / $FD_MAX (post-warm-up baseline $FD_BASE, margin $FD_MARGIN; -1 = no post-warm-up sample)"
  echo "- LEAK count (last sample): $LEAKS"
  echo "- SEVERE count (last sample): $SEVERE, of which up to $SEVERE_EXPECTED provoked by the load ($FAIL_OPS GET /fail, $ABORT_RST uploads abandoned with a reset and $ABORT_FIN with a half-close, which a backend may or may not log); unexpected: $SEVERE_UNEXPECTED (margin $SEVERE_MARGIN)"
  echo "- jdk.VirtualThreadSubmitFailed over the whole run (last sample, counted by the server's own event stream): $RUN_SUBMIT_FAILED (any fails the run); jdk.VirtualThreadPinned over the whole run: $RUN_PINNED (report only)"
  echo
  echo "## Native memory (nmt.csv, $NMT_ROWS summary.diff row(s) against the baseline taken at start)"
  echo
  echo "- total committed KiB at baseline / end of warm-up / end of load: $NMT_BASE_C / $NMT_WARM_C / $NMT_FINAL_C (not gated: it includes Arena Chunk and Tracing)"
  echo "- gated committed KiB (total less Arena Chunk and Tracing) at baseline / end of warm-up / end of load: $NMT_BASE_G / $NMT_WARM_G / $NMT_FINAL_G"
  if [ "$NMT_SLOPE" = "n/a" ]; then
    echo "- gated slope after warm-up: not measured ($NMT_FIT_N summary.diff row(s) after the warm-up, need 2 over a non-zero window)"
  else
    echo "- gated slope after warm-up: $NMT_SLOPE KiB/h (least-squares fit over the $NMT_FIT_N summary.diff rows from the end of the warm-up, window $NMT_WINDOW s, $NMT_GROWTH KiB fitted over it)"
    echo "- gated slope limit applied: $NMT_LIMIT KiB/h = max($RSS_SLOPE_LIMIT_KIB_PER_HOUR KiB/h, $RSS_NOISE_FLOOR_KIB KiB over the fitted window)"
  fi
  echo "- why two series: Arena Chunk is the JVM's transient arenas (compilation, class loading), which move by megabytes between consecutive samples with nothing leaked, and Tracing is the recorder's own buffers, which grow with JFR's activity; both stay in nmt.csv and nmt.log per category. The jdk.NativeMemoryUsageTotal series in the flight-recording section is the whole total with both included, sampled by JFR's own periodic task a few seconds away from each jcmd attach, and covers only the recording's disk ring, so it neither matches this number nor is gated."
  echo
  echo "## server.log"
  echo
  echo "- 'LEAK:' lines: $LEAK_LINES"
  echo "- 'OutOfMemoryError' lines: $OOM_LINES, heap dumps: $HPROF_COUNT"
  echo
  echo "## Load generator (exit $LOAD_EXIT)"
  echo
  echo "- responses read: client=$REQ_CLIENT pipeliner=$REQ_PIPELINER abuser=$REQ_ABUSER churner=$REQ_CHURNER, total $REQ_TOTAL against a floor of $REQ_FLOOR"
  echo "- abuse cases skipped: $SKIPPED_CASES (the counters name the reasons)"
  echo "- 'Exception in thread' lines in load.log: $ESCAPED_LINES"
  echo
  echo '```'
  echo "$LOAD_REPORT"
  echo '```'
  echo
  echo "## Flight recording"
  echo
  if [ -z "$JFR_FILE" ]; then
    echo "- no recording: JFR.dump exit $JFR_DUMP_EXIT and no soak-exit.jfr"
  else
    echo "- report built from $(basename "$JFR_FILE") ($(du -h "$JFR_FILE" | cut -f1 | tr -d ' ')), written by $JFR_SOURCE; JFR.dump exit $JFR_DUMP_EXIT, exit dump $([ -s "$JFR_EXIT" ] && echo "present ($(du -h "$JFR_EXIT" | cut -f1 | tr -d ' '))" || echo "absent")"
    echo "- jdk.VirtualThreadSubmitFailed events in the recording: $SUBMIT_FAILED (the gate is the whole-run counter in the JVM samples above: the recording is a disk ring of $JFR_MAXSIZE, which on a long run holds only its last hours)"
    echo "- jdk.VirtualThreadPinned events in the recording: $PINNED, longest $PINNED_MAX_MS ms (report only; $RUN_PINNED over the whole run)"
    echo "- jdk.OldObjectSample: $OLD_OBJECT_SAMPLES distinct objects, top types [$OLD_OBJECT_TOP_TYPES] (report only)"
    echo "- jdk.ExecutionSample: $EXECUTION_SAMPLES samples, one thread dominates: $([ "$DOMINANT_FLAG" -eq 0 ] && echo no || echo "yes ($DOMINANT_THREAD, $DOMINANT_SHARE %)") (report only)"
    echo "- JfrReport exit $REPORT_EXIT over $JFR_EVENTS events; jfr summary exit $JFR_SUMMARY_EXIT (jfr-summary.txt)"
    echo
    if [ -s "$REPORT_FILE" ]; then
      cat "$REPORT_FILE"
    else
      echo "(no jfr-report.md; see jfr-report.log)"
    fi
  fi
  echo
  echo "## Verdict"
  echo
  echo "$VERDICT"
  [ -z "$NOTES" ] || echo "(${NOTES#; })"
} > "$SUMMARY"

echo
cat "$SUMMARY"
echo
echo "summary: $SUMMARY"
[ "$VERDICT" = "PASS" ]
