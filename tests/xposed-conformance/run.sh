#!/usr/bin/env bash
#
# Drives the libxposed API 102 conformance suite over adb and prints one row per case.
#
# One case per broadcast, one process per case: a case that aborts the runtime loses its own
# result and nothing else, which is the whole reason the harness is shaped this way - the report
# we are reproducing has a case that killed its test service, and the run has to continue past it.
#
# The statuses keep three different answers apart. FAIL is the framework doing the wrong thing;
# READING is the case disagreeing with our reading of the spec rather than with its words; SETUP
# and SKIP are the harness failing to get the case off the ground, which is no answer at all.

set -u

MODULE_PKG=org.matrix.vxmodule
TARGET_PKG=org.matrix.vxtarget
FILES=/data/data/$TARGET_PKG/files
CLI=/data/adb/modules/zygisk_vector/cli

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
MODULE_APK=$HERE/module/build/outputs/apk/debug/module-debug.apk
TARGET_APK=$HERE/target/build/outputs/apk/debug/target-debug.apk

SERIAL=${ANDROID_SERIAL:-}
DO_BUILD=0
DO_INSTALL=1
DO_ENABLE=0
KEEP_PROCESS=0
FULL=0
ONLY=
TIMEOUT=40
OUT=$HERE/run-results.txt

usage() {
    cat <<'EOF'
usage: run.sh [options]

  -s SERIAL        device to talk to (default: $ANDROID_SERIAL, else the only one attached)
  --build          run ./gradlew assembleDebug for both apps first
  --no-install     do not install the APKs, use whatever is on the device
  --enable         enable the module and set its scope through the Vector CLI (needs root)
  --only PATTERN   run only the cases whose id or name contains PATTERN
  --keep-process   do not force-stop the app between cases (faster, but cases share state; a
                   case that crashed the process still starts the next one fresh)
  --timeout SEC    how long to wait for one case (default 40)
  --full           print untruncated failure detail
  -o FILE          where to write the full report (default run-results.txt)
EOF
}

while [ $# -gt 0 ]; do
    case $1 in
    -s) SERIAL=$2; shift 2 ;;
    --build) DO_BUILD=1; shift ;;
    --no-install) DO_INSTALL=0; shift ;;
    --enable) DO_ENABLE=1; shift ;;
    --only) ONLY=$2; shift 2 ;;
    --keep-process) KEEP_PROCESS=1; shift ;;
    --timeout) TIMEOUT=$2; shift 2 ;;
    --full) FULL=1; shift ;;
    -o) OUT=$2; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

fail() {
    echo "run.sh: $*" >&2
    exit 1
}

"${ADB[@]}" wait-for-device >/dev/null 2>&1 || fail "no device"

ROOT=0
if "${ADB[@]}" shell su -c id 2>/dev/null | grep -q 'uid=0'; then
    ROOT=1
fi

# Results come out of a file rather than the log, because a device with a global log.tag=E drops
# every INFO line and would make a healthy run look silent.
read_result() {
    if [ "$ROOT" = 1 ]; then
        "${ADB[@]}" exec-out su -c "cat '$FILES/results/$1' 2>/dev/null" 2>/dev/null
    else
        "${ADB[@]}" exec-out run-as "$TARGET_PKG" cat "files/results/$1" 2>/dev/null
    fi
}

clear_results() {
    if [ "$ROOT" = 1 ]; then
        "${ADB[@]}" shell su -c "rm -rf '$FILES/results'" >/dev/null 2>&1
    else
        "${ADB[@]}" shell run-as "$TARGET_PKG" rm -rf files/results >/dev/null 2>&1
    fi
}

# Is the case's process still there? pidof is the fast path; ps covers the builds whose toybox will
# not match a process name with a colon in it.
suite_pid() {
    local pid
    pid=$("${ADB[@]}" shell "pidof '$TARGET_PKG:suite'" 2>/dev/null | tr -d '\r\n')
    if [ -z "$pid" ]; then
        pid=$("${ADB[@]}" shell "ps -A -o PID,NAME" 2>/dev/null | tr -d '\r' |
            awk -v name="$TARGET_PKG:suite" '$2 == name {print $1; exit}')
    fi
    printf '%s' "$pid"
}

