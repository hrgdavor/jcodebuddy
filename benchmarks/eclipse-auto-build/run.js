#!/usr/bin/env bun
/**
 * run.js — reproducible Eclipse auto-build benchmark driver.
 *
 * What it does, in order:
 *
 *   1. compiles src/bench/BenchApplication.java with a JDK 25 `javac --release 21` against
 *      `<ECLIPSE_HOME>/plugins/*`;
 *   2. jars the result with its manifest (META-INF/MANIFEST.MF) and plugin.xml;
 *   3. installs that jar as `<ECLIPSE_HOME>/plugins/bench.app.jar` for the run, and removes it
 *      again afterwards — plus the `bench.app` line in the install's
 *      configuration/org.eclipse.equinox.simpleconfigurator/bundles.info, which is what makes
 *      the bundle visible to this installation (bucket 4 = start level 4, not auto-started);
 *   4. creates a fresh workspace `.tmp/eclipse-auto-build/ws-<classes>-<run>`;
 *   5. launches
 *        eclipse.exe -data <ws> -nosplash -consoleLog -clean -application bench.app.app
 *                    -vmargs -Dbench.classes=<N> -Dbench.result=<f> -Dbench.ready=<f>
 *      waits for the result JSON, and kills Eclipse when the timeout expires;
 *   6. prints one machine-readable JSON line per run plus a short summary, and restores the
 *      installation.
 *
 * Configuration: ECLIPSE_HOME (default D:\programs\eclipse), BENCH_JDK_HOME or JDK25_HOME
 * (default C:\Program Files\Java\jdk-25). Everything else is a flag — see --help.
 *
 * Runs must not overlap: the bundle is installed into a shared Eclipse installation for the
 * duration of the run. The driver takes a lock file under .tmp/eclipse-auto-build to enforce it.
 *
 * Bun/Node APIs only; no shell wrapper, no .ps1/.cmd/.sh.
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync, rmSync, renameSync, readdirSync,
	statSync } from "node:fs";
import { join, resolve, relative, extname } from "node:path";
import { createHash } from "node:crypto";

const HERE = import.meta.dir;
const REPO_ROOT = resolve(HERE, "..", "..");
const TMP_ROOT = join(REPO_ROOT, ".tmp", "eclipse-auto-build");
const BUILD_ROOT = join(TMP_ROOT, "build");
const BACKUP_ROOT = join(TMP_ROOT, "install-backup");
const LOCK_FILE = join(TMP_ROOT, "install.lock");

const DEFAULT_ECLIPSE_HOME = "D:\\programs\\eclipse";
const DEFAULT_JDK_HOME = "C:\\Program Files\\Java\\jdk-25";
const DEFAULT_JDK_SCAN = "C:\\Program Files\\Java";

const BUNDLE_JAR = "bench.app.jar";
const BUNDLE_ENTRY = "bench.app,1.0.0,plugins/bench.app.jar,4,false";
const BUNDLE_SYMBOLIC_NAME = "bench.app";
const APPLICATION_ID = "bench.app.app";

const BUNDLES_INFO = ["configuration", "org.eclipse.equinox.simpleconfigurator", "bundles.info"];

const DEFAULT_CLASSES = 42;
const DEFAULT_APP_TIMEOUT_MS = 120_000;
/** Eclipse start-up (with -clean) is not part of the app's own budget, so the kill is later. */
const STARTUP_ALLOWANCE_MS = 30_000;
const LOCK_STALE_MS = 30 * 60_000;

const USAGE = `
Usage: bun run benchmarks/eclipse-auto-build/run.js [options]

  --classes <N>        class count to measure; repeatable (default ${DEFAULT_CLASSES})
  --repeat <N>         run each class count N times (default 1)
  --timeout <ms>       the application's own budget, passed as -Dbench.timeoutMs
                       (default ${DEFAULT_APP_TIMEOUT_MS})
  --kill-timeout <ms>  driver-side kill timeout (default: timeout + ${STARTUP_ALLOWANCE_MS})
  --eclipse-home <dir> Eclipse installation (default $ECLIPSE_HOME or ${DEFAULT_ECLIPSE_HOME})
  --jdk <dir>          JDK 25 home used for javac/jar (default $BENCH_JDK_HOME, $JDK25_HOME,
                       ${DEFAULT_JDK_HOME})
  --keep-workspaces    keep each -data workspace after the run (default: keep)
  --clean-workspaces   delete each -data workspace after the run
  --force-unlock       remove a stale install lock instead of refusing to start
  --verbose            echo the Eclipse console of every run
  --help               this text
`.trim();

