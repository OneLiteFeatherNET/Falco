#!/usr/bin/env bash
#
# Falco JMH baseline run.
#
# Produces the citable baseline numbers of this project: one JSON result file and one human
# readable transcript per benchmark class, under docs/benchmarks/baseline-<date>/, next to a
# conditions.txt that records the machine the numbers were taken on. Nothing this script writes
# lands in build/, because a clean deletes build/ and a baseline that a clean deletes is not a
# baseline.
#
# Usage:
#   docs/benchmarks/full-run.sh --dry-run          print every command and the time estimate
#   docs/benchmarks/full-run.sh                    run the citable baseline (~2 h 35 min)
#   docs/benchmarks/full-run.sh --with-leak-arms   additionally run the two non-comparable copy
#                                                  arms into their own, separately named file
#   docs/benchmarks/full-run.sh --date 2026-08-02  override the directory date stamp
#   docs/benchmarks/full-run.sh --forks 5          override the fork count (default 3)
#   docs/benchmarks/full-run.sh --force            run even though the machine is not idle
#
# Read docs/benchmarks/README.md before running this. In particular: the run takes over two hours,
# it must have the machine to itself, and a second Gradle build started while it runs invalidates
# every number it has produced up to that point.

set -euo pipefail

# ---------------------------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------------------------

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

# Three forks, not one. Five of the six benchmark classes carry @Fork(1), which measures a single
# JVM launch: the +- JMH then prints covers variance between iterations of that one launch and says
# nothing about variance between launches. The README of this repository documents a case where
# that mattered — a two-thread RegionFileComparisonBenchmark row that did not reproduce on an
# independent run of the identical configuration, moving from a usable interval to a half width 8,3
# times its own mean. One fork cannot see that; it is invisible by construction.
#
# Three is the smallest count that can show a fork disagreeing with the others. At two forks a
# disagreement is a tie with no majority and no way to tell which launch was the odd one; at three
# there is a middle value, and JMH keeps the per fork raw data in the JSON so the disagreement can
# be read afterwards rather than guessed at. Five would be better and costs 1,67 times as long
# (about 4 h 20 min instead of 2 h 35 min) — pass --forks 5 for the individual claims that end up
# quoted in the README, and record which of the two configurations produced which table.
FORKS="${FORKS:-3}"

# Restated on the command line although the annotations already say 5 and 5. The issue template
# asks reporters for "the full invocation, including every JMH flag", and a command line that omits
# what the annotations supply is only complete for someone holding the matching source revision.
WARMUP_ITERATIONS=5
MEASUREMENT_ITERATIONS=5

# The thread counts of the contention sweep. @Threads is not an axis JMH can cross with @Param, so
# each of these is a separate process and a separate result file.
CONTENTION_THREADS=(1 2 4 8 16)

# Above this one minute load average the script refuses to start. A measurement taken next to
# somebody else's compile measures that compile. The scouting run this baseline replaces was taken
# on a machine that was not idle, and said so.
MAX_LOAD=1.5

DRY_RUN=0
WITH_LEAK_ARMS=0
FORCE=0
DATE_STAMP="$(date -u +%Y-%m-%d)"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --with-leak-arms) WITH_LEAK_ARMS=1; shift ;;
        --force) FORCE=1; shift ;;
        --date) DATE_STAMP="$2"; shift 2 ;;
        --forks) FORKS="$2"; shift 2 ;;
        -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

OUT_DIR="$REPO_ROOT/docs/benchmarks/baseline-$DATE_STAMP"

