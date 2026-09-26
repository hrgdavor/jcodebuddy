# Field-enum compaction — the ordinal migration procedure

> **When to read this:** you want to reclaim the ordinals that retired fields hold. If you are not
> certain that every positional array, change-set patch and snapshot built on the current layout is
> gone, stop — this procedure destroys them silently.
>
> Normative rules: [DEC-023](../../architecture/decisions/DEC-023.md) (field enums are append-only
> ordinal ledgers) and [DEC-025](../../architecture/decisions/DEC-025.md) (compaction is a deliberate,
> acknowledged migration). Background: [the ordinal array contract](ordinal-array-contract.md).

## The short version

```
1. Drain the data.                     (you do this; nothing here can check it)
2. Run the compaction.                 enum-compact --repo . --allow-reorder --acknowledge-drained-data
3. Regenerate.                         bun scripts/gen.js: the generator rewrites the enums canonically
                                       and drops the retired fields from allFields
4. Verify.                             the R1 checker passes; the diff contains only the migration
5. Commit.                             one commit, the report in the message
```

Rollback is `git revert` of that one commit. There is no in-place undo: step 2 already rewrote the
ordinals, so the old layout exists only in git.

## Why the enum grows forever without this

DEC-023 makes a generated field enum append-only, because its constant **ordinal is the positional
index** into the backing array the whole runtime is built on:

- `values[field.ordinal()]` is the field, in `EntityReadArray`, `EntityUpdateArray` and
  `EntityUpdateTrackingArray`;
- the change-tracking bitset is indexed by the same ordinal;
- `ViewMeta.create(Object[])` maps the array positionally onto the generated record's components;
- any writer that emits `INSERT`/`UPDATE` fragments in that order — the draft SQL generator
  (`--adapters`, opt-in and not a supported generator) or, more commonly, your own code.

A removed field therefore cannot have its constant deleted: deleting it would move every constant
after it, so a stored array written under the old layout would be read back with the wrong value in
every slot after the removal — no error, no migration, just wrong data. The generator marks the
constant `@Deprecated`, makes it report `retired() == true`, and leaves it in place. Writers skip it;
readers still tolerate its slot; a generated binder never emits a column for it.

That is the right trade **while data exists**. Once none does, the tombstone is dead weight: it
occupies an ordinal, a nullable component in the record, and a slot in `fieldCount`.

## Step 1 — drain the data

Nothing in the toolchain can verify this step, which is why the command demands an explicit
acknowledgement for it. The artifacts that carry ordinals are:

