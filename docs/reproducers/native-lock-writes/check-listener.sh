#!/bin/sh
# Diagnostic only: reproduces Gradle's listener race without launching a build.
set -eu
if [ "$#" -ne 1 ] || [ ! -d "$1/lib" ]; then
    printf 'Usage: sh check-listener.sh GRADLE_INSTALLATION_DIRECTORY\n' >&2
    exit 2
fi
listener_gradle=$1
listener_scripts=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
listener_classes=$(mktemp -d "${TMPDIR:-/tmp}/native-lock-listener.XXXXXX")
javac --release 17 -cp "$listener_gradle/lib/*" -d "$listener_classes" "$listener_scripts/ListenerRegistrationProbe.java"
java -cp "$listener_classes:$listener_gradle/lib/*" ListenerRegistrationProbe