class BenchError extends Error {}

// ---------------------------------------------------------------------------- arguments

function parseArgs(argv) {
	const options = {
		classes: [],
		repeat: 1,
		appTimeoutMs: DEFAULT_APP_TIMEOUT_MS,
		killTimeoutMs: null,
		eclipseHome: null,
		jdkHome: null,
		keepWorkspaces: true,
		forceUnlock: false,
		verbose: false,
	};
	for (let index = 0; index < argv.length; index++) {
		const flag = argv[index];
		const next = () => {
			const value = argv[++index];
			if (value === undefined) {
				throw new BenchError(`${flag} needs a value`);
			}
			return value;
		};
		switch (flag) {
			case "--classes":
				options.classes.push(parsePositiveInt(flag, next()));
				break;
			case "--repeat":
				options.repeat = parsePositiveInt(flag, next());
				break;
			case "--timeout":
				options.appTimeoutMs = parsePositiveInt(flag, next());
				break;
			case "--kill-timeout":
				options.killTimeoutMs = parsePositiveInt(flag, next());
				break;
			case "--eclipse-home":
				options.eclipseHome = next();
				break;
			case "--jdk":
				options.jdkHome = next();
				break;
			case "--keep-workspaces":
				options.keepWorkspaces = true;
				break;
			case "--clean-workspaces":
				options.keepWorkspaces = false;
				break;
			case "--force-unlock":
				options.forceUnlock = true;
				break;
			case "--verbose":
				options.verbose = true;
				break;
			case "--help":
			case "-h":
				console.log(USAGE);
				process.exit(0);
				break;
			default:
				throw new BenchError(`unknown argument: ${flag}\n\n${USAGE}`);
		}
	}
	if (options.classes.length === 0) {
		options.classes.push(DEFAULT_CLASSES);
	}
	if (options.killTimeoutMs === null) {
		options.killTimeoutMs = options.appTimeoutMs + STARTUP_ALLOWANCE_MS;
	}
	return options;
}

function parsePositiveInt(flag, value) {
	const number = Number(value);
	if (!Number.isInteger(number) || number < 1) {
		throw new BenchError(`${flag} needs a positive integer, got ${JSON.stringify(value)}`);
	}
	return number;
}

// ---------------------------------------------------------------------------- toolchain

function resolveEclipseHome(options) {
	const home = resolve(
		options.eclipseHome ?? process.env.ECLIPSE_HOME ?? DEFAULT_ECLIPSE_HOME);
	const eclipseExe = join(home, "eclipse.exe");
	if (!existsSync(eclipseExe)) {
		throw new BenchError(
			`eclipse.exe not found at ${eclipseExe}. Set ECLIPSE_HOME or pass --eclipse-home.`);
	}
	const plugins = join(home, "plugins");
	if (!existsSync(plugins)) {
		throw new BenchError(`no plugins directory at ${plugins}`);
	}
	return { home, eclipseExe, plugins };
}

function resolveJdk(options) {
	const candidates = [
		options.jdkHome,
		process.env.BENCH_JDK_HOME,
		process.env.JDK25_HOME,
		DEFAULT_JDK_HOME,
		...scanJdkDirectories(DEFAULT_JDK_SCAN),
	].filter(Boolean);
	const tried = [];
	for (const candidate of candidates) {
		const home = resolve(candidate);
		const javac = join(home, "bin", "javac.exe");
		if (!existsSync(javac)) {
			tried.push(`${home} (no bin/javac.exe)`);
			continue;
		}
		const version = runTool(javac, ["-version"]);
		const match = /javac (\d+)/.exec(version);
		if (!match) {
			tried.push(`${home} (unreadable javac version: ${version.trim()})`);
			continue;
		}
		const major = Number(match[1]);
		if (major !== 25) {
			tried.push(`${home} (javac ${major}, need 25)`);
			continue;
		}
		return { home, javac: join(home, "bin", "javac.exe"), jar: join(home, "bin", "jar.exe"),
			version: version.trim() };
	}
	throw new BenchError(
		`no JDK 25 found. Tried:\n  ${tried.join("\n  ")}\n`
		+ "Set BENCH_JDK_HOME (or JDK25_HOME) to a JDK 25 home, or pass --jdk <dir>.");
}

