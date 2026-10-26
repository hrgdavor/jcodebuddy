#!/usr/bin/env bun
/**
 * What to say when `scripts/gen.js` fails.
 *
 * The historical defect this module exists for: `gen.js` printed its `GeneratorPreflight`/tooling-version
 * hint on **any** non-zero status. A run whose preflight was `ok` and whose last step was the HTML index
 * therefore blamed the tooling version, and the hint sent the reader after a cause its own log had ruled
 * out. The hint belongs to the step that failed, and the last `$ <command>` line in the log is that step.
 */

/** The text of the hint, kept here so the failing-step decision and the message are one unit. */
export const PREFLIGHT_HINT =
  '[gen] A failure naming GeneratorPreflight means the tooling being run is older than this invocation, '
  + 'so it would ignore --java-out and write generated .java into the metadata directory. Re-run; if it '
  + 'persists, the local repository copy is being resolved instead of the reactor: compile with '
  + '`bun scripts/mvn-jdk25.js -o -pl :hipster-entity-tooling -am compile`.';

/**
 * The last command the run logged, or `null` when no step was logged.
 *
 * @param {string} logText the full log a `step(...)` call appended to
 * @returns {string|null} the command line, without its leading `$ `
 */
export function lastCommand(logText) {
  const lines = String(logText ?? '').split(/\r?\n/);
  for (let index = lines.length - 1; index >= 0; index--) {
    if (lines[index].startsWith('$ ')) {
      return lines[index].slice(2);
    }
  }
  return null;
}

/**
 * The command that failed and the hint that actually applies to it.
 *
 * @param {string} logText the full log a `step(...)` call appended to
 * @returns {{ command: string|null, hint: string|null }}
 */
export function failureHint(logText) {
  const command = lastCommand(logText);
  return {
    command,
    hint: command && command.includes('GeneratorPreflight') ? PREFLIGHT_HINT : null,
  };
}
