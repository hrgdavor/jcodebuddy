#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# mvn-jdk25.sh - run Maven with JDK 25 on both the Maven JVM and the surefire
# test fork (plan.dsflash.md 0.1 / 0.3). POSIX sibling of mvn-jdk25.cmd.
#
# Usage:
#   scripts/mvn-jdk25.sh                       -> hipster-entity set, `test`
#   scripts/mvn-jdk25.sh hipster-entity install
#   scripts/mvn-jdk25.sh -o -pl <mods> -am test
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

export JAVA_HOME="$JCODEBUDDY_JDK25"

if [ "${1:-}" = "hipster-entity" ]; then
    shift
    goal="${1:-test}"
    [ $# -gt 0 ] && shift
    exec "$JCODEBUDDY_MVN" -o -pl "$JCODEBUDDY_HE_MODULES" -am "$goal" "$@"
fi

exec "$JCODEBUDDY_MVN" "$@"
