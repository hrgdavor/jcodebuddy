#!/usr/bin/env bun
/**
 * Turn one merge-java test fixture into a real git repository.
 *
 * A fixture in `src/test/resources/fixtures/<case>/` is three complete versions of
 * a file - base, ours, theirs - and nothing else. That is exactly enough to rebuild
 * the merge it describes as a real repository: a base commit, an `upstream` branch
 * carrying what the upstream did, and a `feature` branch carrying what we did. The
 * result can be opened in a git GUI, driven from the git CLI, or handed to any tool
 * that resolves conflicts, which a JUnit fixture cannot.
 *
 * Usage:
 *   bun run scripts/git-sample/sample-repo.js <fixture|path> [options]
 *
 *   --list                  list every fixture that can be simulated
 *   --out <dir>             where to build (default: <tmp>/merge-java-<fixture>)
 *   --base-branch <name>    branch holding the base commit   (default: base)
 *   --feature-branch <name> our side                         (default: feature)
 *   --upstream-branch <n>   their side                       (default: upstream)
 *   --merge                 leave the repository mid-merge, with conflict markers
 *   --force                 replace an existing --out directory
 *   --json                  print the manifest as JSON instead of a summary
 *   --capture <file>        write this run's output there instead of to the console
 *
 * Exit codes: 0 built, 1 the fixture or the repository is unusable, 2 bad usage.
 *
 * What it builds, for fixture `import-add-both`:
 *
 *   upstream  o   theirs   (what the upstream did since we last synced)
 *              \
 *   base        o         (the last-synced upstream state - the merge base)
 *                \
 *   feature       o       (what our branch did since we last synced)
 *
 * `feature` tracks `origin/upstream`, so `git merge` in the generated repository
 * conflicts where and how merge-java says it does - and
 * `MergeWorkflow.open(repo).path(...).run()` needs no other argument, because the
 * last-sync marker is written too.
 *
 * Design notes that are not obvious from the code:
 *
 *   - The three trees are written with plumbing (`hash-object`, `read-tree`,
 *     `update-index`, `write-tree`, `commit-tree`), never by checking a branch out
 *     and committing it. A deletion, an addition and a modification are then all
 *     expressed the same way, and no working-tree state can leak between the sides.
 *   - A child process's own output is never read through a pipe: git writes to a
 *     file when this tool needs to read something back, and to the console when it
 *     only needs to run. Builds stay reproducible and the tool works in restricted
 *     sandboxes that forbid pipe handles.
 */

