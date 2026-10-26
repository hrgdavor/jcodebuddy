#!/usr/bin/env bun
/**
 * `gen.js`'s failure hint: the message must name the step that failed, not the step that happened to
 * run first. The case pinned here is the measured one — a green preflight, then a failing HTML index.
 */
import { describe, expect, test } from 'bun:test';
import { failureHint, lastCommand, PREFLIGHT_HINT } from './gen-diagnostic.js';

describe('the failing step', () => {
  test('is the last command the log recorded', () => {
    const log = [
      '',
      '$ java -cp x hr.hrg.hipster.entity.tooling.GeneratorPreflight',
      'preflight ok',
      '$ bun scripts/entity-html/index.js --module hipster-entity/hipster-entity-example',
      'index: 301 links verified, 0 rejected',
      'error: one link did not resolve',
      '',
    ].join('\n');

    expect(lastCommand(log)).toBe(
      'bun scripts/entity-html/index.js --module hipster-entity/hipster-entity-example',
    );
  });

  test('no command is not a crash', () => {
    expect(lastCommand('')).toBeNull();
    expect(lastCommand(undefined)).toBeNull();
    expect(failureHint('')).toEqual({ command: null, hint: null });
  });
});

describe('the preflight hint', () => {
  test('is not printed when the preflight was ok and a later step failed', () => {
    const log = [
      '$ java -cp x hr.hrg.hipster.entity.tooling.GeneratorPreflight',
      'preflight ok',
      '$ bun scripts/entity-html/index.js',
      'error: one link did not resolve',
    ].join('\n');

    const { command, hint } = failureHint(log);
    expect(command).toContain('index.js');
    expect(hint).toBeNull();
  });

  test('is printed when the preflight itself is the failing command', () => {
    const log = [
      '$ java -cp x hr.hrg.hipster.entity.tooling.GeneratorPreflight',
      'error: no --java-out support',
    ].join('\n');

    const { command, hint } = failureHint(log);
    expect(command).toContain('GeneratorPreflight');
    expect(hint).toBe(PREFLIGHT_HINT);
  });
});