# The last line of whatever took a process down. The log is cleared before every case, so anything
# this finds belongs to the case being waited on.
fatal_line() {
    "${ADB[@]}" logcat -d 2>/dev/null | tr -d '\r' |
        grep -Eo 'JNI DETECTED ERROR IN APPLICATION: .*|Fatal signal [0-9]+ .*|FORTIFY: .*|Abort message: .*' |
        tail -1
}

# The last breadcrumb the case logged before it stopped answering. Missing breadcrumbs on a dead
# case usually mean the device drops INFO lines - check adb shell getprop log.tag.
last_step() { # $1 = case id
    "${ADB[@]}" logcat -d -s 'VXConf:*' 2>/dev/null | tr -d '\r' | grep -o "STEP $1 .*" | tail -1
}

if [ "$DO_BUILD" = 1 ]; then
    (cd "$HERE" && ./gradlew :target:assembleDebug :module:assembleDebug) || fail "build failed"
fi

if [ "$DO_INSTALL" = 1 ]; then
    [ -f "$TARGET_APK" ] || fail "missing $TARGET_APK - run with --build, or ./gradlew :target:assembleDebug"
    [ -f "$MODULE_APK" ] || fail "missing $MODULE_APK - run with --build, or ./gradlew :module:assembleDebug"
    echo "installing $TARGET_APK"
    "${ADB[@]}" install -r "$TARGET_APK" >/dev/null || fail "cannot install the target"
    echo "installing $MODULE_APK"
    "${ADB[@]}" install -r "$MODULE_APK" >/dev/null || fail "cannot install the module"
fi

if [ "$DO_ENABLE" = 1 ]; then
    [ "$ROOT" = 1 ] || fail "--enable needs root"
    "${ADB[@]}" shell su -c "$CLI modules enable $MODULE_PKG" || fail "cannot enable the module"
    "${ADB[@]}" shell su -c "$CLI scope set $MODULE_PKG $TARGET_PKG/0" || fail "cannot set the scope"
fi

# A freshly installed package is in the stopped state and receives no broadcast until something
# starts it, which otherwise reads as a framework failure.
"${ADB[@]}" shell am start -n "$MODULE_PKG/.MainActivity" >/dev/null 2>&1
"${ADB[@]}" shell am start -n "$TARGET_PKG/.MainActivity" >/dev/null 2>&1
sleep 1
"${ADB[@]}" shell input keyevent KEYCODE_HOME >/dev/null 2>&1

clear_results

# One broadcast, one case. The receiver lives in :suite and writes the answer to a file. am reports
# a receiver it could not reach on stdout and still exits 0, so the output is what has to be read: a
# case that never started is not a statement about the framework and must not be counted as one.
dispatch() { # $1 = case id -> prints nothing, or why the broadcast did not go out
    local out
    out=$("${ADB[@]}" shell am broadcast \
        -a org.matrix.vxtarget.RUN \
        -n "$TARGET_PKG/.RunReceiver" \
        -f 0x00000020 \
        --es case "$1" 2>&1 | tr -d '\r')
    case $out in
    *"Broadcast completed"*) return 0 ;;
    esac
    printf '%s' "$out" | tr '\n' ' '
    return 1
}

# Waits for one case's result, and gives up early when the process it was running in disappears
# without leaving one: that is a case that took the runtime down, and sitting out the full timeout
# for it would only delay the rest of the run.
await() { # $1 = case id -> prints the raw "STATUS|detail" line, or nothing
    local waited=0
    local seen=0
    local answer
    while [ "$waited" -lt "$TIMEOUT" ]; do
        answer=$(read_result "$1")
        if [ -n "$answer" ]; then
            printf '%s' "$answer" | tr -d '\r'
            return 0
        fi
        if [ -n "$(suite_pid)" ]; then
            seen=1
        elif [ "$seen" = 1 ]; then
            # It was there and now it is not. One more read: the result may have landed between
            # the two calls.
            answer=$(read_result "$1")
            if [ -n "$answer" ]; then
                printf '%s' "$answer" | tr -d '\r'
                return 0
            fi
            return 1
        elif [ "$waited" -gt 0 ] && [ $((waited % 5)) -eq 0 ] && [ -n "$(fatal_line)" ]; then
            # Never seen alive and the log already carries an abort: it died before the first poll.
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

echo "reading the case list"
[ "$KEEP_PROCESS" = 1 ] || "${ADB[@]}" shell am force-stop "$TARGET_PKG" >/dev/null 2>&1
undelivered=$(dispatch '@list') || :
LISTING=$(await '@list')
if [ -z "$LISTING" ]; then
    echo
    echo "The suite never answered. The usual reasons, in order:" >&2
    echo "  * the module is not enabled, or $TARGET_PKG is not in its scope" >&2
    echo "  * the framework is not installed on this device" >&2
    echo "  * the app is still in the stopped state - launch it once by hand" >&2
    [ -n "$undelivered" ] && echo "  * the broadcast did not go out: $undelivered" >&2
    echo >&2
    "${ADB[@]}" logcat -d -s 'VXConf:*' 2>/dev/null | tail -20 >&2
    exit 1
fi
case $LISTING in
PASS\|*) ;;
*)
    # SKIP means the module never reached the process; anything else is the bridge itself failing.
    echo "The suite could not list its cases: ${LISTING#*|}" >&2
    exit 1
    ;;