import { spawnSync } from "node:child_process";
import {
  appendFileSync,
  closeSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

// ---------------------------------------------------------------- constants

/** Where fixtures live, relative to the module root. */
const FIXTURE_ROOT = join("src", "test", "resources", "fixtures");

/**
 * Suffixes stripped from a fixture file name to get the repository path.
 *
 * Only the `.txt` marker is removed: `PaymentProcessor.java.txt` is the
 * repository's `PaymentProcessor.java`, because the fixture stores compilable
 * Java under a suffix that keeps Maven from compiling it. Longest first, so a
 * `.java.txt` name is not half-matched by a shorter rule.
 */
const SOURCE_SUFFIXES = [".txt"];

/** One fixed timestamp for every object, so a rebuild produces the same ids. */
const EPOCH_SECONDS = 1700000000;
const EPOCH = `${EPOCH_SECONDS} +0000`;

const IS_WINDOWS = process.platform === "win32";

const USAGE = `usage: sample-repo.js <fixture|path> [options]

  --list                  list every fixture that can be simulated
  --out <dir>             where to build (default: <tmp>/merge-java-<fixture>)
  --base-branch <name>    branch holding the base commit (default: base)
  --feature-branch <name> our side (default: feature)
  --upstream-branch <n>   their side (default: upstream)
  --merge                 leave the repository mid-merge, with conflict markers
  --force                 replace an existing --out directory
  --json                  print the manifest as JSON instead of a summary
  --capture <file>        write this run's output there instead of to the console
  --help                  this text`;

/** Raised for anything the caller can act on; carries the process exit code. */
class ToolError extends Error {
  constructor(message, exitCode = 1) {
    super(message);
    this.exitCode = exitCode;
  }
}

// ------------------------------------------------------------ shell plumbing

/**
 * The module root: the directory holding `scripts/` and `src/test/resources`.
 */
function moduleRoot() {
  return resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
}

/**
 * The shell that runs the git commands.
 *
 * Resolved by looking for the executable on PATH rather than by asking the
 * operating system, because asking would mean spawning a probe process to read
 * its output - a pipe, which this tool deliberately never needs.
 *
 * On Windows both names are tried: Windows PowerShell ships with every supported
 * release, and PowerShell 7 is only present when it has been installed.
 */
const SHELL = resolveShell();

function resolveShell() {
  if (!IS_WINDOWS) {
    return "/bin/sh";
  }
  const directories = (process.env.PATH ?? "").split(";").filter(Boolean);
  directories.push(join(process.env.SystemRoot ?? "C:\\WINDOWS",
    "System32", "WindowsPowerShell", "v1.0"));
  for (const name of ["pwsh.exe", "powershell.exe"]) {
    for (const directory of directories) {
      const candidate = join(directory, name);
      if (existsSync(candidate)) {
        return candidate;
      }
    }
  }
  throw new ToolError("no PowerShell found on PATH; this tool needs pwsh or powershell");
}

/**
 * Quote one argument for the shell that is actually running.
 */
function quote(value) {
  const text = String(value);
  if (IS_WINDOWS) {
    return /^[A-Za-z0-9_./:@=+-]+$/.test(text) ? text : `'${text.replaceAll("'", "''")}'`;
  }
  return `'${text.replaceAll("'", "'\\''")}'`;
}

/**
 * Environment that makes a generated repository independent of the machine: a
 * fixed identity, a fixed clock, and no user or system git configuration.
 */
function gitEnv(extra = {}) {
  return {
    GIT_AUTHOR_NAME: "merge-java fixture",
    GIT_AUTHOR_EMAIL: "fixture@example.invalid",
    GIT_COMMITTER_NAME: "merge-java fixture",
    GIT_COMMITTER_EMAIL: "fixture@example.invalid",
    GIT_AUTHOR_DATE: EPOCH,
    GIT_COMMITTER_DATE: EPOCH,
    GIT_CONFIG_NOSYSTEM: "1",
    GIT_CONFIG_GLOBAL: IS_WINDOWS ? "NUL" : "/dev/null",
    GIT_TERMINAL_PROMPT: "0",
    LC_ALL: "C",
    // A shell that inherits a PATH without PATHEXT cannot resolve `git` to
    // `git.exe`, however complete that PATH is. Declared rather than inherited so
    // the build does not depend on which host started it.
    ...(IS_WINDOWS ? { PATHEXT: ".COM;.EXE;.BAT;.CMD" } : {}),
    ...extra,
  };
}

/**
 * An absolute path to git, resolved from PATH.
 *
 * A bare `git` would also work, but naming the executable outright means a
 * misconfigured shell cannot silently resolve a different one.
 */
const GIT = resolveGit();

function resolveGit() {
  const directories = (process.env.PATH ?? "").split(IS_WINDOWS ? ";" : ":").filter(Boolean);
  const names = IS_WINDOWS ? ["git.exe", "git.cmd"] : ["git"];
  for (const name of names) {
    for (const directory of directories) {
      const candidate = join(directory, name);
      if (existsSync(candidate)) {
        return candidate;
      }
    }
  }
  // Fall back to the bare name: the shell may still resolve it, and the failure
  // is reported with the command it could not run.
  return "git";
}

/**
 * Run one shell command, discarding all output.
 *
 * `stdio: "inherit"` matters: a captured stream is a pipe, and this tool needs to
 * run where pipes to children are not permitted.
 */
function runShell(command, cwd, env) {
  const child = spawnSync(SHELL, shellArgs(command), {
    cwd,
    stdio: ["ignore", "inherit", "inherit"],
    env: gitEnv(env),
  });
  if (child.error) {
    throw new ToolError(`could not run ${SHELL}: ${child.error.message}`);
  }
  return child.status ?? 1;
}

function shellArgs(command) {
  return IS_WINDOWS
    ? ["-NoProfile", "-NonInteractive", "-Command", command]
    : ["-c", command];
}

/** How a git command starts, as a term the shell can invoke. */
function gitCommand() {
  // On Windows an executable path must be called with the call operator, or a
  // path containing spaces would be treated as a string.
  return IS_WINDOWS ? `& ${quote(GIT)}` : quote(GIT);
}

/** A quiet git invocation, for its effect. */
function git(root, args, env) {
  const status = runShell(`${gitCommand()} ${args.map(quote).join(" ")}${discard()}`, root, env);
  if (status !== 0) {
    throw new ToolError(`git ${args[0]} failed (exit ${status}) in ${root}`);
  }
}

/**
 * A git invocation whose standard output is wanted back.
 *
 * The output is redirected to a file, never to a pipe, and read from there.
 *
 * @param options.mergeErrorStream also keep standard error, for the commands -
 *        `merge-tree` and `merge` - that report their conflicts there
 * @param options.allowFailure     return the exit status instead of throwing
 */
function gitCapture(root, args, env, { allowFailure = false, mergeErrorStream = false } = {}) {
  const capture = scratchPath("out");
  try {
    const redirect = mergeErrorStream ? " 2>&1" : "";
    const status = runShell(
      `${gitCommand()} ${args.map(quote).join(" ")}${redirect}${captureTo(capture, mergeErrorStream)}`,
      root, env);
    if (status !== 0 && !allowFailure) {
      throw new ToolError(`git ${args[0]} failed (exit ${status}) in ${root}`);
    }
    return { status, output: readCapture(capture) };
  } finally {
    rmSync(capture, { force: true });
  }
}

/** The same, for a git invocation whose input is a file. */
function gitCaptureStdin(root, args, stdinPath, env) {
  const capture = scratchPath("out");
  try {
    const status = runShell(
      `${gitCommand()} ${args.map(quote).join(" ")}${inputFrom(stdinPath)}${captureTo(capture)}`,
      root, env);
    if (status !== 0) {
      throw new ToolError(`git ${args[0]} failed (exit ${status}) in ${root}`);
    }
    return readCapture(capture);
  } finally {
    rmSync(capture, { force: true });
  }
}

/**
 * Shell text that throws a command's output away.
 *
 * PowerShell cannot redirect to the NUL device (`Out-File` refuses a device
 * path), so it swallows the stream instead - the pipeline is PowerShell's own and
 * never becomes a handle on the child.
 */
function discard() {
  return IS_WINDOWS ? " 2>&1 | Out-Null" : " > /dev/null 2>&1";
}

/**
 * Shell text that sends a command's standard output to a file.
 *
 * Only standard output is redirected, and never to the NUL device: PowerShell
 * refuses to open a device through `Out-File`. The error stream is left alone, so
 * a failing command still says why on the console.
 *
 * @param keepLines a single value is captured without a trailing newline, but a
 *        line-oriented report - git's conflict messages - must keep its breaks
 */
function captureTo(path, keepLines = false) {
  if (!IS_WINDOWS) {
    return ` > ${quote(path)}`;
  }
  return ` | Out-File -Encoding ascii${keepLines ? "" : " -NoNewline"} ${quote(path)}`;
}

/**
 * Shell text that feeds a file to a command's standard input.
 *
 * PowerShell has no `<` redirection operator, so the file is piped in instead;
 * that pipeline is PowerShell's own and is not a stdio handle on the child.
 */
function inputFrom(path) {
  return IS_WINDOWS
    ? ` | Get-Content -Raw ${quote(path)}`
    : ` < ${quote(path)}`;
}

/**
 * Read a captured command output.
 *
 * The byte-order mark a shell's redirection may prepend is dropped, and whatever
 * trailing whitespace the redirection added is removed - but newlines *inside* the
 * text are left alone, because a conflict report's line breaks are its structure.
 */
function readCapture(path) {
  const text = existsSync(path) ? readFileSync(path, "utf8") : "";
  return text.replace(/^\uFEFF/, "").replace(/\s+$/, "");
}

function scratchPath(kind) {
  return join(tmpdir(), `merge-java-${kind}-${process.pid}-${Math.random().toString(36).slice(2)}`);
}

// ----------------------------------------------------------------- fixtures

/**
 * A merge case: three versions of every path that is involved.
 *
 * A path absent from one of the maps is a deletion on that side, which is a real
 * merge situation and is carried through as one.
 */
function loadFixture(name) {
  const directory = resolveFixture(name);
  const versions = {
    base: readVersion(directory, "base"),
    ours: readVersion(directory, "ours"),
    theirs: readVersion(directory, "theirs"),
  };

  const paths = [...new Set([
    ...Object.keys(versions.base),
    ...Object.keys(versions.ours),
    ...Object.keys(versions.theirs),
  ])].sort();

  if (paths.length === 0) {
    throw new ToolError(`${directory} holds no base/, ours/ or theirs/ files`);
  }

  return {
    name: basename(directory),
    directory,
    paths,
    versions,
    oursDiff: readOptional(join(directory, "ours.diff")),
    theirsDiff: readOptional(join(directory, "theirs.diff")),
  };
}

function resolveFixture(name) {
  const candidates = [
    resolve(name),
    resolve(moduleRoot(), FIXTURE_ROOT, name),
    resolve(process.cwd(), FIXTURE_ROOT, name),
    resolve(process.cwd(), "merge-java", FIXTURE_ROOT, name),
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate) && statSync(candidate).isDirectory()) {
      return candidate;
    }
  }
  throw new ToolError(
    `no fixture directory for '${name}' (tried ${candidates.join(", ")})`);
}

