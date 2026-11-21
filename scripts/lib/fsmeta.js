#!/usr/bin/env bun
/**
 * Where the `fsmeta` executable is, for a tool that starts its own private daemon.
 *
 * <p>The compiler never uses this: it discovers a **daemon serving the project root** through fs-meta's own
 * `roots.json`, so the location of the fs-meta *source checkout* is not part of discovery. This resolver is for
 * the repository's examples and benchmarks, which spawn a private daemon.</p>
 *
 * <p>Resolution order, and why:</p>
 *
 * <ol>
 *   <li>{@code FSMETA_BIN} — an explicit path always wins, so a caller can test a build without touching
 *       anything else;</li>
 *   <li><b>a sibling checkout</b> — {@code <this checkout>/../fs-meta/zig-out/bin/fsmeta}; the preferred
 *       layout, because the watcher's own source and build tree is then **outside** any root this repository
 *       registers with a daemon (a served root that contained it would watch its own {@code zig-out} and
 *       {@code .zig-cache});</li>
 *   <li>{@code fsmeta} on {@code PATH} — the installed binary, which is what a machine-wide daemon runs.</li>
 * </ol>
 *
 * <p>The old absolute default ({@code D:\wrk\core\fs-meta\...}) is deliberately gone: a checkout path is a
 * fact about one machine, and hard-coding it is why moving the checkout used to break the scripts.</p>
 */
import { existsSync } from 'node:fs'
import { join } from 'node:path'

const EXECUTABLE = process.platform === 'win32' ? 'fsmeta.exe' : 'fsmeta'

/**
 * @param {string} repoRoot the JCodeBuddy checkout root
 * @returns {string} an executable path or command name
 * @throws when no candidate exists, naming all three and the override
 */
export function resolveFsmeta(repoRoot) {
  const candidates = []
  if (process.env.FSMETA_BIN) {
    candidates.push({ path: process.env.FSMETA_BIN, source: 'FSMETA_BIN' })
  }
  const sibling = join(repoRoot, '..', 'fs-meta', 'zig-out', 'bin', EXECUTABLE)
  candidates.push({ path: sibling, source: 'the sibling checkout' })
  const onPath = Bun.which('fsmeta')
  if (onPath) {
    candidates.push({ path: onPath, source: 'PATH' })
  }
  for (const candidate of candidates) {
    if (existsSync(candidate.path)) {
      return candidate.path
    }
  }
  throw new Error('no fsmeta executable found:\n'
    + candidates.map((candidate) => `  ${candidate.source}: ${candidate.path}`).join('\n')
    + '\n  build the sibling checkout, install `fsmeta`, or set FSMETA_BIN')
}

/**
 * Runs `fsmeta` and captures one JSON document; the daemon's own state is the authority, never a file this
 * repository guesses.
 *
 * @param {string} binary
 * @param {string[]} args
 * @param {{cwd?: string, env?: Record<string, string>}} [options]
 */
export function fsmetaJson(binary, args, options = {}) {
  const result = Bun.spawnSync([binary, ...args], {
    cwd: options.cwd ?? process.cwd(),
    env: { ...process.env, ...(options.env ?? {}) },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  if (result.exitCode !== 0) {
    const stderr = new TextDecoder().decode(result.stderr).trim()
    throw new Error(`fsmeta ${args.join(' ')} failed (${result.exitCode})${stderr ? `\n${stderr}` : ''}`)
  }
  const text = new TextDecoder().decode(result.stdout).trim()
  try {
    return JSON.parse(text)
  } catch (error) {
    throw new Error(`fsmeta ${args.join(' ')} did not print JSON: ${error.message}\n${text}`)
  }
}
