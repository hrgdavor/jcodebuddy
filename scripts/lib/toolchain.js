/**
 * Shared toolchain resolution for the repository's Bun scripts.
 *
 * These three things used to be duplicated across `mvn-jdk25.cmd`, `mvn-jdk25.sh`, `gen.cmd` and
 * `run-demo.cmd` — the same JDK default, the same Maven default, the same "run it and check the version"
 * dance — and the duplication is exactly where they drifted: the follow-up notes' D-21 added `clean` to the one
 * launcher and not the other, and `GateParityTest` had to exist to catch it afterwards. One implementation in one
 * language cannot drift from itself, which is the real reason these scripts are no longer shell.
 *
 * Resolution order, and why:
 *
 *  1. the explicit environment variable (`JCODEBUDDY_JDK25`, `JCODEBUDDY_MVN`);
 *  2. `JAVA_HOME`, which is what a developer who already pinned a JDK expects to be honoured;
 *  3. a platform default, so a fresh checkout works without configuration.
 *
 * A candidate is always **run** before it is accepted. A path that exists but holds Java 21 produces
 * `UnsupportedClassVersionError` from inside the generator, which reads like a classpath bug and sends the
 * reader after the wrong thing; failing here says what is actually wrong.
 */

import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { delimiter, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

/** How many majors the tooling needs (class file 69). */
export const REQUIRED_JAVA = 25;

/**
 * The Zig release the repository's Zig sources are written for.
 *
 * **0.17, and it is a hard floor rather than a preference.** Zig 0.17 removed the `**` array-repetition
 * operator and reworked `std.Io`/`std.process`, so a source written for 0.17 does not compile under 0.16 and
 * the other way round — the two are different languages for these purposes. The wyhash project's `gen-vectors.zig`
 * is deliberately written to compile under both (it builds its repeated fixture at run time) because it is the
 * *reference* generator and a machine that regenerates the vectors should not have to be on one exact release;
 * what must not happen is regenerating them under an older Zig and committing a file whose `reference.zigVersion`
 * silently moves backwards.
 */
export const REQUIRED_ZIG = '0.17';

const here = dirname(fileURLToPath(import.meta.url));

/** The repository root, derived from this file's own location rather than from the caller's cwd. */
export function repoRoot() {
  return resolve(here, '..', '..');
}

function exe(name) {
  return process.platform === 'win32' ? `${name}.exe` : name;
}

/**
 * Runs a program and returns `{status, stdout, stderr}`, with `.cmd`/`.bat` launchers routed through the
 * command interpreter because Windows cannot execute them directly.
 *
 * @param {object} [options] `stdio` defaults to `inherit`; pass `pipe` to capture.
 */
export function run(command, args, options = {}) {
  const { stdio = 'inherit', env, cwd } = options;
  const isBatch = /\.(cmd|bat)$/i.test(command);
  const file = isBatch && process.platform === 'win32' ? process.env.ComSpec || 'cmd.exe' : command;
  const argv = isBatch && process.platform === 'win32' ? ['/d', '/c', command, ...args] : args;
  const result = spawnSync(file, argv, {
    stdio,
    env: env ?? process.env,
    cwd,
    windowsVerbatimArguments: false,
  });
  return {
    status: result.status ?? 1,
    stdout: result.stdout ? result.stdout.toString() : '',
    stderr: result.stderr ? result.stderr.toString() : '',
    error: result.error,
  };
}

/** True when the command answers at all; used to accept only a launcher that actually works. */
function isRunnable(command, args = ['--version']) {
  try {
    const result = run(command, args, { stdio: 'pipe' });
    return result.status === 0;
  } catch {
    return false;
  }
}

/** The major version a `java -version` prints, or null when it cannot be determined. */
export function javaMajor(java) {
  // `java -version` writes to stderr, in every release.
  const result = run(java, ['-version'], { stdio: 'pipe' });
  const text = `${result.stderr}${result.stdout}`;
  const match = text.match(/version "(\d+)/);
  return match ? Number.parseInt(match[1], 10) : null;
}

/**
 * A JDK 25+ home, verified by running its `java`.
 *
 * @returns {{home: string, java: string, version: number}}
 * @throws when nothing acceptable is found, with the candidates it tried
 */
export function resolveJdk25() {
  const candidates = [];
  if (process.env.JCODEBUDDY_JDK25) {
    candidates.push(process.env.JCODEBUDDY_JDK25);
  }
  if (process.env.JAVA_HOME) {
    candidates.push(process.env.JAVA_HOME);
  }
  if (process.platform === 'win32') {
    candidates.push('C:\\Program Files\\Java\\jdk-25');
    // A Microsoft/Adoptium-style install directory, so a machine without the exact default still works.
    for (const base of ['C:\\Program Files\\Java', 'C:\\Program Files\\Eclipse Adoptium']) {
      if (existsSync(base)) {
        try {
          for (const entry of readdirSync(base)) {
            candidates.push(join(base, entry));
          }
        } catch {
          /* unreadable: skip */
        }
      }
    }
  } else {
    candidates.push('/usr/lib/jvm/jdk-25', '/opt/java/jdk-25', '/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home');
  }

  const tried = [];
  for (const home of candidates) {
    const java = join(home, 'bin', exe('java'));
    if (!existsSync(java)) {
      tried.push(`${home} (no ${java})`);
      continue;
    }
    const version = javaMajor(java);
    if (version === null) {
      tried.push(`${home} (could not read its version)`);
      continue;
    }
    if (version < REQUIRED_JAVA) {
      tried.push(`${home} (Java ${version}, needs ${REQUIRED_JAVA}+)`);
      continue;
    }
    return { home, java, version };
  }

  throw new Error(
    `no JDK ${REQUIRED_JAVA}+ found. Tried:\n  ${tried.join('\n  ')}\n` +
      'Set JCODEBUDDY_JDK25 to a JDK 25 home (for example C:\\Program Files\\Java\\jdk-25) and re-run.'
  );
}

/** The `major.minor` a `zig version` prints, or null when it cannot be determined. */
export function zigVersion(zig) {
  const result = run(zig, ['version'], { stdio: 'pipe' });
  if (result.status !== 0) {
    return null;
  }
  const match = `${result.stdout}${result.stderr}`.match(/(\d+)\.(\d+)/);
  return match ? `${match[1]}.${match[2]}` : null;
}

/** Whether `found` is at least `required`, comparing `major.minor` numerically. */
export function atLeastVersion(found, required) {
  if (!found) {
    return false;
  }
  const [foundMajor, foundMinor] = found.split('.').map((part) => Number.parseInt(part, 10));
  const [requiredMajor, requiredMinor] = required.split('.').map((part) => Number.parseInt(part, 10));
  return foundMajor > requiredMajor || (foundMajor === requiredMajor && foundMinor >= requiredMinor);
}

/**
 * The Zig executable, at {@link REQUIRED_ZIG} or newer, **verified by running it**.
 *
 * Resolution order, the same shape as {@link resolveJdk25}: the explicit `JCODEBUDDY_ZIG` (then `ZIG`, which is
 * the variable Zig's own tooling reads), the recorded per-release directories under the machine's Zig root,
 * then `zig` from the PATH. A PATH `zig` that is older than {@link REQUIRED_ZIG} is reported as such rather
 * than used: on this machine the PATH holds 0.16 while the sources are 0.17, and "it compiled yesterday" is
 * exactly the confusion this resolver exists to prevent.
 *
 * @returns {{command: string, version: string, source: string}}
 * @throws when nothing acceptable is found, with the candidates it tried
 */
export function resolveZig() {
  const candidates = [];
  if (process.env.JCODEBUDDY_ZIG) {
    candidates.push([process.env.JCODEBUDDY_ZIG, 'JCODEBUDDY_ZIG']);
  }
  if (process.env.ZIG) {
    candidates.push([process.env.ZIG, 'ZIG']);
  }
  // The recorded Zig root: one directory per release, as this machine keeps them. Newest first, so a machine
  // that holds 0.16 and 0.17 together picks 0.17 without the PATH having to be right.
  for (const root of [
    'D:\\wrk\\zig',
    join(process.env.USERPROFILE ?? process.env.HOME ?? '', 'zig'),
  ]) {
    if (!existsSync(root)) {
      continue;
    }
    try {
      const releases = readdirSync(root)
        .map((entry) => entry)
        .sort((left, right) => Number.parseInt(right, 10) - Number.parseInt(left, 10));
      for (const release of releases) {
        candidates.push([join(root, release, exe('zig')), `${root}\\${release}`]);
      }
    } catch {
      /* unreadable: skip */
    }
  }
  candidates.push(['zig', 'PATH']);

  const tried = [];
  for (const [command, source] of candidates) {
    if (command.includes('\\') && !existsSync(command)) {
      continue;
    }
    const version = zigVersion(command);
    if (version === null) {
      tried.push(`${command} (${source}: could not read its version)`);
      continue;
    }
    if (!atLeastVersion(version, REQUIRED_ZIG)) {
      tried.push(`${command} (${source}: Zig ${version}, needs ${REQUIRED_ZIG}+)`);
      continue;
    }
    return { command, version, source };
  }

  throw new Error(
    `no Zig ${REQUIRED_ZIG}+ found. Tried:\n  ${tried.join('\n  ')}\n` +
      `Set JCODEBUDDY_ZIG to a Zig ${REQUIRED_ZIG} executable (for example D:\\wrk\\zig\\17\\zig.exe) and re-run.`
  );
}

/**
 * The Maven launcher, in this order: `JCODEBUDDY_MVN`, the distribution this repository's notes recorded, then
 * `mvn` from the PATH.
 *
 * `mvnd` is the reason this function exists at all rather than one `spawn('mvn')` call. On some machines `mvn` on
 * the PATH *is* mvnd — a Maven 4 distribution whose daemon registry lives outside the workspace — which a file
 * sandbox refuses and which then fails in a way that looks like a Maven problem. The old `.cmd` avoided it by
 * defaulting to an absolute Apache Maven path; that intent is kept here as an ordered candidate, and when the
 * resolved launcher turns out to be mvnd the caller is told, once, instead of being left to guess.
 *
 * @returns {{command: string, source: string, distribution: string, isMvnd: boolean}}
 */
export function resolveMaven() {
  const classify = (command, source) => {
    const probed = run(command, ['--version'], { stdio: 'pipe' });
    const text = `${probed.stdout}${probed.stderr}`;
    const isMvnd = /mvnd/i.test(text);
    const version = (text.match(/Apache Maven ([^\s]+)/) || [])[1] ?? 'unknown';
    return {
      command,
      source,
      distribution: isMvnd ? `mvnd (Maven ${version})` : `Apache Maven ${version}`,
      isMvnd,
    };
  };

  const explicit = process.env.JCODEBUDDY_MVN;
  if (explicit) {
    if (!existsSync(explicit) && !isRunnable(explicit, ['--version'])) {
      throw new Error(`JCODEBUDDY_MVN points at '${explicit}', which cannot be run.`);
    }
    return classify(explicit, 'JCODEBUDDY_MVN');
  }

  const candidates = [];
  if (process.platform === 'win32') {
    // The distribution the old mvn-jdk25.cmd defaulted to, as a candidate rather than a hardcoded default.
    candidates.push(['D:\\programs\\mvn\\bin\\mvn.cmd', 'the recorded Apache Maven location']);
  }
  for (const name of process.platform === 'win32' ? ['mvn.cmd', 'mvn.bat', 'mvn'] : ['mvn']) {
    candidates.push([name, 'PATH']);
  }

  for (const [command, source] of candidates) {
    if (command.includes('\\') && !existsSync(command)) {
      continue;
    }
    if (isRunnable(command, ['--version'])) {
      return classify(command, source);
    }
  }

  throw new Error(
    'no Maven launcher found: set JCODEBUDDY_MVN, or put mvn on the PATH.'
  );
}

/** The environment a Maven invocation needs: the selected JDK, nothing else overridden. */
export function envWith(jdk, extra = {}) {
  return { ...process.env, JAVA_HOME: jdk.home, ...extra };
}

export { delimiter };