/** Every fixture file under one side, keyed by the path it should occupy. */
function readVersion(fixtureDir, side) {
  const root = join(fixtureDir, side);
  const files = {};
  if (!existsSync(root)) {
    return files;
  }
  for (const entry of walk(root)) {
    const repositoryPath = toRepositoryPath(relative(root, entry).split(sep).join("/"));
    files[repositoryPath] = readFileSync(entry, "utf8");
  }
  return files;
}

function walk(directory) {
  const found = [];
  const pending = [directory];
  while (pending.length > 0) {
    const current = pending.pop();
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) {
        pending.push(path);
      } else if (entry.isFile()) {
        found.push(path);
      }
    }
  }
  return found;
}

/**
 * `PaymentProcessor.java.txt` is the repository's `PaymentProcessor.java`. The
 * `.txt` marker exists only to keep fixture sources out of the Maven compilation,
 * so it is the marker that is removed - never the `.java` extension under it.
 */
function toRepositoryPath(fixturePath) {
  for (const suffix of SOURCE_SUFFIXES) {
    if (fixturePath.endsWith(suffix)) {
      return fixturePath.slice(0, -suffix.length);
    }
  }
  return fixturePath;
}

function readOptional(path) {
  return existsSync(path) ? readFileSync(path, "utf8") : "";
}