# ---------------------------------------------------------------------------------------------
# The plan, and what it costs
# ---------------------------------------------------------------------------------------------
#
# Every figure below is derived, not guessed. The scouting run of 2026-08-01 gives the two
# constants the derivation needs, per benchmark class:
#
#   combinations x forks x (warmup + measurement) x iteration time  +  combinations x forks x start
#
# The scouting run measured 118 combinations at -f 1 -wi 2 -i 3, so 5 s of iterations per
# combination, and its six invocations took 32 s, 44 s, 151 s, 245 s, 190 s and 65 s of wall clock,
# 727 s in total. Subtracting the iteration time from the wall clock and dividing by the number of
# forks gives the per fork start cost of each class, which is the only part that is not arithmetic:
#
#   SectionAllocationBenchmark      5 combos,  25 s iterations,  32 s wall  ->  1,4 s per fork
#   SetBlockContentionBenchmark     6 combos,  30 s iterations,  44 s wall  ->  2,3 s per fork
#   ChunkComparisonBenchmark       24 combos, 120 s iterations, 151 s wall  ->  1,3 s per fork
#   LazySectionBenchmark           39 combos, 195 s iterations, 245 s wall  ->  1,3 s per fork
#   PaletteIndirectGetBenchmark    36 combos, 180 s iterations, 190 s wall  ->  0,3 s per fork
#   ChunkResendCostBenchmark        8 combos,  40 s iterations,  65 s wall  ->  3,1 s per fork
#
# The three classes that call MinecraftServer.init() cost between 1,3 s and 3,1 s per fork, not the
# minutes their javadoc assumes when it argues for a single fork. At the full configuration a fork
# runs 10 s of iterations, so the start is between 12 % and 31 % of it — which is what makes three
# forks affordable at all.
#
# At -f 3 -wi 5 -i 5 each combination therefore costs 3 x 10 s of iterations plus 3 x the start:
#
#   #  Benchmark                                     Combinations              Estimate
#   -  --------------------------------------------  -----------------------  -----------
#   1  SectionAllocationBenchmark                     5 methods x 1      =  5    2 min 51 s
#   2  SetBlockContentionBenchmark, t = 1,2,4,8,16    2 x 3 x 5 runs     = 30   18 min 27 s
#   3  ChunkResendCostBenchmark                       4 methods x 4      = 16   10 min 29 s
#   4  PaletteIndirectGetBenchmark                    6 methods x 6      = 36   18 min 30 s
#   5  LazySectionBenchmark                          13 methods x 3      = 39   21 min 60 s
#   6  ChunkComparisonBenchmark, comparable arms      8 methods x 18     = 144  81 min 24 s
#   -  --------------------------------------------  -----------------------  -----------
#      Citable baseline                                                   270   2 h 34 min
#      Optional: ChunkComparisonBenchmark leak arms   2 methods x 18     = 36   20 min 20 s
#      Optional: contention monitor evidence, JFR     1 combination      =  1        < 1 min
#      --------------------------------------------  -----------------------  -----------
#      Everything                                                         307   2 h 55 min
#
# Plus roughly one minute for :falco-benchmarks:jmhJar, once, before the first measurement.
#
# Cross check against the scouting run as a whole: 12,1 min for 118 combinations at 5 s each scales
# to 6 x (270 / 118) x 12,1 = 166 min for 270 combinations at 30 s each. The per class derivation
# above lands at 154 min because it uses each class's own start cost instead of the average. The
# two agree to within eight percent, which is as close as an estimate of this kind gets.
#
# On the order. Ascending cost, with one deliberate exception. SectionAllocationBenchmark runs
# first because it takes three minutes, has no parameter axis and starts no server: if the jar, the
# fixture or the classpath is broken, it fails in three minutes rather than ninety.
# SetBlockContentionBenchmark is pulled forward out of cost order to second place, because it is the
# only benchmark in this suite whose subject is core scaling. Every other class runs single
# threaded and loads one core; this one saturates sixteen for eighteen minutes. Running it after two
# hours of sustained load would measure a thermally throttled machine and attribute the result to
# lock granularity. ChunkComparisonBenchmark runs last because it is half of the total.
#
# ChunkResendCostBenchmark carries one caveat this estimate cannot remove: at content = TERRAIN its
# resendViewDistance10 arm measured 765 ms per operation in the scouting run. JMH does not cut an
# operation short, so a one second iteration there completes one or two operations and overshoots to
# between 0,8 s and 1,6 s. The class's own estimate is therefore the least reliable of the six, and
# a resend row backed by two operations per iteration is a row to treat with suspicion regardless of
# what its +- says.

