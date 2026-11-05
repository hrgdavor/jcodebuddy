#!/usr/bin/env bun
/**
 * E8 of {@code plans/hipster-entity-mvp.md}: the clean-consumer acceptance check.
 *
 * This is deliberately **not** a Maven-gate test. It needs a local install, a JDK 25 and Maven, and it
 * resolves the entity artifacts from `~/.m2` the way an outside project would. It builds a consumer-shaped
 * project under `.tmp/entity-mvp-check`, runs a full pass, compiles/runs the consumer, edits one view, runs
 * `--incremental`, compiles/runs again, and finishes with `--check`.
 *
 * It never puts a reactor `target/classes` on the generator's classpath: the generator is resolved from
 * the installed tooling jar and its installed dependencies, which is the consumer's view.
 */
import { spawnSync } from 'node:child_process';
import {
  cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { delimiter, join } from 'node:path';
import { repoRoot, resolveJdk25, resolveMaven, run, envWith } from './lib/toolchain.js';
import { reportDuration, stopwatch } from './lib/timing.js';

const REPO = repoRoot();
const VERSION = '1.0-SNAPSHOT';
const GROUP = 'hr.hrg.jcodebuddy';
const M2 = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const SCRATCH = join(REPO, '.tmp', 'entity-mvp-check');
const FIXTURE = join(REPO, 'hipster-entity', 'hipster-entity-tooling', 'src', 'test', 'resources',
  'mvp-consumer');

function artifactJar(name) {
  return join(M2, ...GROUP.split('.'), name, VERSION, `${name}-${VERSION}.jar`);
}

function fail(message, hint) {
  console.error(`[mvp] ${message}`);
  if (hint) {
    console.error(`[mvp] ${hint}`);
  }
  return 1;
}

function runChecked(command, args, options) {
  const result = run(command, args, { ...options, stdio: 'pipe' });
  if (result.status !== 0) {
    if (result.stdout) {
      console.error(result.stdout);
    }
    if (result.stderr) {
      console.error(result.stderr);
    }
    throw new Error(`${command} ${args.join(' ')} failed with ${result.status}`);
  }
  return result;
}

function readRecord(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function main() {
  const elapsed = stopwatch();
  const jdk = resolveJdk25();
  const maven = resolveMaven();
  const env = envWith(jdk);

  const required = ['hipster-entity-api', 'hipster-entity-core', 'hipster-entity-tooling'];
  for (const artifact of required) {
    if (!existsSync(artifactJar(artifact))) {
      return fail(`missing installed artifact ${GROUP}:${artifact}:${VERSION} in ${M2}`,
        'run: bun scripts/mvn-jdk25.js hipster-entity install "-DskipTests=true"');
    }
  }

  rmSync(SCRATCH, { recursive: true, force: true });
  mkdirSync(SCRATCH, { recursive: true });
  cpSync(FIXTURE, SCRATCH, { recursive: true });

  const sourceRoot = join(SCRATCH, 'src', 'main', 'java');
  const reportDir = join(SCRATCH, '.jcodebuddy', 'metadata', 'entity');
  const record = join(reportDir, 'generation.json');
  const consumerCp = join(SCRATCH, 'consumer-cp.txt');
  const toolingCp = join(SCRATCH, 'tooling-cp.txt');

  // A second, throwaway POM is only for the generator's own classpath. The consumer POM deliberately
  // does not depend on the tooling: an application must not ship it.
  const toolingPom = join(SCRATCH, 'tooling-pom.xml');
  writeFileSync(toolingPom, `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>gen.mvp</groupId><artifactId>tooling-cp</artifactId><version>1</version>
  <dependencies><dependency>
    <groupId>${GROUP}</groupId><artifactId>hipster-entity-tooling</artifactId><version>${VERSION}</version>
  </dependency></dependencies>
</project>
`);

  const mavenBase = ['-o', '-q', '-f', join(SCRATCH, 'pom.xml'), '-Dmaven.repo.local=' + M2];
  const generatorBase = ['-o', '-q', '-f', toolingPom, '-Dmaven.repo.local=' + M2];

  try {
    runChecked(maven.command, [...generatorBase, 'dependency:build-classpath',
      `-Dmdep.outputFile=${toolingCp}`], { env, cwd: SCRATCH });
    const generatorClasspath = `${artifactJar('hipster-entity-tooling')}${delimiter}${readFileSync(toolingCp, 'utf8').trim()}`;
    const generator = ['-cp', generatorClasspath, 'hr.hrg.hipster.entity.tooling.EntityMetadataGenerator'];
    const common = [sourceRoot, reportDir, '--java-out', sourceRoot, '--packages', 'gen.mvp',
      '--validate=STRICT', '--run-record', record];

    const fullStart = stopwatch();
    runChecked(jdk.java, [...generator, ...common, '--incremental'], { env, cwd: SCRATCH });
    const fullMs = fullStart();
    const full = readRecord(record);
    const stateFiles = join(SCRATCH, '.jcodebuddy', 'cache', 'entity-build', 'files.json');
    if (!existsSync(stateFiles)) {
      throw new Error(`the first pass did not publish its build state at ${stateFiles}`);
    }

    runChecked(maven.command, [...mavenBase, 'compile'], { env, cwd: SCRATCH });
    runChecked(maven.command, [...mavenBase, 'dependency:build-classpath',
      `-Dmdep.outputFile=${consumerCp}`], { env, cwd: SCRATCH });
    const consumerClasspath = `${join(SCRATCH, 'target', 'classes')}${delimiter}${readFileSync(consumerCp, 'utf8').trim()}`;
    const firstRun = run(jdk.java, ['-cp', consumerClasspath, 'gen.mvp.Main'], { env, stdio: 'pipe' });
    if (firstRun.status !== 0 || !firstRun.stdout.includes('Ada Lovelace')) {
      throw new Error(`the consumer main did not run: ${firstRun.stdout}${firstRun.stderr}`);
    }

    const detail = join(sourceRoot, 'gen', 'mvp', 'PersonDetail.java');
    writeFileSync(detail, readFileSync(detail, 'utf8').replace('String email();',
      'String email();\n    String nickname();'));

    const incrementalStart = stopwatch();
    runChecked(jdk.java, [...generator, ...common, '--incremental'], { env, cwd: SCRATCH });
    const incrementalMs = incrementalStart();
    const incremental = readRecord(record);
    if (incremental.mode !== 'incremental') {
      throw new Error(`the edited consumer did not run incrementally: ${JSON.stringify(incremental)}`);
    }
    if (!incremental.affected.some((name) => name.endsWith('PersonDetail'))) {
      throw new Error(`the affected list does not name PersonDetail: ${JSON.stringify(incremental.affected)}`);
    }

    runChecked(maven.command, [...mavenBase, 'compile'], { env, cwd: SCRATCH });
    const secondRun = run(jdk.java, ['-cp', consumerClasspath, 'gen.mvp.Main'], { env, stdio: 'pipe' });
    if (secondRun.status !== 0 || !secondRun.stdout.includes('Ada Lovelace')) {
      throw new Error(`the consumer main did not run after incremental: ${secondRun.stdout}${secondRun.stderr}`);
    }

    const checkStart = stopwatch();
    const check = run(jdk.java, [...generator, ...common.slice(0, -2), '--check'], { env, stdio: 'pipe' });
    const checkMs = checkStart();
    if (check.status !== 0) {
      throw new Error(`--check rejected the consumer tree: ${check.stdout}${check.stderr}`);
    }

    console.log(`[mvp] full pass: ${full.mode}, emitted ${full.emitted}, reused ${full.reused}`);
    console.log(`[mvp] incremental: ${incremental.mode}, affected ${incremental.affected.join(', ')},`
      + ` emitted ${incremental.emitted}, reused ${incremental.reused}`);
    console.log(`[mvp] consumer main ran before and after the edit; --check is current`);
    console.log(reportDuration(elapsed(), {
      what: 'MVP consumer check passed',
      detail: `(full ${fullMs.toFixed(0)}ms, incremental ${incrementalMs.toFixed(0)}ms, check ${checkMs.toFixed(0)}ms)`,
    }));
    return 0;
  } catch (error) {
    return fail(error.message, `scratch tree left at ${SCRATCH}`);
  }
}

try {
  process.exit(main());
} catch (error) {
  console.error(`[mvp] ${error.message}`);
  process.exit(1);
}