function scanJdkDirectories(root) {
	if (!existsSync(root)) {
		return [];
	}
	try {
		return readdirSync(root)
			.filter((name) => /^jdk-?25/.test(name))
			.sort()
			.reverse()
			.map((name) => join(root, name));
	} catch {
		return [];
	}
}

function runTool(executable, args) {
	const result = Bun.spawnSync([executable, ...args], { stdout: "pipe", stderr: "pipe" });
	const text = new TextDecoder().decode(result.stdout) + new TextDecoder().decode(result.stderr);
	if (result.exitCode !== 0) {
		throw new BenchError(`${executable} ${args.join(" ")} failed (${result.exitCode}):\n${text}`);
	}
	return text;
}

// ---------------------------------------------------------------------------- building the bundle

function findJavaSources(root) {
	const sources = [];
	const walk = (directory) => {
		for (const entry of readdirSync(directory, { withFileTypes: true })) {
			const full = join(directory, entry.name);
			if (entry.isDirectory()) {
				walk(full);
			} else if (extname(entry.name) === ".java") {
				sources.push(full);
			}
		}
	};
	walk(root);
	return sources.sort();
}

function buildBundle(jdk, eclipse) {
	const classesDir = join(BUILD_ROOT, "classes");
	rmSync(classesDir, { recursive: true, force: true });
	mkdirSync(classesDir, { recursive: true });

	const sources = findJavaSources(join(HERE, "src"));
	if (sources.length === 0) {
		throw new BenchError(`no .java sources under ${join(HERE, "src")}`);
	}
	const classpath = join(eclipse.plugins, "*");
	const javacArgs = ["--release", "21", "-encoding", "UTF-8", "-nowarn", "-d", classesDir,
		"-cp", classpath, ...sources];
	runTool(jdk.javac, javacArgs);

	const jarPath = join(BUILD_ROOT, BUNDLE_JAR);
	rmSync(jarPath, { force: true });
	runTool(jdk.jar, ["--create", "--file", jarPath,
		"--manifest", join(HERE, "META-INF", "MANIFEST.MF"),
		"-C", classesDir, ".", "-C", HERE, "plugin.xml"]);

	const entries = runTool(jdk.jar, ["--list", "--file", jarPath])
		.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
	for (const required of ["META-INF/MANIFEST.MF", "plugin.xml",
		"bench/BenchApplication.class"]) {
		if (!entries.includes(required)) {
			throw new BenchError(`the built jar is missing ${required}; entries were: `
				+ entries.join(", "));
		}
	}
	// OSGi reads the manifest whatever its position; the check is that it is there and that the
	// classes are not listed ahead of it (a jar written by hand fails this).
	const manifestIndex = entries.indexOf("META-INF/MANIFEST.MF");
	const firstClassIndex = entries.findIndex((entry) => entry.endsWith(".class"));
	if (manifestIndex === -1 || (firstClassIndex !== -1 && firstClassIndex < manifestIndex)) {
		throw new BenchError(`the manifest is missing or misplaced in ${jarPath}; `
			+ `entries were: ${entries.join(", ")}`);
	}

	return {
		classesDir,
		jarPath,
		jarBytes: statSync(jarPath).size,
		jarSha256: createHash("sha256").update(readFileSync(jarPath)).digest("hex").slice(0, 16),
		javacArgs,
		entries,
	};
}

// ---------------------------------------------------------------------------- install / restore

/**
 * Installs the bundle into the Eclipse installation for the run.
 *
 * Two files change, and both are restored afterwards:
 *   - plugins/bench.app.jar            the bundle itself;
 *   - configuration/.../bundles.info   the list simpleconfigurator starts bundles from.
 * The second one is not optional for this installation: a jar that is merely present in
 * plugins/ is never resolved (dropins is not honoured by this install either — its p2
 * reconciler reads a dropin directory as a repository and fails on a bare jar).
 */