# ---------------------------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------------------------

log() { printf '\n=== %s\n' "$*"; }

run() {
    if [[ $DRY_RUN -eq 1 ]]; then
        printf '%q ' "$@"
        printf '\n'
    else
        "$@"
    fi
}

check_idle() {
    [[ -r /proc/loadavg ]] || return 0
    local load
    load="$(cut -d' ' -f1 < /proc/loadavg)"
    if awk -v l="$load" -v m="$MAX_LOAD" 'BEGIN { exit !(l > m) }'; then
        echo "The one minute load average is $load, above the $MAX_LOAD this script accepts." >&2
        echo "Something else is using this machine. A measurement taken now measures it too." >&2
        echo "Stop the other work, or pass --force and record the load in conditions.txt." >&2
        exit 1
    fi
}

record_conditions() {
    local file="$OUT_DIR/conditions.txt"
    {
        echo "Falco JMH baseline, $DATE_STAMP"
        echo
        echo "Every field below is one the performance report issue template asks reporters for."
        echo "A number without these is not comparable with one that has them."
        echo
        echo "## Run"
        echo "started            $(date -uIseconds)"
        echo "forks              $FORKS"
        echo "warmup iterations  $WARMUP_ITERATIONS x 1 s"
        echo "measurement iters  $MEASUREMENT_ITERATIONS x 1 s"
        echo "profiler           gc"
        echo "leak arms included $WITH_LEAK_ARMS"
        echo "script             docs/benchmarks/full-run.sh"
        echo
        echo "## Source"
        echo "commit             $(git -C "$REPO_ROOT" rev-parse HEAD)"
        echo "describe           $(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
        echo "working tree"
        git -C "$REPO_ROOT" status --porcelain | sed 's/^/  /' || true
        echo
        echo "## Machine"
        echo "os                 $(uname -sr)"
        [[ -r /etc/os-release ]] && echo "distribution       $(. /etc/os-release && echo "$PRETTY_NAME")"
        echo "cpu                $(LC_ALL=C lscpu 2>/dev/null | sed -n 's/^Model name: *//p' | head -1)"
        echo "cores / threads    $(LC_ALL=C lscpu 2>/dev/null | sed -n 's/^Core(s) per socket: *//p' | head -1) / $(nproc)"
        echo "governor           $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
        echo "boost              $(cat /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || echo unknown)"
        echo "load at start      $(cut -d' ' -f1-3 < /proc/loadavg 2>/dev/null || echo unknown)"
        echo "memory             $(LC_ALL=C free -h 2>/dev/null | sed -n '2p' || echo unknown)"
        echo
        echo "## JVM"
        java -version 2>&1 | sed 's/^/  /'
        echo
        echo "Heap and any other JVM flag come from the @Fork(jvmArgsAppend) of each class."
        echo "-jvmArgs is deliberately not passed: it replaces the inherited base arguments"
        echo "instead of adding to them, which would be a different JVM than the annotation"
        echo "describes."
        echo
        echo "## Idle"
        echo "Answer the issue template's question here, honestly, before quoting anything:"
        echo "was this machine otherwise idle for the whole run?  [yes / no / unsure]"
    } > "$file"
    echo "conditions written to $file"
}

# ---------------------------------------------------------------------------------------------
# The runs
# ---------------------------------------------------------------------------------------------

