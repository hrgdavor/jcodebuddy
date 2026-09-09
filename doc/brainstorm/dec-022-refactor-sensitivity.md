# Refactor-Sensitivity Rules for Generated Code — Proposals

> **Status:** proposal, captured for [DEC-022](../architecture/decisions/DEC-022.md).

The full decision (rationale, alternatives, consequences, acceptance
criteria) lives in
[`doc/architecture/decisions/DEC-022.md`](../architecture/decisions/DEC-022.md).
This file is the **executive summary** for reviewers: the two
proposals at the heart of the decision, why they are needed, and
what the concrete rules look like.

## Proposal 1 — Two naming contracts: implicit derivation vs explicit API name

**The problem.** When a generator derives a name from a source
identifier (type name, method name, field name), a normal IDE
rename refactor on the source can leave the generated name
disjointed. The IDE refactor is correct for the source; the
generated code is correct for the generator; the two are now
inconsistent. The developer expected the rename to be consistent;
it is not.

**The proposal.** Every derived name a generator emits must be
classified as one of two contracts:

- **Refactor-sensitive (implicit derivation).** The generated
  name is derived from a source identifier, and the relationship
  must be wired through navigable Java so a standard IDE rename
  refactor can reach it. The wiring is ordinary Java
  declarations and references: `{@link}` in the class-file
  header, `@see` / `@GeneratedFrom` in Javadoc, the generated
  declaration itself. The IDE's rename refactor follows these
  references and offers to rename the generated code too.

- **Refactor-insensitive (explicit API name).** The generated
  name is NOT derived from a source identifier. It is either
  hard-coded by the generator or configured explicitly. The IDE
  rename refactor on the source must NOT touch it, because the
  name is an explicit contract with external clients (JSON-RPC
  method labels, workflow trigger names, audit event names).

**Concrete examples.**

| Emitted name                       | Derivation         | Contract          | Why |
| ---------------------------------- | ------------------ | ----------------- | --- |
| `OrderView` record name            | implicit (`Order` + `View` suffix) | refactor-sensitive | Renaming `Order` should offer to rename `OrderView` |
| `withTotal` builder method name    | implicit (`total` field) | refactor-sensitive | Renaming `total()` accessor should offer to rename `withTotal` |
| `"users.get"` JSON-RPC case label  | explicit (`@RpcMethod.value()`) | refactor-insensitive | API label must not change when Java method is renamed |
| `"shipment.create"` next-step trigger | explicit (workflow name) | refactor-insensitive | Workflow name is an external contract |

**What generators must do.**

- Every generator MUST publish a **naming-contract table** in its
  README listing every derived name, its derivation, and whether
  it is refactor-sensitive.
- Every refactor-sensitive name MUST be wired through navigable
  Java (`{@link}`, `@see`, `@GeneratedFrom`, or the generated
  declaration itself).
- Every refactor-insensitive name MUST be an explicit API name,
  not derived from a source identifier.

## Proposal 2 — Divergence reporting after every regen pass

**The problem.** Even with explicit contracts, IDE refactors can
make generated code diverge from what the generator would emit
today. The developer renames a method, the IDE updates some
references but misses others, and the generated code is now
inconsistent. Today the generator says nothing; the developer
discovers the inconsistency later (test failure, runtime error,
manual inspection).

**The proposal.** After every regeneration pass (build-time or
sidecar), the generator compares its current output against what
it **would** emit if it ran from scratch on the current source.
Any difference is a **divergence**, and the generator MUST emit
a diagnostic.

**The diagnostic format.**

```
[generator:divergence] <generatorId> / <file>
  kind:     refactor-divergence | source-change-divergence
  location: <path>:<startLine>-<endLine>
  cause:    <what the IDE refactor did>
  current:  <what is in the file now>
  canonical:<what the generator would emit today>
  action:   <suggested user action>
```

Two kinds:

- **source-change-divergence** — the source changed in a way the
  generator tracks (e.g. `Order` renamed to `PurchaseOrder`).
  The canonical output is the new derived name
  (`PurchaseOrderView`). The user should rename the generated
  record or let the generator re-emit it.