function installBundle(eclipse, bundle, options) {
	const installedJar = join(eclipse.plugins, BUNDLE_JAR);
	const bundlesInfoPath = join(eclipse.home, ...BUNDLES_INFO);
	if (!existsSync(bundlesInfoPath)) {
		throw new BenchError(
			`${bundlesInfoPath} is missing; this installation does not use simpleconfigurator, `
			+ "so the benchmark's install step does not apply to it.");
	}
	mkdirSync(BACKUP_ROOT, { recursive: true });

	const state = {
		installedJar,
		bundlesInfoPath,
		preExistingJar: null,
		bundlesInfoBackup: null,
		bundlesInfoHashBefore: sha256(readFileSync(bundlesInfoPath)),
	};

	// A jar left behind by an interrupted run is moved aside rather than restored.
	if (existsSync(installedJar)) {
		const stale = join(BACKUP_ROOT, `preexisting-bench.app.jar.${stamp()}`);
		renameSync(installedJar, stale);
		state.preExistingJar = stale;
	}

	const original = readFileSync(bundlesInfoPath, "utf8");
	const eol = original.includes("\r\n") ? "\r\n" : "\n";
	const withoutEntry = stripBundleEntry(original, eol);
	state.bundlesInfoBackup = join(BACKUP_ROOT, "bundles.info");
	writeFileSync(state.bundlesInfoBackup, withoutEntry);

	// Install.
	copyFileSync(bundle.jarPath, installedJar);
	writeFileSync(bundlesInfoPath, addBundleEntry(withoutEntry, eol));

	const installed = readFileSync(bundlesInfoPath, "utf8");
	if (!installed.includes(`${BUNDLE_SYMBOLIC_NAME},`)) {
		throw new BenchError(`could not register ${BUNDLE_SYMBOLIC_NAME} in ${bundlesInfoPath}`);
	}
	return state;
}

function stripBundleEntry(text, eol) {
	const lines = text.split(/\r?\n/);
	return lines.filter((line) => !line.startsWith(`${BUNDLE_SYMBOLIC_NAME},`)).join(eol);
}

function addBundleEntry(text, eol) {
	const lines = text.split(/\r?\n/);
	if (lines.some((line) => line.startsWith(`${BUNDLE_SYMBOLIC_NAME},`))) {
		return text;
	}
	const index = lines.findIndex((line) => {
		if (line.trim() === "" || line.startsWith("#")) {
			return false;
		}
		return line.split(",")[0].trim().localeCompare(BUNDLE_SYMBOLIC_NAME) > 0;
	});
	const at = index === -1 ? lines.length : index;
	lines.splice(at, 0, BUNDLE_ENTRY);
	return lines.join(eol);
}

function uninstallBundle(state) {
	const report = { jarRemoved: false, bundlesInfoRestored: false, keptJar: false, problem: null };
	try {
		if (existsSync(state.installedJar)) {
			rmSync(state.installedJar, { force: true });
		}
		report.jarRemoved = !existsSync(state.installedJar);
		if (state.preExistingJar) {
			report.keptJar = true;
		}
	} catch (failure) {
		report.problem = `could not remove ${state.installedJar}: ${failure.message}`;
	}
	try {
		// Restore the file without our line: that is the state we found, and it also clears a
		// residue an earlier interrupted run may have left in it. Line endings are kept.
		const backup = readFileSync(state.bundlesInfoBackup, "utf8");
		const restored = stripBundleEntry(backup, backup.includes("\r\n") ? "\r\n" : "\n");
		writeFileSync(state.bundlesInfoPath, restored);
		const after = readFileSync(state.bundlesInfoPath, "utf8");
		report.bundlesInfoRestored = !after.includes(`${BUNDLE_SYMBOLIC_NAME},`);
		report.bundlesInfoMatchesBackup = sha256(restored) === state.bundlesInfoHashBefore;
	} catch (failure) {
		report.problem = `${report.problem ? report.problem + "; " : ""}`
			+ `could not restore ${state.bundlesInfoPath}: ${failure.message}`;
	}
	return report;
}

// ---------------------------------------------------------------------------- lock

function acquireLock(options) {
	mkdirSync(TMP_ROOT, { recursive: true });
	if (existsSync(LOCK_FILE)) {
		const ageMs = Date.now() - statSync(LOCK_FILE).mtimeMs;
		const holder = readLockHolder();
		const alive = holder && isProcessAlive(holder.pid);
		if (ageMs < LOCK_STALE_MS && alive && !options.forceUnlock) {
			throw new BenchError(
				`another run holds the install lock ${LOCK_FILE} (pid ${holder.pid}, `
				+ `${Math.round(ageMs / 1000)}s old). Wait for it, or pass --force-unlock.`);
		}
		rmSync(LOCK_FILE, { force: true });
	}
	writeFileSync(LOCK_FILE, JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }));
	return () => rmSync(LOCK_FILE, { force: true });
}