jmh() {
    # jmh <result-file-stem> <include-regex> [extra jmh flags...]
    local stem="$1"; shift
    local include="$1"; shift

    local cmd=(java -jar "$JAR" "$include"
        -f "$FORKS"
        -wi "$WARMUP_ITERATIONS"
        -i "$MEASUREMENT_ITERATIONS"
        -prof gc
        -foe true
        -rf json
        -rff "$OUT_DIR/$stem.json"
        "$@")

    log "$stem"
    if [[ $DRY_RUN -eq 1 ]]; then
        printf '%q ' "${cmd[@]}"
        printf '| tee %q\n' "$OUT_DIR/$stem.human.txt"
    else
        "${cmd[@]}" 2>&1 | tee "$OUT_DIR/$stem.human.txt"
    fi
}

main() {
    cd "$REPO_ROOT"

    if [[ $DRY_RUN -eq 0 ]]; then
        [[ $FORCE -eq 1 ]] || check_idle
        mkdir -p "$OUT_DIR"
    fi

    log "building the benchmark jar"
    run ./gradlew --quiet :falco-benchmarks:jmhJar

    JAR="$(ls "$REPO_ROOT"/falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar 2>/dev/null | head -1 || true)"
    if [[ -z "$JAR" && $DRY_RUN -eq 1 ]]; then
        JAR='falco-benchmarks/build/libs/falco-benchmarks-<version>-jmh.jar'
    fi
    if [[ -z "$JAR" ]]; then
        echo "no jmh jar under falco-benchmarks/build/libs" >&2
        exit 1
    fi

    [[ $DRY_RUN -eq 0 ]] && record_conditions

    # 1 — three minutes, no server, no axis. The canary: if this fails, nothing below would have
    #     worked either, and it failed after three minutes instead of ninety.
    jmh SectionAllocationBenchmark 'SectionAllocationBenchmark'

    # 2 — out of cost order on purpose, see the note on ordering above. Five separate processes,
    #     because @Threads is not an axis @Param can cross. Five separate files, because a single
    #     -rff would be rewritten by each following thread count; that is exactly what cost the
    #     scouting run four of its six result sets.
    for t in "${CONTENTION_THREADS[@]}"; do
        jmh "SetBlockContentionBenchmark-t$t" 'SetBlockContentionBenchmark' -t "$t"
    done

    # 3 — the four content shapes. See the caveat above about resendViewDistance10 at TERRAIN.
    jmh ChunkResendCostBenchmark 'ChunkResendCostBenchmark'

    # 4 — the palette break-even curve. Every one of the six sizes is a distinct statement: four of
    #     them are distinct entry widths, and 192 against 256 holds the width fixed at 8 to separate
    #     entry count from width. The scouting run resolved all six in gc.alloc.rate.norm to +- 0 B.
    jmh PaletteIndirectGetBenchmark 'PaletteIndirectGetBenchmark'

    # 5 — three shares, down from four. See the javadoc of LazySectionBenchmark#emptyPercent for the
    #     measurement that retired the fourth.
    jmh LazySectionBenchmark 'LazySectionBenchmark'

    # 6 — everything except the two copy arms that are documented as non-comparable. The exclusion
    #     is anchored so that minestomCopyIsolated and falcoCopyIsolated, which are the comparable
    #     pair, stay in.
    jmh ChunkComparisonBenchmark 'ChunkComparisonBenchmark' -e '\.(minestomCopy|falcoCopy)$'

    if [[ $WITH_LEAK_ARMS -eq 1 ]]; then
        # Separate file, and the file name says what it holds. The time column of this run is not a
        # baseline and must not be quoted; the allocation column is. See docs/benchmarks/README.md.
        jmh ChunkComparisonBenchmark-viewer-cache-leak-NOT-A-BASELINE \
            'ChunkComparisonBenchmark\.(minestomCopy|falcoCopy)$'
    fi

    log "done"
    if [[ $DRY_RUN -eq 0 ]]; then
        echo "results in $OUT_DIR"
        echo "answer the idle question at the end of conditions.txt before quoting anything"
    fi
}

main "$@"