/** Every fixture directory, for --list. */
function listFixtures() {
  const root = resolve(moduleRoot(), FIXTURE_ROOT);
  if (!existsSync(root)) {
    return [];
  }
  return readdirSync(root, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => {
      const directory = join(root, entry.name);
      const paths = [...new Set([
        ...Object.keys(readVersion(directory, "base")),
        ...Object.keys(readVersion(directory, "ours")),
        ...Object.keys(readVersion(directory, "theirs")),
      ])].sort();
      return { name: entry.name, directory, paths };
    })
    .sort((left, right) => left.name.localeCompare(right.name));
}

// ----------------------------------------------------------------- the build

/**
 * Build the repository and return the manifest describing what was built.
 */
function buildRepository(fixture, options) {
  const root = options.out ?? defaultOut(fixture.name);
  prepareDirectory(root, options.force);

  const scratch = mkdtempSync(join(tmpdir(), "merge-java-build-"));
  try {
    git(root, ["-c", "init.defaultBranch=main", "init", "--quiet"]);
    configureRepository(root);

    // Plumbing, not checkout: each side's tree is written straight into the
    // object database, so the build never depends on working-tree state.
    const baseCommit = commitTree(root, writeTree(root, fixture.versions.base,
      join(scratch, "index-base")), [], `fixture base: ${fixture.name}`);
    const oursCommit = commitTree(root, writeTree(root, fixture.versions.ours,
      join(scratch, "index-ours")), [baseCommit],
      `ours: ${describeChange(fixture.oursDiff, "our branch")}`);
    const theirsCommit = commitTree(root, writeTree(root, fixture.versions.theirs,
      join(scratch, "index-theirs")), [baseCommit],
      `theirs: ${describeChange(fixture.theirsDiff, "the upstream")}`);

    const refs = {
      base: options.baseBranch,
      feature: options.featureBranch,
      upstream: options.upstreamBranch,
    };

    // The remote is the repository itself, named `origin`, so `origin/upstream`
    // exists, `feature` tracks it, and both a plain `git merge` and
    // MergeWorkflow's default `upstream("HEAD")` resolve to their side - while
    // `git fetch` and `git push` still behave, because a local path is a real
    // remote git can talk to. A fixture needs no network and no second clone.
    git(root, ["remote", "add", "origin", root]);

    git(root, ["update-ref", `refs/heads/${refs.base}`, baseCommit]);
    git(root, ["update-ref", `refs/heads/${refs.upstream}`, theirsCommit]);
    git(root, ["update-ref", `refs/heads/${refs.feature}`, oursCommit]);
    git(root, ["symbolic-ref", "HEAD", `refs/heads/${refs.feature}`]);
    git(root, ["update-ref", `refs/remotes/origin/${refs.upstream}`, theirsCommit]);
    git(root, ["config", `branch.${refs.feature}.remote`, "origin"]);
    git(root, ["config", `branch.${refs.feature}.merge`, `refs/heads/${refs.upstream}`]);
    git(root, ["checkout", "--quiet", "--force", refs.feature]);

    writeSyncMarker(root, fixture, refs, baseCommit);
    writeExpectedDiffs(root, fixture);

    const manifest = {
      fixture: fixture.name,
      fixtureDir: fixture.directory,
      repository: root,
      branches: refs,
      commits: { base: baseCommit, ours: oursCommit, theirs: theirsCommit },
      paths: fixture.paths,
      changes: describeChanges(fixture),
      mergeStaged: false,
      conflictsUnderGit: [],
    };

    if (options.merge) {
      stageMerge(root, refs, options);
      // What git left behind decides whether a merge is really in progress;
      // nothing is inferred from git's exit code, which is non-zero for the
      // conflict that is the expected outcome here.
      manifest.mergeStaged = existsSync(join(root, ".git", "MERGE_HEAD"));
      if (!manifest.mergeStaged) {
        throw new ToolError(
          "the two sides did not conflict under git, so no merge could be left in " +
          "progress; rebuild without --merge to inspect the repository");
      }
      manifest.conflictsUnderGit = conflictedPaths(root);
    } else {
      manifest.conflictsUnderGit = simulatedConflicts(root, refs);
    }

    // Written last, so it can describe the state the caller will actually find.
    writeReadme(root, fixture, options, refs, manifest);
    return manifest;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

function configureRepository(root) {
  const settings = [
    ["merge.conflictStyle", "diff3"],
    ["merge.renamelimit", "1000"],
    ["diff.renames", "true"],
    ["core.autocrlf", "false"],
    ["core.safecrlf", "false"],
  ];
  for (const [key, value] of settings) {
    git(root, ["config", key, value]);
  }
}

/**
 * Write one version of every path as a tree object, through a scratch index so
 * the repository's own index is never involved.
 */
function writeTree(root, files, indexPath) {
  rmSync(indexPath, { force: true });
  const env = { GIT_INDEX_FILE: indexPath };

  // An index file that does not exist yet is not reliably read as empty, so it is
  // created empty first.
  git(root, ["read-tree", "--empty"], env);
  for (const path of Object.keys(files).sort()) {
    const objectId = hashObject(root, files[path], env);
    git(root, ["update-index", "--add", "--cacheinfo", `100644,${objectId},${path}`], env);
  }
  return gitCapture(root, ["write-tree"], env).output;
}

/**
 * Put a fixture file's text in the object database and return its id.
 *
 * git is pointed at the file directly rather than fed through a pipe, so nothing
 * has to reproduce the fixture's exact bytes - and `--no-filters` means no
 * gitattributes rule can rewrite them either. A fixture's line endings are part
 * of the case being simulated.
 */
function hashObject(root, content, env) {
  const blob = scratchPath("blob");
  try {
    writeFileSync(blob, content, "utf8");
    const objectId = gitCapture(root, ["hash-object", "-w", "--no-filters", blob], env).output;
    if (!/^[0-9a-f]{40,64}$/.test(objectId)) {
      throw new ToolError(`could not write a blob to the object database: '${objectId}'`);
    }
    return objectId;
  } finally {
    rmSync(blob, { force: true });
  }
}

function commitTree(root, tree, parents, subject) {
  const args = ["commit-tree", tree];
  for (const parent of parents) {
    args.push("-p", parent);
  }
  args.push("-m", subject);
  return gitCapture(root, args).output;
}

/**
 * Leave the repository where `git merge` leaves it: conflict markers in the
 * working tree, the merge in progress, nothing committed and nothing staged as
 * resolved.
 */
function stageMerge(root, refs, options) {
  const outcome = gitCapture(root, ["merge", "--no-commit", "--no-ff", refs.upstream], {},
    { allowFailure: true, mergeErrorStream: true });
  if (outcome.status !== 0 && !existsSync(join(root, ".git", "MERGE_HEAD"))) {
    throw new ToolError(
      `could not start the merge in ${root} (git exit ${outcome.status})`);
  }
  void options;
}

/** The paths a merge in progress left conflicted. */
function conflictedPaths(root) {
  const listed = gitCapture(root, ["diff", "--name-only", "--diff-filter=U"],
    {}, { allowFailure: true }).output;
  return listed.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
}

/**
 * The paths git <em>would</em> conflict on, without touching the working tree.
 *
 * `merge-tree` performs the real three-way merge in memory and writes its
 * `CONFLICT` lines to standard output. Having this in the manifest matters because
 * git conflicts on regions, not on conflict types: a fixture's branches usually
 * also differ in a comment each side rewrote, so the markers a user sees can cover
 * more than the change merge-java is named for. Recording it means that is never a
 * surprise.
 */
function simulatedConflicts(root, refs) {
  const result = gitCapture(root, ["merge-tree", "--write-tree", refs.feature, refs.upstream],
    {}, { allowFailure: true, mergeErrorStream: true });
  // Exit 1 is the "conflicted" answer from this command; anything larger is a real
  // failure, and then there is simply nothing to report.
  if (result.status !== 0 && result.status !== 1) {
    return [];
  }
  const paths = [];
  for (const rawLine of result.output.split("\n")) {
    // git on Windows reports carriage returns as well; the line break is
    // structure, the carriage return is not.
    const match = rawLine.replace(/\r$/, "").trim()
      .match(/^CONFLICT \([^)]*\): .* in (.*)$/);
    if (match && !paths.includes(match[1])) {
      paths.push(match[1]);
    }
  }
  return paths;
}

/** A one-line subject for a branch's commit, taken from the fixture's diff. */
function describeChange(diff, fallback) {
  const subject = diff.split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line.startsWith("+") && !line.startsWith("+++"));
  return subject ? subject.slice(1).trim() : fallback;
}

