# Why structural, API and three-way body conflicts are never auto-resolved

This is a **design constraint, not a backlog item**. It is the boundary that makes
the rest of the module trustworthy, so it is written down explicitly and enforced
by tests rather than left to reviewer vigilance.

Referenced from [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) and
[`IMPROVEMENT_PROPOSAL.md`](IMPROVEMENT_PROPOSAL.md) §6. If a future change wants
to cross this line, it must first argue with this document.

---

## 1. The three excluded cases

| Case | Conflict types | What the module does today |
|---|---|---|
| Structural change | `STRUCTURAL_CHANGE` | Always `MANUAL`; summarises what each side does that the other does not |
| Public contract change | `API_INCOMPATIBILITY` | Always `MANUAL`; names exactly which contract element moved |
| Overlapping body edits | `METHOD_BODY_CHANGE` (overlap only) | Combines **disjoint** edits for `REVIEW`; overlapping edits are reported, never merged |

---

## 2. The governing asymmetry

The module's whole value rests on classifying a conflict by the *kind of change*
it is, and treating two kinds oppositely:

**Additive change** — both sides add, neither removes or replaces. Import lists,
comment blocks, constant sets, distinct overloads. The union is a function of the
two inputs; there is no judgement to make. If the tool is wrong here, it is wrong
in a way that a compiler catches immediately.

**Substitutive change** — a side removes or replaces something the other side
also touched. One of two competing intentions must be discarded, or a third
created that neither author wrote. The correct answer depends on intent that
exists only in a person's head.

Structural, API and overlapping-body conflicts are all substitutive. Automatic
resolution of a substitutive conflict does not *merge*: it **discards one author's
work and reports success**. That is categorically worse than refusing, because
the refusal is visible and the discard is not.

The tool's promise is narrow and testable: *additive changes are resolved;
everything else is explained.* A tool that crosses this line cannot be trusted on
the cases it gets right, because the caller can no longer predict which those are.

---

## 3. Why "structural" cannot be inferred syntactically

The difference between "both branches changed the method" and "both branches
replaced the method" is not visible in text.

```java
// base
void process() { validate(); }

// branch 1: extended            // branch 2: replaced
void process() {                 void process() {
    validate();                      check();
    audit();                     }
}
```

Both sides touched the same member. A line-level diff cannot distinguish
*extended* from *replaced* without understanding that `validate()` and `check()`
are not the same call. Treating this as additive would produce
`validate(); audit(); check();` — code that no author wrote, that may not compile,
and that if it compiles, does something none of the three versions did.

A concrete instance of the same trap: if branch 1 renames a private helper and
branch 2 adds a call to the old name, the union produces a call to a method that
no longer exists. Structurally the change looks additive — nothing was removed
*textually* — but semantically branch 1 removed a symbol branch 2 depends on.
Detecting this requires resolved symbols across both trees, which is precisely
the capability the module does not have today.

**The honest position:** without resolved symbols we cannot tell these apart, so
we refuse all of them. Refusing the safe ones costs a reviewer a few minutes.
Accepting the unsafe ones costs a silent regression.

---

## 4. Why "public contract" cannot be inferred from the repository

`API_INCOMPATIBILITY` is different in kind from every other type: **the callers are
not in the merge.**

```java
// base
public void process() throws IOException { }

// branch 1                    // branch 2
public void process() { }      public long process() { return 0L; }
```

Branch 1 drops a checked exception; branch 2 changes the return type. Both are
source-compatible with their own branch and both may be perfectly reasonable.
Their effect is on code *outside* the conflict:

- Callers in other modules that catch `IOException` from `process()` stop
  compiling — at least that one is loud.
- Callers that relied on the `void` return and are updated mechanically by an
  IDE refactor may silently change behaviour.
- Reflection, service loaders, serialization frameworks, and published API
  surface have consumers the static analysis cannot enumerate at all.

None of this is visible to a tool that sees one repository, and much of it is not
visible even to one that sees all of it. A merge tool cannot be the arbiter of an
API compatibility decision, because the information required to make the decision
is not in its input.

There is also a process argument. A breaking contract change is normally a
*deliberate* act with a deprecation cycle: keep the old signature, add the new
one, migrate callers, remove the old one later. An auto-resolver that picks a
winner silently collapses that cycle into a single commit, destroying the
migration path for every consumer.

---

## 5. Why three-way body merges cannot be made safe

This is the case most likely to tempt a "just do it" implementation, so it needs
the most careful argument.

### 5.1 Two correct edits do not compose

Both branches made a change that is correct **in isolation**. Combining them can
produce code that is incorrect.

```java
// base
int read(Reader r) {
    return r.read();
}

// branch 1: handle the null case       // branch 2: handle the close case
int read(Reader r) {                    int read(Reader r) {
    if (r == null) {                        int value = r.read();
        return -1;                          r.close();
    }                                       return value;
    return r.read();                    }
}
```

