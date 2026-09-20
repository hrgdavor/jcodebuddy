# `plan.dsflash.md` — execution notes: ambiguities, deviations and findings

Companion to [`plan.dsflash.md`](plan.dsflash.md) and
[`plan.dsflash.baseline.md`](plan.dsflash.baseline.md). Written by the executor. Per the task
instruction ("if anything ambiguous is found document it"), everything below is a place where the
plan, the repository, or the execution environment did not line up exactly, plus what was done
about it. Entries are numbered `N-n` (notes), `D-n` (deliberate deviations) and `F-n` (findings
whose plan text or premise was wrong).

---

## D-1 — the recorded Maven launcher is Apache Maven 3.9, not mvnd

**Plan:** § 0.1a/§ 0.4 say the recorded command was verified with **mvnd 1.0.0-m4 / Maven
4.0.0-alpha-4** (via the `mvn` wrapper on `PATH`, `D:\programs\cmd\mvn.bat`), and only *warn* that
the launcher should be named.

**What happened:** the mvnd client cannot run in this execution environment at all. It fails
before building with

```
org.mvndaemon.mvnd.common.DaemonException: java.nio.file.AccessDeniedException:
C:\Users\hrg\.m2\mvnd\registry\1.0.0-m4\registry.bin
```

because it writes its daemon registry outside the workspace. `scripts/mvn-jdk25.cmd` therefore
defaults to **Apache Maven 3.9.0** (`D:\programs\mvn\bin\mvn.cmd`) and honours `JCODEBUDDY_MVN`,
so a developer whose mvnd is not sandboxed can switch back with one environment variable.

**Consequence for the plan:** § 0.4's counts were **re-recorded on Maven 3.9** and match exactly
(`api 0, core 42, tooling 22, jackson 0, test 4, example 0` — 68), so the baseline claim in the
plan is confirmed on a second launcher as well. Note this also means the plan's
"`$LASTEXITCODE` is unreliable" warning does **not** apply to the recorded command under
`mvn.cmd`, where it is meaningful.

## D-2 — `mvn install` cannot be exercised here, so G4's ordering trap is documented, not demonstrated

**Plan:** § 4.5/G4 requires `mvn -o -pl hipster-entity-tooling -am install` to precede the first
generation-triggering build, and says "if this proves brittle in practice, the fallback is a
`maven-invoker-plugin`-driven verification or dropping `-o` … decide empirically in Phase 4".

**What happened:** `install` cannot run in this environment — the local repository
(`C:\Users\hrg\.m2\repository`) is outside the workspace and is read-only here:

```
Failed to install artifact hr.hrg.jcodebuddy:jcodebuddy-parent:pom:1.0-SNAPSHOT:
C:\Users\hrg\.m2\repository\...\jcodebuddy-parent-1.0-SNAPSHOT.pom.<tmp>   → [Help 1]
```

So the ordering trap is **documented in the README/notes for Phase 3.23 but could not be
demonstrated end-to-end** in this run. The `test`/`test-compile` phases (reactor-scoped, no
install) are used throughout instead. Phase 3.23's `exec-maven-plugin` binding is therefore
implemented so that it works in-reactor (it does not require the tooling artifact to be
installed) — see the Phase 3 notes.

## D-3 — the field-enum tests use a purpose-built `FieldDef` fixture rather than the JMH harness enums

**Plan:** § 6.4 does not say which `FieldDef` enum the new core tests should use.

**What happened:** `hipster-entity-core/src/test/.../TrackingTestFixtures.java` was added with two
enums — a 7-constant one (`Fields`, the 64-bit variant) and a **65**-constant one (`BuiltIn`,
selected at the boundary to prove the `Large` variant is picked). The JMH harness's own `E64`/`E96`
enums are in `EEnumSetTrackingJmhBenchmark` and are not reusable from a plain JUnit class. The
fixtures' first seven constant names deliberately match so the same mutation sequence can be
compared across the boundary.

---

## F-1 — DEC-012's "compare → record → set bit" is now real, but "previous value" had no home

> **Superseded in part by D-16.** The *order* this entry is about — compare, then mark — still holds
> and is now enforced at the write site. The **recorder half is gone**: `ChangeRecorder` was deleted,
> `addOrdinalChange` no longer exists, and no previous value is stored anywhere. Read this entry as the
> history of how the rule was first implemented, not as the current contract.

**Plan:** § 6.2/1.6 states the order and requires the array path to feed a `ChangeRecorder`.

**Finding:** confirmed as described. `addOrdinalChange` was a `default` interface method with no
state, so previous values were not merely unimplemented — there was no object that *could* hold
them. The method is now abstract on `EEnumSetBuilder` and implemented in both concrete builders,
which hold the recorder. `EEnumSetBuilder.Strict`'s override was removed because it became
redundant (the base method now compares before recording); the class is kept as the documented
extension point (§ 4.5/G3).

## F-2 — `previousValue` is the *replaced* value; `currentValue` is the new one

> **Superseded by D-16.** `previousValue`/`hasPreviousValue` no longer exist and `diff()` is
> `changedValues()`; `currentValue` remains, and the old half of a comparison is read from the caller's
> baseline instance. The disambiguation recorded here — "previous" meant the *replaced* value, not the
> value before the first write — is why the removal was unambiguous when it came.

**Plan ambiguity worth pinning:** § 4.2/D2 and § 6.4 tests 6/14/15 describe "previous value"
without ever stating the partner accessor for the *new* value, and `ViewChangeTracking` in § 4.1/S5
declares only `previousValue`/`hasPreviousValue`/`diff()`.

**Resolution implemented:** `ViewChangeTracking` gained `Object currentValue(E field)` alongside
`previousValue(int)`, because `diff()` needs both halves and a snapshot of the change set alone
cannot supply the current value. `diff()` yields `(field, previous, current)` triples, and the
tests now pin the pairing explicitly (`previousValue(ord)` is the value the slot held **before**
the write; `currentValue(field)` is the value it holds **now**).

## F-3 — the third tracking-array constructor parameter is `universe`, not a second `Class`

**Plan:** § 6.2/1.4 keeps `ForNameOrdinal` and adds `F[] universe`. § 6.2/1.5 says to "add an
`E[] universe` constructor; keep the `Class<E>` constructor delegating to it" for the builders.

**Finding / deviation:** for the **builders** the two are redundant — `Class<E>` and `E[]` are
different spellings of the same thing. The `E[] universe` constructor is primary and derives
`enumClass` from `universe.getClass().getComponentType()`; the `Class<E>` constructors are kept as
delegating overloads because `EEnumSetAll`/`EEnumSet64`/`EEnumSetLarge`'s `toBuilder()` methods and
the JMH harness call them. Applying the same idea to the **tracking array** (`create(ForNameOrdinal,
universe, values)` — no separate `fieldCount`) also let the plan's new arity assertion be stated as
`universe.length == values.length` in one place.

## F-4 — `createUpdatable` had a latent dispatch bug that only a tracking view can reach

**Plan:** § 6.2/1.9 specifies the new proxy arms and their placement, and says to "keep the
throw-on-unknown-name behaviour (D5)".

**Finding:** the existing `set` arm was

```java
if (name.equals("set") && parameterCount == 2) {
    F field = (F) args[0];          // ← ClassCastException for set(String, Object)
    ...
}
```

`ViewWriter` declares **both** `set(String, Object)` and `set(int, Object)`, and both have two
parameters. The arm assumed the first argument is the field enum, so calling the documented
`set(String, Object)` on an updatable proxy threw `ClassCastException` instead of returning `-1`
or throwing `IllegalArgumentException`. **D5's split was therefore not actually delivered on the
proxy path at all** before this work: the plan's verified citation
(`ArrayBackedViewProxyFactory.java:150-152`) is the *one-argument* fallback, not the two-argument
arm. The arm now dispatches on the **declared parameter type** (`method.getParameterTypes()[0]`),
never on the runtime argument type, and `set(String, Object)` returns `-1` at the array level while
the proxy converts it to `IllegalArgumentException` as D5 requires.

## F-5 — `createUpdatable` proxies only the view type, so the tracking surface needs a type that declares it