/**
 * The fixture's own diffs, kept exactly as written.
 *
 * They are the case's documentation - what each side intended - and they are
 * relative to the fixture's `base/`, `ours/`, `theirs/` directories. They are
 * reproductions of the three-way changes, not patches for the generated
 * repository, and the README says so; rewriting their paths would make them stop
 * matching the fixture they describe.
 */
function writeExpectedDiffs(root, fixture) {
  if (!fixture.oursDiff && !fixture.theirsDiff) {
    return;
  }
  const expected = join(root, "expected");
  mkdirSync(expected, { recursive: true });
  if (fixture.oursDiff) {
    writeFileSync(join(expected, "ours.diff"), fixture.oursDiff, "utf8");
  }
  if (fixture.theirsDiff) {
    writeFileSync(join(expected, "theirs.diff"), fixture.theirsDiff, "utf8");
  }
}

/**
 * The module's last-sync marker, so the generated repository is already in the
 * situation merge-java is built for: a branch that has seen this upstream before.
 *
 * The commit id is written out in full rather than left as a ref name. The marker
 * exists precisely because a ref name is not a fixed point - `upstream` moves the
 * moment anything else is pushed to it, and `MergeWorkflow` resolves whatever the
 * marker holds. Naming the branch there made the base silently become the
 * upstream tip, which reads as "neither side changed anything".
 */