esac

IFS=';' read -r -a ROWS <<<"${LISTING#*|}"

passed=0
failed=0
crashed=0
readings=0
unrun=0
: >"$OUT"

printf '%-7s %-46s %s\n' STATUS CASE DETAIL
printf '%-7s %-46s %s\n' '-------' '----------------------------------------------' '------'

for row in "${ROWS[@]}"; do
    row=${row//$'\n'/}
    [ -n "$row" ] || continue
    id=${row%%$'\t'*}
    name=${row#*$'\t'}
    if [ -n "$ONLY" ] && [[ "$id" != *"$ONLY"* && "$name" != *"$ONLY"* ]]; then
        continue
    fi

    [ "$KEEP_PROCESS" = 1 ] || "${ADB[@]}" shell am force-stop "$TARGET_PKG" >/dev/null 2>&1
    "${ADB[@]}" logcat -c >/dev/null 2>&1

    # A complaint from am is kept rather than acted on: a case that aborts the runtime can make the
    # broadcast look undelivered too, and a crash has to stay a crash.
    undelivered=$(dispatch "$id") || :
    answer=$(await "$id")

    if [ -n "$answer" ]; then
        status=${answer%%|*}
        detail=${answer#*|}
    else
        # No answer: either the process is gone - the case took the runtime down with it - or it is
        # still sitting there. The log is all that is left either way.
        step=$(last_step "$id")
        fatal=$(fatal_line)
        if [ -z "$(suite_pid)" ]; then
            status=CRASH
            detail="the process died after: ${step:-<no breadcrumb>}${fatal:+ | $fatal}"
            # There is nothing left of it, and whatever state it died in must not follow the next
            # case into a process the framework restarts on its own.
            "${ADB[@]}" shell am force-stop "$TARGET_PKG" >/dev/null 2>&1
        elif [ -n "$undelivered" ]; then
            # The process is fine and the broadcast never arrived, so the case never ran.
            status=SETUP
            detail="the broadcast never reached the app: $undelivered"
        else
            status=HUNG
            detail="no result after ${TIMEOUT}s, process still alive, last step: ${step:-<none>}"
        fi
    fi

    case $status in
    PASS) passed=$((passed + 1)) ;;
    READING) readings=$((readings + 1)) ;;
    SETUP | SKIP) unrun=$((unrun + 1)) ;;
    CRASH) crashed=$((crashed + 1)) ;;
    *) failed=$((failed + 1)) ;;
    esac

    printf '%s\t%s\t%s\t%s\n' "$status" "$id" "$name" "$detail" >>"$OUT"

    shown=$detail
    if [ "$FULL" = 0 ] && [ ${#shown} -gt 76 ]; then
        shown="${shown:0:73}..."
    fi
    printf '%-7s %-46s %s\n' "$status" "$name" "$shown"
done

echo
echo "$((passed + failed + crashed + readings + unrun)) cases: $passed passed, $failed failed," \
    "$crashed crashed, $readings reading, $unrun not run"
if [ "$readings" -gt 0 ] || [ "$unrun" -gt 0 ]; then
    echo "READING: the framework disagrees with our reading of the spec rather than with its words"
    echo "SETUP/SKIP: the case never ran, so it says nothing about the framework either way"
fi
echo "full detail in $OUT"

# A reading is a question about the interface, not a defect, so it does not fail the run. A case
# that could not run does: an inconclusive row must not read as a clean one.
[ "$failed" = 0 ] && [ "$crashed" = 0 ] && [ "$unrun" = 0 ]
