# `metadata/entity/` — entity metadata reports

One `<Entity>.metadata.json` per entity and view, written here by
`EntityMetadataGenerator` (invoked from this module's `pom.xml` via
`exec-maven-plugin`, and by `EntityRegenerationWatcher` during a watched run).

Ignored by default — see `../README.md` for the opt-in rule that makes it tracked.
