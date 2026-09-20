# `metadata/` — derived output (ignored)

Machine-written and regenerable, so the whole subtree is ignored by
`.jcodebuddy/.gitignore`; only the `README.md` files are tracked, so the shape of the
tree is visible without carrying the data.

| Subdir | Written by | Contents |
|---|---|---|
| `entity/` | `EntityMetadataGenerator` (the `exec-maven-plugin` binding in this module's `pom.xml`) | one `<Entity>.metadata.json` per entity/view, plus `generation.json` — the run record of the last pass (revision, artifact, roots, flags, counts) |
| `watch/` | the watch agent's `MetadataCache` and `AuditManager` | `metadata.db` checksum cache, plus `audit/<toolSet>/<timestamp>_<action>/{manifest.json,summary.md,before/,after/}` |
| `project/` | whole-project metadata passes | `index.fury` and related index files |

To commit a subtree as a contract instead, add an opt-in rule to
`.jcodebuddy/.gitignore`, for example:

```gitignore
!metadata/entity/
!metadata/entity/**
```

Note what that opt-in deliberately does **not** carry: the generator writes only `.json` here, and
the ignore rules keep `README.md` tracked while ignoring everything else, so a stray `.java` file
(an old hand-run generator pass that wrote generated source into the report directory, instead of
passing `--java-out`) can never be committed by that rule. Generated `.java` belongs under
`src/main/java`, never here.
