# `metadata/entity/` — entity metadata reports

One `<Entity>.metadata.json` per entity and view, written here by
`EntityMetadataGenerator` — by every generation pass: `scripts\gen.cmd` (manual or
watched), this module's explicit, phase-less `exec:java@hipster-entity-generate`
goal, or `EntityRegenerationWatcher` under a watched run. No Maven build writes it;
nothing binds the generator to a lifecycle phase.

`generation.json` is the last pass's run record (`--run-record`): which generator
revision ran, the artifact its classes came from, the roots it resolved, the flags
it ran with, and the validation and divergence counts. Every generation pass rewrites
it, and no build does. It is machine state rather than a document to read by hand —
but it answers "what generated this tree?", which no build log keeps.

Both are ignored by default — see `../README.md` for the opt-in rule that makes
this subtree tracked.
