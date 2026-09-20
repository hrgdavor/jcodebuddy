# `metadata/entity/` — entity metadata reports

One `<Entity>.metadata.json` per entity and view, written here by
`EntityMetadataGenerator` (invoked from this module's `pom.xml` via
`exec-maven-plugin`, and by `EntityRegenerationWatcher` during a watched run).

`generation.json` is the last pass's run record (`--run-record`): which generator
revision ran, the artifact its classes came from, the roots it resolved, the flags
it ran with, and the validation and divergence counts. Every build rewrites it, so
it is machine state rather than a document to read by hand — but it answers "what
generated this tree?", which no build log keeps.

Both are ignored by default — see `../README.md` for the opt-in rule that makes
this subtree tracked.
