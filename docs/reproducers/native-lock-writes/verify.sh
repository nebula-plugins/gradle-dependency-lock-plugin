#!/bin/sh
# Checks native persistence in disposable fixtures; never modifies a consumer build.
set -eu

if [ "$#" -lt 3 ]; then
    printf 'Usage: sh verify.sh GRADLE_EXECUTABLE ATTEMPTS fresh|repeat [GRADLE_OPTIONS...]\n' >&2
    exit 2
fi
native_gradle=$1
native_attempts=$2
native_mode=$3
shift 3
case "$native_attempts" in
    ''|*[!0-9]*|0*) printf 'ATTEMPTS must be a positive integer without leading zeros\n' >&2; exit 2 ;;
esac
case "$native_mode" in
    fresh|repeat) ;;
    *) printf 'Fixture mode must be fresh or repeat\n' >&2; exit 2 ;;
esac
native_scripts=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
native_results=$(mktemp -d "${TMPDIR:-/tmp}/native-lock-verification.XXXXXX")
printf 'Evidence: %s\n' "$native_results"
printf '%s\n' "$native_gradle" "$@" > "$native_results/arguments.txt"
printf 'attempt\texit\tlibrary_lock\ttools_lock\tpersisted\tfixture\n' > "$native_results/results.tsv"

valid_lock() {
    [ -s "$1" ] || return 1
    # Both dynamic dependencies must have resolved and persisted at the fixture's latest version.
    awk '
        /^fixture:widget:1[.]1=/ { widget = 1 }
        /^fixture:other:1[.]1=/ { other = 1 }
        END { exit !(widget && other) }
    ' "$1"
}

native_fixture=''
native_complete=0
native_missing=0
native_failed=0
native_attempt=1
while [ "$native_attempt" -le "$native_attempts" ]; do
    if [ -z "$native_fixture" ] || [ "$native_mode" = fresh ]; then
        native_fixture=$(sh "$native_scripts/prepare.sh") || exit 2
    else
        # Only these generated files in our disposable fixture are removed.
        rm -f -- "$native_fixture/libraries/lib/gradle.lockfile" "$native_fixture/tools/lib/gradle.lockfile"
    fi
    native_snapshot="$native_results/attempt-$native_attempt"
    mkdir -p "$native_snapshot"
    native_status=0
    "$native_gradle" -p "$native_fixture" :libraries:lib:dependencies :tools:lib:dependencies \
        --write-locks --console=plain --stacktrace "$@" > "$native_snapshot/build.log" 2>&1 || native_status=$?
    native_library=no
    native_tools=no
    valid_lock "$native_fixture/libraries/lib/gradle.lockfile" && native_library=yes
    valid_lock "$native_fixture/tools/lib/gradle.lockfile" && native_tools=yes
    # Snapshot existing files before the next repeated write; absence remains visible in the TSV.
    if [ -f "$native_fixture/libraries/lib/gradle.lockfile" ]; then
        cp "$native_fixture/libraries/lib/gradle.lockfile" "$native_snapshot/libraries.gradle.lockfile"
    fi
    if [ -f "$native_fixture/tools/lib/gradle.lockfile" ]; then
        cp "$native_fixture/tools/lib/gradle.lockfile" "$native_snapshot/tools.gradle.lockfile"
    fi
    native_persisted=$(awk '/Persisted dependency lock state for project/ { n++ } END { print n+0 }' "$native_snapshot/build.log")
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$native_attempt" "$native_status" "$native_library" "$native_tools" "$native_persisted" "$native_fixture" >> "$native_results/results.tsv"
    printf 'attempt=%s exit=%s library=%s tools=%s persisted=%s\n' "$native_attempt" "$native_status" "$native_library" "$native_tools" "$native_persisted"
    if [ "$native_status" -ne 0 ]; then
        native_failed=$((native_failed + 1))
    elif [ "$native_library" = yes ] && [ "$native_tools" = yes ]; then
        native_complete=$((native_complete + 1))
    else
        native_missing=$((native_missing + 1))
    fi
    native_attempt=$((native_attempt + 1))
done
printf 'writes=%s complete=%s missing_or_invalid=%s build_failures=%s\n' "$native_attempts" "$native_complete" "$native_missing" "$native_failed"
if [ "$native_failed" -ne 0 ]; then exit 2; fi
if [ "$native_missing" -ne 0 ]; then exit 1; fi
