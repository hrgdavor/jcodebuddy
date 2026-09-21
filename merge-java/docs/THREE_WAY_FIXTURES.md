# Writing a three-way fixture

How to add a merge case as real source files, and why the fixtures are laid out
this way. Referenced from [`ThreeWayFixture`](../src/test/java/com/codebuddy/merge/ThreeWayFixture.java).

---

## Layout

```
src/test/resources/fixtures/<case-name>/
  base/<Type>.java.txt        the LAST-SYNCED upstream state - a complete file
  ours/<Type>.java.txt        our branch - a complete file
  theirs/<Type>.java.txt      their branch - a complete file
  ours.diff                   what ours changed, for a human
  theirs.diff                 what theirs changed, for a human
```

### What `base/` holds — read this before writing a fixture

`base/` is **not** Git's merge base. It is the upstream (`main`) exactly as it stood
the **last time `main` was merged into this branch**. The two are different
reference points and conflating them is a real defect, fully explained in
[`WHAT_IS_BASE.md`](WHAT_IS_BASE.md).

Put the *last-synced* content there: the upstream file at the last sync — not
`main`'s current tip, and not a synthetic common ancestor. If `base/` is stale, the
upstream looks as though it re-added everything the branch already merged and the
branch looks as though it deleted it, which produces conflicts that do not exist.

The `.java.txt` suffix keeps the files out of compilation while leaving them
ordinary readable Java. **Use the real type name** — `PaymentProcessor.java.txt`,
not `base.java.txt` — because the reported `filePath` is derived from it and type
attribution depends on a plausible source path.

---

## Load and use

```java
class MyConflictTest {

    private final ThreeWayFixture fixture = ThreeWayFixture.load("import-add-both");

    @Test
    void resolvesAsExpected() {
        Conflict conflict = fixture.conflict(ConflictType.IMPORT_ADD);
        ConflictResolution resolution = new ImportConflictResolver().resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
    }
}
```

`fixture.conflict(type)` anchors the conflict to the fixture's `filePath`, attaches
`TestTypeContexts.jdk()` so type-resolving resolvers can work, and passes the three
complete files.

For a different path than the default:

```java
ThreeWayFixture.load("my-case", "src/main/java/com/example/Other.java");
```

---

## The rules that make a fixture trustworthy

### 1. The three files must be complete, compilable units

Not hunks. A fragment cannot be parsed with type attribution, and — more
importantly — a fragment cannot *express a change*. A change is only visible
against the base, and that is the entire point of the layout.

### 2. The diffs are documentation, never input

`ours.diff` and `theirs.diff` state the intended change so a reviewer can see it at
a glance. **No code parses them.** The authoritative change is computed structurally
by comparing a branch against the base, because a resolver that parsed diffs would
inherit exactly the fragility this module exists to remove.

Assert the two agree, so a fixture whose diff lies fails rather than misleads:

```java
assertEquals(
    ThreeWayFixture.addedImportsInDiff(fixture.oursDiff()),
    fixture.oursImportChange().orElseThrow().added().stream()
        .map(ImportChange.ImportRef::rendered).collect(Collectors.toSet()),
    "the computed addition must match the documented one");
```

Write diffs as real unified diffs (`---`, `+++`, `@@`) so they read correctly in a
reviewer's diff viewer.

### 3. Choose a case where a text diff fails

A fixture that a two-way text comparison handles correctly proves nothing. The
valuable cases are the ones where comparing the two branches *cannot* see what
happened:

| Case shape | Why text comparison fails |
|---|---|
| Ours adds an import, theirs removes a **different** one | Two-way shows each side holding an import the other lacks, which reads as a clash over the block. Only the base reveals an independent addition and removal. |
| Both branches add a method into the same class body | The insertions sit at the same location, so a line diff interleaves them and can produce a method nested inside another — syntactically broken code. |
| Both add a constant with **different** values | Textually both are "a new line"; only the base shows it is the *same name* being defined twice. |
| One branch renames, the other adds a use of the old name | The addition looks harmless until you know the base declared it. |

### 4. Assert the structure survived, not just the text

The failure mode is corrupt structure, so check the shape of the output:

```java
assertTrue(ResolutionVerifier.wasBalanced(resolved));
for (String line : resolved.split("\n")) {
    if (!line.isBlank()) {
        assertTrue(line.startsWith("import ") && line.endsWith(";"),
            "every emitted line must be a complete declaration");
    }
}
```

For a member-addition case, assert the member count and that each declaration
closes.

---

## Naming

Name the directory for the **change shape**, not the outcome:
`import-add-and-remove-different`, `method-add-both-same-body`,
`constant-same-name-different-value`. The name should say what makes the case hard.

---

## Checklist

- [ ] Directory named for the change shape
- [ ] `base/`, `ours/`, `theirs/` hold complete files with the real type name
- [ ] `ours.diff` and `theirs.diff` are real unified diffs
- [ ] The case is one a two-way text comparison gets wrong
- [ ] A test asserts the computed change agrees with each diff
- [ ] A test asserts the resolved output is structurally intact
