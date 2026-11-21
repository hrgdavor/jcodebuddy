#!/usr/bin/env bun
/**
 * The fsmeta resolver's order matters: an explicit `FSMETA_BIN` wins, a sibling checkout beats the machine's
 * `PATH`, and a caller that passes neither gets the sibling path in the error rather than a guess.
 */
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, test } from 'bun:test'
import { resolveFsmeta } from './fsmeta.js'

const EXE = process.platform === 'win32' ? 'fsmeta.exe' : 'fsmeta'
const fixtures = []

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'fsmeta-resolver-'))
  const repo = join(root, 'jcodebuddy')
  mkdirSync(repo, { recursive: true })
  const fixture = { root, repo, sibling: join(root, 'fs-meta', 'zig-out', 'bin', EXE) }
  fixtures.push(fixture)
  return fixture
}

function fakeBinary(path) {
  mkdirSync(join(path, '..'), { recursive: true })
  writeFileSync(path, '')
}

afterEach(() => {
  delete process.env.FSMETA_BIN
  while (fixtures.length > 0) {
    rmSync(fixtures.pop().root, { recursive: true, force: true })
  }
})

test('FSMETA_BIN is used when it exists', () => {
  const { root, repo, sibling } = fixture()
  const explicit = join(root, 'explicit', EXE)
  fakeBinary(explicit)
  fakeBinary(sibling)
  process.env.FSMETA_BIN = explicit
  expect(resolveFsmeta(repo)).toBe(explicit)
})

test('the sibling checkout is preferred over fsmeta on PATH', () => {
  const { repo, sibling } = fixture()
  delete process.env.FSMETA_BIN
  fakeBinary(sibling)
  // This machine has an `fsmeta` on PATH, so a resolver that preferred PATH would return that instead.
  expect(resolveFsmeta(repo)).toBe(sibling)
})

test('a missing FSMETA_BIN is skipped rather than trusted', () => {
  const { root, repo, sibling } = fixture()
  fakeBinary(sibling)
  process.env.FSMETA_BIN = join(root, 'nowhere', EXE)
  expect(resolveFsmeta(repo)).toBe(sibling)
})
