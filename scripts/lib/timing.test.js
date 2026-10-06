#!/usr/bin/env bun
/**
 * The duration line, which every utility now ends with.
 *
 * Tested because it is a *rule for utilities* rather than one tool's preference: a number a person reads at the end of
 * a run is either right or misleading, and the formatting is the part that gets hand-rolled differently in each tool.
 */
import { describe, expect, test } from 'bun:test';
import { formatDuration, reportDuration, stopwatch } from './timing.js';

describe('the format', () => {
  test('milliseconds under a second, seconds under a minute, minutes beyond', () => {
    expect(formatDuration(0)).toBe('0ms');
    expect(formatDuration(294)).toBe('294ms');
    expect(formatDuration(999)).toBe('999ms');
    expect(formatDuration(1000)).toBe('1.0s');
    expect(formatDuration(3300)).toBe('3.3s');
    expect(formatDuration(59_400)).toBe('59.4s');
    expect(formatDuration(60_000)).toBe('1m 0s');
    expect(formatDuration(75_500)).toBe('1m 16s');
  });

  test('a nonsense duration is not a crash', () => {
    expect(formatDuration(undefined)).toBe('0ms');
    expect(formatDuration(-5)).toBe('0ms');
    expect(formatDuration(Number.NaN)).toBe('0ms');
  });
});

describe('the last line a utility prints', () => {
  test('names what took the time, and how many files when that is known', () => {
    expect(reportDuration(294, { what: 'links checked', files: 272 })).toBe('links checked in 294ms (272 files)');
    expect(reportDuration(1200, { what: 'tables fixed', files: 1 })).toBe('tables fixed in 1.2s (1 file)');
    expect(reportDuration(2500, { what: 'documents rendered' })).toBe('documents rendered in 2.5s');
    expect(reportDuration(500)).toBe('done in 500ms');
    expect(reportDuration(500, { what: 'checked', detail: '7 skipped' })).toBe('checked in 500ms 7 skipped');
  });
});

describe('the stopwatch', () => {
  test('measures forward and is readable', async () => {
    const elapsed = stopwatch();
    const first = elapsed();
    await new Promise((resolve) => setTimeout(resolve, 20));
    const second = elapsed();
    expect(second).toBeGreaterThanOrEqual(first);
    expect(second).toBeGreaterThanOrEqual(15);
    expect(formatDuration(second)).toMatch(/^\d+ms$|^\d+\.\ds$/);
  });
});
