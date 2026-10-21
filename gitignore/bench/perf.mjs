#!/usr/bin/env bun
// The workloads, and a runner that measures any port against them.
//
//   bun bench/perf.mjs --emit                 write the workloads to .tmp/workloads/
//   bun bench/perf.mjs --port "zig/zig-out/bin/ignore-decide" [--repeat 5]
//
// A workload is a rule file and a path list; a port is any command that reads them and prints one verdict per path
// (include | exclude | no-opinion). Every port is measured on identical bytes, which is the only way "my Java matcher
// is fast" becomes a number that means something.
//
// The shapes are the ones that broke the first implementations, generated so that **no checkout of Apache Fory or any
// other project is required**:
//
//   flat       1,000 paths, 8 literal rules                     the easy case
//   deep       2,000 paths, 14 segments, 8 rules                where a naive matcher starts to hurt
//   doublestar 2,000 paths, 146 rules, 50 of them `**/x`        the real .gitignore that cost 2.97x
//   anchored   2,000 paths, 60 anchored `dir/sub/` rules        the class that needed the first-segment prune
//   dirs       1,000 paths in 100 folders, 40 dir-only rules    the excluded-directory path
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

const out = '.tmp/workloads';

function* generated() {
  // flat
  {
    const rules = ['*.log', '*.tmp', 'build/', 'dist/', '/target', '!keep.log', 'node_modules/', '.idea/'];
    const paths = Array.from({ length: 1000 }, (_, i) => `src/file${i}.${i % 7 === 0 ? 'log' : 'java'}`);
    yield { name: 'flat', rules, paths };
  }
  // deep: 14 segments, like a Java package tree
  {
    const rules = ['*.class', '*.jar', 'target/', '.gradle/', '*.iml', '/build', 'out/', '!*.class.txt'];
    const paths = Array.from({ length: 2000 }, (_, i) => {
      const segments = ['src', 'main', 'java', 'org', 'apache', 'fory', `pkg${i % 40}`, 'impl', 'internal', 'util', 'io', 'mem', 'codec', `File${i}.java`];
      return segments.join('/');
    });
    yield { name: 'deep', rules, paths };
  }
  // doublestar: the Fory-shaped rule set that made the matcher superlinear
  {
    const rules = ['# build', '/target', '*.class', '*.jar', '*.so', '*.dll', '*.dylib'];
    for (const ext of ['cpp', 'h', 'hpp', 'c', 'cc', 'rs', 'go', 'py', 'js', 'ts', 'kt']) rules.push(`**/*.${ext}`);
    for (const name of ['target', 'build', 'out', 'bazel-out', 'bazel-bin', '.bazel_user_root', 'cmake-build-debug', 'node_modules', '.idea', '.gradle', '.cxx']) rules.push(`**/${name}`);
    for (let i = 0; i < 100; i += 1) rules.push(`java/fory-${i}/src/main/resources/generated/`);
    const paths = Array.from({ length: 2000 }, (_, i) => {
      const ext = i % 3 === 0 ? 'cpp' : i % 3 === 1 ? 'java' : 'md';
      return `java/fory-${i % 100}/src/main/${i % 5 === 0 ? 'resources/generated/' : ''}File${i}.${ext}`;
    });
    yield { name: 'doublestar', rules, paths };
  }
  // anchored
  {
    const rules = Array.from({ length: 60 }, (_, i) => `module${i}/src/main/java/`);
    const paths = Array.from({ length: 2000 }, (_, i) => `module${i % 80}/src/main/java/F${i}.java`);
    yield { name: 'anchored', rules, paths };
  }
  // dirs
  {
    const rules = Array.from({ length: 40 }, (_, i) => `pkg${i}/`);
    const paths = Array.from({ length: 1000 }, (_, i) => `pkg${i % 100}/file${i}.txt`);
    yield { name: 'dirs', rules, paths };
  }
}

const emit = process.argv.includes('--emit');
const port_at = process.argv.indexOf('--port');
const port = port_at >= 0 ? process.argv[port_at + 1] : null;
const repeat_at = process.argv.indexOf('--repeat');
const repeat = repeat_at >= 0 ? Number(process.argv[repeat_at + 1] ?? 5) : 5;

mkdirSync(out, { recursive: true });
const workloads = [];
for (const workload of generated()) {
  const rules_path = `${out}/${workload.name}.gitignore`;
  const paths_path = `${out}/${workload.name}.paths`;
  writeFileSync(rules_path, workload.rules.join('\n') + '\n');
  writeFileSync(paths_path, workload.paths.join('\n') + '\n');
  workloads.push({ ...workload, rules_path, paths_path });
  console.log(`${workload.name.padEnd(11)} ${String(workload.rules.length).padStart(4)} rules  ${String(workload.paths.length).padStart(5)} paths`);
}

if (emit || !port) {
  console.log(`\nwritten to ${out}/; point a port at them with --port "<command> --rules <file.gitignore> --paths <file.paths>"`);
  process.exit(0);
}

// A port is measured by wall time over the whole path list, best of `repeat`, and the number is ns per decision.
const template = port.split(' ').filter(Boolean);
console.log('\n| workload | port | best ms | ns/decision |');
console.log('| -------- | ---- | ------: | ----------: |');
for (const workload of workloads) {
  let best = Infinity;
  for (let attempt = 0; attempt < repeat; attempt += 1) {
    const args = template.map((part) => part.replace('{rules}', workload.rules_path).replace('{paths}', workload.paths_path));
    const started = performance.now();
    const result = spawnSync(args[0], args.slice(1), { stdio: ['ignore', 'pipe', 'inherit'] });
    const elapsed = performance.now() - started;
    if (result.status !== 0) {
      console.log(`| ${workload.name} | ${template[0]} | failed (${result.status}) | |`);
      break;
    }
    const lines = String(result.stdout ?? '').trim().split('\n').filter(Boolean).length;
    if (lines !== workload.paths.length) {
      console.log(`| ${workload.name} | ${template[0]} | verdict count ${lines} != ${workload.paths.length} | |`);
      break;
    }
    best = Math.min(best, elapsed);
  }
  if (Number.isFinite(best)) {
    console.log(`| ${workload.name} | ${template[0].split('/').pop()} | ${best.toFixed(1)} | ${((best * 1e6) / workload.paths.length).toFixed(0)} |`);
  }
}