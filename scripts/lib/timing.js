#!/usr/bin/env bun
/**
 * How long a utility took, as the last line it prints.
 *
 * A utility that walks or processes many files is a tool somebody runs again and again, and its duration is the first
 * thing they want to know: it is how a regression is noticed (a check that went from three seconds to three minutes),
 * and how one decides whether to wait or to go and do something else. A tool that says nothing leaves that to `time`,
 * a shell builtin, which is exactly the kind of invisible, platform-specific wiring this repository does not accept in
 * its tooling.
 *
 * **The rule, for every JavaScript utility here: report the duration at the end of the output.** The rule and its
 * reason are in AGENTS.md § 2; this module is how a utility keeps it, so no two tools invent a format.
 *
 * ```js
 * import { stopwatch, reportDuration } from './lib/timing.js';
 *
 * const elapsed = stopwatch();
 * // ... the work ...
 * console.log(reportDuration(elapsed(), { what: 'links checked', files: files.length }));
 * // links checked in 0.4s (264 files)
 * ```
 */

/**
 * A stopwatch. Call it to get the milliseconds since it started.
 *
 * @returns {() => number}
 */
export function stopwatch() {
  const started = performance.now();
  return () => performance.now() - started;
}

/**
 * A duration a person can read: milliseconds under a second, seconds with one decimal under a minute, and minutes and
 * seconds beyond that. A number nobody has to convert.
 *
 * @param {number} milliseconds
 */
export function formatDuration(milliseconds) {
  const ms = Math.max(0, Number(milliseconds) || 0);
  if (ms < 1000) {
    return `${Math.round(ms)}ms`;
  }
  if (ms < 60_000) {
    return `${(ms / 1000).toFixed(1)}s`;
  }
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  return `${minutes}m ${seconds}s`;
}

/**
 * The last line a utility prints.
 *
 * @param {number} milliseconds
 * @param {object} [options]
 * @param {string} [options.what] what took the time, in the past tense: "links checked", "tables fixed"
 * @param {number} [options.files] how many files it was about, when that is a number worth seeing
 * @param {string} [options.detail] anything else that belongs on the line
 * @returns {string}
 */
export function reportDuration(milliseconds, options = {}) {
  const what = options.what ?? 'done';
  const files = Number.isFinite(options.files) ? ` (${options.files} file${options.files === 1 ? '' : 's'})` : '';
  const detail = options.detail ? ` ${options.detail}` : '';
  return `${what} in ${formatDuration(milliseconds)}${files}${detail}`;
}