function writeSyncMarker(root, fixture, refs, baseCommit) {
  const historyRoot = join(root, ".jcodebuddy", "merge-history", refs.feature);
  mkdirSync(historyRoot, { recursive: true });
  writeFileSync(join(historyRoot, "last-sync"),
    `upstreamCommit = ${baseCommit}\n`
    + `upstreamRef = ${refs.upstream}\n`
    + `recordedAt = ${new Date(EPOCH_SECONDS * 1000).toISOString()}\n`
    + `note = generated from fixture ${fixture.name}\n`, "utf8");
}

// ------------------------------------------------------------------- output

function defaultOut(name) {
  return join(tmpdir(), `merge-java-${name}`);
}

/**
 * Which side changed each path, as a fact about the fixture.
 *
 * A fixture's name is not evidence of its shape: `import-add-remove-same` sounds
 * like both sides touch the import block, and in the file on disk ours is
 * *identical* to the base. Anything the generated README says about why git is
 * happy has to come from comparing the three versions, not from reading the name.
 */
function describeChanges(fixture) {
  const changes = {};
  for (const path of fixture.paths) {
    changes[path] = {
      ours: fixture.versions.ours[path] !== fixture.versions.base[path],
      theirs: fixture.versions.theirs[path] !== fixture.versions.base[path],
    };
  }
  return changes;
}

function prepareDirectory(root, force) {
  if (existsSync(root)) {
    if (!force) {
      throw new ToolError(`${root} already exists; pass --force to replace it`);
    }
    removeRobust(root);
  }
  mkdirSync(root, { recursive: true });
}

/**
 * Windows keeps a handle on `.git` briefly after a git process exits, so a single
 * removal can fail with EBUSY on an otherwise correct rebuild.
 */
function removeRobust(root) {
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      rmSync(root, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
      return;
    } catch (error) {
      if (attempt === 4) {
        throw new ToolError(`could not remove ${root}: ${error.message}`);
      }
      Bun.sleepSync(200);
    }
  }
}