| artifact | what "drained" means |
| --- | --- |
| Positional `Object[]` rows written to a database, file or cache | every stored row has been migrated or is no longer read |
| Queued or archived **change-set patches** (the JSON from `EntityJacksonMapper.toJsonChanges`) | replayed or discarded — a patch names changed ordinals |
| Serialized **snapshots** of a tracking builder | invalidated or rewritten |
| SQL `INSERT`/`UPDATE` fragments cached by a connection pool or a statement cache (the draft SQL generator's `<View>Binder`, or your own) | the statements are prepared per call, so this is normally free; a pool that caches them must be flushed |
| Anything else that stored `field.ordinal()` or a bit position | found by grepping for the view's field enum |

Writers to drain are any generated `<View>Binder` (only if your project opted into the draft SQL
generator with `--adapters`), any caller that kept a prepared statement across the migration, and any
consumer of a change-set patch. Readers are unaffected *after* the migration only if they were
regenerated in the same commit — a stored array and the enum that reads it must always be from the
same revision, which is exactly what step 3 guarantees.

## Step 2 — run the compaction

```bash
bun scripts/mvn-jdk25.js hipster-entity test          # the tests must be green first
java -cp <tooling classpath> hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
    enum-compact --repo . --allow-reorder --acknowledge-drained-data
```

The first line only runs the test set: like every Maven command here it compiles the committed
generated source and never regenerates it, so the regeneration is the separate step 3 below. The
compaction itself is always an explicit command.

The two flags are not alternatives and neither implies the other:

- `--allow-reorder` — the ordinal layout of these enums may change;
- `--acknowledge-drained-data` — no persisted positional array, JSON patch or snapshot survives.

Without both, the command exits `2` and writes nothing. `--target <path-substring>` restricts which
files are considered; it does **not** weaken the gate.

The command prints a migration report:

```
field-enum compaction: 1 file(s) rewritten, 1 tombstone(s) dropped, 1 ordinal(s) moved
  dropped  hr.example.entity.PersonSummary_.lastName (was ordinal 2)
  moved    hr.example.entity.PersonSummary_.email 3 -> 2
```

**Keep this output.** It is the migration record (DEC-025 § 5), and with the regeneration diff it is
the whole audit trail.

What the command will not do:

- it never touches an enum without the `entityFieldEnum:true` marker — a marker-less enum has no
  committed ledger and is rebuilt by a normal generation pass instead;
- it drops a constant only when it is **both** `@Deprecated` and `retired() == true`; a deprecated
  constant that does not retire itself is a hand edit and is left alone;
- it refuses a file it cannot parse rather than rewriting a partial one;
- it refuses any compaction whose result is not a subsequence in order of the original.

## Step 3 — regenerate

```bash
bun scripts/gen.js
```

`bun scripts/gen.js` is the regeneration pass; `bun scripts/mvn-jdk25.js` alone would not do it, because
the generator is a side tool with no lifecycle binding and the build only compiles the committed
generated source.

This is not optional. Compaction rewrites generated source through a parser, so its output is not
byte-identical to the generator's canonical emission; the generation pass normalises it. It also
rewrites `allFields` in `*.metadata.json`, where the retired fields disappear because they no longer
have accessors. That membership change *is* the metadata half of the migration record — as DEC-025
notes, `allFields` carries no ordinal, so there is nothing ordinal-shaped in it to rewrite.

## Step 4 — verify

```bash
java -cp <tooling classpath> hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
    enum-order --repo . --baseline HEAD
```

Run against the **pre-migration** `HEAD`, this reports `enum_constant_removed` for exactly the
tombstoned names. That is not a failure: it is the migration record, and it is the one case where
that output is expected. Read it and confirm the names are the ones the compaction report listed — if
it names anything else, or reports `enum_order_shuffled`, stop, because something other than
compaction moved a constant.

(The checker cannot be the gate that *authorises* a compaction, and the command does not ask it to
be. Its removal rule exists because a normal generation pass must never drop a constant; compaction is
the sanctioned exception. What the command checks before writing is the narrower and exact property:
the survivors are a subsequence in order of the original — removes, never shuffles.)

Then read the diff and confirm it contains **only** the migration:

- a tombstone constant gone from each `<View>_.java`, and every later constant's position shifted by
  the number of tombstones before it;
- the `forName` arm for each dropped constant gone;
- the retired field gone from `fieldCount`, from the generated record's components, from the
  builders' `get(int)`/`set(int)` arms and from the JDBC `COLUMNS`/`ORDINALS` arrays;
- `entityFieldEnum:true` **still present** in every header — compaction renumbers the ledger, it does
  not opt the enum out of the guard.

If the diff contains anything else, the compaction is not what changed it.

## Step 5 — commit

One commit, containing the compacted enums, the regenerated artifacts and the metadata JSONs. Put
the migration report in the commit message. The commit is the rollback point.

## Rules of thumb

- **Do not compact during a release.** The window between step 2 and the deployment of step 3's
  artifacts is a window in which two revisions exist; a stored array must always be read by the enum
  from the revision that wrote it.
- **Compact one entity at a time** with `--target` if the entities have different drain schedules.
- **Never compact to "tidy up".** A tombstone is evidence that a field once existed; DEC-023 keeps it
  precisely so nobody has to remember.
- **Do not edit the `@Deprecated` or `retired()` off a tombstone to make it compactable** — that is
  what `--allow-reorder` plus `--acknowledge-drained-data` are for, and doing it by hand loses the
  report.
