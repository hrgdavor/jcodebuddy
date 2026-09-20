#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# mvn-jdk25.sh - run Maven with JDK 25 on both the Maven JVM and the surefire
# test fork (plan.dsflash.md 0.1 / 0.3). POSIX sibling of mvn-jdk25.cmd.
#
# This file is kept behaviourally identical to mvn-jdk25.cmd, and GateParityTest
# asserts that: the same six-module -pl list, the same `clean test` default, the
# same refusal of a property that a shell split before the script saw it, and
# the same -Dmaven.compiler.useIncrementalCompilation=false on every invocation.
# Before that test existed the .sh silently diverged: D-21 added `clean` to the
# .cmd only, so `scripts/mvn-jdk25.sh` with no arguments ran a bare `mvn` - not
# the recorded gate at all - and nothing reported it (notes D-15 reviewed this
# file by inspection, and the inspection looked at the usage block, not the code).
#
# Usage:
#   scripts/mvn-jdk25.sh                       -> hipster-entity set, `clean test`
#   scripts/mvn-jdk25.sh hipster-entity install
#   scripts/mvn-jdk25.sh -o -pl <mods> -am test
#
# A property argument must be quoted when the shortcut is used, exactly as in
# the .cmd: `scripts/mvn-jdk25.sh hipster-entity test "-Dtest=SomeTest"`.
# A `-D` token that arrives without `=` is the wreckage of a split property, and
# is refused with exit 2 rather than forwarded as a malformed assignment.
#
# Environment overrides: JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES
# ---------------------------------------------------------------------------
set -euo pipefail

: "${JCODEBUDDY_JDK25:=/c/Program Files/Java/jdk-25}"
: "${JCODEBUDDY_MVN:=mvn}"
: "${JCODEBUDDY_HE_MODULES:=hipster-entity-api,hipster-entity-core,hipster-entity-tooling,hipster-entity-jackson,hipster-entity-test,hipster-entity-example}"

if [ ! -x "$JCODEBUDDY_JDK25/bin/java" ]; then
    echo "[mvn-jdk25] ERROR: no JDK 25 at '$JCODEBUDDY_JDK25'. Set JCODEBUDDY_JDK25." >&2
    exit 1
fi

# The .cmd checks this too; a missing launcher must fail here, not halfway
# through a build with a confusing "command not found".
if ! command -v "$JCODEBUDDY_MVN" >/dev/null 2>&1 && [ ! -x "$JCODEBUDDY_MVN" ]; then
    echo "[mvn-jdk25] ERROR: no Maven launcher at '$JCODEBUDDY_MVN'. Set JCODEBUDDY_MVN." >&2
    exit 1
fi

export JAVA_HOME="$JCODEBUDDY_JDK25"

# A property the shell split before this script saw it (an unquoted -Dname=value)
# arrives as a `-D` token carrying no `=`. Refuse rather than forward fragments:
# in the .cmd the same fragments made Maven drop the -pl list, and the "gate"
# silently became a build of the whole 23-module reactor (notes D-20).
check_split_property() {
    local token
    for token in "$@"; do
        case "$token" in
            -D*=*) ;;
            -D*)
                echo "[mvn-jdk25] ERROR: a property argument was split before this script saw it." >&2
                echo "[mvn-jdk25]   Quote it at the call site, and give boolean properties a value:" >&2
                echo "[mvn-jdk25]     scripts/mvn-jdk25.sh hipster-entity test \"-Dtest=SomeTest\"" >&2
                echo "[mvn-jdk25]     scripts/mvn-jdk25.sh hipster-entity package \"-DskipTests=true\"" >&2
                echo "[mvn-jdk25]   Refusing to run rather than silently building the whole reactor." >&2
                return 2
                ;;
        esac
    done
    return 0
}

INCREMENTAL_OFF="-Dmaven.compiler.useIncrementalCompilation=false"

if [ "${1:-}" = "" ] || [ "${1:-}" = "hipster-entity" ]; then
    if [ "${1:-}" = "hipster-entity" ]; then
        shift
    fi
    if [ $# -eq 0 ]; then
        # No goal at all -> the recorded gate (0.3/0.4), `clean test`, exactly as
        # the .cmd's :he_default branch does. `clean` is not optional: notes F-47
        # found this gate satisfiable by a previous revision's class files.
        exec "$JCODEBUDDY_MVN" -o -pl "$JCODEBUDDY_HE_MODULES" -am "$INCREMENTAL_OFF" clean test
    fi

    check_split_property "$@"
    goal="$1"
    shift
    check_split_property "$@"
    exec "$JCODEBUDDY_MVN" -o -pl "$JCODEBUDDY_HE_MODULES" -am "$INCREMENTAL_OFF" "$goal" "$@"
fi

check_split_property "$@"
exec "$JCODEBUDDY_MVN" "$INCREMENTAL_OFF" "$@"