function writeReadme(root, fixture, options, refs, manifest) {
  const commits = manifest.commits;
  const path = fixture.paths[0] ?? "<path>";
  const lines = [
    `# merge-java fixture: ${fixture.name}`,
    "",
    "Generated by `merge-java/scripts/git-sample/sample-repo.js` from",
    `\`${fixture.directory}\`. Disposable: delete it, or rebuild it with \`--force\`.`,
    "",
    "## Shape",
    "",
    "```",
    `${refs.base.padEnd(10)} ${commits.base.slice(0, 12)}  the last-synced upstream state (the merge base)`,
    `${refs.upstream.padEnd(10)} ${commits.theirs.slice(0, 12)}  what the upstream did since we last synced`,
    `${refs.feature.padEnd(10)} ${commits.ours.slice(0, 12)}  what our branch did since we last synced`,
    "```",
    "",
    `HEAD is \`${refs.feature}\`, which tracks \`origin/${refs.upstream}\`.`,
    "",
    "## The conflict, as git sees it",
    "",
    "```sh",
    "git log --oneline --graph --all",
    `git diff ${refs.base} ${refs.feature}     # what ours changed`,
    `git diff ${refs.base} ${refs.upstream}    # what theirs changed`,
    `git merge ${refs.upstream}                # conflict`,
    "git merge --abort",
    "```",
    "",
  ];

  if (manifest.conflictsUnderGit.length > 0) {
    lines.push(
      `Git conflicts on: ${manifest.conflictsUnderGit.map((p) => `\`${p}\``).join(", ")}.`,
      "",
      "Git conflicts on *regions*, not on conflict types, so the markers can cover",
      "more than the change this fixture is named for - several fixtures also differ",
      "in a comment the two sides rewrote, which is not the change the fixture is",
      "about. merge-java's own classification of the same case is narrower and is",
      "what the resolvers act on.",
      "");
  } else {
    // Why git is happy matters, and it is a fact about the fixture rather than
    // something to assume: a merge is trivially clean when only one side touched
    // the file, and says nothing at all about a merge tool.
    const sides = manifest.changes[path] ?? { ours: false, theirs: false };
    lines.push("Git reports no conflict here.", "");
    if (sides.ours && sides.theirs) {
      lines.push(
        "Both sides changed the file, but in regions git can combine, so the merge is",
        "textually clean. That is the case a two-way comparison gets wrong and",
        "merge-java is built for: only the base shows what each side actually did.",
        "");
    } else {
      const changed = sides.ours ? `\`${refs.feature}\`` : `\`${refs.upstream}\``;
      const unchanged = sides.ours ? `\`${refs.upstream}\`` : `\`${refs.feature}\``;
      lines.push(
        `Only ${changed} changed the file; ${unchanged} is identical to the base, so`,
        "there is nothing to reconcile and the clean merge proves nothing about a merge",
        "tool. The fixture isolates one side's change for the resolver to be tested",
        "against in isolation.",
        "");
    }
  }

  lines.push(
    "## The same case through merge-java",
    "",
    "The last-sync marker is written, so the workflow needs no other argument:",
    "",
    "```java",
    `MergeWorkflow.Result result = MergeWorkflow.open(Path.of("${slashy(root)}"))`,
    `    .path("${path}")`,
    "    .dryRun(false)",
    "    .run();",
    "System.out.println(result.describe());",
    "```",
    "",
  );

  if (fixture.oursDiff || fixture.theirsDiff) {
    lines.push(
      "`expected/ours.diff` and `expected/theirs.diff` are the fixture's own diffs,",
      "verbatim: they are written against the fixture's `base/`, `ours/` and `theirs/`",
      "directories and state what each side intended, which is what a resolver's",
      "output should be compared against.",
      "");
  }

  if (options.merge) {
    lines.push(
      "## Mid-merge",
      "",
      "The repository was left with the merge in progress: conflict markers are in the",
      "working tree (`merge.conflictStyle = diff3`, so the base hunk is shown too).",
      "Nothing is committed and nothing is staged as resolved.",
      "",
      "```sh",
      "git status",
      `git checkout --ours   ${path}   # take our side`,
      `git checkout --theirs ${path}   # take their side`,
      "git merge --abort",
      "```",
      "");
  }

  writeFileSync(join(root, "README.md"), lines.join("\n"), "utf8");
}

function slashy(path) {
  return path.split(sep).join("/");
}

function summarize(manifest) {
  const conflicts = manifest.conflictsUnderGit.length === 0
    ? "none"
    : manifest.conflictsUnderGit.join(", ");
  return [
    `fixture ${manifest.fixture}`,
    `  repository    ${manifest.repository}`,
    `  from          ${manifest.fixtureDir}`,
    "  branches      " + `${manifest.branches.feature} (ours) <- `
      + `${manifest.branches.base} -> ${manifest.branches.upstream} (theirs)`,
    `  paths         ${manifest.paths.join(", ")}`,
    `  git conflicts ${conflicts}${manifest.mergeStaged ? " (staged)" : ""}`,
    "",
    `next: cd "${manifest.repository}" && git log --oneline --graph --all`,
  ].join("\n");
}

// ---------------------------------------------------------------------- main

