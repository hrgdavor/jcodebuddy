#!/usr/bin/env bun
/**
 * Tests for sample-repo.js: build a repository from a fixture, then ask git and the
 * file system what actually appeared.
 *
 * The generator is imported and driven in this process rather than run as a child
 * process, because it is plain JavaScript over the file system and git - there is
 * no second program whose output would need capturing, and a test that spawns
 * itself is a test whose failures are hard to attribute. The command-line surface
 * (arguments, exit codes, --capture) is covered by `SampleRepoTest` on the JVM
 * side, which does need a process boundary.
 *
 * Usage:
 *   bun run scripts/git-sample/sample-repo.test.js
 */

import { spawnSync } from "node:child_process";
import {
  closeSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  ToolError,
  defaultOptions,
  listFixtures,
  resolveFixture,
  runOnce,
} from "./sample-repo.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const MODULE_ROOT = resolve(HERE, "..", "..");

/**
 * Where this run's scratch goes.
 *
 * Inside the working tree rather than the system temporary directory: the
 * generated repositories are the thing under test, and a sandbox that permits
 * writes under the workspace should still be able to run these tests. The
 * directory is ignored by git.
 */
const SCRATCH_ROOT = join(MODULE_ROOT, ".sample-repos", ".test-scratch");
mkdirSync(SCRATCH_ROOT, { recursive: true });
const scratch = mkdtempSync(join(SCRATCH_ROOT, "run-"));

let passed = 0;
let failed = 0;
const failures = [];

function check(description, assertion) {
  try {
    if (assertion()) {
      passed++;
      console.log(`  ok   ${description}`);
    } else {
      failed++;
      failures.push(description);
      console.log(`  FAIL ${description}`);
    }
  } catch (error) {
    failed++;
    failures.push(`${description} (${error.message})`);
    console.log(`  FAIL ${description} - ${error.message}`);
  }
}

function group(name, body) {
  console.log(`\n${name}`);
  body();
}

/**
 * Run the generator in this process, with its own reporting silenced.
 *
 * Silence, not capture: what these tests assert on is the repository on disk, and
 * a test that also asserted on prose would fail every time the prose improved.
 */
function generate(options) {
  const quiet = [];
  const stdout = process.stdout.write.bind(process.stdout);
  process.stdout.write = (text) => {
    quiet.push(String(text));
    return true;
  };
  try {
    const manifest = runOnce(defaultOptions(options));
    return { manifest, output: quiet.join("") };
  } finally {
    process.stdout.write = stdout;
  }
}

/** The generator's refusal for a set of options, or null when it did not refuse. */
function refuse(options) {
  try {
    runOnce(defaultOptions(options));
    return null;
  } catch (error) {
    if (error instanceof ToolError) {
      return { message: error.message, exitCode: error.exitCode };
    }
    throw error;
  }
}

const IS_WINDOWS = process.platform === "win32";

/** The same git the generator uses, resolved the same way. */
const GIT = (() => {
  const names = IS_WINDOWS ? ["git.exe", "git.cmd"] : ["git"];
  for (const name of names) {
    for (const directory of (process.env.PATH ?? "").split(IS_WINDOWS ? ";" : ":")) {
      if (directory && existsSync(join(directory, name))) {
        return join(directory, name);
      }
    }
  }
  return "git";
})();

const GIT_ENV = { ...process.env, GIT_CONFIG_NOSYSTEM: "1" };

/**
 * Run git in a repository, for its effect. Output is discarded rather than read:
 * what these tests assert on is the state git left behind.
 */
function git(repo, args) {
  const child = spawnSync(GIT, args, { cwd: repo, stdio: "ignore", env: GIT_ENV });
  return child.status;
}

/**
 * Run git and read its output back, through a file this process opened.
 *
 * A pipe would be simpler and is what a shell redirection would build, but a host
 * is entitled to refuse a pipe handle; a file descriptor asks for nothing unusual.
 */
function gitOutput(repo, args) {
  const capture = join(scratch, `git-${Math.random().toString(36).slice(2)}`);
  const descriptor = openSync(capture, "w");
  try {
    const child = spawnSync(GIT, args, {
      cwd: repo,
      stdio: ["ignore", descriptor, descriptor],
      env: GIT_ENV,
    });
    return { status: child.status, text: readFileSync(capture, "utf8").trim() };
  } finally {
    closeSync(descriptor);
    rmSync(capture, { force: true });
  }
}

/** True when `ancestor` is reachable from `descendant`. */
function isAncestor(repo, ancestor, descendant) {
  return git(repo, ["merge-base", "--is-ancestor", ancestor, descendant]) === 0;
}

function marker(out) {
  return readFileSync(join(out, ".jcodebuddy", "merge-history",
    "feature", "last-sync"), "utf8");
}