A text-level union of these two edits can produce a method that returns `-1`
before closing anything, drops the `close()`, or closes a reader it never used.
Each branch's intent is defensible; the combination is not a function of the two
intents. The result may compile and may even pass the tests that exist, because
the bug appears only in the interaction — exactly the situation that tests are
worst at catching.

### 5.2 "Disjoint statements" is not a semantic guarantee

The module currently combines body edits when the two sets of changed statements
are disjoint, and reports the result as `REVIEW`, not `AUTO`. That is the right
level of confidence, and it is deliberately not an auto-apply:

- Disjoint changed lines can still interact through shared local variables,
  ordering, control flow, or side effects.
- A branch may have moved code rather than edited it, which looks like a deletion
  plus an addition and defeats line-level disjointness.
- The two edits may each assume the *other's* change has not happened.

The confidence the tool has here is *syntactic* ("these statements do not
textually overlap"), which is strictly weaker than *semantic* ("these changes
cannot affect each other"). Reporting that as `REVIEW` is honest; reporting it as
`AUTO` would be overclaiming.

### 5.3 The failure mode is invisible and attributable to the tool

This is the decisive argument. When the module gets an additive resolution wrong,
the result is a compile error on the next build — loud, immediate, cheap, and
obviously the tool's fault.

When a body merge is wrong, the result is behaviour that no author intended. It
compiles. It may pass CI. It surfaces as a production incident weeks later, and
the merge tool's contribution is buried in history. The module would be trading a
visible inconvenience for an invisible liability.

---

## 6. Why tests and verification do not rescue this

A natural objection: "verify the resolution compiles, then apply it." That helps,
and WS3 adds exactly that gate — but it does not change the conclusion:

- Compilation proves *well-formedness*, not *intent*. Every dangerous example in
  §5 compiles.
- Test suites encode the cases their authors thought of. The failure modes above
  live in the interactions authors did not think of.
- A verifier can downgrade a resolution, which is useful, but it cannot upgrade
  an auto-resolution into a safe one. Verification is a floor, not a proof.

So the verification gate makes the automatic cases safer. It does not make the
excluded cases automatic.

---

## 7. How the constraint is enforced

Not by convention — by construction:

| Mechanism | Effect |
|---|---|
| `ConflictType.STRUCTURAL_CHANGE` and `API_INCOMPATIBILITY` declare `Handling.MANUAL` | The type's policy, not a per-resolver choice |
| `AbstractResolverTest.kindAgreesWithStrategy` | A `MANUAL` strategy producing anything but a `MANUAL` kind fails the suite |
| `StructuralChangeConflictResolver` / `ApiIncompatibilityConflictResolver` return `null` from `doResolve` unconditionally | They cannot resolve even by accident |
| `AbstractConflictResolver.resolve` converts `null` and exceptions into the manual fallback | A future resolver cannot fail *into* an auto-resolution |
| `ConflictResolversTest.everyConflictTypeIsHandled` | A new type must declare a policy; it cannot be left unclassified |
| `AbstractResolverTest.resolutionCarriesFixPathsWhenNotAuto` | A refusal must still be actionable |
| `ResolverExtensionTest` | The worked example is an *additive-only* body resolver that declines every removal, demonstrating the boundary in executable form |

The last row matters most: the extension pattern's own worked example encodes this
rule. A contributor copying it inherits the conservative behaviour rather than
having to rediscover why it is necessary.

---

## 8. What we do instead

Refusing to automate is not refusing to help. For these three cases the module's
job is to make the human decision **fast and well-informed**:

- **Name what moved.** `ApiIncompatibilityConflictResolver` reports the exact
  contract elements that differ — visibility, modifiers, return type, parameters,
  declared exceptions — relative to base, instead of a generic "conflict".
- **Quantify the loss.** `StructuralChangeConflictResolver` reports how many lines
  are unique to each side, so a reviewer can see the cost of either choice before
  making it.
- **Warn about blast radius.** The API fix paths state explicitly that callers
  outside the merge may break and must be searched for, and offer the
  compatibility-bridge option rather than a binary winner.
- **Never write anything.** These resolutions carry
  `ConflictResolution.MANUAL_MARKER` as their resolved code, so no caller can
  apply one by mistake, and `isReplayable()` is false, so a one-off decision can
  never become silent policy on the next update.

---

## 9. If the boundary is ever revisited

The constraint is about *insufficient information*, not about automation being
wrong in principle. So the test for revisiting it is not "is the analysis better?"
but:

1. Does the analysis have **resolved symbols on both sides** plus the call sites
   affected by the change?
2. Can it distinguish additive from substitutive changes on **every** case, with
   evidence rather than heuristics?
3. Is the failure mode **loud** when it is wrong?

Only if all three hold may a case move. And even then, the first step is not
`AUTO` — it is a better-informed `REVIEW` with the reasoning shown. Promotion to
`AUTO` should require evidence from real merges, because the cost of the two error
directions is not symmetric:

- Refusing a resolvable conflict costs **reviewer minutes**.
- Resolving an unresolvable one costs **a silent behavioural regression**.

Until that evidence exists, the answer is no, and this document is why.
