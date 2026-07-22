# DEC-009 Brainstorm: Source-visible generation strategy

This document expands DEC-009 with practical use cases, alternatives, and implementation tradeoffs.

For a deeper rationale focused on annotation processing and reflection avoidance, see [Annotation processing vs generated metadata and materialized code](annotation-processing-vs-generated-metadata.md).

## 1. Problem statement

The project needs generation that is:
- understandable by developers in normal code review flow
- deterministic and safe for repeated runs
- resilient when source is temporarily broken
- compatible with long-lived maintenance and incident recovery

Past experience with annotation-processing-heavy pipelines often leads to low visibility and fragile behavior in mixed IDE/build environments.

## 2. Decision scope recap

DEC-009 proposes:
- JavaParser-based source analysis as primary path
- Java source generation only (no bytecode generation)
- generated outputs checked into VCS by default
- freeze mode for manual takeover
- in-file augmentation allowed (for example builder inside interface)
- sidecar tooling with editor-aware patching/undo
- optional skip generation on syntax errors

## 3. Use cases

### 3.1 Projection-only materialization

Input is an interface projection (SQL or Mongo), no concrete entity class exists.

Desired behavior:
- generate builder/adapter code visible in source
- keep generated code in repository so changes are reviewable
- allow manual fix in freeze mode during incidents

### 3.2 Large refactor in progress

During partial renames and API churn, some files fail parsing/compilation.

Desired behavior:
- sidecar tool reports parse failures
- skip generation in failing areas to avoid noisy churn
- preserve previously generated code until source stabilizes

### 3.3 Incident fallback

Generator emits incorrect patch for a critical module.

Desired behavior:
- team enables freeze for impacted package/module
- generated code becomes manually maintained short-term
- generator can be re-enabled later when fixed

### 3.4 Incremental developer workflow

Developer edits interface and runs generation from editor.

Desired behavior:
- minimal deterministic patch to affected files
- undo support through editor history / sidecar patch tracking
- no hidden bytecode transformation layer

## 4. Why prefer this over annotation processing

### 4.1 Annotation processing pain points (observed in practice)

- Compilation lifecycle coupling can make failures harder to isolate.
- IDE and build-tool behavior may diverge for generated outputs.
- Generated artifacts are frequently treated as transient build byproducts, reducing visibility.
- Cross-file refactors can cause confusing processor ordering/dependency issues.
- Debugging processor state is often harder than debugging source-to-source transforms.

### 4.2 Source-parser approach advantages

- Source-first: easier for developers to reason about input and output.
- Better reviewability: generated source can be diffed and reviewed like handwritten code.
- More flexible tool UX: sidecar/editor operations are natural with source patches.
- Reduced hidden behavior: no bytecode layer to inspect when behavior is wrong.

### 4.3 Tradeoffs of this choice

- Must handle formatting and idempotent patching carefully.
- Parser must tolerate partial code states and report actionable diagnostics.
- In-file augmentation needs robust conflict detection and anchors.
- Checked-in generated code requires clear ownership and regeneration policy.

## 5. Bytecode generation vs Java source generation

### Java source generation (chosen)

Pros:
- transparent and debuggable
- works with normal review workflows
- can be manually hotfixed in emergencies

Cons:
- bigger diff footprint
- style/format consistency work

### Bytecode generation (not chosen)

Pros:
- potentially smaller source tree footprint
- can avoid exposing generated internals

Cons:
- opaque behavior for developers
- harder to debug and reason about
- weaker manual fallback options

## 6. Checked-in generated code policy draft

Recommended baseline:
- Generated code is committed by default.
- CI verifies deterministic regeneration (no unexpected diff).
- Generated blocks/files carry stable markers or headers.
- Ownership policy is explicit: generated unless frozen.

Potential exceptions:
- experimental modules
- short-lived PoCs

## 7. Freeze mode details

Freeze mode goals:
- permit manual maintenance of generated outputs
- prevent generator overwrite in frozen scope
- retain a trace of why/when freeze was enabled

Suggested controls:
- freeze at module/package/file granularity
- metadata file with reason, owner, and expiry review date
- optional in-source freeze markers with `until` metadata for expected fix tracking
- warning in generation logs when writing is skipped due to freeze or when expected fix dates are overdue

## 8. In-file augmentation guidelines

In-file augmentation is allowed but must be strict:
- deterministic insertion anchors
- idempotent reruns (no duplicate generated sections)
- minimal patch span to preserve user edits
- explicit conflict diagnostic if anchors are missing or ambiguous

Example target:
- place generated builder as nested type inside view interface when policy says "co-locate"

## 9. Syntax-error skip policy

Recommended behavior:
- parser error in a source unit should block writes for that unit
- unaffected units may still generate if graph integrity allows
- clear diagnostics: file, line, reason, and skipped outputs

Benefits:
- avoids noisy generated churn from incomplete edits
- improves trust in generated output quality

Risk:
- stale generated output if errors persist too long

Mitigation:
- warning thresholds and CI checks for stale generation age

## 10. Open questions for implementation

- Should skip-on-syntax-error be default `on` or opt-in?
- Should in-file augmentation support both nested and sibling file placement policies?
- What is the canonical marker format for generated blocks?
- How is freeze state represented and versioned?
- What level of deterministic formatting guarantees are required?

## 11. Proposed next implementation checkpoints

1. Define deterministic patch/anchor format for in-file augmentation.
2. Implement freeze metadata and skip-write enforcement.
3. Add syntax-error skip diagnostics with per-file reporting.
4. Add CI regeneration consistency check for checked-in outputs.
5. Add sidecar undo/rollback trace format for applied generation patches.