try {
  group("--list", () => {
    const fixtures = listFixtures();
    check("finds the fixtures in the tree", () => fixtures.length >= 2);
    check("names a known fixture",
      () => fixtures.some((fixture) => fixture.name === "import-add-both"));
    check("reports the repository path, not the fixture file name",
      () => fixtures.every((fixture) => fixture.paths.every((path) => !path.endsWith(".txt"))));
    check("reports a path that looks like source",
      () => fixtures[0].paths[0] === "PaymentProcessor.java");
  });

  group("a generated repository", () => {
    const out = join(scratch, "basic");
    const { manifest } = generate({ fixture: "import-add-both", out, json: true });

    check("is a git repository", () => existsSync(join(out, ".git")));
    check("checks out our side", () => readFileSync(join(out, "PaymentProcessor.java"), "utf8")
      .includes("import java.time.Instant;"));
    check("drops only the .txt marker from the fixture file name",
      () => existsSync(join(out, "PaymentProcessor.java")) && !existsSync(join(out, "PaymentProcessor")));
    check("has all three branches", () => git(out, ["rev-parse", "--verify", "refs/heads/base"]) === 0
      && git(out, ["rev-parse", "--verify", "refs/heads/feature"]) === 0
      && git(out, ["rev-parse", "--verify", "refs/heads/upstream"]) === 0);
    check("has the upstream as a remote-tracking ref",
      () => git(out, ["rev-parse", "--verify", "refs/remotes/origin/upstream"]) === 0);
    check("leaves our branch checked out",
      () => gitOutput(out, ["symbolic-ref", "--short", "HEAD"]).text === "feature");
    check("writes the last-sync marker where the module reads it",
      () => existsSync(join(out, ".jcodebuddy", "merge-history", "feature", "last-sync")));
    check("the marker pins the base commit by id, not by a moving ref name", () => {
      const text = marker(out);
      return text.includes(`upstreamCommit = ${manifest.commits.base}`)
        && !text.includes("upstreamCommit = upstream");
    });
    check("the marker still names the upstream ref for a human",
      () => marker(out).includes("upstreamRef = upstream"));
    check("carries the fixture's diffs for comparison",
      () => existsSync(join(out, "expected", "ours.diff"))
        && existsSync(join(out, "expected", "theirs.diff")));
    check("documents itself", () => readFileSync(join(out, "README.md"), "utf8")
      .includes("merge-java fixture: import-add-both"));
    check("says which paths git conflicts on",
      () => readFileSync(join(out, "README.md"), "utf8")
        .includes("Git conflicts on: `PaymentProcessor.java`"));

    const log = gitOutput(out, ["log", "--format=%H %P", "--all"]).text;
    check("git records both sides' parents as the base",
      () => log.split("\n").filter((line) => line.includes(manifest.commits.base)).length >= 2);
    check("both sides hang off the one base commit",
      () => isAncestor(out, manifest.commits.base, manifest.commits.ours)
        && isAncestor(out, manifest.commits.base, manifest.commits.theirs));
    check("the base is the only common ancestor",
      () => gitOutput(out, ["merge-base", manifest.commits.ours, manifest.commits.theirs]).text
        === manifest.commits.base);
    check("neither side is an ancestor of the other",
      () => !isAncestor(out, manifest.commits.ours, manifest.commits.theirs)
        && !isAncestor(out, manifest.commits.theirs, manifest.commits.ours));
  });

  group("git conflicts on a generated repository", () => {
    const out = join(scratch, "conflicting");
    const { manifest } = generate({ fixture: "import-add-both", out, json: true });

    check("the manifest records the conflicting path",
      () => manifest.conflictsUnderGit.includes("PaymentProcessor.java"));
    check("merging for real conflicts", () => git(out, ["merge", "--no-commit", "upstream"]) !== 0);
    check("git leaves the merge in progress", () => existsSync(join(out, ".git", "MERGE_HEAD")));
    check("the working tree holds conflict markers",
      () => readFileSync(join(out, "PaymentProcessor.java"), "utf8").includes("<<<<<<<"));
    check("aborting restores our side", () => {
      git(out, ["merge", "--abort"]);
      return readFileSync(join(out, "PaymentProcessor.java"), "utf8")
        .includes("import java.time.Instant;");
    });
    check("the merge produced no commit: HEAD never moved",
      () => gitOutput(out, ["rev-parse", "HEAD"]).text === manifest.commits.ours);
    check("the branch still points at our commit",
      () => gitOutput(out, ["rev-parse", "refs/heads/feature"]).text
        === manifest.commits.ours);
    check("aborting left the source file untouched",
      () => gitOutput(out, ["status", "--porcelain", "PaymentProcessor.java"]).text === "");
    check("the last-sync marker is working-tree state, not a commit",
      () => gitOutput(out, ["status", "--porcelain"]).text
        .includes("?? .jcodebuddy/"));
  });

  group("a fixture git merges cleanly while the sides still disagree", () => {
    const out = join(scratch, "clean");
    const { manifest } = generate({ fixture: "import-add-remove-same", out, json: true });

    check("the manifest reports no conflict",
      () => Array.isArray(manifest.conflictsUnderGit) && manifest.conflictsUnderGit.length === 0);
    check("git really does merge it", () => git(out, ["merge", "--no-commit", "upstream"]) === 0);
    check("and leaves the branch where it was", () => {
      git(out, ["merge", "--abort"]);
      return gitOutput(out, ["symbolic-ref", "--short", "HEAD"]).text === "feature";
    });
    check("the README says which side changed the file, rather than guessing",
      () => readFileSync(join(out, "README.md"), "utf8")
        .includes("Only `upstream` changed the file"));
    check("the manifest records the side that did not change",
      () => manifest.changes["PaymentProcessor.java"].ours === false
        && manifest.changes["PaymentProcessor.java"].theirs === true);
    check("ours really is the base, byte for byte in the repository",
      () => gitOutput(out, ["diff", "--name-only", "base", "feature"]).text === "");
  });

  group("--merge leaves the repository mid-merge", () => {
    const out = join(scratch, "merged");
    const { manifest } = generate({ fixture: "import-add-both", out, merge: true, json: true });

    check("reports the merge as staged", () => manifest.mergeStaged === true);
    check("reports which paths conflict in the working tree",
      () => manifest.conflictsUnderGit.includes("PaymentProcessor.java"));
    check("the tree holds diff3 markers, including the base", () => {
      const text = readFileSync(join(out, "PaymentProcessor.java"), "utf8");
      return text.includes("<<<<<<<") && text.includes("|||||||");
    });
    check("the README says how to leave the merge",
      () => readFileSync(join(out, "README.md"), "utf8").includes("git merge --abort"));
  });

  group("refusals", () => {
    const unknown = refuse({ fixture: "no-such-fixture", out: join(scratch, "missing") });
    check("an unknown fixture is reported by name",
      () => unknown?.exitCode === 1 && unknown.message.includes("no fixture directory"));

    const empty = join(scratch, "empty-fixture");
    mkdirSync(empty, { recursive: true });
    writeFileSync(join(empty, "placeholder"), "");
    const notAFixture = refuse({ fixture: empty, out: join(scratch, "from-empty") });
    check("a directory with no base/ours/theirs is refused",
      () => notAFixture?.exitCode === 1 && notAFixture.message.includes("no base/, ours/ or theirs/"));

    const out = join(scratch, "existing");
    generate({ fixture: "import-add-both", out });
    const again = refuse({ fixture: "import-add-both", out });
    check("an existing directory is not replaced without --force",
      () => again?.exitCode === 1 && again.message.includes("--force"));

    const rebuild = join(scratch, "rebuild");
    const first = generate({ fixture: "import-add-both", out: rebuild }).manifest;
    const second = generate({ fixture: "import-add-both", out: rebuild, force: true }).manifest;
    check("--force replaces it, reproducibly",
      () => first.commits.base === second.commits.base
        && first.commits.ours === second.commits.ours
        && first.commits.theirs === second.commits.theirs);
  });

  group("custom branch names", () => {
    const out = join(scratch, "named");
    generate({
      fixture: "import-add-both",
      out,
      baseBranch: "main",
      featureBranch: "topic",
      upstreamBranch: "trunk",
    });

    check("uses the requested branches", () => git(out, ["rev-parse", "--verify", "refs/heads/topic"]) === 0
      && git(out, ["rev-parse", "--verify", "refs/heads/trunk"]) === 0
      && git(out, ["rev-parse", "--verify", "refs/heads/main"]) === 0);
    check("checks out the requested feature branch",
      () => gitOutput(out, ["symbolic-ref", "--short", "HEAD"]).text === "topic");
    check("writes the marker for that branch",
      () => existsSync(join(out, ".jcodebuddy", "merge-history", "topic", "last-sync")));
    check("tracks the requested upstream",
      () => gitOutput(out, ["rev-parse", "--abbrev-ref", "topic@{upstream}"]).text
        === "origin/trunk");
  });

  group("every fixture in the tree is simulatable", () => {
    const fixtures = listFixtures();
    check("the fixture tree is not empty", () => fixtures.length > 0);
    for (const fixture of fixtures) {
      const out = join(scratch, "all", fixture.name);
      const { manifest } = generate({ fixture: fixture.name, out, json: true });
      check(`'${fixture.name}' builds a repository`,
        () => statSync(join(out, ".git")).isDirectory()
          && manifest.paths.length > 0);
      check(`'${fixture.name}' checks out a file that exists`,
        () => manifest.paths.every((path) => existsSync(join(out, path))));
      check(`'${fixture.name}' is findable by name`,
        () => resolveFixture(fixture.name) === fixture.directory);
    }
  });
} finally {
  rmSync(scratch, { recursive: true, force: true });
}

console.log(`\n${passed} passed, ${failed} failed`);
if (failed > 0) {
  console.log("failed:");
  for (const failure of failures) {
    console.log(`  - ${failure}`);
  }
  process.exit(1);
}