- **refactor-divergence** — the IDE refactor changed something
  the generator does NOT track (e.g. Java method `get` renamed
  to `fetch`, but the JSON-RPC case label `"users.get"` is
  explicit and unchanged). The canonical output for the label
  is still `"users.get"`, but the **body** now calls `fetch`
  instead of `get`. The user should review the case-arm body.

**What the user can do.**

1. **Accept the generator's canonical output.** Delete the
   affected block (or the whole file, or just the header) and
   let the generator re-emit. This is the default for
   source-change-divergence.
2. **Keep the current file as-is.** The user edits the file to
   match the new source. The generator preserves the block on
   the next pass and reports the divergence again. The user can
   acknowledge it (e.g. `divergence_acknowledged: true` in the
   class-file header) to silence the report.
3. **Change the generator's contract.** If the divergence is
   caused by the generator inferring a name that should have
   been explicit (or vice versa), the user changes the
   generator's naming contract and re-emits. This is a
   generator-level fix.

## Worked examples (summary)

### Example 1 — generated record for an interface (refactor-sensitive)

`interface Order` → generated `record OrderView`. Developer renames
`Order` → `PurchaseOrder` via IDE. The IDE sees `{@link Order}` and
`@see Order` in the generated record and offers to rename
`OrderView` → `PurchaseOrderView`. If the developer declines, the
generator reports a `source-change-divergence` with the canonical
name `PurchaseOrderView`.

### Example 2 — RPC method rename (refactor-insensitive)

`@RpcMethod("users.get") public User getUser(...)` → generated
`case "users.get" -> usersService.get(...)`. Developer renames
`getUser` → `fetchUser` via IDE. The IDE refactor renames the Java
method call inside the case arm to `usersService.fetch(...)` but
does NOT touch the `"users.get"` label. The generator sees the
label is still `"users.get"` (canonical) and the body now calls
`fetch` (also canonical, because the canonical body calls the
current Java method). **No divergence is reported.** The explicit
API label is stable; the body follows the rename.

### Example 3 — divergence when the IDE did NOT update the body

Same as Example 2, but the developer renames `getUser` to
`fetchUser` **without** using the IDE refactor (hand edit). The
case-arm body still calls `usersService.get(...)`. The generator
compares current (`usersService.get(...)`) against canonical
(`usersService.fetch(...)`) and reports a
`source-change-divergence` suggesting the user update the body or
delete the arm to regenerate.

## The three rules at a glance

1. **Implicit derivation = refactor-sensitive.** If the generator
   derives a name from a source identifier, wire the relationship
   through navigable Java (`{@link}`, `@see`, `@GeneratedFrom`).
   The IDE rename refactor must reach the generated code.
2. **Explicit API name = refactor-insensitive.** If the name is an
   explicit contract (JSON-RPC label, workflow trigger, audit
   event), the generator must NOT derive it from a Java
   identifier that the IDE might rename.
3. **Report divergence after every regen pass.** Compare current
   output against canonical output. Emit a diagnostic with kind,
   cause, current, canonical, and suggested action. The user can
   accept, keep, or fix the generator contract.

## Open questions

> `<!-- TODO/EXPLORE: should the IDE plugin offer "rename
> generated record" as part of the standard rename refactor
> flow, using the `{@link}` / `@see` references as the
> navigation contract? -->`

> `<!-- TODO/EXPLORE: how to handle a generator upgrade that
> changes the naming contract itself. E.g. a builder that used
> to emit `with<Field>` methods now emits `<field>()` methods
> as well. The first regen after the upgrade would emit new
> methods next to preserved old ones, and the divergence report
> would fire for every file. A migration recipe. -->`

> `<!-- TODO/EXPLORE: should the divergence report include a
> "apply fix" code action in the IDE that automatically
> updates the generated code to the canonical form? -->`

> `<!-- TODO/EXPLORE: how to make the naming-contract table
> machine-readable so the IDE can use it to drive refactor
> offers without the user having to read the README. -->`