function parseArgs(argv) {
  const options = {
    out: null,
    capture: null,
    merge: false,
    force: false,
    json: false,
    list: false,
    baseBranch: "base",
    featureBranch: "feature",
    upstreamBranch: "upstream",
    fixture: null,
  };

  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    switch (arg) {
      case "--list":
        options.list = true;
        break;
      case "--merge":
        options.merge = true;
        break;
      case "--force":
        options.force = true;
        break;
      case "--json":
        options.json = true;
        break;
      case "--out":
        options.out = resolve(requireValue(argv, ++index, arg));
        break;
      case "--capture":
        options.capture = resolve(requireValue(argv, ++index, arg));
        break;
      case "--base-branch":
        options.baseBranch = requireValue(argv, ++index, arg);
        break;
      case "--feature-branch":
        options.featureBranch = requireValue(argv, ++index, arg);
        break;
      case "--upstream-branch":
        options.upstreamBranch = requireValue(argv, ++index, arg);
        break;
      case "--help":
      case "-h":
        report(USAGE);
        process.exit(0);
        break;
      default:
        if (arg.startsWith("-")) {
          throw new ToolError(`unknown option '${arg}'\n\n${USAGE}`, 2);
        }
        if (options.fixture !== null) {
          throw new ToolError(`only one fixture can be simulated at a time\n\n${USAGE}`, 2);
        }
        options.fixture = arg;
    }
  }

  if (!options.list && options.fixture === null) {
    throw new ToolError(USAGE, 2);
  }
  if (options.baseBranch === options.featureBranch
    || options.baseBranch === options.upstreamBranch
    || options.featureBranch === options.upstreamBranch) {
    throw new ToolError("the three branch names must differ", 2);
  }
  return options;
}

function requireValue(argv, index, option) {
  const value = argv[index];
  if (value === undefined || value.startsWith("--")) {
    throw new ToolError(`${option} needs a value\n\n${USAGE}`, 2);
  }
  return value;
}

/** Where this run's output goes: the console, or a file when `--capture` asked. */
let output = { write: (text) => process.stdout.write(text), isFile: false };

/**
 * Send everything this run prints - including a failure's message - to `--capture`
 * when one was given.
 *
 * The option exists for callers that need both the output and the exit status: a
 * native command whose error stream goes through a PowerShell pipeline loses its
 * exit status, so a test that folded stderr into the pipeline could not tell a
 * usage error from a crash.
 */
function writeTo(path) {
  writeFileSync(path, "", "utf8");
  output = {
    isFile: true,
    write(text) {
      appendFileSync(path, text, "utf8");
    },
  };
}

function report(text = "") {
  output.write(`${text}\n`);
}

/**
 * Run one fixture generation, the way the command line does.
 *
 * Exported so the test harness can drive the generator in its own process: the
 * builder is plain JavaScript over the file system and git, so there is nothing to
 * gain from a second process, and one fewer moving part to explain a failure.
 * `--capture` exists for the cases that really do need a process boundary.
 */
export function runOnce(options) {
  if (options.list) {
    const fixtures = listFixtures();
    if (options.json) {
      report(JSON.stringify(fixtures, null, 2));
    } else if (fixtures.length === 0) {
      report("no fixtures found");
    } else {
      for (const fixture of fixtures) {
        report(`${fixture.name.padEnd(28)} ${fixture.paths.join(", ")}`);
      }
    }
    return null;
  }
  const fixture = loadFixture(options.fixture);
  const manifest = buildRepository(fixture, options);
  report(options.json ? JSON.stringify(manifest, null, 2) : summarize(manifest));
  return manifest;
}

/** Options a caller can be given without going through the command line. */
export function defaultOptions(overrides = {}) {
  return {
    out: null,
    capture: null,
    merge: false,
    force: false,
    json: false,
    list: false,
    baseBranch: "base",
    featureBranch: "feature",
    upstreamBranch: "upstream",
    fixture: null,
    ...overrides,
  };
}

export { listFixtures, loadFixture, resolveFixture, summarize, ToolError };

function main() {
  try {
    // Capture is set up from the raw argument list, before parsing, so a parse
    // error is captured too.
    const captureIndex = process.argv.indexOf("--capture");
    if (captureIndex !== -1 && process.argv[captureIndex + 1]) {
      writeTo(resolve(process.argv[captureIndex + 1]));
    }
    runOnce(parseArgs(process.argv.slice(2)));
  } catch (error) {
    if (error instanceof ToolError) {
      report(`sample-repo: ${error.message}`);
      process.exit(error.exitCode);
    }
    throw error;
  }
}

// Only when this file is the program being run. Imported - by the test harness -
// it is a library and must not build anything.
if (import.meta.main) {
  main();
}