**Plan:** § 6.4 states this explicitly ("`createUpdatable` proxies exactly
`new Class[]{viewType}` … so the type handed to `createUpdatable` must itself declare the tracking
methods"), and accepts the consequence.

**Deviation:** the proxy now also advertises `ViewChangeTracking` in its interface list, while the
plan's § 6.4 fixture advice (`TrackedPersonSummary`) is implemented **as well**. Reason: the same
plan requires § 6.7 (Phase 6) *"an `ArrayBackedViewProxyFactory` updatable view must produce the
same `changesDeep()` result as the generated builder"*, and Phase 6's `changesDeep()` would
otherwise need yet another hand-written fixture interface per view type. Advertising the interface
keeps the documented `TrackedPersonSummary` fixture working unchanged and makes the deep-tracking
requirement expressible later. The `UpdatableHandler` was made non-generic in `T` (it holds an
`EntityUpdateTrackingArray<?, F>`) for this — see F-6.

## F-6 — Eclipse's incremental compiler left `Unresolved compilation problems` markers in class files

**Finding (toolchain, not plan):** after several source edits, the `hipster-entity-test` classes
compiled by Maven's **ECJ** compiler still carried Eclipse problem markers, so tests failed at
runtime with `java.lang.Error: Unresolved compilation problems: …` while `javac` compiled the very
same sources with exit code 0. Deleting `hipster-entity-test/target/classes` and
`target/test-classes` and recompiling cleared it. This is a stale-incremental-artifact effect; the
remedy is noted here because it produced a very confusing failure signature and can recur.

---

## F-7 — the `shallowReferenceHazard` test's first formulation asserted a wrong expectation (test since deleted with the hazard, D-16)

> **Superseded by D-16 — the hazard does not exist any more.** There is no recorded previous value, so
> there is no stale shallow reference to warn about. `EntityUpdateTrackingArrayTest` now pins the
> opposite property (`noStaleReferenceExistsBecauseNoPreviousValueIsKept`): the reported value is the
> one the field holds, and the caller's own baseline keeps its contents. This entry is kept because it
> documents *why* the old design needed the warning.

**Plan:** § 4.2/D2's "Hazard, pinned by test" says: *"mutating a `Map`/`List` field in place instead
of replacing it leaves `previousValue()` comparing equal to current while `changes()` still marks
the field — so `diff()` can be empty while `changes()` is not. Test both halves and document it."*

**Finding:** the plan's description of the *observable* is imprecise in one respect, and the
implemented, verified semantics are:

1. `previousValue(ord)` is the value of the slot that was **replaced**, held as a **shallow
   reference** (so it is not a defensive copy);
2. therefore, mutating the collection that was replaced **is visible through**
   `previousValue(ord)` — a caller who mutates in place sees its own later mutation reflected in the
   reported "before" picture;
3. `changes()` keeps marking the field, and `diff()` still reports one `FieldChange` whose
   `previous` and `current` are **two distinct instances** (never a copy of each other).

Point 2 is the hazard, and it is what the test pinned (`shallowReferenceHazard`, part (b)). The
plan's phrasing "`diff()` can be empty while `changes()` is not" describes a *consequence* a caller
can reach, not the recorder's own output: the recorder always emitted a `FieldChange` for a marked
ordinal. **First-write-wins (see F-8) meant a second write to the same ordinal did not re-record**,
so the baseline stayed the first value the session saw — which is why the test asserted the
reference identity of that first value rather than its content.

## F-8 — first-write-wins is a real rule the plan only implies

> **Superseded by D-16.** "First write wins" was a property of the *stored baseline*; with no baseline
> stored there is nothing to win. What replaces it is stated in F-51's sibling decision: a write back
> to the original value leaves the ordinal marked, and the caller's comparison — not the tracker —
> answers "is it different from what I loaded".

**Plan:** § 4.2/D2 says previous values are recorded per ordinal and that `clearChanges()` releases
them, but never states what a **second** write to an already-recorded ordinal does.

**Resolution implemented at the time (and pinned by `firstWriteWinsForPreviousValueAcrossUnmark`):**
the **first** write in a tracking session fixed the baseline for that ordinal; a later write did not
re-record, so a mark → unmark → re-mark cycle still reported the value the field held *before any
write in this session*. That was the only reading under which a baseline is meaningful, and it made
`removeOrdinal` drop the recorded value (so a subsequent write was a fresh baseline) — which is also
what `unmark` did before. The test named here no longer exists; D-16 records its replacement.

## F-9 — the 65-field boundary exposes a placeholder value, not a bug

**Finding (test authoring, not plan):** the plan's § 6.4 test 7 asks for "64- and 65-field views
produce equivalent `changes()` for the same mutation". The first draft asserted equality against
ordinal 63 in both, which cannot hold: the 7-field fixture has no ordinal 63. The test now compares
the **first seven** ordinals, which both fixtures share by construction, and a separate test drives
ordinal 64 to prove the `Large` variant really sets a bit in its second `long` segment.

---

## F-10 — three real generator defects the § 0.5 compile gate exposed

**Plan:** § 8.3/3.7 requires the `META` output to be a real `FieldDef` enum that *compiles*.

**Finding:** routing the emitter through `fieldEnumMode` (instead of the `propertyEnumMode` path
that produced the broken example enums) immediately surfaced three defects that had never been
visible because nothing compiled the emitted source:

1. **`forName` was emitted as a `SwitchExpr` whose arms carried `return` statements.** The
   post-print arrow-flattening pass rewrote them to `case "id" -> return id;`, which is not legal
   Java (`attempt to return out of a switch expression`). Field-enum mode now emits a `switch`
   **statement**; property-enum mode keeps the switch expression.
2. **`javaType()` was generated to return `Class<?>`.** `FieldDef.javaType()` returns
   `java.lang.reflect.Type`, and a generic field is passed as
   `TypeUtils.parameterizedType(...)` — which is a `Type`, not a `Class`. The return type, the
   backing field and the constructor parameter are all `Type` now. `classTypeWithWildcard()` is no
   longer used.
3. **The emitter used the *marker's* package for a view declared elsewhere.** The lookup was
   `interfaceMap.get(marker.package + "." + view.name)`, which misses
   `paymentMethod.entity.PaymentMethodAuditable` (marker-derived from `example.Auditable`) and made
   the emitter fall back to the marker's package — emitting `example.PersonAuditable_` whose `META`
   referenced a class that does not exist. `findViewInfo` now falls back to a simple-name search.

**Consequence for the plan's § 9/4.1 file list:** the third defect means the pre-Phase-4 generator
emitted the example's `*_` enums into the **wrong package** for any view whose package differs from
its marker's. Phase 4.1 must regenerate, not merely compare, those files.

## F-11 — `CompileHarness` must include the view interfaces, and must ignore javac `NOTE`s

Two harness details worth recording, because both produce misleading failures:

- The `META` output is generated source that *implements the author's view*, so a compile gate that
  passes only the generated tree fails with `cannot find symbol: class <View>`. The gate passes the
  hand-written source root alongside the generated one.
- `javac` emits `NOTE: ... uses unchecked or unsafe operations` for any generic/varargs boundary,
  including `EntityUpdateTrackingArray.create(...)`. Only `ERROR`, `WARNING` and
  `MANDATORY_WARNING` count as diagnostics; asserting on `NOTE`s makes the gate unpassable.

## D-4 — JDK-25 `javac` was NOT used for the tooling; the module builds on ECJ

**Finding (toolchain):** `hipster-entity-tooling` logs `unchecked or unsafe operations` warnings
inside `EEnumSetBuilder.java`, and — more importantly — several edits produced
`java.lang.Error: Unresolved compilation problems` at *runtime* while `javac` compiled the same
sources cleanly (see F-6). Several `target/classes` removals were needed during this run. Any
future confusing "method does not exist" error that `javap` contradicts is almost certainly a stale
ECJ class file, not a source problem.

## D-5 — the two `@View` shapes that cannot compile are still parsed, deliberately

`@View(read = …, write = …)` (used by every old fixture) and `@View(true)` cannot come from
compiling source. The reader parses both anyway and emits
`unknown_view_attribute` / `unsupported_view_annotation_form`, because § 4.5/G9 requires the
parser to be shape-blind and to diagnose rather than crash. All old fixtures that used the
non-existent attributes have been rewritten.

## D-6 — Jackson 3 renamed two `JsonReadFeature` constants the DEC-021 table names

DEC-021 § 4 lists the pinned JSON5 subset using Jackson 2 spelling. Under Jackson 3
(`tools.jackson.*`, version `3.0.0-rc4` on this tree) the constants are
`ALLOW_UNQUOTED_PROPERTY_NAMES` (not `..._FIELD_NAMES`), the features are enabled directly on
`JsonMapper.builder().enable(feature)` (there is no `mappedFeature()` accessor), and
`ALLOW_TRAILING_COMMA`, `ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS`,
`ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS`, `ALLOW_NON_NUMERIC_NUMBERS` and
`ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER` keep their names. `EnumConstantOrderChecker` uses the
Jackson 3 names; the DEC-021 table should be updated in Phase 4.7/4.8 so the normative record and
the code agree.

## D-7 — the R1 checker reports the *moved* constant, and needs a queue-jump rule

**Plan:** § 4.6/R1.3 says the comparison is "assert the old list is a subsequence of the new list,
in the same order", and that an addition in the middle "is reported as a reorder, because that is
what it is from the bitmask's point of view".

**Finding:** a plain subsequence test does **not** catch a middle insertion —
`[id, firstName]` → `[id, email, firstName]` *is* a valid subsequence even though `firstName`
moved from ordinal 1 to 2, which is exactly the corruption R1 exists to prevent. The implemented
rule is therefore stronger and still satisfies the plan's intent: every constant still present in
the target and standing *before* the current baseline constant must itself be an *earlier* baseline
constant. A constant that jumps the queue (a new one inserted before it, or a later baseline
constant moved ahead) means the current constant's ordinal changed, and the **moved** constant
(`firstName`, old 1 → new 2) is what gets reported — which is what § 6.4 tests 21/22 assert.

## D-8 — the header comment is attached to `package`, not an orphan

**Finding:** a first implementation filtered header candidates on
`comment.getCommentedNode().isEmpty()`. JavaParser attaches the last comment of a run to the
following declaration, so the `{...}` config line that sits immediately above `package` is *not*
an orphan — the filter therefore rejected exactly the comment that carries the marker and every
marked enum read as unmarked. The reader now scans all comments but only accepts a line whose
trimmed content starts with `{`, which a javadoc body never does.

---

# Findings


**Plan:** § 4.5/G6's addon rules and § 4.3/X3's "the packages knob filters generation, not indexing"
both rest on the same mechanism: an interface declared in another package still contributes its
accessors.

**Finding:** `collectInterfaceProperties` computed the parent lookup key as

```java
String qualified = extName.contains(".") ? extName : ...extName.replaceAll("<.*>$", "");
```

The generic-stripping only ran on the *unqualified* branch, so the example's
`PersonAuditable extends Person, Auditable<Long>` produced the lookup key `Auditable<Long>` — which
is not an interface name — and the view silently lost every accessor inherited from `Auditable`.
`PersonAuditable_` was therefore missing `createdAt`/`updatedAt`, which § 9/4.1 says it must gain
**structurally** (it is not an addon user). The name is now stripped of type arguments first, then
resolved qualified, package-qualified, and finally by simple name.

## F-27 — addon merging was not implemented at all

**Plan:** § 4.5/G6 specifies the addon's accessors are merged into the declaring view's field list,
appended after its own and inherited run, with collisions skipped and reported.

**Finding:** nothing merged them, so `@View(addons = …)` was inert. With F-26 fixed, the example now
produces exactly the plan's ordinals — `PersonDetails_` is
`{id, firstName, lastName, email, phoneNumber, createdAt, updatedAt}`, i.e. 0–4 unchanged and the
audit columns at 5–6 (the plan says 0–4 then 5–6; the example's own run is one field longer than the
plan's sketch because it also inherits `Person`) — and the collision on `firstName`/`lastName` is
reported as `addon_field_collision`. `AddonAndInheritanceTest` (5 tests) pins the append order, the
collision diagnostic, per-view resolution (a sibling gains nothing), and the
`unresolved_addon` diagnostic.

## F-28 — a `RECORD`-level view is not a `ViewReader`, so the JSON serializer needed a second entry point

**Plan:** § 8.4/3.10 makes `META.create()` return the concrete record, and § 5 assumes the Jackson
serializer handles views.

**Finding:** `EntityJacksonViewSerializer` walks `ViewReader.get(ordinal)`. A proxy-backed
materialization *is* a `ViewReader`; a record deliberately is not, so
`EntityJacksonMapper.toJson(meta, view, writer)` could not serialize a `RECORD`-level view — the
demo hit this immediately with a `ClassCastException`. A second overload takes the view plus the
mapping to its positional array, which is the same ordinal contract `META.create(Object[])` consumes
in reverse; no reflection is involved and the change-set serializer is unaffected because it works
from the view's own `changedValues()` (then `diff()`, renamed by D-16).


---

## F-23 — a standalone generator run must use the *pinned* JavaParser, or sealed types vanish silently

**Plan:** § 15 records JavaParser pinned centrally at `3.28.0` (root POM property +
`dependencyManagement`), and `hipster-entity-tooling/pom.xml` deliberately declares no version.

**Finding:** that is correct for the Maven build, but the local repository holds **eleven** JavaParser
versions (`3.25.1` … `3.28.2`), and an ad-hoc classpath built by globbing the repository picked
`3.25.1` first. JavaParser before 3.26 does not accept `sealed`/`non-sealed`, so
`paymentMethod/entity/PaymentMethod.java` and its four subclasses failed to parse, the parse
`null`-check skipped them, and **the generator silently produced nothing for five files** — the four
subclass enums kept their hand-written content, and the run looked successful. The only visible
symptom was that those files were absent from a diff.

The failure mode is worth recording because it is silent and shape-dependent: a repository that uses
a Java 17+ modifier anywhere will, under the wrong parser version, generate a *partial* tree with no
error. Two mitigations are now in place: the regeneration test compares against the committed tree
(so a partial generation shows up as a diff), and this note. A generator run outside Maven should
take its classpath from `mvn dependency:build-classpath` rather than a repository glob.

## F-24 — every entity marker had to be excluded from discovery, not just the marker being iterated

**Plan:** § 4.5/G8 rule 0 — "the marker itself stays excluded".

**Finding:** the code excluded only the marker currently being iterated
(`!i.name().equals(marker.name())`). A marker derives from `EntityBase` like anything else, so from
*another* marker's point of view it looks like a derived view: the example's
`paymentMethod.entity.PaymentMethod` appeared in the view lists of **both** `example.Auditable` and
`person.entity.Person`, and a `PaymentMethod_` was generated for it with none of its hand-written
discriminator wiring. § 9/4.9 says that enum stays hand-written and is the documented hand-off for
the polymorphic path. The exclusion is now a property of the type (the set of all markers), not of
the loop.

`FieldBoilerplateGenerator.isPolymorphicRootEnum` adds a second, structural line of defence: an
existing enum whose `META` carries a **non-empty permitted-subtype list** is never overwritten. The
first version of that check keyed on "more than four `DefaultViewMeta` arguments", which is also true
of a plain generated enum whose optional arguments happen to be populated — it therefore disabled the
append/tombstone ledger for every view, and `FieldEnumLedgerRegenerationTest` caught it. Requiring a
populated array is unambiguous.

## F-25 — the S1 write-surface reduction is visible in the regenerated example, and one user tweak was lost

**Plan:** § 4.1/S1 predicted that the regenerated `Write` surface and tracking builder would drop
`age` and `departmentName`. The regenerated tracking builder does exactly that: it emits setters and
`set(int)`/`set(String)` arms only for `id`, `firstName`, `lastName` and `metadata`, while `age`
(`DERIVED`) and `departmentName` (`JOINED`) keep their read accessors and their ordinals. The
generated `PersonSummaryBuilder` behaves the same way, and the generated field enums carry the
DEC-021 header with the R1 marker.

**One deliberate loss to record.** `PersonSummaryBuilderTracking` previously carried a nested
`TrackingStrict` class (the user-authored extension point of § 4.5/G3). The emitters rewrite a whole
file, so G3's "preserve the nested class verbatim" behaviour — task 3.18 — is not implemented yet,
and the class is gone from the regenerated file. It was behaviourally identical to its parent
(§ 4.5/G3's own correction: it does *not* re-inject `EEnumSetBuilder64.Strict`), so nothing regressed
at runtime, but the plan's run-twice shape-recognition regression test cannot be written until 3.18
lands. This is the one place where "regenerate the example" and "preserve user tweaks" conflict, and
3.19's general block recognition is what resolves it.

## F-21 — the `@View` seed initially *dropped* the view it exists to find

**Plan:** § 4.5/G8 rule 0 — "a type is a view iff (a) it derives transitively from the package's
marker, **or** (b) it carries a `@View` annotation".

**Finding:** implementing the seed, the entity-root guard that excludes the documentation samples was
composed with **AND** rather than applied to the `@View` half alone — and `PersonCreateForm` **extends
nothing at all**, not even `EntityBase`. The result was that the census stayed at six views and
`PersonCreateForm_` was still missing, i.e. the exact bug the seed was written to fix. The guard is
now gone from the predicate entirely: the doc samples are already excluded twice over (both are
package-private, and X3's `packages` knob keeps their packages out of generation), so `isPublic()`
plus the filter is sufficient and the predicate reads as the plan states it. `ViewDiscoveryTest`
asserts the seven-view census so this cannot regress silently.

**Related, and the reason the bug was hard to see:** a view reached through two markers
(`PersonSummary` sits under both `PersonEntity` and `Auditable`) is discovered once per marker root,
so its per-view diagnostics were reported three times. The discovery itself was already deduplicated
(`alreadyFound`); the *reporting* is not. Recorded rather than fixed here.

## F-22 — a view with no entity root cannot carry the array-backed creator

**Plan:** § 3/§ 8 assume every view is an entity view.

**Finding:** `ViewRecordGenerator`/`proxyCreatorBody`'s `createRead(…Class<V>…)` requires
`V extends EntityBase<ID>`, so an `@View`-annotated interface that extends nothing generated a
metadata enum that could not compile. Such a view is now materialized by a concrete record instead —
which is also the honest reading of the level ladder, since a view with no entity root has no array
contract to be proxied over.


## F-20 — the ledger planner's divergences were computed and then dropped

**Plan:** § 4.5/G5 ("failures are diagnostics, not silence") and § 8.7/3.20 (report after each pass).

**Finding:** task 3.7a's planner produces a divergence entry for every appended constant, every
retired field, every bootstrap-dropped constant and a present `allowReorder` — but nothing consumed
them until `DivergenceReporter` was added and fed from `generateViewPropertyEnum`. The entries are
now printed in the uniform DEC-022 shape and readable via
`EntityMetadataGenerator.lastDivergences()`, so a test or a build step can assert on what a
regeneration changed. Running the generator over the example now prints, among others:

```
kind=enum_constant_removed, location=PersonSummary_.toBuilder, cause=the constant has no matching
accessor and the enum carried no entityFieldEnum marker, action=bootstrap: dropped pre-R1 constant
```

which is the plan's § 9/4.1 expectation — the leaked pre-R1 constants disappear under G7's bootstrap
rule — observed end to end for the first time.

## F-17 — a generated builder used a declared field type without importing it

**Plan:** § 8.2/3.6 ("stop emitting raw source type strings") and § 8.6/3.14.

**Finding:** the first `BUILDER_TRACKED` output declared `Map<String, List<Long>> metadata;` with no
`import java.util.Map;`, so it did not compile. The field *declaration* has the same
type-resolution problem the plan attributes to `parseProperty`. `ViewTrackingBuilderGenerator` now
resolves the JDK types a field declaration can name (`java.util`, `java.time`, `java.math`) to
imports; a type from any other package is left alone and the compile gate catches it. Task 3.6's
full fix — resolving through JavaParser's symbol solver so *any* type is emitted fully qualified —
is still outstanding.

**Resolved later (see F-51).** 3.6 is now complete for every name an offline reader can resolve: the
declaring unit's import table travels with the property *and* the pass's own declared types are
indexed per declaring package, so a type the accessor's own package owns — the addon case, and a
nested type — is imported too. The emission rule was resolved in favour of "the author's spelling
plus its import" for author-declared types and fully-qualified names for framework/JDK types, with
the reasoning in F-51.

## F-18 — a rendered `view.source` test fixture must declare `id()`

**Finding (test authoring):** the first tracking-builder fixture's marker extended only
`EntityBase<Long>`, so the view had no `id()` accessor while the generated copy constructor
referenced `source.id()`. The generator is right to include `id` (it is the identity ordinal);
the fixture was wrong, and now extends `Identifiable<Long>` like the real example marker.

## F-19 — a `DERIVED` field's ordinal is rejected by `set(int, Object)`, which is the S1 contract

**Finding:** the first version of the shared state-sharing sequence drove `AGE` (a `DERIVED` field)
through the generated builder's positional setter and got
`UnsupportedOperationException: Field 3 is not writable on PersonSummary`. That is **correct
behaviour** (S1: only `COLUMN` fields are writable), and the test was wrong to drive it. It now
uses a second `COLUMN` field, and a separate test asserts the rejection deliberately.

## D-9 — the generated-materialization runtime test belongs in the tooling module

**Plan:** § 6.4 places the proxy-parity fixture in `hipster-entity-test`; § 4/§ 3 require the
generated builder to be covered too.

**Deviation:** a test that must (a) call `EntityMetadataGenerator` and (b) compile and run its
output cannot live in `hipster-entity-test`, because `AGENTS.md` § 2 forbids a runtime app module
from depending on the dev-time tooling/`project-automation` modules. `GeneratedTrackingBuilderContractTest`
therefore lives in `hipster-entity-tooling` (where the generator is already local) and compiles the
emitted source into a temp directory, loads it in a child class loader, and drives it. The view-level
parity fixture in `hipster-entity-test` keeps its hand-written stand-in.

**Consequence worth recording:** because the generated field enum is loaded by a child loader it is
a *different* type from anything a test can declare, so the emitted
`implements ViewChangeTracking<…, EEnumSet<…>>` clause is asserted on the **source text** (which is
the precise DR-4 claim), while behaviour is driven through the parent-loader `ViewChangeTracking`
interface. The `instanceof` check that the generated class implements that interface is itself the
proof that the emitted clause names the same contract.


## F-15 — "wiring the nest" is a change; only a *descendant* change must stay invisible to `changes()`

**Plan:** § 11/6.8 asks for a fixture where the nested leaf is mutated and asserts that
`changes()` marks the parent field while `changesDeep()` reports the leaf path.

**Finding:** that assertion is only true when the parent field was **reassigned**. Writing
`root.set(CHILD, child)` is itself a write to the parent's own field, so `changes()` correctly marks
it — the one bit that means "the reference changed". Once that bit is cleared, a later leaf-only
mutation leaves `changes()` empty and `shallowPaths()` empty, which is the property that makes pull
worth choosing. `DeepChangeTrackingTest` asserts both halves separately, because a single assertion
covering both would be false.

## F-16 — `changesDeep()` needs a value-level walk on the array path

**Plan:** § 11/6.3 requires the nested-change index to be allocated "only when a field's declared
type is itself `ViewChangeTracking`, so a view with no nested trackable field pays nothing", and
§ 11/6.5 puts that detection in the generator.

**Finding:** an *array-backed* view has no declared field types at runtime — it stores an
`Object[]`. The array path therefore discovers nesting with an `instanceof` test on the value it
already holds, only for marked ordinals (see `nestedTrackers()`), while a generated builder can
answer the same question from its typed fields. Both satisfy the "pay only for what you use"
discipline; the plan's wording assumes the generated case only, and the difference is worth stating
so a future reader does not read the `instanceof` as a design violation.


## F-13 — the `packages` filter needs the *view*'s package, and the pre-3.7 emitter got it wrong twice

**Plan:** § 4.3/X3 and § 4.7/DR-1 specify `packages` as a *generation* filter and describe marker-package ownership in detail.

**Finding:** implementing the filter exposed that the emitter's notion of "the view's package" was
the **marker's** package, resolved by `interfaceMap.get(marker.package + "." + view.name)`. That is
wrong for any view whose declaring package differs from its marker's — the example's
`paymentMethod.entity.PaymentMethodAuditable` (marker-derived from `example.Auditable`) is the live
case. The visible symptom was a generated `example.PersonAuditable_` whose `META` referenced a class
that does not exist. Two helpers now fix it: `findViewInfo` (marker package first, then a
simple-name search) and `isGenerationTarget` (applied to the *view's* package, not the marker's).

## F-14 — the emitted `forName` and `javaType()` were both uncompilable, and nobody had noticed

Recorded separately from F-10 because it is the clearest evidence for the plan's central claim that
"the docs are ahead of the code": two of the three generators that produced the example's field
enums emitted Java that **cannot compile** (`case "id" -> return id;`, and `Class<?> javaType()`
where `FieldDef` requires `Type`). Both survived because no test ever compiled generated output, and
because the hand-written example enums were frozen before the emitter changed. Task 0.5's gate is
what makes that class of defect impossible to reintroduce.


## F-12 — the Phase 2 documents were written by a delegated subagent

`doc-hipster-entity/user/patterns/ordinal-array-contract.md` and
`doc-hipster-entity/user/patterns/jdbc-row-adapter.md` were produced by a delegated subagent rather
than by the primary executor. Both were checked for existence and encoding; their **content has not
been line-by-line reviewed** against the plan. Two points the subagent raised are recorded here
because they are real:

- **No standalone R1 DEC exists yet.** Both documents reference R1 by its exact title rather than
  inventing a decision number, because task 4.8 creates that record. Whichever number 4.8 assigns
  must be linked back into both files.
- **`FieldDef.column()` returns `null` for every enum in the tree today,** because no hand-written
  or generated enum overrides it yet. The generator now *does* emit a `column()` override for an
  annotated accessor (realising the "annotation label, else accessor name" resolution), but the
  plan's X2 wording — "add `default String column() { return null; }`" — leaves the unannotated
  case without a column name. An adapter driven purely by `column()` therefore sees no writable
  column for a view that carries no `@FieldSource` at all. Flagged for Phase 7.2–7.4.

---

## F-29 — a polymorphic subclass cannot name its root's discriminator constant, so it passes `null`

**Plan:** § 9/4.3 and § 9/4.9 — "`discriminatorField` support already exists in
`FieldBoilerplateGenerator` but is exercised only by the tooling's own boilerplate test; this task is
what exercises it end to end", with the base enum as "the documented hand-off".

**Finding:** the plan's own framing is right and its first implementation was wrong in a way only the
compiler could reveal. `DefaultViewMeta<V, F>`'s fifth parameter is typed `F` — the **view's own**
field enum. A concrete subclass therefore cannot fill it: passing `PaymentMethod_.type` from
`PayPalPaymentMethod_` is a `PaymentMethod_` where a `PayPalPaymentMethod_` is required, and widening
the parameter to `FieldDef` to make it fit broke every hand-written call site in the tree
(`incompatible types: PaymentMethod_ cannot be converted to CardOnly_`).

**Resolution, now pinned by `PolymorphicGenerationTest`:** the discriminator **field** belongs solely
to the root's hand-written enum — the one artifact that binds the family together — and a generated
concrete subclass emits a literal `null` in that slot plus its own `discriminatorValue`. That is
exactly what the committed example already did (`null, "PAYPAL", new Class<?>[0]`), so this is not a
new shape, it is the shape the plan's prose had not yet spelled out.

**Second finding, from the same task:** the *value* must be recognised structurally, not by a naming
convention. The first implementation looked for an accessor literally called `type` and defaulted to
that name when the family named its discriminator something else — which is a published name contract
the generator had no business inventing, and it silently mis-read the `PolymorphicGenerationTest`
fixture, whose family names the accessor `kind`. `EntityMetadataGenerator.discriminatorValueOf` now
recognises: a `default`, no-arg, non-void method whose body is a single `return` of a **string
literal**, whose name overrides an abstract accessor declared by a **marker** interface in the view's
supertype chain. The marker requirement is what separates a discriminator from an ordinary computed
`default` property. An explicit `@View(discriminatorField = "…")` still wins when present.

## F-30 — cooperative preservation must copy source *text*; an AST round-trip silently deletes javadoc

**Plan:** § 8.6/3.18 and § 8.7/3.19 — "Copy the recognized body verbatim, including user edits", and
G3's "the `TrackingStrict` class in the regenerated example survives regeneration".

**Finding:** `CooperativeCodegen`'s first implementation re-printed the preserved member from its
parsed AST. It looked correct — the class, its modifiers and its constructor all came back — but the
member's **javadoc was gone**, because JavaParser attaches comments to the compilation unit's comment
map rather than to the declaration node. For a user-authored extension point the comment is often the
entire content, so "verbatim" had to mean the bytes, not the AST.

The implementation now slices the member out of the original file using the parsed node's source
range, extending the slice backwards over a comment that is the sole thing between itself and the
declaration. A second iteration was needed for indentation: a javadoc's range begins at `/**`, so
slicing from there re-emitted the member flush against the left margin and produced a
whitespace-only regeneration diff. `CooperativeCodegenTest` now asserts the full text comes back
byte-for-byte, which is the assertion the first version would have failed.

**No hint comment is emitted.** DEC-020 permits one UX aid comment, and the first implementation
emitted it. It cannot be stable here: the hint sits *outside* every preserved member's range, so each
pass would drop the hint the previous pass wrote and then re-add it. Recognition is structural, so
the aid buys nothing and would only create a regeneration diff.

## F-31 — the example's regeneration check had to become in-place, or cooperative codegen cannot be tested on it

**Plan:** § 8.6/3.18 — "the `TrackingStrict` class in the regenerated example survives regeneration.
Add a regeneration test asserting exactly that."

**Finding:** `ExampleRegenerationTest.regenerate()` originally generated into a **clean temp
directory** and compared the result against the committed tree. In that configuration cooperative
codegen is vacuous by construction: the generator reads the previous revision of the file it is about
to replace, and in an empty output directory there is no previous revision, so nothing can be
preserved. Adding `TrackingStrict` to the example therefore failed the no-op assertion for a reason
that had nothing to do with the generator being wrong.

The check now copies the committed source tree and regenerates **in place** in the copy, which is what
§ 4.1/S2 actually specifies (generated source is committed into `src/main/java`) and the only
configuration in which § 8.6/3.18 is observable. The assertion is unchanged and now covers strictly
more: `PersonSummaryBuilderTracking.TrackingStrict` is committed, and in-place regeneration
reproduces it byte-for-byte.

`TrackingStrict` itself had to be **recreated**: the working tree's hand-written revision was
overwritten by an earlier regeneration pass and the original is not in git (the only commit is the
one that already contains the regenerated file), so the pre-plan revision survives only in the plan's
own quotations. The recreated class is behaviourally identical to its parent, as § 4.5/G3's
correction says it always was.

## F-32 — task 3.17a makes the generator write into the developer's own view file

**Plan:** § 8.2/G2 and § 8.6/3.17a — emit `toBuilder()`/`toBuilderTracking()` as `default` methods on
the view interface "as part of the view's generated block".

**Finding:** every other emission target is a file the generator owns. The view interface is the one
file the developer owns, so this task introduces a genuinely new class of write, and two rules keep it
safe:

- the file is written **only when a method is actually missing**. The example declares both by hand,
  so example regeneration stays byte-identical while the capability exists. This is the property that
  makes the change acceptable at all;
- when a method must be added, the rest of the file is printed through `LexicalPreservingPrinter` so
  the developer's own layout survives. The lexical printer refuses an added `default` modifier
  (`UnsupportedOperationException: Not supported keywordDEFAULT`), so the emitter falls back to the
  standard printer for that case — a cosmetic loss of formatting, where refusing to add the method
  would be a functional one, since deleting the method is the developer's only way to ask for it back.

Deliberately **not** implemented: any check that the method's return type matches the builder the
current level emits. A developer who narrows it owns that decision, and a generator that "corrected"
it would be editing code it was told to leave alone.

## D-10 — `exec:java`'s default classpath scope excludes `provided`, and the tooling must stay `provided`

**Plan:** § 8.8/3.23 — bind `exec-maven-plugin` to `generate-sources`; "**Not** a new
`hipster-entity-maven-plugin` module".

**Finding:** the binding fails with `ClassNotFoundException: EntityMetadataGenerator` if the tooling
dependency is `provided` and the plugin is left at its defaults, because `exec:java` uses the
**runtime** classpath and a `provided` dependency is not on it. The tooling must stay `provided`
(AGENTS.md §2: an application module must not gain a runtime dependency on the generator), so the
plugin is configured with `<classpathScope>compile</classpathScope>`, which is also the honest
description of when the generator runs.

The binding also needed a way to keep generated `.java` in `src/main/java` while sending the metadata
JSON to `target/`, or a build would drop three untracked `*.metadata.json` files into a source tree.
Rather than overload the positional arguments, the generator's CLI gained `--java-out <dir>`; the
positional output directory keeps its meaning (the JSON report), and `main` gained a usage block for
the flags it had accumulated. The Maven binding is now the second consumer of the same flag surface
the manual run uses, which is what § 8.8/3.23 asks for.

## F-33 — the new in-module Jackson tests found a real defect in the complex-type read path

**Plan:** § 10/5.1 and § 10/5.3 — `hipster-entity-jackson` "has **no tests of its own**"; add
round-trip coverage next to the code, and note that the existing paths are "already exercised
end-to-end" so this is **not a repair task**.

**Finding:** the coverage immediately failed, and the cause was a genuine bug rather than a missing
test. `EntityJacksonViewDeserializer`'s fallback for a complex field type read the value with
`ObjectReader.readValue(JsonParser)`. The parser has already been advanced onto the field's value
token inside an enclosing object, so the token that follows the value is the outer object's
`END_OBJECT` — which `readValue(JsonParser)` treats as a trailing token and rejects:
`Trailing token (JsonToken.END_OBJECT) found after value (bound as java.util.Map)`.

The existing tests never hit it because the only complex fixture field in the tree
(`PersonSummary.metadata`) was `null` in every case, so the reader was never invoked. Any view with a
non-null collection or object field was broken. The read now goes through `JsonParser.readValueAs`,
which is designed for reading a nested value and does not check for trailing tokens; the resolved
`JavaType` is still cached per field.

**Related, and also invisible without the new tests:** a fixture whose field type is the raw
`Map.class` binds the map's values as `Integer`, so a `{"role":[1,2]}` payload comes back as
`List<Integer>` where the view declares `List<Long>` — silently, with no error. That is why § 8.3/3.7
requires the emitter to write `TypeUtils.parameterizedType(...)` rather than a class literal for a
generic field, and the new `Account_` fixture keeps the generics so the round-trip test asserts value
types and not merely values.

## D-11 — `scripts/mvn-jdk25.cmd` and a `-D` argument: cmd splits it before the script runs

**Plan:** § 0.3 — the recorded launcher, and the `hipster-entity` shortcut that expands to the
`-pl <6 modules> -am` form.

**Finding:** `scripts\mvn-jdk25.cmd hipster-entity test -Dtest=SomeTest` fails with
`Unknown lifecycle phase "SomeTest"`. The first diagnosis recorded here blamed the `:he` branch's
positional re-expansion through `call`; **that was wrong**, and measuring it changed the fix:

- cmd.exe's batch-argument tokenizer splits an argument at `=` **and at `.`**, before the first line
  of the script executes. Measured with a two-line probe script that prints its own `%1`, `%2`, …:
  `-Dtest=SomeTest` arrives as `-Dtest` and `SomeTest`, and
  `-Dsurefire.failIfNoSpecifiedTests=false` arrives as *three* arguments (`-Dsurefire`,
  `.failIfNoSpecifiedTests`, `false`). No script-side quoting can undo that — the tokens are already
  separate by the time the script sees them.
- Quoting each property **at the call site** keeps it whole:
  `scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=SomeTest"` works, from PowerShell or from
  `cmd /c`. That is now the documented invocation in the script's usage comment.
- `-DskipTests package` (no `=`) was never affected, which is why `run-demo.cmd` worked all along.

A filtered run therefore works either way — through the shortcut with quoted properties, or by
invoking Maven directly with the same `-pl` list:

```
cmd /c "scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=X" "-Dsurefire.failIfNoSpecifiedTests=false""
JAVA_HOME="C:\Program Files\Java\jdk-25" mvn -o -pl <6 modules> -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false
```

(The second form works because `mvn.cmd` is reached through the same argument vector that made
`-Dtest=X` work in every test run recorded in this file.)

**What was fixed in the script anyway.** The `:he` branch expanded `%1 %2 %3 %4 %5 %6 %7 %8 %9`,
which silently drops a tenth argument; it now collects the remaining arguments one at a time and
re-quotes each. Two further defects that fix exposed, both recorded because they cost real time:

- **`if "%HE_ARGS%"==""` cannot be used with a value that contains quotes** — cmd's `if` parser
  rejects the embedded pair (`=AddonAndInheritanceTest" "…""=="" was unexpected at this time`). The
  emptiness test is `if not defined HE_ARGS`.
- **A batch file must stay pure ASCII and CRLF.** The first edit of this script wrote it with LF-only
  line endings (and one UTF-8 em dash in a comment), and cmd.exe then mis-parsed the `goto` loop:
  the symptom was `'m' is not recognized as an internal or external command`, three times, with no
  Maven output at all — a failure that looks like a broken script rather than a line-ending problem.
  Re-saving as ASCII + CRLF fixed it, and the script now says so in its header so the next editor
  does not repeat it.

Also worth recording: `-DfailIfNoSpecifiedTests=false` is **not** the flag — surefire's property is
`-Dsurefire.failIfNoSpecifiedTests=false`, and `-pl hipster-entity-tooling` without `-am` silently
resolves `hipster-entity-core` from the local repository instead of the reactor, which produced a
wall of `cannot find symbol` errors against a stale `ViewChangeTracking` before the cause was obvious.

## D-12 — the documentation work package was delegated, and its deviations are recorded

**Plan:** § 8.7/3.22 and § 9/4.5–4.8.

**Finding:** these tasks are text-only and were executed by a delegated subagent while the Java track
continued in parallel (as with F-12). It created `DEC-023.md`, the three module READMEs, the
getting-started guide and the tooling README's two required sections, and corrected
`getting-started.md`, `faq.md`, `materialization-levels.md`, `plan-for-continuation.md`, `DEC-012.md`,
`decisions/README.md` and `roadmap/README.md`. Four deviations it raised are accepted and recorded
here rather than silently absorbed:

- **AGENTS.md §2 does not contain the rule that "the tooling module must not depend on
  `hipster-entity-test`".** §2 says a `project-automation` module orchestrates dev-time codegen and
  that runtime app modules never depend on it. The README states that actual rule, plus the concrete
  evidence (the tooling compiles against `api` only; a tooling-to-test edge would cycle through
  `core`).
- **DEC-012's factory signature is `create(ForNameOrdinal, F[] universe, Object...)`, not
  `create(ForNameOrdinal, int, Object...)`.** The implemented signature is what was documented, with
  the plan's form noted as the value-level summary that names the length.
- **The plan has eight phases (0–7), not six.** The banner says "the combined execution plan".
- **`decisions/README.md` carries five pre-existing broken links** (`DEC-W001.md` … `DEC-W005.md`);
  the files live under `doc/architecture/decisions-watch/`. Not introduced by this execution and left
  alone, because fixing them is a separate change to the index the plan did not ask for.

## D-13 — untracked `tmp/` debris can defeat the R1 order checker

**Plan:** § 6.3/1.14–1.16, and the gate-review note GR-6 that `.kilo/worktrees/` holds a second
checkout which must not be scanned.

**Finding:** the working tree contains an untracked `tmp/` directory (`tmp/regen3`, `tmp/regen4`,
`tmp/Probe.java`) holding copies of the example's generated `*_.java` files from earlier regeneration
probes. `EnumConstantOrderCli` excludes `.kilo/worktrees/` and `/target/`, but not `tmp/`, so a
`--repo`-scoped run would read those copies as if they were part of the tree and report divergences
against the wrong file — the same class of hazard GR-6 warns about, from a different directory. It was
left in place rather than deleted, because it is untracked debris this execution did not create; the
checker's exclusion list is the thing that should grow.

**Corrected later — the hazard does not exist for this checker.** The claim above was reasoned, not
measured, and it is wrong: `EnumConstantOrderCli.run` iterates the **baseline revision's tracked
files** (`git ls-tree -r --name-only <ref>`) and only then looks the path up in the working tree, so an
untracked copy is never a comparison — it has no baseline entry to be the "after" of, and its path
cannot collide with a tracked one. Measured and pinned by
`EnumConstantOrderCliTest.untrackedCopiesInTheWorkingTreeAreNotCompared`, which plants exactly this
debris (a copy under `tmp/regen3/example/`) and asserts exit 0. So no `tmp/` entry is needed in the
exclusion list, and the debris is untidy rather than dangerous. GR-6's `.kilo/worktrees/` exclusion is
still right for the *working-tree* walk (`Files.walk`, which does see untracked files) and for the
tools that scan the tree — but not for this comparison, whose file set the baseline decides.

## D-14 — the ViewInterfaceGenerator writes to indexed input

**Finding (recorded for the README's naming table):** task 3.17a means the generator now has one
output class whose target is *input*. The tooling README's naming table lists `toBuilder` and
`toBuilderTracking` as refactor-sensitive: they are derived from the builder type names, and a rename
of the view must reach them. Because the file they live in is hand-written, an IDE rename refactor
reaches them through ordinary Java — they are `default` methods on the view itself, not strings — which
is the DEC-019 property the table exists to state.

## D-15 — the POSIX launcher exists but could not be executed in this environment

**Plan:** § 0.1 — "create one, e.g. `scripts/mvn-jdk25.cmd` + `.sh`, and reference it from
`README.md`".

**Finding (verification limit).** `scripts/mvn-jdk25.sh` exists and was reviewed by reading, but it
cannot be *run* here: the only bash on this machine is WSL's `C:\WINDOWS\system32\bash.exe`, which
fails with `CreateInstance/E_ACCESS_DENIED` (no usable distro, and a service start the sandbox
refuses), and Git's `C:\Program Files\Git\bin\bash.exe` dies at startup with
`fatal error - CreateFileMapping … Win32 error 5` — the same process-isolation boundary that makes
`mvnd` unusable (D-1) and that the platform note records as "programs cannot open named pipes". So
every gate result in this file is a **Windows/cmd** result; the `.sh` sibling is asserted only by
inspection: same six-module list, same `JAVA_HOME` pin, the `hipster-entity` shortcut with `test` as
the default goal, and no exposure to the cmd `=`/`.` argument split of D-11 (POSIX `"$@"` passes
arguments through unchanged).

**Also completed in the same place:** § 0.1's last clause — "reference it from `README.md`" — was
missing. The root `README.md` had no build section at all, so it now carries a *Building and Testing*
table naming both launchers, the JDK-25 requirement on the fork, the environment overrides, and the
two cmd.exe editing rules from D-11 (quote property arguments; keep the file ASCII + CRLF).

## D-16 — the tracker keeps no previous value: § 4.2/D2 is superseded by direct instruction

**Instruction (recorded verbatim, because it reverses a decision this plan made explicitly):**

> view change tracking class must not hold old value and no api about old value should exits. write that
> in relevant decisions and documentation and update the implementation. Any mutable will be created
> from enother mutable or imutable, and it is on user to provide both to change consumers and change
> consumers should know how to compare.

**What it supersedes.** § 4.2/D2 answered "must `previousValue(ord)` exist?" with **Yes**, and § 6.2/1.6
introduced `ChangeRecorder` to store it; § 6.4 tests 6/14/15 and § 7.2's
`{"previous": …, "current": …}` change document depended on it, and notes F-1/F-2 implemented and pinned
it. All of that is now **reversed**: the plan text stays as the historical record, the code and the
contract are gone, and DEC-012 carries the revision. The instruction's second half is the design that
replaces it: a mutable tracking view is created from another mutable or an immutable, the *caller* owns
both sides, and a consumer that wants an old→new comparison is handed both and compares them.

**Removed — the whole previous-value surface.**

| Removed | Where |
|---|---|
| `previousValue(int)`, `hasPreviousValue(int)` | `ViewChangeTracking`, `EntityUpdateTrackingArray`, `EEnumSetBuilder`, `EEnumSetBuilder64`/`Large` |
| `diff()` (renamed) | → `changedValues()`, one `FieldChange` per marked ordinal |
| `FieldChange.previous()` | `FieldChange<E>` is now `(E field, Object current)` |
| `addOrdinalChange(int, Object, Object)`, `clearPreviousValues()` | `EEnumSetBuilder` family |
| the `ChangeRecorder` class (storage: `Object[] previous` + presence bits) | `hipster-entity-core`, deleted outright |
| `previousValue`/`hasPreviousValue` proxy arms | `ArrayBackedViewProxyFactory` |
| `serialize(tracking, gen, includePrevious)`, `toJsonChanges(meta, tracking, writer, boolean)` | `hipster-entity-jackson` — and every `"previous"` member of the deep patch |
| the emitted `previousValue`/`hasPreviousValue` methods and `mf.addOrdinalChange(...)` setters | `ViewTrackingBuilderGenerator`; the example was regenerated |
| `previousValue`/`hasPreviousValue`/`diff` from the framework-accessor set | `EntityMetadataGenerator` (now `changedValues`, `currentValue`) |

**What did NOT change.** DEC-012's compare-then-mark no-op rule, the S5 state-sharing contract
(`changes()` snapshot vs `changesBuilder()` live view over one piece of state), the R1 tombstone
handling, `EntityUpdateTrackingArray#set`'s rejection of ordinal 0, and the whole deep/collection
reporting surface. What moved is only *where the comparison happens* and *what is retained*:

- the comparison is now emitted **at the write site** —
  `if (!java.util.Objects.equals(this.firstName, value)) { this.firstName = value; mf.addOrdinal(1); }`
  in a generated setter, and the slot comparison in `EntityUpdateTrackingArray#set` — so the change set
  itself is a plain ordinal set with no value state;
- a write whose value equals the one the field holds at that moment marks nothing;
- a write back to the original value afterwards **stays marked**, because nothing recorded the original.
  That is the accepted consequence of the instruction, and it is pinned by tests on both materializations
  (`writingTheOriginalValueBackStaysMarkedAndTheCallerCompares`,
  `aRevertedWriteStaysMarkedBecauseThereIsNoBaselineInTheTracker`): the tracker answers "was this field
  written with a different value", the caller's baseline answers "does it differ from what I loaded";
- the **shallow-reference hazard** the plan had to document (§ 4.2/D2, notes F-2) is gone by
  construction — there is no second copy that could go stale. `EntityUpdateTrackingArrayTest` now pins
  that positively instead of pinning the hazard.

**The one genuine ambiguity, and how it was resolved.** The instruction says the tracking class must
hold no old value. `ListChangeTracker` necessarily retains *something* from the baseline: add / remove /
reorder cannot be told apart from the current list alone, and DEC-024's whole point is that those three
stay distinguishable. The interpretation taken is:

- the retained state is each entry's **identity token** (`Identifiable.id()`), one per entry, and the
  count and positions of the baseline — **never a field value of any entry**, and never a copy of the
  element instances (elements are always read from the live list);
- so a collection delta still reports `kind`, `index`, `previousIndex`, `identity` and the element's own
  changed fields *with their current values only*;
- the alternative reading — dropping the baseline too, and computing structural deltas in a
  consumer-side comparator over the two lists the caller holds — was considered and **not** taken: it
  would delete the structural half of DEC-024 rather than the previous *values* the instruction is
  about, and the identity baseline is not a copy of the caller's data. It is recorded here so the choice
  is visible; if a future instruction is to be read that way, `ListChangeTracker` is the single place
  that changes.

**Where it is written down.** DEC-012 gained a revision section (the reversal, the accepted
consequences, and the caller-side comparison as the supported answer); DEC-024, `materialization-levels.md`,
`hipster-entity-core/README.md`, `faq.md`, `getting-started-new-project.md`,
`patterns/jackson-setup.md`, `patterns/deep-change-tracking.md`, `hipster-entity-test/README.md` and the
roadmap's decision table were updated to match. (`hipster-entity-jackson/README.md` was inspected and
needed no change: it documents the module's benchmarks, not the change-set API.) `PersonDemo` prints
the new shape: the changed-fields
step lists the fields with their current values plus the caller's own comparison, and the change-set
step shows the flat merge patch next to an `audit pair` the caller builds from the baseline view it
holds. (D-17 removed the demo's SQL step afterwards, so those two steps are numbered 4 and 5 of six
rather than 5 and 6 of seven; the content is unchanged.)

**Verification.** `EEnumSetBasicFunctionTest` and `EntityUpdateTrackingArrayTest` each gained a
reflection guard asserting that `previousValue`/`hasPreviousValue`/`addOrdinalChange` no longer exist on
the change-set builder and the tracking contract — the removal is a decision, so re-adding one is now a
failing test rather than a quiet regression. Per-module clean runs of that round: core 88, tooling 213,
jackson 26, test 31, example compiles and regenerates byte-identically (`ExampleRegenerationTest` 9/9).
The tooling count became 214 with D-17's opt-in test; the *Suite size* footer at the end of this file is
the current one.

**A verification trap re-confirmed.** Non-clean `mvn test` runs are worthless here twice over: the
compiler reports "Nothing to compile - all classes are up to date" even when a source changed, and ECJ
can leave test classes carrying `Unresolved compilation problems` markers, which then fail at runtime
with messages like *"The method create(Class&lt;E&gt;) … is not applicable"* in a class whose source does
not compile that way. Both subagent workstreams hit it independently and both had to use `clean test`
(F-47's lesson, one layer deeper: it is the *test* class files that go stale).

## D-17 — the SQL generators are a draft/exploration, opt-in only, and out of the example

**Instruction (recorded verbatim):**

> remove geenrated *Binder and *RowAdapter from examples project and mark an code generators for SQL
> as draf/exploration

followed by:

> SQL generation like others in the future must be opt-in

**What was done.**

1. **Removed from the example.** All 24 generated SQL classes are deleted from
   `hipster-entity-example` (`PersonSummaryRowAdapter`/`PersonSummaryBinder` and the same pair for
   `PersonAuditable`, `PersonCreateForm`, `PersonDetails`, `PersonDto`, `PersonUpdatableView`,
   `PersonUpdateForm`, and the six `paymentMethod.entity` views). The example's
   `exec-maven-plugin` binding no longer passes `--adapters`, so regeneration cannot bring them back,
   and the POM comment states why. `ExampleRegenerationTest` was switched to regenerate with SQL
   generation **off** in both of its in-place passes — with it on, the comparison would have reported
   every adapter as "MISSING from the committed tree", which is exactly the failure the test exists to
   catch — so the byte-identical no-op now doubles as proof that the example does not opt in.
2. **`PersonDemo` lost its SQL sections** — it no longer imports or uses the adapter pair. Section 2
   ("the SQL row adapter reads by column name") is gone; sections 3–7 are renumbered 2–6; the
   `UPDATE`/`parameterCount` lines are replaced by *"changed columns: firstName (a partial write would
   touch these)"*, read straight off the change set. The demo still exercises the whole tracking
   story, and the `run-demo.cmd` output now has six sections instead of seven.
3. **Marked as draft/exploration** in the places a reader actually meets it:
   `ViewAdapterGenerator`'s class javadoc (a `Status: DRAFT / EXPLORATION` section),
   `EntityMetadataGenerator`'s flag javadoc and `--adapters` usage line, the tooling README (intro,
   generator table, status callout, flag table, entry-point snippet and the "adding a field" recipe),
   the pattern doc `user/patterns/jdbc-row-adapter.md` (a status banner at the top plus a retitled
   final section) and its index entry in `user/patterns/README.md`, the two getting-started guides'
   flag tables, `user/patterns/field-enum-compaction.md` (which had treated generated binders as a
   shipped writer), and `DEC-023`'s three references to the binder. The
   hand-written adapter *pattern* in that document is explicitly left as valid guidance: it needs no
   generator at all.
4. **Opt-in made a tested property, not a promise.** `EntityMetadataGenerator.isGenerateAdapters()`
   was added (it previously had only a setter), and
   `ViewAdapterGeneratorTest#sqlGenerationIsOptInAndOffByDefault` generates the same fixture tree
   twice: the default pass must emit **no** `*RowAdapter`/`*Binder` while still emitting the ordinary
   generators, and only the explicit `setGenerateAdapters(true)` pass may emit them. The generator's
   flag is a plain `private static boolean generateAdapters = false` with one CLI route
   (`--adapters`) — no POM property, no profile, no annotation — so "opt-in" means exactly one
   deliberate request.
5. **The plan's § 12.1 is annotated** as re-scoped (draft/exploration + opt-in, tasks kept as the
   historical specification), and the status table's 7.1–7.4 row records what changed.

**The rule for the future, stated where it will be read.** The instruction is not only about this
draft: *any* SQL generation must be opt-in. That sentence is written into the tooling README's status
callout, the pattern doc's banner, the POM comment, the generator's javadoc and the test's name, so
the default pass can never quietly grow a SQL step. The mechanism to copy is the one mappers already
use (`--mapper`, repeatable, requested per pair) rather than a level in the `GenLevel` ladder, which
is reserved for what a view's *shape* implies.

**Verification.** The six-module clean gate is green after the removal — **359 tests** (core 88,
tooling 214, jackson 26, test 31), with the tooling count up by one for the new opt-in test — and
`ExampleRegenerationTest`'s in-place comparison proves the committed example is exactly what a default
(non-`--adapters`) pass emits, which is what makes the "the example does not opt in" claim checkable
rather than asserted. `scripts/run-demo.cmd` exits 0 with **six** sections (was seven): the row-array,
JSON, tracking, changed-fields, change-set and no-op steps, and no SQL. The out-of-gate watcher suite
is 11/11, including its own default-config assertion that no binder appears.

## F-34 — JavaParser is error-tolerant, so "the parse returned a result" is not "the file is readable"

**Plan:** § 8.7/3.20 — the two field-set diagnostics "must exist first", and `stale_switch`,
`missing_setter`, `type_mismatch`, `ordinal_drift`, `enum_order_shuffled`,
`enum_constant_removed` plus the warning-level `enum_reorder_allowed`.

**Finding (a) — the dangerous one.** `planLedger` read the existing enum with
`new JavaParser().parse(existing).getResult().orElse(null)` and treated a present result as a
successful read. JavaParser is **error-tolerant**: for genuinely broken source it still returns a
partial compilation unit — and when the syntax error is in the constant list, the partial unit has
**no constants at all**. The ledger planner therefore saw "an enum with zero constants", took the
marker-less **bootstrap** path, and rebuilt the list from the resolved fields. That is a silent
renumbering of a persisted positional array — the exact outcome R1 exists to prevent, reached by the
code written to prevent it. The check is now `ParseResult.isSuccessful()`, which treats a file with
parse problems as unreadable and reports a new `enum_not_parsed` divergence saying the pass did
**not** preserve the ledger. This is DR-7's fail-safe direction applied to the read itself rather
than only to the header.

**Finding (b) — `missing_setter` must match arity, not name.** The first implementation collected
every declared method name and checked that a writable field had one. It always passed: the builder
declares a **no-arg read accessor** for every field, so `lastName()` satisfied a check looking for
the `lastName(...)` setter. The check now requires a one-parameter method of that name.

**Finding (c) — the test harness needed two guards of its own.** Generated source is printed through
JavaParser, which uses the platform line separator, so the files carry `\r\n` on Windows while the
Java text blocks holding the damage patterns carry `\n` — a substitution written against `\n`
silently matched nothing. And a substitution that misses leaves a *valid, unchanged* file, so the
test passes for the wrong reason: this is how the first version of the class reported six failures
that were all "no divergence found", i.e. the damage had never been applied. The test class now
normalises line endings on read and asserts that each damage pattern actually matched.

**Coverage:** `DivergenceKindTest` (9 tests) covers all eight kinds plus an `enum_not_parsed` case and
a clean-pass guard asserting none of them fire when nothing is wrong — without that guard the report
becomes noise and stops being read.

## F-35 — the R1 tombstone kept the constant but nothing else knew the ordinal space had changed

**Plan:** § 8.3/3.7a (tombstoning) and § 6.4 test 25, whose generator half is a **Phase 3 exit-gate
obligation**: "the tombstone constant kept in place, `@Deprecated`, `retired() == true`, no builder
setter or `Write` method, `forName` still resolving, ordinals and `fieldCount` unchanged".

**Finding — the most serious defect this execution found.** `planLedger` implemented tombstoning
correctly: a marker-carrying enum that loses an accessor keeps the constant in place, deprecated,
with `retired() == true`, and `forName` still resolving it. But **every other emitter computed a
field's ordinal from the index of the field in the interface's resolved property list**, while the
enum defines that ordinal by the ledger. The two agree exactly while no tombstone precedes a live
field — which is why nothing had noticed.

Retire a **middle** accessor and they diverge. Measured on the real emitter before the fix, with
`firstName`, `lastName`, `email` and then `lastName` removed:

| artifact | emitted | correct |
|---|---|---|
| `PersonSummary_.java` | `id(0), firstName(1), lastName(2, retired), email(3)` | — (this half was right) |
| `PersonSummaryBinder.ORDINALS` | `{0, 1, 2}` | `{0, 1, 3}` |
| `PersonSummaryBuilderTracking` | `mf.addOrdinalChange(2, this.email, value)` | ordinal `3` |
| `PersonSummaryRecord` | `(id, firstName, email)` — three components | four, tombstone nullable |

So the JDBC binder read `view.get(2)` — the **retired `lastName` slot** — and bound it into the
`email` column, silently. The tracking builder recorded changes against the tombstone's own ordinal.
The record had one component fewer than the enum had slots, so `create()`'s positional mapping and
`EntityReadArray`'s length check would fail at runtime. None of the generated code looks wrong, and
no compiler catches any of it: the code compiles, it just addresses the wrong slot. For a rule whose
entire purpose is to stop a persisted positional layout from moving, that is the failure mode that
matters most.

**Fix.** `EntityMetadataGenerator.ledgerOrderedProperties` makes the ordinal space explicit **once**,
at the one place that can see both the interface and the committed enum on disk. It returns the
fields in the order the enum's constants occupy, with a `RETIRED` placeholder for every tombstoned
constant, and every emitter that maps a field to an ordinal now consumes that list instead of the
declaration-ordered one: `<View>Builder`, `<View>BuilderTracking`, `<View>Record` (and the nested
`create()` target), and the JDBC adapters. The field **enum** still receives the declaration-ordered
accessors, because the ledger planner is the component that decides where tombstones go.

A `RETIRED` property is not writable (`ViewAdapterGenerator.isWritable` already excluded it, since its
kind is neither `null` nor `COLUMN`), so it gets no setter, no column and no `INSERT`/`UPDATE`
fragment — while keeping its slot in `get(int)`, in the record's components and in the positional
array. Two emitters needed an explicit `isRetired` check for the parts that must *not* mention a field
that no longer has an accessor: the builder copy constructors (`this.lastName = source.lastName()`
does not compile when the interface no longer declares it — which is how the gap first announced
itself) and the named read accessors.

**Verification.** `TombstoneLedgerTest` (8 tests) covers both positions. The mid-list case asserts the
binder's `ORDINALS = {0, 1, 3}`, the tracking setter's ordinal literal, the record's nullable
component, `build()`'s positional pass-through and the absence of `source.lastName()`, and ends with a
`CompileHarness` gate — the compiler is the only artifact that checks the whole chain at once. The
trailing-removal case keeps the original assertions about the tombstone constant itself.

> **Note after D-16/D-17.** The `PersonSummaryBinder` in that measurement is the **draft** SQL
> generator's output (opt-in, `--adapters`), which the example no longer ships; the test generates it
> on purpose to keep the draft covered. The tracking-setter half of the measurement now reads
> `if (!java.util.Objects.equals(this.email, value)) { …; mf.addOrdinal(3); }` — same ordinal, no
> recorded value (D-16) — and the record/enum halves are unchanged.

## F-36 — `GenLevelResolver` rule 1 was unreachable for every entity view

**Plan:** § 4.5/G1 rule 1 — "a matching nested `record` &rarr; `RECORD`", with rule 3's `META` as the
fallback for everything else.

**Finding.** The rule compares a nested record's component list against the view's field list. Both
callers passed the view's <strong>own declared accessors</strong>: the validator never sees the
package marker, and the generator resolves the level while it is still building the type map. But the
*resolved* ordinal list of an entity view begins with the marker's `id`, which the view does not
declare. So `[firstName, lastName, age, metadata]` was compared against a record declaring
`[id, firstName, lastName, age, metadata]`, the resolver called the record **stale**, emitted a
`nested_record_mismatch` diagnostic and fell back to `META`.

The consequence is exactly the wrong shape: an author who wrote a nested record — the one signal that
says "I want a record, do not proxy me" — got an array-backed proxy, and the nested record plus its
`create()` mapping were dead code. Rule 1 could not fire for any view that inherits `id`, which is
every view of every entity. It was found because a mapper fixture declared matching nested records on
both sides and the emitted mapper went through `META.create` instead of `new Target.Record(...)`.

**Fix.** `GenLevelResolver` now accepts a nested record whose components equal the declared list
**or** that list prefixed by `id`. The check is not weakened: a record matching neither spelling is
still reported as a mismatch and still falls back to `META`. The `nested_record_mismatch` diagnostic
now prints the prefixed list, so the message matches what the reader will find in the enum.
Deliberately not handled at this level: a field contributed by an `addons` declaration, because
neither caller can resolve addons at that point; such a view keeps the old fallback, which is the safe
direction and a shape the tree does not contain. The generator's actual "is the nested record usable?"
decision is a *separate*, already-correct comparison done later with the resolved ledger order, which
is why the emitter reported `nested_record_reused` while the level said `META` — the two disagreed and
only one of them was consulted for the materialization.

## F-37 — a mapper is the first generator feature whose input is a *relationship*, not a type

**Plan:** § 12.2/7.5–7.8.

**Finding (recorded for the design, not as a defect).** Every other emitter is driven by one view.
A mapper is driven by a requested *pair*, which forced three decisions the plan leaves open:

- **Where a request is resolved.** The emitter phases run inside a per-marker loop, but the two views
  of a pair may belong to different markers. Mappers are therefore generated by a separate phase after
  the marker loop, from a catalogue of the views this pass actually emitted. That catalogue has a
  second benefit: a pair naming a view the `packages` knob filtered out cannot be half-generated — the
  request is reported as `mapper_view_not_found` instead.
- **Which package the mapper lives in.** The target's, so the return type and the constructed record
  need no import; the source is referenced fully-qualified when it lives elsewhere, which is always
  correct and avoids a simple-name clash between a source and a target that share a name.
- **How the target is built.** `new Target.Record(...)` when the target has a record, and
  `Target_.META.create(new Object[]{...})` when it does not — the same positional contract `create()`
  has always used. Without the fallback the mapper would only work for targets at a record-bearing
  level.

**Type policy, pinned by test.** Only widening conversions are emitted, and each is null-guarded so an
absent value stays absent instead of becoming a boxed zero: `Integer → Long` emits
`src.age() == null ? null : src.age().longValue()`. A narrowing (`Long → Integer`) is refused even
though a cast would compile, because it truncates on overflow; a nullable source into a primitive
target is refused too, because that would be an NPE the compiler cannot flag. Both produce a
`mapper_type_incompatible` diagnostic and a literal `null`, so the emitted file still compiles.
Fields missing on either side are reported in both directions (`mapper_field_missing_in_source`,
`mapper_field_missing_in_target`) — a silent omission in a mapper is a data-loss bug no compiler
catches.

## F-38 — `type_mismatch` reported a false positive that was really "the generator could not resolve the type"

**Plan:** § 8.7/3.20 — `type_mismatch` is one of the kinds that must be produced.

**Finding.** The check compares the constant's declared type on disk (as text) with
`parseTypeExpression(property.type())` (also as text). That is a reasonable proxy for "the developer
hand-edited the type", except for one input: `classLiteral` maps the **type parameter name `ID`** —
the one `Identifiable<ID>` declares — to `java.lang.Object`. So a view whose `id` resolves under a
marker that is still generic yields `Object.class` as the canonical type while the constant on disk
correctly says `Long.class`, and every pass reported a hand edit that never happened. Ten enum
constants in the example were reported on every run, including the first.

**Fix, and the principle.** The comparison is skipped when the canonical expression is
`java.lang.Object.class` and the declared type is not literally `Object`. `Object.class` in that
position means "unresolved", not "the developer wrote Object", and a divergence line that cannot be
acted on trains readers to ignore the whole report — which is worse than the missed diagnostic,
because the report is the only signal this subsystem has.

**Related, pre-existing, not fixed here.** `classLiteral`'s default branch passes an unrecognised
bare identifier straight through, so a view accessor returning a *different* type parameter name
(`T`, `E`) would emit `T.class` in the enum — which does not compile. It is unreachable in the tree
today (only `ID` occurs, and that one is special-cased to `Object`), and fixing it properly means
resolving the type parameter through the view's supertypes, which is § 8.2/3.6's symbol-solver work.
Recorded rather than papered over.

## F-39 — Bean Validation: two ways a constraint reaches the caller, and why both are emitted

**Plan:** § 12.3/7.9–7.12.

**Finding (design decisions the plan leaves open, recorded because they are load-bearing).**

- **The constraint's source of truth is the accessor annotation**, read with JavaParser exactly as
  `@FieldSource` is. Recognition is by **simple name** against the Bean Validation 3.0 built-in set,
  plus anything written fully-qualified under `jakarta.validation`/`javax.validation`. The
  consequence worth stating: an **unqualified custom constraint is invisible** to the generator, and
  deliberately so — it cannot tell that an unfamiliar simple name is a constraint, and pretending to
  check it would be worse than leaving it alone. The README says so in the same words.
- **Applicability follows Bean Validation's own per-annotation type rules**, not a preference,
  because an inapplicable constraint throws at validation time instead of checking anything. A
  `@Size` on an `Integer` is therefore a `validation_constraint_type_mismatch` and is **not**
  emitted — the generator does not invent a check for a constraint the provider would reject.
- **Annotations and the generated `<View>Validator` both exist, and the split is documented.** The
  annotations are the contract a provider at the boundary reads; the validator is explicit messages
  for a caller with no provider. The validator is *not* a fallback for inexpressible constraints —
  those are reported — and it states in its own javadoc which constraints it left to the provider
  (`@Email`, `@Past`/`@Future`, `DecimalMin`/`DecimalMax`). Emitting both is deliberate, so a later
  "simplification" pass has to argue with the decision rather than with an omission.
- **The null rule mirrors the provider's.** A null field is skipped by every check except the null
  constraints, because Bean Validation treats null as valid for `@Size`/`@Min`/`@Pattern`. A checker
  that disagreed with the annotations it duplicates would be worse than no checker, so the test
  asserts the exact violation set for a null field that also carries `@NotNull`.
- **The dependency is `provided`** (`jakarta.validation:jakarta.validation-api:3.0.2`, pinned to what
  the local repository holds so `mvn -o` works). `CompileHarness` had to learn the
  `jakarta/validation` tree for the same reason it already learned `tools/jackson`: the generated
  records carry the annotations, so proving they compile needs the annotation types.

**Regression the feature caused, and fixed.** Adding the constraints paragraph to the record
emitter's javadoc changed the reference shape, so `PersonCreateFormRecord.java` no longer matched the
committed example. That is the case `ExampleRegenerationTest` is written for — "a generator change
that Phase 4.1 would have to commit" — and the example was regenerated and committed in the same
pass, which is why the test is green again.

## F-40 — the compaction command's own safety check rejected compaction

**Plan:** § 12.4/7.13–7.16.

**Finding.** The first implementation verified the compacted constant list with
`EnumConstantOrderChecker.compare(original, survivors)` — the R1 checker's own comparison. It
refused every file, because that comparison reports a **removal** as `enum_constant_removed`; that is
its whole job, since a normal generation pass must never drop a constant. Compaction is the single
sanctioned exception, so asking the checker to authorise it is asking the wrong question.

It is now a dedicated `isSubsequenceInOrder` check — every survivor appears in the original and the
survivors keep their relative order — which is the exact property compaction must preserve. The
checker is still useful, one step later: run against the pre-migration baseline it names exactly the
dropped constants, which *is* the migration record. Both DEC-025 and the pattern document were
corrected, because the first draft of each claimed the checker would pass after a compaction.

This was caught by a standalone smoke probe rather than by the test suite, because the test suite
could not compile while the concurrent Phase 6 work was mid-edit.

## F-41 — two parallel workstreams, and the coordination cost

**Finding (process, recorded because it shaped the round).** Phase 6 (delegated) and Phase 7 (this
worker) ran concurrently against one repository. The modules are disjoint — Phase 6 owns `core`,
`jackson`, `test`, Phase 7 owns `tooling`, `example` — but the *build* is not: `hipster-entity-test`
and `hipster-entity-core` depend on nothing Phase 7 touches, while every Phase 7 verification needs a
compiling `core`. Three times during the round `mvn` failed inside the other worker's module and
blocked verification entirely. The workaround that kept the round productive was to compile and run
the tooling's main sources and its 184 tests **standalone** — `javac` against
`hipster-entity-{api,core}/target/classes` plus the pinned jars, then
`junit-platform-console-standalone` — which needs no reactor and no other module's tests. That
harness is worth keeping in mind for the next parallel round; it is also how F-40 was found, because
it let a probe run while the reactor was red.

## F-42 — the emitted `forName` parameter shadows a field constant called `name`

**Plan:** § 8.3/3.7 (the `forName` switch) and § 11/6.5, which is what exposed it.

**Finding.** `forName` is emitted as `public static <Enum> forName(String name)` and each arm as
`case "x": return x;`. For a view with a field called `name`, the arm for that field reads
`case "name": return name;` — and `name` resolves to the **String parameter**, not the enum constant.
javac: `incompatible types: java.lang.String cannot be converted to <Enum>`. So *any* view with an
accessor named `name` produced a field enum that does not compile, and `name` is about the most
ordinary accessor a view can have. No fixture in the tree had one until the deep-tracking test's
`Directory.name()`, which is why it survived 190 passing tests.

**Fix.** The arms now return **qualified** constants — `return Directory_.name;` — which is
independent of anything the method's own scope declares. The cost is a text change in every generated
enum, so the example was regenerated in the same pass (a legitimate "generator change Phase 4.1 has to
commit", which is what `ExampleRegenerationTest` exists to catch).

## F-43 — the `type_mismatch` guard was written against the wrong string, so it never fired

**Plan:** § 8.7/3.20.

**Finding.** The guard added for F-38 compared `normalizeType(...)`'s result against the literal
`"java.lang.Object.class"` — but `normalizeType` strips `java.lang.`, so the only value it can produce
is `Object.class`. The condition was therefore never true, and the false `type_mismatch` I had
reported as fixed was still being emitted on every pass. It stayed invisible because the tests that
would have shown it assert *specific* kinds rather than the absence of others, and the example tests
do not look at the report at all. **A guard that cannot fire is worse than no guard**, because it also
stops the search for the real cause — and the real cause was F-44.

## F-44 — the same view was emitted once per claiming marker, and the winner depended on hash order

**Plan:** § 4.5/G8 rule 0 (the discovery predicate), § 8.3/3.7a and § 9/4.1 (the example's enum is
tree output).

**Finding — the most serious defect found in the whole execution.** The discovery predicate is
`isDerivedFrom(view, marker) || view.view() != null`: the `@View` half exists so a view that extends
nothing (`PersonCreateForm`) is still found. Taken literally, it also admits a view that **is**
marker-derived to *every* marker's list, and the "one emission per view" set was declared **inside**
the marker loop. So `PersonSummary` — which derives from `person.entity.Person` — was emitted twice:
once under its real marker, where the inherited `id` is `Long`, and once under `example.Auditable`,
whose `EntityBase<ID>` makes the inherited `id` the **type parameter** `ID`. Both writes target
`person/entity/PersonSummary_.java`, and the last one wins. `packageToMarkers` was a `HashMap`, so
*which* one won varied between runs.

The symptoms this produced, in the order they were chased:

- `PersonSummary_.id` alternating between `id(java.lang.Long.class)` and
  `id(java.lang.Object.class)` across identical inputs — generation was **not deterministic**, which
  is a § 3.21 / § 0.5 exit-gate property, and `ExampleRegenerationTest` reported it as a diff without
  naming the cause.
- A stream of `type_mismatch` divergences against ten correct constants (F-38/F-43), which was the
  audit noticing the symptom rather than the disease.
- `PersonCreateFormRecord.java` declaring its component as `ID id` — a type parameter in a committed
  record, which does not compile — because the markerless view was claimed by the generic
  `example.Auditable` marker and borrowed `ID` as its id type.

**Fix, three parts.**

1. `alreadyFound` is hoisted above the marker loop, so a view is emitted **once per pass**. The
   comment in the code has always said "one emission per view, even when two markers apply"; this is
   that rule actually implemented.
2. `packageToMarkers` is a `TreeMap`, so the pass visits markers in a stable order. Compiler-mandated
   determinism had been left to a hash iteration order.
3. The `@View` half of the predicate is refined: an annotated view is a **fallback** candidate only
   when *no* marker claims it by derivation. A view with a real marker now belongs to that marker, and
   a view with no marker is still generated exactly once.

## F-45 — a markerless view must not borrow a marker's id type, and a generic marker cannot supply one

**Plan:** § 8.2/3.6 (type resolution) and § 4.5/G8 rule 0.

**Finding.** `collectViewProperties` seeded the `id` slot from `marker.entityBaseIdType()`, whatever
marker happened to be claiming the view. For a view with no entity root that is simply wrong
(`PersonCreateForm` declares no `id`, so any marker may claim it), and for a **generic** marker it is
worse than wrong: `example.Auditable<ID>` reports its own type parameter, and `ID` is not a class.
The two consumers disagreed about that name — the field enum's `classLiteral` maps `ID` to
`java.lang.Object` and compiled, while the generated record and builder declared `ID id` and did not.

**Fix.** The marker's id type is used only when the view **actually derives from that marker**; and
`emitSafeIdType` refuses a bare single-identifier name that is not a known entity-id type, mapping it
to `java.lang.Object` so the declaration and the metadata agree. For a markerless view the id type is
then resolved from the view's own `Identifiable<X>` — a small, targeted piece of the type resolution
§ 8.2/3.6 describes, applied only where a declaration cannot be emitted without it. `Node extends
Identifiable<Long>` therefore yields `Long id`, and a view that binds nothing yields `Object id`.

## F-46 — § 11/6.5's rule is unreachable as written, because declaring the contract excludes a view from discovery

**Plan:** § 11/6.5 — "detect, for each field, whether its type is itself a trackable `@View` type —
including generic collections thereof — and emit the deep-index wiring in the generated tracking
builder".

**Finding.** The natural reading of "a trackable `@View` type" is "a view that extends
`ViewChangeTracking`". That is unreachable in this codebase: G8 rule 1 excludes any interface
extending a framework surface (`ViewReader`, `ViewWriter`, `ViewChangeTracking`) from discovery,
because such a type is generated output rather than a view. A rule phrased that way matches only
hand-written fixtures — which is exactly how the deep-tracking fixtures in `hipster-entity-test` are
built (`TrackedNode`, `TrackedDirectory`), and exactly why they can never be generator targets.

**Implemented instead:** a type is trackable when its **generation level** is `BUILDER_TRACKED` or
`BUILDER_ALL`, because that is what makes the pass emit a `<View>BuilderTracking` implementing the
contract. `DEFAULT` never resolves to a tracking level, so reading the annotation's own level before
the marker loop is equivalent to resolving it and needs no resolution machinery.

The consequence is a **cast** in the generated code, and it is worth being explicit about why that is
acceptable rather than papered over: the field's declared type is the plain view interface, so the
walk is reached through `(ViewChangeTracking<<View>_, ?>) value`. That is ordinary, navigable Java —
the compiler checks the cast's possibility and the target type is named in full — and it is safe by
construction for values the generated builders produced, which is the only way a tracking value comes
to exist. The type argument is the element's **own field enum** rather than a wildcard, because with a
wildcard `changedValues()`'s result could not be widened into the `List<FieldChange<?>>` a `ListDelta` carries.

**Diagnostic instead of silence.** A field holding a view the author annotated but did not give a
tracking level gets `deep_tracking_type_not_enabled`, naming the literal fix. A nested view the author
never asked to track is silent: a report whose value is that every line is actionable must not be
filled with warnings about the normal case.

**What the wiring emits.** `changesDeep()` (this level's own marks, minus the nested fields, then one
path per nested change with the element index, plus a fallback naming a marked nested field whose
children report nothing), `nestedTrackers()` for direct children, `collectionDeltas()` asking each
element for its own `diff()`, and `hasCollection(int)`. Structural deltas are **not** produced on this
path: the builder holds the elements, so its baseline is each element's own, while the array-backed
path keeps a `ListChangeTracker` over the list it stores and is the one that can report structure.
Both agree on the paths, which is what § 6.7's parity test asserts. Each element is asked for its own
`changedValues()` (renamed from `diff()` by D-16). A view that nests nothing keeps
the interface defaults and gains no code at all.

## F-47 — the recorded gate can be satisfied by stale build output, so it must be run after `clean`

**Finding — a correction to this execution's own evidence.** `hipster-entity-core`'s test source
`EEnumSetTrackingJmhBenchmark.java` did not compile — it used `List<ChangePath>` without importing
`java.util.List` — and yet repeated `mvn test` runs of the recorded gate reported `core 88` and
`BUILD SUCCESS`. It was found only when `scripts/run-demo.cmd` failed, because that script runs
`package` (which test-compiles) after a build in which the stale output had been removed. A clean run
reproduces it immediately: `mvn -o clean test -pl <6 modules>` fails at
`EEnumSetTrackingJmhBenchmark.java:270: cannot find symbol: class List`.

The mechanism is inferred rather than proven, and it is worth stating as an inference: the recorded
gate does not `clean`, so `maven-compiler-plugin`'s incremental check can decide a module's test
sources are already up to date and skip recompiling them, and a module whose tests were compiled from
an *earlier* revision keeps passing. This is the same class of hazard the notes already record as
**F-6** (ECJ left `Unresolved compilation problems` markers in class files) and it invalidates the
green readings taken before the fix.

**What this changes about the evidence for this execution.** Every "BUILD SUCCESS, N tests" claim made
in these notes before this finding should be read as "the incremental gate passed". The claims are
restored, not withdrawn, by one clean run: the final verification is

```
mvn -o clean test -pl hipster-entity-api,hipster-entity-core,hipster-entity-tooling,\
    hipster-entity-jackson,hipster-entity-test,hipster-entity-example
```

→ **BUILD SUCCESS**, api / core / tooling / jackson / example / test all `SUCCESS`, **342 tests, 0
failures** (core 88, tooling 197, jackson 26, test 31) — the count of that round; the *Suite size*
footer at the end of this file is the current one. `scripts/run-demo.cmd` then printed the plan's
§ 9/4.2 acceptance output again, and `EntityRegenerationWatcherTest`'s 11 tests pass. (Since D-17 the
demo prints six of those steps, without the SQL `UPDATE` leg.)

**Recommendation, recorded rather than applied:** add `clean` to the recorded command in
`scripts/mvn-jdk25.cmd`, or set
`-Dmaven.compiler.useIncrementalCompilation=false`, so the gate cannot be satisfied by a previous
revision's class files. It is left as a recommendation because changing the recorded command is a
§ 0.3 decision that belongs to the plan's owner, not to an execution pass.

## F-48 — `project-automation`'s reactor carries a pre-existing failure in another subsystem

**Plan:** § 12.5/7.17's conditional, which chose `project-automation` because Phase 0.2 kept it in the
reactor.

**Finding.** `mvn -o -pl project-automation -am test` fails — not in `project-automation`, but in
`metadata-server`: `MetadataServerTest.httpForyRoundTrip` gets `HTTP response code: 500` from
`http://localhost:18510/api/fory`. That module belongs to the metadata/HTTP subsystem, is not one of
the plan's six entity modules, and is not touched by any task in it. The consequence for § 7.17 is
that the watcher cannot be verified by "the reactor is green": its tests are run directly with
`-Dtest=EntityRegenerationWatcherTest -Dsurefire.failIfNoSpecifiedTests=false`, which is how the 11
passing tests above were produced. The failure itself is left alone — it is outside this plan's scope
and fixing another subsystem's HTTP test is not what this execution was asked to do.

## F-49 — JMH's annotation processor does not run on JDK 25, so 6.9's benchmarks did not exist

**Plan:** § 11/6.9 — "**Benchmarks** (JMH, DEC-014 precedent): pull on 'no nested change' and 'deep
nested change' … this measures the cost it accepts and would only reopen the decision if pull turned
out materially worse on the no-nested-change case, where it should be free."

**Finding.** Task 6.9 was reported as "done (compiles and registered; **NOT run**, as instructed)".
Compiling is all it was: **JDK 23 and later do not run annotation processors discovered on the
classpath unless processing is requested explicitly**, so `jmh-generator-annprocess` never ran and no
harness was generated. `hipster-entity-core/target` contained the benchmark *sources* compiled as
plain classes and no `*_jmhTest` classes and no `META-INF/BenchmarkList` — so there was nothing to run
even if someone had tried, and the failure was invisible because the build is green either way.

**Fix.** `-proc:full` added to `maven-compiler-plugin` **inside the `jmh` profile only**, so the JDK
default stays in force for every other module. A `clean` test-compile with `-Pjmh` now generates the
harness; without `clean`, Maven's incremental check skips the recompile and the processor does not get
a second chance, which is F-47 again from the other side.

**The measured result** (JDK 25, `-f 1 -wi 4 -i 6 -r 2s -w 1s`, ops/ms, throughput):

| benchmark | score | error |
|---|---|---|
| `pullNoNestedChange` | 49 988 | ±1 739 |
| `shallowOnlyNoNestedChange` | 46 732 | ±805 |
| `pullDeepNestedChange` | 49 867 | ±235 |
| `shallowOnlyDeepNestedChange` | 46 896 | ±204 |

**Reading it honestly.** The gate asks whether pull is "materially worse on the no-nested-change
case". It is not worse at all: pull measures ~50.0k against the shallow equivalent's ~46.7k, and the
deep-change case costs the same as the no-change case (~49.9k) because the walk enters a child that
reports nothing and stops. D4's pull model therefore stays closed, on measurement rather than on
argument.

Two caveats, stated rather than buried. First, the shallow-only variants being *slower* than the pull
variants is not a claim that pull beats push: `shallowOnly*` reaches through `state.node.array` to a
second object, so the ~6% gap is a property of the benchmark harness (an extra indirection), not of
the two models. Only the "not worse" conclusion is being drawn. Second, these are short runs on a
shared machine — the error bars on the first, noisiest configuration were ±15–30k — so the numbers are
fit for a regression gate and not for a performance claim.

## F-50 — the two halves of the Phase 7 exit gate that had no test

**Plan:** § 12.5 — "**Exit gate:** § 12.1–12.4 tests green; the R1 checker passes after a compaction
round-trip; no library module gained a hard JDBC or validation dependency."

**Finding.** Both of those clauses were *true* and neither was *asserted*, which is the state a gate
clause decays into.

- `CompactionRoundTripTest` (2 tests) now covers the round-trip as a sequence: a ledger with a middle
  tombstone is compacted, the compacted list becomes the baseline, a generation pass over that tree is
  a no-op, and a field added afterwards lands at the **end** with the R1 checker accepting it against
  the compacted baseline. It also asserts the mirror: the *pre-migration* baseline still reports the
  tombstone's removal, which is the checker doing its job and the reason the baseline is advanced by
  the migration commit rather than the migration being smuggled past the checker.
- `DependencyBoundaryTest` (3 tests) asserts the dependency clauses by reading the POMs and the
  sources as **text**, because a dependency's *scope* is what matters and no classpath check can tell
  you that a `provided` dependency stayed `provided`. It checks that only the tooling declares
  `jakarta.validation-api`, that it is `provided` and pinned to 3.0.2, that no module declares a
  database driver or pool, and that `java.sql` appears in no library module's own source and in no
  real `import` statement of the tooling (the emitter writes it as a string literal, which an
  unanchored substring check mistakes for an import — the first version of this test did exactly
  that).

**A consequence the test found, now recorded in DEC-025.** The first run of
`CompactionRoundTripTest` failed because the revision I wrote re-added the removed accessor: after a
compaction that dropped `lastName`'s tombstone, restoring `lastName()` appends a **new** constant at
the end rather than restoring the old ordinal. That is correct R1 behaviour and it is an operational
consequence DEC-025 did not state — compaction cannot be undone by putting the accessor back, because
the old ordinals are gone and so is the data written under them. DEC-025 now says so, and the test
takes the revision from the post-removal view instead.

## F-51 — task 3.6 finished as "resolve and import", not "rewrite every name fully qualified"

**Plan:** § 8.2/3.6 — "Resolve through JavaParser's symbol solver or an import table built from the
`CompilationUnit`, then emit fully-qualified names — matching what
`EntityMetadataGenerator.classLiteral` already attempts." § 8.7/3.19 — "Copy the recognized body
verbatim, including user edits."

**The ambiguity, and how it was resolved.** 3.6 offers two resolution mechanisms and one emission
rule, and they do not have to agree. *Resolution* is now complete for everything an offline reader
can see (see below). *Emission* is deliberately mixed, and this is the interpretation the plan does
not settle:

- **framework and JDK types are emitted fully qualified** — `java.util.Map`,
  `hr.hrg.hipster.entity.core.ViewChangeTracking`, `java.time.Instant` — exactly as
  `classLiteral` already did;
- **a type the view author declared is emitted as the author spelled it, with its import** —
  `private Node head;` plus `import a.hr.Node;`, or `thing(LocalThing.class)` plus
  `import p.addon.LocalThing;`.

The reason is DEC-019: generated source is supposed to be readable and IDE-navigable, and a
`{@link}`-able simple name with a single-type import is both of those things, while
`private a.hr.Node head;` is neither. The plan's phrase "emit fully-qualified names" was written
against the *defect* it was diagnosing — a raw, **unresolved** type string reaching the emitter, with
no import and nothing to resolve it — and that defect is gone: every name now either resolves to a
known FQN, travels with the import that makes it resolve, or is reported. Nothing emits an
unresolved raw string any more, which is the property the task exists to establish.

**Two real defects the completion found.** Both were silent — the output did not compile and nothing
said so, which is the failure class 3.6 is about.

1. **A type from the addon's own package was never imported.** `JdkImportSupport`'s documentation
   claimed a type named without an import is "a type in the declaring package — same package as the
   generated file, so no import is needed". That is false for the plan's own anchor feature: an addon
   declared via `@View(addons = …)` lives in a different package than the view, and its accessor may
   name one of *its own* package's types without importing it. Measured before the fix: a view in
   `p.view` with `@View(addons = {AuditAddon.class})` where `addon.other.AuditAddon` declares
   `LocalThing thing();` produced `p/view/PersonDetails_.java` containing `thing(LocalThing.class)`
   with **no import for `p.addon.LocalThing`** — `javac`: *cannot find symbol: class LocalThing*.
2. **A user's nested type inside a generated record or field enum was deleted.** The whole-file
   builders preserved user nested members (`CooperativeCodegen`, G3's `TrackingStrict`), but
   `ViewRecordGenerator` and `FieldBoilerplateGenerator` rewrote their files outright. Measured
   before the fix: a `public static final class UserStrict { }` with a javadoc, added inside
   `PersonSummaryRecord.java` or `PersonSummary_.java`, was gone after the next pass — the same
   silent-deletion failure as F-25, in two files F-25's fix did not reach.

**What was implemented.**

- `Property.typeImports()` still carries the declaring unit's import table (the previous round's
  slice), and the reader now adds a second source: a pass-wide index of every type the source set
  declares (`declaredTypesBySimpleName`), consulted **only for the package the accessor was declared
  in**, because that is the only candidate Java's own resolution order can produce for an unimported
  simple name. `EntityMetadataGenerator` was split into "parse every file" / "read every interface"
  so the index is complete before any property is read — reading while walking cannot see a file it
  has not reached yet, which is exactly the cross-package case.
- A name the declaring package declares **twice** (two nested `Inner` types under different outer
  types) is the one genuinely ambiguous case. It is *reported*, in the DEC-022 shape, as
  `type_ambiguous` with both candidates named, rather than resolved by hash order — the same
  treatment F-44 gave the multiply-claimed view.
- **Not** resolved: on-demand (`import a.b.*;`) imports, a `java.lang` type, a type variable, or a
  type from outside the source set. All of those are either correctly import-free or genuinely
  unresolved, and the compile gate reports the last one. JavaParser's symbol solver would cover them;
  this build stays offline and on the pinned `javaparser-core`.
- `CooperativeCodegen.preservedNestedTypes` now accepts any top-level type declaration (class,
  interface, enum, record) instead of only a class, and both remaining whole-file emitters call it.
  A record or enum emitter produces no nested type of its own, so everything found in the previous
  revision is the developer's and is copied as **text** (javadoc included) rather than re-printed.

**What is still not preserved, and why it is not an oversight.** A user-added *non-type* member (a
helper method, a field) and an edit to the body of a *generated* method are still overwritten in the
record and enum, and were never preserved in the builders either. Recognising those is a different
mechanism: the generator owns those names, so DEC-020's three-state model requires comparing the
previous revision against the canonical emission member by member and reporting a divergence when
they differ — the treatment the R1 ledger already gives the enum's constant list, and the reason a
hand-added `forName` arm is reported (`stale_switch`) rather than silently kept or dropped. Shipping
that comparison for every generated method is a change to every emitter and to what "regeneration is
a no-op" means; it is recorded here as the remaining part of 3.19 rather than half-done. The plan's
own 3.19 list is satisfied for the shapes it names: the nested `record`/`Write` (which live in the
hand-written view file and are never overwritten), the user-added nested class (now in all four
whole-file emissions), and the `forName` arms (tombstone-aware re-emission plus the reported
divergence).

**Tests.** `DeepTrackingWiringTest.aNestedTypeFromAnotherPackageIsImportedIntoTheEmittedBuilder`,
`AddonAndInheritanceTest.aTypeFromTheAddonsOwnPackageIsImportedIntoTheGeneratedView` (both with a
`javac` gate, both of which fail on the old code),
`AddonAndInheritanceTest.aNameTheDeclaringPackageDeclaresTwiceIsReportedRatherThanGuessed`, and
`CooperativeCodegenTest.aUserMemberInsideTheGeneratedRecordAndEnumSurvives` (two passes, byte-identical,
then compiled). Tooling: 202 → 206 tests.

## F-52 — a probe that avoids the reactor's `target/classes` fails for the wrong reason

**Finding (verification method).** While checking the 3.6 work outside Maven, a freshly generated
tree failed `javac` with *cannot infer type arguments for `EEnumSetBuilder64<>`* and two
*does not override or implement a method from a supertype* errors. None of it was the generator's:
`dependency:build-classpath` resolves `hipster-entity-api`/`hipster-entity-core` to the
**installed** `~/.m2` jars, which are a previous revision, so the generated source was compiled
against stale `core`. Prepending the reactor's own `hipster-entity-api\target\classes` and
`hipster-entity-core\target\classes` made the same tree compile with exit 0. This is F-47's lesson in
a second place: any out-of-band check must name the current revision's classes first, or its failures
are as untrustworthy as its passes. It also cost a false lead — the two `@Override` errors looked
like a broken emitted supertype and were purely unresolved-supertype noise.

## Plan scope status

| Phase | Status after this execution round |
|---|---|
| 0.1/0.1a/0.2/0.3/0.4/0.5 | **done** — JDK-25 script (both `scripts/mvn-jdk25.cmd` and its `scripts/mvn-jdk25.sh` sibling, the latter reviewed rather than run: see D-15), POM fix, baseline, shared compile gate, determinism gate. The launcher's `hipster-entity` branch was repaired and the root `README.md` now references both scripts (§ 0.1's last clause, which was missing): see D-11 and D-15 |
| 1.1/1.2/1.3 | **done** — `ViewWriter.get(String)`/`supports(String)`, `FieldDef`'s five `@FieldSource` accessors |
| 1.4–1.11 | **done** — tracking-array NPE fixed, S5 contract, proxy arms, accessor renames with no aliases, JMH call sites (4 ctor + 8 accessor) updated. **`ChangeRecorder` (1.6) was implemented here and then deleted by D-16's instruction**: the tracker keeps no previous value, and the compare-then-mark rule now lives at the write site |
| 1.12/1.13 | **1.12 done; 1.13 was NOT done and this row claimed it was** — `ViewAnnotationReader` + `GenLevelResolver`, `ViewAnnotationRule` rewritten, 9-case shape matrix. But "wire the validator into the generator" (`EntityRulesValidator` constructed from `src/main`, issues collected before any write) did not happen: the class was instantiated **only from tests**, there was no `validate` subcommand, and the four rules had no production call site at all. Measured afterwards by running them by hand over the committed example: **20 issues about 19 correct interfaces plus one stale `@View(addons=…)` on the `PaymentMethod` marker**. Fixed in the follow-up round — see `plan.dsflash.md` § 6.3/1.13 for what landed (`--validate[=OFF\|REPORT\|STRICT]`, a `validate` subcommand, `EntityRule.validateAll`, rewritten `ViewInterfaceRule`/`MarkerEntityRule`, `ValidationIssue.kind()`/`isWarning()`, and the example's binding running it) and `ViewInterfaceRuleTest`/`GateParityTest`/`DefaultViewMetaContractTest` for the tests |
| 1.14/1.15/1.16/1.17 | **done** — `EnumConstantOrderChecker`, `EntityFieldEnumOrderRule` (registered in `EntityRulesValidator`), 11 R1 tests. 1.16's CLI (`EnumConstantOrderCli`, reachable as the tooling entry point's `enum-order` subcommand with `--repo --baseline [--target] [--diff] [--strict]`, documented in `hipster-entity-tooling/README.md`) was implemented earlier but **untested** until this round: an earlier revision of this table called it "not wired yet", which was wrong — it was wired and simply never exercised. `EnumConstantOrderCliTest` (6 tests) now drives it against a real temporary git repository and pins the exit-code contract: append → 0, removal → 1, shuffle → 1, an unmarked *baseline* enum skipped → 0 (opt-in by absence, R1.2), untracked working-tree copies not compared → 0 (which corrects D-13), missing `--baseline` → 2, and `--diff` refused with 2 while unimplemented rather than silently ignored |
| 2.1/2.2 | **done** — the two pattern documents (written by a delegated subagent; see F-12) |
| 2.3 | **done** — the boundary test the task asks for lives in `hipster-entity-test` as `ResultSetRowAdapterTest` (4 tests): a fake `ResultSet` (a `Proxy` over `java.sql.ResultSet` that answers `getObject(String)` and throws on anything else) feeds a hand-written ~15-line reference adapter, the resulting `Object[]` becomes a view, every accessor is asserted against its own slot, and `values.length == PersonSummary_.values().length` is pinned. An earlier revision of this table said "not started": the test already existed, the row was wrong. The *generated* adapter is a separate, opt-in draft — see 7.1–7.4 and D-17 |
| 2.4/2.5 | **done** — `EntityJacksonChangeSerializer`, `EntityJacksonMapper.toJsonChanges`, `changeSerializer`, 8 new tests including the "same patch from both paths" exit gate. **Amended by D-16:** the `includePrevious` mode and the `{"previous":…,"current":…}` shape are gone; the document is a JSON Merge Patch of current values, and an audit-style pair is the caller's comparison of the baseline instance with the tracking view |
| 2.6 | **done** — `jackson-setup.md` rewritten around Jackson 3 coordinates, the null policy and the change-set shape |
| 3.1–3.4 | **done** — real `ViewAttributes`, shape-blind `ViewAnnotationReader`, `GenLevelResolver`, `gen` in the metadata JSON round-trip |
| 3.5/3.6 | **done** — 3.5 is regression-tested (default-method leak + framework accessors); 3.6 is complete as of F-51. Type names are resolved from three sources in Java's own order — the declaring unit's import table, the pass-wide index of types the *declaring package* owns (an addon's own package type, a nested type), and `JdkImportSupport`'s JDK table — with `type_ambiguous` reported when the declaring package declares the name twice and on-demand imports deliberately unresolved. Emission keeps the author's spelling plus its import for author-declared types and uses fully-qualified names for framework/JDK types; F-51 records why that reading of "emit fully-qualified names" is the one DEC-019 supports |
| 3.7/3.7a/3.8/3.9 | **done** — `FieldDef` shape, `Type` return type, `forName` switch statement, DEC-021 header with the R1 marker, `@FieldSource` overrides, and **3.7a's append-only ledger regeneration** (marker-scoped bootstrap, tombstoning, append-at-end, idempotence). 5 ledger tests plus `TombstoneLedgerTest` (8 tests), which covers **§ 6.4 test 25's generator half** and in doing so found and fixed the ordinal-space defect of F-35. `enum_not_parsed` (F-34) closes the fail-safe hole in the ledger *read* |
| 3.10–3.13 | **done** — `RECORD` (`ViewRecordGenerator`: top-level record, component order = enum order, `create()` targets it positionally, nested record reused rather than duplicated), `WRITABLE` (falls back to the array-backed updatable proxy as § 8.5/3.13 prescribes), `BUILDER` (`ViewBuilderGenerator`: mutable fields, writable-only setters, `get(int)`/`set(int,Object)`/`set(String,Object)` with the `-1` probe, `build()` targeting the record) |
| 3.2a | **done** — the view predicate is now marker-derivation **OR** `@View`, surfaces excluded, no descent into nested types, one emission per view, plus a package-private guard. 
`ViewDiscoveryTest` (7 tests) covers each clause, regeneration as a fixed point, and the real-tree `person.entity` census (seven views including `PersonCreateForm_`; no `Person_`; no `Write_`) |
| 3.14/3.15/3.16 | **done** — `ViewTrackingBuilderGenerator` emits `<View>BuilderTracking` with the S5 accessor pair, the verified setter order, generation-time 64/Large selection, the mandatory baseline copy constructor, and no setter for a `DERIVED` field; 16 tests across two classes, one of which compiles and **runs** the emitted class |
| 3.17 | **done implicitly** — the level ladder is cumulative, so `BUILDER_TRACKED` emits the plain builder too and `BUILDER_ALL` emits both. Caught by an assertion rather than assumed: `AllLevelsCompileTest.theLadderIsCumulative` failed until `BUILDER_TRACKED` stopped *dropping* the untracked builder |
| 3.17a/3.18 | **done** — `ViewInterfaceGenerator` emits `toBuilder()`/`toBuilderTracking()` as `default` methods on the view interface for `BUILDER`/`BUILDER_TRACKED`/`BUILDER_ALL`, shape-recognised (an existing method is the developer's, whatever its body says) and written only when one is actually missing, which is what keeps example regeneration a no-op. `CooperativeCodegen` preserves user-added nested members verbatim by **source range**, so `TrackingStrict` survives with its javadoc intact; `CooperativeCodegenTest` (5 tests) includes the run-twice byte-identical assertion the plan asks for, and `ExampleRegenerationTest` now regenerates **in place** so the real example proves the same property |
| 3.19 | **done for the shapes the task names; one mechanism remains open** — the whole-file emitters (`<View>Builder`, `<View>BuilderTracking`, `<View>Record`, the field enum) preserve a developer's nested type verbatim, javadoc included, by **source range** (F-30), and `CooperativeCodegenTest` proves it is a fixed point across passes for all four files; the nested `record`/`Write` live in the hand-written view file, which the entry-point emitter edits in place rather than rewriting; `FieldBoilerplateGenerator` performs the R1 ledger's shape-independent preservation for the constant list. **Open:** a user-added *non-type* member, and an edit to the body of a *generated* method, are still replaced — recognising those needs DEC-020's three-state comparison per member in every emitter (the R1 ledger does it for the constants, and a hand-added `forName` arm is reported as `stale_switch` instead). See F-51 for the full statement and the measurements |
| 3.20 | **done** — `DivergenceReporter` carries the uniform DEC-022 shape and the recognized-kind list, the generation pass surfaces its entries (`EntityMetadataGenerator.lastDivergences()` plus a printed report), and **every declared kind is produced by a check**: the two field-set kinds and `stale_switch` / `type_mismatch` / `ordinal_drift` from `auditExistingEnum`, `missing_setter` from both builder emitters, `enum_reorder_allowed` and `enum_not_parsed` from the ledger planner, `mapper_*` from the mapper phase, `validation_constraint_*` from the validation pass, `deep_tracking_type_not_enabled` from § 11/6.5, plus the pre-existing `enum_constant_appended` / `enum_constant_removed` / `field_retired` / `nested_record_reused` / `addon_field_collision` / `polymorphic_root_enum_preserved`. `ordinal_drift` is deliberately reported only on the bootstrap path. 9 tests in `DivergenceKindTest` plus the per-feature assertions. **Known remaining gap:** a view reached by more than one marker reports its divergences once per marker — the *emission* duplication was fixed in F-44, the reporting duplication was not |
| 3.21 | **done** — determinism is asserted in `GeneratedSourceCompilesTest` (byte-identical across passes) and again as a fixed point in `ViewDiscoveryTest` and `CooperativeCodegenTest`. It is also what caught F-44: the example's `${View}_.id` alternated between `Long.class` and `Object.class` across identical inputs |
| 3.22 | **done** (delegated doc pass) — `hipster-entity-tooling/README.md` carries the naming-contract table (refactor-sensitive derived names vs refactor-insensitive explicit labels) and the R1 order-contract section, including the operational recipe for adding a field, the `allowReorder` escape hatch and the checker CLI. See D-12 for the deviations |
| 3.23 | **done** — the `exec-maven-plugin` binding is wired into `hipster-entity-example` at `generate-sources` with the CLI flags (`--packages`, `--java-out`; **not** the SQL-generation flag — see D-17), `classpathScope=compile` (D-10), and a `jcodebuddy.entity.codegen.skip` knob. Generated `.java` is regenerated in place; the metadata JSON goes to `target/entity-metadata`. No new Maven plugin module was created |
| 4.x | **4.1, 4.2, 4.3, 4.4, 4.9, 4.10 done** — the example's two entity packages are regenerated and committed: the S1 write-surface reduction (including the hand-written `PersonSummary.Write`, whose `age`/`departmentName` setters are dropped), the dropped pre-R1 constants, the orphan `Write_` and the two stale `example/` enums deleted, the four `paymentMethod` subclasses generated, `PersonCreateForm_`/`PersonCreateFormRecord` newly emitted, the hand-written `PaymentMethod_` root preserved, `PersonDetails` exercising `addons`, and `TrackingStrict` restored as a preserved user member. **The JDBC adapters were generated here too and were later removed again by D-17** — the example ships no SQL classes now. `ExampleRegenerationTest` (9 tests) asserts **in-place** regeneration is a byte-identical no-op — which is how F-44 was found — and after D-17 it regenerates with SQL generation off, so it also proves the example does not opt in. `PersonDemo` runs end to end via `scripts/run-demo.cmd` (six sections since D-17). **4.3/4.9 done:** `PaymentMethodController` is driven by the generated enums through a direct-call switch, with `discriminatorValues()`/`metaFor(String)`/`permittedSubtypes()`; `PolymorphicGenerationTest` (5 tests) pins the field/value split of F-29. **4.10 done:** the three cp1252-contaminated DEC files were transcoded surgically. **4.4 done** by the Phase 6 workstream: `TrackedDirectory`/`TrackedNode` fixtures plus `TrackedPersonSummaryFixture`, and `DeepChangeTrackingParityTest` (9 tests) runs the § 6.4 sequences against both materializations — the proxy path and the builder path |
| 5.x | **done** — `hipster-entity-jackson` has its own test surface: 26 tests across `JacksonViewRoundTripTest`, `JacksonAbsentFieldPolicyTest`, `JacksonPolymorphicViewTest` and the deep-patch suite, covering the `META`/proxy route, the `RECORD` route through the positional overload, unknown-field skipping, module registration, both JSON wrappers, the S4 absent/explicit-null policy, the polymorphic field/value split, and the RFC-6902-like deep patch. The in-module tests found and fixed a real defect in the complex-type read path (F-33) |
| 6.x | **done** — `DEC-024`, `ChangePath`, `ListDelta`/`ListChangeKind`/`CollectionDiagnostic`/`ListChangeTracker`, the deep walk on both materializations, the 3-level `List<Tracked>` proof, the deep JSON patch, the pull-vs-push JMH axis, and `user/patterns/deep-change-tracking.md`. **6.5 (generator wiring) done** — see F-46 for why the plan's own wording for it is unreachable and what was implemented instead; `DeepTrackingWiringTest` (8 tests) covers detection, the emitted wiring, the cross-package import, the compile gate and the diagnostic. **6.9 now measured, not just instrumented** — see F-49: pull on the no-nested-change case is ~50.0k ops/ms against the shallow equivalent's ~46.7k, so the decision is not reopened, and the deep-change case costs the same as no change. Getting there needed `-proc:full` in the `jmh` profile, because JDK 25 does not run the JMH processor implicitly. **Amended by D-16:** collection deltas still report `kind`/`index`/`previousIndex`/`identity`, but the per-element field deltas carry the element's **current** values only, and the retained baseline is each entry's identity token — never a field value |
| 7.1–7.4 | **done as a DRAFT/EXPLORATION, and re-scoped to opt-in only (D-17)** — `ViewAdapterGenerator` emits `<View>RowAdapter` + `<View>Binder`; 5 source-level tests plus a zero-diagnostic compile gate, and **7.4's fake-`ResultSet` round trip** in `GeneratedAdapterRoundTripTest` (6 tests): a generated reader fills one slot per field, every accessor reads its own slot, an absent column stays null, and the generated binder writes exactly the writable non-tombstoned columns in ordinal order as SQL NULL or a value. The fixture carries a tombstone **and** a DERIVED field at once, so `ORDINALS = {0, 1, 3}` is asserted rather than assumed. § 2.3's boundary test lives in `hipster-entity-test` (`ResultSetRowAdapterTest`, 4 tests) against the hand-written fixture. **What changed (D-17):** the generator is documented as an unsupported draft, it is reachable **only** through `--adapters` (off by default, no property or profile can enable it — pinned by `ViewAdapterGeneratorTest#sqlGenerationIsOptInAndOffByDefault`), and the example no longer generates or ships it: its 24 committed `*RowAdapter`/`*Binder` classes were deleted, the POM's `--adapters` argument is gone, `ExampleRegenerationTest` no longer enables it, and `PersonDemo`'s SQL sections became "which columns would a partial write touch?" read straight off the change set |
| 7.5–7.12 | **done** — `ViewMapperGenerator` (7.5–7.8): a requested view-to-view mapper emitted into the target's package, with direct accessor calls, a widening-only conversion table (each widening null-guarded), a `null` plus a diagnostic for anything else, and both missing-field directions reported; 8 tests including five compile gates. `ValidationGenerator` (7.9–7.12): constraints read off the accessors, emitted onto the record's components and both builders' fields where Bean Validation's own applicability rules allow, `validation_constraint_type_mismatch` / `validation_constraint_unsupported` otherwise, a `<View>Validator` with explicit messages for the mechanical subset, and 9 tests that **run** the emitted validator against a valid instance and one negative case per constraint. `jakarta.validation-api:3.0.2` is `provided`; the README documents the recognised set and the two limits |
| 7.13–7.16 | **done** — `EnumCompactionCli` implements the deliberate migration: a subcommand (`enum-compact`), two mandatory acknowledgements, tombstone recognition requiring both `@Deprecated` and `retired() == true`, a refusal to touch a marker-less or unparseable file, an internal subsequence-in-order verification (F-40), and a report naming every dropped constant and every moved ordinal. `DEC-025` records the decision and `user/patterns/field-enum-compaction.md` the end-to-end procedure. 10 tests, dominated by what it refuses |
| 7.17 | **done** — `EntityRegenerationWatcher` in `project-automation` (the module § 12.5 keeps in the reactor, so the conditional's first branch applies). It pairs `java-watch-core`'s batched watcher with the generator, takes its flags from the same CLI-style surface, and breaks the self-write loop with a **content** check rather than a timing flag, because filesystem events arrive asynchronously and a flag clears too early. It runs at most one no-op pass per generated-file set, then goes quiet — asserted, not assumed. 11 tests drive the decision directly; no test waits on real filesystem events |

**Suite size:** **359 tests, all green** — core 88, tooling 214, jackson 26, test 31 — verified by a
**clean** reactor run (`mvn -o clean test -pl <6 entity modules>`), with `api` and `example` `SUCCESS`
and `BUILD SUCCESS` overall. The clean run matters twice over: see F-47, which found that the recorded
gate can be satisfied by a previous revision's class files, and D-16, which found that a non-clean run
reuses ECJ-emitted *test* classes carrying `Unresolved compilation problems`. The example additionally
carries the `exec-maven-plugin` `generate-sources` binding whose regeneration pass is asserted
byte-identical (with SQL generation off, per D-17), and `scripts/run-demo.cmd` prints six of the
§ 9/4.2 acceptance steps: § 4/§ 5 of that output show D-16's shape (the changed fields with their
current values, the caller's own comparison, and the flat merge patch next to a caller-built audit
pair), and the SQL `UPDATE` leg of § 9/4.2 is gone with the rest of the example's SQL (D-17). § 7.17's
watcher has 11 passing tests outside this reactor (F-48). Baseline for the whole execution was 68 tests.
**Where the execution stands.** Every phase's tasks are complete, with one mechanism explicitly left
open and two scope decisions recorded rather than half-built:

- **3.19's per-member three-state comparison** is the one mechanism still open — a user-added non-type
  member, or an edit to a generated method's body, is still replaced; nested types are preserved
  everywhere, which is what the task's own shape list names (F-51). 3.6 is complete (F-51), and 6.9's
  benchmark result was measured rather than instrumented (F-49).
- **§ 4.2/D2's previous-value decision was reversed** by direct instruction and replaced by
  caller-side comparison — the only part of the plan deliberately undone (D-16, and DEC-012's revision
  section).
- **§ 12.1's SQL generation was re-scoped to a draft/exploration that is strictly opt-in** and removed
  from the example (D-17): the generator still exists behind `--adapters` with its tests, the example
  ships no `*RowAdapter`/`*Binder`, the demo's `UPDATE` leg is gone, and the opt-in rule is pinned by
  `ViewAdapterGeneratorTest#sqlGenerationIsOptInAndOffByDefault`. § 12.1 and § 9/4.2 carry the
  re-scope annotation in the plan itself.

Two exit-gate obligations that were open are discharged: § 6.4 test 13's textual assertion, and test
25's generator half. The clearest remaining risk is unchanged: § 7.17's watcher lives in
`project-automation`, outside the six-module reactor, so its 11 tests are not part of the recorded gate
— they were run explicitly and pass, but nothing in the gate would catch their regression.

