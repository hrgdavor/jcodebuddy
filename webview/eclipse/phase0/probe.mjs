#!/usr/bin/env bun
// webview/eclipse/phase0/probe.mjs — Phase 0 apparatus for the Eclipse host plan.
//
// Resolves the pinned Eclipse bundles from the local Maven repository or Maven Central into
// target/, compiles SwtProbe.java with javac from JAVA_HOME, and runs it with those jars on
// the classpath. The probe prints one line per experiment (A–F of
// webview/eclipse/PHASE0-ECLIPSE-FINDINGS.md). It writes only into target/.
//
//   bun run webview/eclipse/phase0/probe.mjs

import path from "node:path";
import fs from "node:fs";
import { spawnSync } from "node:child_process";

const here = path.dirname(path.resolve(import.meta.path));
const target = path.join(here, "target");
const classes = path.join(target, "classes");
fs.mkdirSync(classes, { recursive: true });

const javaHome = process.env.JAVA_HOME;
if (!javaHome) {
  console.error(
    "probe.mjs: JAVA_HOME is not set. Set it to a JDK 17+ — this repository uses " +
      "C:\\Users\\hrg\\.jdks\\openjdk-25.0.1 — and re-run."
  );
  process.exit(2);
}
const bin = (name) => path.join(javaHome, "bin", process.platform === "win32" ? name + ".exe" : name);

// The 2026-09 train that webview/eclipse/PLATFORM-REFERENCE.md pins.
const BUNDLES = [
  { group: "org/eclipse/platform", artifact: "org.eclipse.swt.win32.win32.x86_64", version: "3.135.0" },
  { group: "org/eclipse/platform", artifact: "org.eclipse.ui", version: "3.209.100" },
  { group: "org/eclipse/platform", artifact: "org.eclipse.ui.browser", version: "3.9.200" },
];

const m2 = path.join(process.env.USERPROFILE || process.env.HOME || "", ".m2", "repository");

async function resolveBundle(b) {
  const jarName = `${b.artifact}-${b.version}.jar`;
  const local = path.join(m2, b.group, b.artifact, b.version, jarName);
  const dest = path.join(target, jarName);
  if (fs.existsSync(local)) {
    if (!fs.existsSync(dest)) fs.copyFileSync(local, dest);
    console.log(`bundle ${b.artifact}-${b.version} from the local Maven repository`);
    return dest;
  }
  const url = `https://repo1.maven.org/maven2/${b.group}/${b.artifact}/${b.version}/${jarName}`;
  const res = await fetch(url);
  if (!res.ok) {
    console.error(`probe.mjs: failed to download ${url}: HTTP ${res.status}`);
    process.exit(3);
  }
  fs.writeFileSync(dest, Buffer.from(await res.arrayBuffer()));
  console.log(`bundle ${b.artifact}-${b.version} downloaded from Maven Central`);
  return dest;
}

const jars = await Promise.all(BUNDLES.map(resolveBundle));

console.log("compiling SwtProbe.java");
const compile = spawnSync(
  bin("javac"),
  ["-d", classes, "-cp", jars.join(path.delimiter), path.join(here, "SwtProbe.java")],
  { stdio: "inherit", cwd: here }
);
if (compile.status !== 0) {
  console.error("probe.mjs: javac failed with exit " + compile.status);
  process.exit(4);
}

const classpath = [classes, ...jars].join(path.delimiter);
console.log("running the probe (120 s cap; it opens short-lived windows)");
const run = spawnSync(
  bin("java"),
  [
    // Suppress the one-time "Default browser engine not available" dialog if the WebView2
    // runtime is missing, so a missing runtime reports as an exception instead of a modal.
    "-Dorg.eclipse.swt.browser.DisableWebViewUnavailableDialog=true",
    "-cp", classpath,
    "hr.hrg.eclipse.phase0.SwtProbe"
  ],
  { stdio: "inherit", cwd: here, timeout: 120000 }
);
if (run.error) {
  console.error("probe.mjs: the probe timed out or failed to start: " + run.error.message);
  process.exit(6);
}
if (run.status !== 0) {
  console.error("probe.mjs: the probe exited " + run.status);
  process.exit(5);
}
process.exit(0);
