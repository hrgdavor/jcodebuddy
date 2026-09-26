#!/usr/bin/env bun
/**
 * mvn-jdk25.js — run Maven with JDK 25 on both the Maven JVM and the surefire test fork.
 *
 * The root POM requires `maven.compiler.release=25`, so the compiler and the forked test JVM must both be JDK 25.
 * `.mvn/jvm.config` cannot select a JDK — it only passes JVM options to the Maven process — so the JDK is
 * selected here, through `JAVA_HOME`.
 *
 * This replaces BOTH `scripts/mvn-jdk25.cmd` and `scripts/mvn-jdk25.sh`. That is the point rather than a
 * side-effect: the two launchers silently diverged once (follow-up note D-21 added `clean` to the `.cmd` and not
 * the `.sh`, so the POSIX form ran a bare `mvn` — not the gate at all), and `GateParityTest` existed only to
 * catch that class of bug after the fact. One implementation cannot disagree with itself.
 *
 * Usage:
 *   bun scripts/mvn-jdk25.js                       -> the hipster-entity set, `clean test`
 *   bun scripts/mvn-jdk25.js hipster-entity test   -> an explicit goal (no implicit `clean`)
 *   bun scripts/mvn-jdk25.js hipster-entity install
 *   bun scripts/mvn-jdk25.js -o -pl <mods> -am test
 *
 * Environment overrides:
 *   JCODEBUDDY_JDK25       JDK 25 home (default: JAVA_HOME, then the platform default)
 *   JCODEBUDDY_MVN         Maven launcher (default: mvn from the PATH)
 *   JCODEBUDDY_HE_MODULES  comma-separated module list for the `hipster-entity` shortcut
 *
 * A property argument must be a single token (`-Dtest=SomeTest`). A `-D` token with no `=` is the wreckage of a
 * property some shell split, and is refused with exit 2 rather than forwarded as a malformed assignment: in the
 * old `.cmd` that fragment made Maven drop the `-pl` list, so the "gate" silently became a build of the whole
 * reactor, where an unrelated module's failure looks like the recorded gate failing.
 */

import { INCREMENTAL_OFF, buildGateArgs, splitPropertyAdvice } from './lib/gate.js';
import { envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js';

function usage() {
  console.log(`mvn-jdk25.js — Maven with JDK 25 selected for the build and the test fork

  bun scripts/mvn-jdk25.js                        the hipster-entity set, clean test
  bun scripts/mvn-jdk25.js hipster-entity test    the same module set with an explicit goal
  bun scripts/mvn-jdk25.js hipster-entity install install the six modules into the local repository
  bun scripts/mvn-jdk25.js -o -pl <mods> -am test a free-form Maven invocation with the JDK pinned

  Every invocation passes ${INCREMENTAL_OFF}.
  The default (no arguments) also cleans: the recorded gate is \`clean test\`, because F-47 found it
  satisfiable by a previous revision's class files.

  Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES`);
}

function main() {
  const argv = process.argv.slice(2);

  if (argv.includes('--help') || argv.includes('-h')) {
    usage();
    return 0;
  }

  const jdk = resolveJdk25();
  const maven = resolveMaven();
  const modules = process.env.JCODEBUDDY_HE_MODULES;
  const gate = buildGateArgs(argv, modules ? { modules } : {});

  if (gate.error) {
    console.error(splitPropertyAdvice('mvn-jdk25', gate.error));
    return 2;
  }

  if (process.env.JCODEBUDDY_QUIET !== '1') {
    console.error(`[mvn-jdk25] JDK ${jdk.version} at ${jdk.home}`);
    console.error(`[mvn-jdk25] maven: ${maven.distribution} (${maven.source}: ${maven.command})`);
    console.error(`[mvn-jdk25] repo:  ${repoRoot()}`);
    if (maven.isMvnd) {
      console.error('[mvn-jdk25] note: that launcher is mvnd, whose daemon registry lives outside the '
        + 'workspace; a file sandbox refuses it. Set JCODEBUDDY_MVN to Apache Maven if that bites.');
    }
  }

  const result = run(maven.command, gate.args, { stdio: 'inherit', env: envWith(jdk) });
  if (result.error) {
    console.error(`[mvn-jdk25] ERROR: could not run ${maven.command}: ${result.error.message}`);
    return 1;
  }
  return result.status;
}

process.exit(main());