function readLockHolder() {
	try {
		return JSON.parse(readFileSync(LOCK_FILE, "utf8"));
	} catch {
		return null;
	}
}

function isProcessAlive(pid) {
	if (!Number.isInteger(pid)) {
		return false;
	}
	try {
		process.kill(pid, 0);
		return true;
	} catch {
		return false;
	}
}

// ---------------------------------------------------------------------------- utilities

function sha256(bytes) {
	return createHash("sha256").update(bytes).digest("hex");
}

function stamp() {
	const now = new Date();
	const pad = (value, width = 2) => String(value).padStart(width, "0");
	return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
		+ `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

function shortId() {
	return Math.random().toString(36).slice(2, 8);
}

function copyFileSync(source, destination) {
	writeFileSync(destination, readFileSync(source));
}

function millis(nanos) {
	return nanos / 1_000_000;
}

function formatMillis(nanos) {
	return `${millis(nanos).toFixed(2)} ms`;
}

function fail(message) {
	throw new BenchError(message);
}

// ---------------------------------------------------------------------------- one run

async function runOnce(options, eclipse, classes, runId) {
	const workspace = join(TMP_ROOT, `ws-${classes}-${runId}`);
	const resultFile = `${workspace}.result.json`;
	const readyFile = `${workspace}.ready`;
	const consoleFile = `${workspace}.console.log`;
	rmSync(workspace, { recursive: true, force: true });
	rmSync(resultFile, { force: true });
	rmSync(readyFile, { force: true });
	mkdirSync(workspace, { recursive: true });

	// -vmargs must come last: everything after it belongs to the JVM, not to Eclipse.
	const argv = [
		eclipse.eclipseExe,
		"-data", workspace,
		"-nosplash",
		"-consoleLog",
		"-clean",
		"-application", APPLICATION_ID,
		"-vmargs",
		`-Dbench.classes=${classes}`,
		`-Dbench.result=${resultFile}`,
		`-Dbench.ready=${readyFile}`,
		`-Dbench.timeoutMs=${options.appTimeoutMs}`,
	];

	const startedAt = Date.now();
	const proc = Bun.spawn(argv, { stdout: "pipe", stderr: "pipe", stdin: "ignore" });
	const consoleText = { value: "", truncated: false };
	const capture = (chunk) => {
		if (consoleText.value.length < 2_000_000) {
			consoleText.value += chunk;
		} else {
			consoleText.truncated = true;
		}
	};
	const pumps = [
		pump(proc.stdout, capture),
		pump(proc.stderr, capture),
	];

	const failurePattern =
		/could not be found in the registry|Plug-in bench\.app was unable to load class|UnsupportedClassVersionError/;
	const deadline = startedAt + options.killTimeoutMs;
	let result = null;
	let timedOut = false;
	let killed = false;
	let failureHint = null;
	let readyAt = null;

	while (true) {
		if (result === null && existsSync(resultFile)) {
			try {
				result = JSON.parse(readFileSync(resultFile, "utf8"));
			} catch {
				// A partially written file: try again on the next turn.
			}
		}
		if (result !== null) {
			break;
		}
		if (readyAt === null && existsSync(readyFile)) {
			readyAt = Date.now();
		}
		if (failureHint === null) {
			const match = failurePattern.exec(consoleText.value);
			if (match) {
				failureHint = match[0];
				killed = true;
				killTree(proc);
				break;
			}
		}
		if (Date.now() > deadline) {
			timedOut = true;
			killed = true;
			killTree(proc);
			break;
		}
		if (proc.exitCode !== null) {
			// The process is gone and no result appeared: give a last write a moment, then stop.
			await Bun.sleep(200);
			if (existsSync(resultFile)) {
				try {
					result = JSON.parse(readFileSync(resultFile, "utf8"));
				} catch {
					result = null;
				}
			}
			break;
		}
		await Bun.sleep(50);
	}

	if (!killed) {
		const exited = await waitForExit(proc, 20_000);
		if (!exited) {
			killed = true;
			killTree(proc);
			await waitForExit(proc, 5_000);
		}
	} else {
		await waitForExit(proc, 10_000);
	}
	await Promise.all(pumps);
	const wallMillis = Date.now() - startedAt;
	const exitCode = proc.exitCode;
	writeFileSync(consoleFile, consoleText.value + (consoleText.truncated
		? "\n[run.js] console output truncated at 2 MB\n" : ""));

	const run = {
		classes,
		runId,
		ok: result !== null && result.ok === true && result.classFileChanged === true,
		result,
		timedOut,
		killed,
		failureHint,
		exitCode,
		wallMillis,
		readyAt,
		workspace,
		resultFile,
		consoleFile,
		argv,
		consoleText: consoleText.value,
	};
	if (!options.keepWorkspaces) {
		rmSync(workspace, { recursive: true, force: true });
	}
	return run;
}

function killTree(proc) {
	try {
		proc.kill();
	} catch {
		// Already gone.
	}
	try {
		Bun.spawnSync(["taskkill", "/PID", String(proc.pid), "/T", "/F"],
			{ stdout: "ignore", stderr: "ignore" });
	} catch {
		// taskkill is a Windows convenience; proc.kill() above is the portable part.
	}
}

async function waitForExit(proc, timeoutMs) {
	return await Promise.race([
		proc.exited.then(() => true),
		Bun.sleep(timeoutMs).then(() => false),
	]);
}

async function pump(stream, sink) {
	const reader = stream.getReader();
	const decoder = new TextDecoder();
	try {
		while (true) {
			const { done, value } = await reader.read();
			if (done) {
				return;
			}
			sink(decoder.decode(value, { stream: true }));
		}
	} catch {
		// The stream closed with the process.
	}
}

// ---------------------------------------------------------------------------- output

function printRun(run) {
	console.log(`bench.result ${JSON.stringify(payloadOf(run))}`);
}

function payloadOf(run) {
	const result = run.result ?? {};
	const buildNanos = typeof result.buildNanos === "number" ? result.buildNanos : -1;
	return {
		schema: "eclipse-auto-build/1",
		ok: run.ok,
		classes: run.classes,
		editNanos: result.editNanos ?? -1,
		buildNanos,
		buildMillis: toMillis(buildNanos),
		queueMillis: toMillis(result.queueNanos),
		builderMillis: toMillis(result.builderNanos),
		buildKind: result.buildKind ?? -1,
		classFileChanged: result.classFileChanged ?? false,
		classFileStampChanged: result.classFileStampChanged ?? false,
		workspace: run.workspace,
		initialBuildMillis: toMillis(result.initialBuildNanos),
		initialErrors: result.initialErrors ?? -1,
		initialClassFiles: result.initialClassFiles ?? -1,
		startupMillis: toMillis(result.startupNanos),
		wallMillis: run.wallMillis,
		timedOut: run.timedOut,
		killed: run.killed,
		failureHint: run.failureHint,
		eclipseExitCode: run.exitCode,
		resultFile: run.resultFile,
		consoleLog: run.consoleFile,
		command: run.argv,
		error: result.error ?? null,
	};
}

function toMillis(nanos) {
	return typeof nanos === "number" && nanos >= 0 ? Number(millis(nanos).toFixed(3)) : -1;
}

function printSummary(runs, cleanup) {
	console.log("");
	console.log("summary");
	console.log(`  eclipse:      ${SHOWN.eclipseHome}`);
	console.log(`  jdk:          ${SHOWN.jdk}`);
	console.log(`  bundle:       ${SHOWN.bundle}`);
	console.log(`  runs:         ${runs.length}`);
	for (const run of runs) {
		if (!run.ok) {
			const why = run.timedOut ? "timed out"
				: run.failureHint ? `eclipse said: ${run.failureHint}`
					: run.result?.error ? run.result.error
						: `no result (exit code ${run.exitCode})`;
			console.log(`  FAILED  N=${run.classes} run=${run.runId}: ${why}`);
			console.log(`          console: ${relative(REPO_ROOT, run.consoleFile)}`);
			continue;
		}
		const result = run.result;
		console.log(`  N=${String(run.classes).padEnd(4)} `
			+ `edit->POST_BUILD ${formatMillis(result.buildNanos).padStart(12)}`
			+ ` (queued ${formatMillis(result.queueNanos).padStart(9)},`
			+ ` builder ${formatMillis(result.builderNanos).padStart(9)})`
			+ `   initial FULL_BUILD ${formatMillis(result.initialBuildNanos).padStart(12)}`
			+ `   classFileChanged=${result.classFileChanged}`
			+ `   startup ${(millis(result.startupNanos) / 1000).toFixed(1)}s`);
	}
	const measured = runs.filter((run) => run.ok).map((run) => run.result.buildNanos);
	if (measured.length > 1) {
		const sorted = [...measured].sort((left, right) => left - right);
		const median = sorted[Math.floor(sorted.length / 2)];
		console.log(`  spread:       min ${formatMillis(sorted[0])}`
			+ `, median ${formatMillis(median)}, max ${formatMillis(sorted[sorted.length - 1])}`);
	}
	console.log("");
	console.log("cleanup");
	if (cleanup === null) {
		console.log("  nothing was installed (the run stopped before the install step)");
	} else {
		console.log(`  plugins/${BUNDLE_JAR}:        `
			+ `${cleanup.jarRemoved ? "removed" : "STILL PRESENT"}`
			+ (cleanup.preExistingJar
				? ` (a pre-existing jar was moved to ${relative(REPO_ROOT, cleanup.preExistingJar)})`
				: ""));
		console.log(`  bundles.info:      `
			+ `${cleanup.bundlesInfoRestored ? "restored" : "STILL CONTAINS " + BUNDLE_SYMBOLIC_NAME}`
			+ (cleanup.bundlesInfoMatchesBackup === false
				? " (differs from the pre-existing file: it already held a bench.app line)"
				: ""));
		if (cleanup.problem) {
			console.log(`  PROBLEM: ${cleanup.problem}`);
		}
	}
	if (runs.length > 0) {
		console.log("");
		console.log("command");
		console.log(`  ${formatCommand(runs[runs.length - 1].argv)}`);
	}
}

function formatCommand(argv) {
	return argv.map((part) => (/\s/.test(part) ? `"${part}"` : part)).join(" ");
}

// ---------------------------------------------------------------------------- main

const SHOWN = { eclipseHome: "", jdk: "", bundle: "" };

async function main() {
	const options = parseArgs(process.argv.slice(2));
	const eclipse = resolveEclipseHome(options);
	const jdk = resolveJdk(options);
	SHOWN.eclipseHome = eclipse.home;
	SHOWN.jdk = `${jdk.home} (${jdk.version})`;
	mkdirSync(TMP_ROOT, { recursive: true });

	const releaseLock = acquireLock(options);
	const runs = [];
	let cleanup = null;
	let exitCode = 0;
	try {
		const bundle = buildBundle(jdk, eclipse);
		SHOWN.bundle = `${relative(REPO_ROOT, bundle.jarPath)} `
			+ `(${bundle.jarBytes} bytes, sha256 ${bundle.jarSha256})`;
		console.log(`eclipse:  ${eclipse.home}`);
		console.log(`jdk:      ${jdk.home} (${jdk.version})`);
		console.log(`bundle:   ${relative(REPO_ROOT, bundle.jarPath)} `
			+ `(${bundle.jarBytes} bytes, sha256 ${bundle.jarSha256})`);
		const state = installBundle(eclipse, bundle, options);
		console.log(`installed ${state.installedJar}`);
		console.log(`registered ${BUNDLE_SYMBOLIC_NAME} in ${state.bundlesInfoPath}`);
		try {
			for (const classes of options.classes) {
				for (let repetition = 0; repetition < options.repeat; repetition++) {
					const runId = `${shortId()}${options.repeat > 1 ? `-r${repetition + 1}` : ""}`;
					const run = await runOnce(options, eclipse, classes, runId);
					runs.push(run);
					printRun(run);
					if (options.verbose) {
						console.log(`--- eclipse console (N=${classes}) ---`);
						console.log(run.consoleText);
						console.log("--- end console ---");
					}
				}
			}
		} finally {
			cleanup = { ...uninstallBundle(state), preExistingJar: state.preExistingJar };
		}
	} finally {
		releaseLock();
	}

	printSummary(runs, cleanup);
	if (runs.length === 0 || !runs.every((run) => run.ok)) {
		exitCode = 1;
	}
	return exitCode;
}

try {
	process.exitCode = await main();
} catch (error) {
	if (error instanceof BenchError) {
		console.error(`error: ${error.message}`);
	} else {
		console.error(error);
	}
	process.exitCode = 2;
}

