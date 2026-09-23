# Adding a conflict resolver

This is the guide the code points at. A new resolver is **one small class plus one
registry line**, and from that point it takes part in orchestration, fix-path
reporting, history replay and the "every type is covered" test without any other
file changing.

The pattern is executed as a test in
`src/test/java/com/codebuddy/merge/ResolverExtensionTest.java`, so if this
document and the code ever disagree, that test fails.

> **Where the conflict comes from.** A resolver is usually needed because a real
> file stayed conflicted: `MergeFileTool` fixes what it can and copies every
> block it cannot into a private fixture workspace whose `AGENTS.md` drives the
> loop — *anonymize the case, build the resolver from the anonymized fixture
> only (following this guide), then re-verify it on the original with
> `MergeFileTool.reverify`*. See
> [docs/CONFLICT_FILE_TOOL.md](docs/CONFLICT_FILE_TOOL.md). Proprietary user
> code never enters this repository; the steps below are identical whether the
> fixture arrived through that loop or was invented by hand.

## The three steps

### 1. Add the conflict type

In `ConflictType`, add a constant and declare how much trust an automatic answer
deserves:

```java
/**
 * Both branches add a constructor parameter. Additive while the parameter
 * lists differ.
 */
CONSTRUCTOR_PARAM_ADD(Handling.AUTO),
```

| `Handling` | Meaning | Effect |
|---|---|---|
| `AUTO` | Safe to apply without asking | `ResolutionKind.AUTO`, sticky by default |
| `REVIEW` | Correct, but a human should confirm | `ResolutionKind.REVIEW`, not sticky |
| `STICKY` | The answer is a preference | `ResolutionKind.REVIEW`, sticky by default |
| `MANUAL` | Must go to a human | `ResolutionKind.MANUAL` |

Choosing `Handling` decides two things at once: the conflict's automation level
and whether decisions are remembered. That keeps the policy in one place instead
of being restated by every call site.

### 2. Write the resolver

Extend `AbstractConflictResolver` and implement exactly three methods. Nothing
else is abstract.

```java
public final class ConstructorParamAddResolver extends AbstractConflictResolver {

    @Override
    public ConflictType supportedType() {
        return ConflictType.CONSTRUCTOR_PARAM_ADD;
    }

    /** Return null (or throw) to decline; the base class then offers a human. */
    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        Set<String> added1 = addedParams(conflict.getBranch1Code(), conflict.getBaseCode());
        Set<String> added2 = addedParams(conflict.getBranch2Code(), conflict.getBaseCode());

        if (added1.isEmpty() || added2.isEmpty()) {
            return null;   // not our conflict
        }

        Set<String> collision = new LinkedHashSet<>(added1);
        collision.retainAll(added2);
        if (!collision.isEmpty()) {
            // Same parameter added on both sides: one declaration must win.
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .explanation("Both branches added parameter " + collision + ".")
                .build();
        }

        return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode(conflict.getBranch1Code() + "\n" + conflict.getBranch2Code())
            .explanation("The added parameters are distinct, so both are kept.")
            .build();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        return List.of(newFixPath(conflict)
            .description("Keep the parameters added by both branches")
            .options("Keep both", "Keep branch 1", "Keep branch 2")
            .recommended("Keep both")
            .justification("Distinct parameters do not collide.")
            .impact("None - every call site keeps compiling.")
            .build());
    }
}
```

## What the base class does for you

| Provided | Why it matters |
|---|---|
| `supports(ConflictType)` derived from `supportedType()` | A resolver cannot accidentally claim a second type |
| `resolve(Conflict)` wraps `doResolve` and converts `null` **or a thrown exception** into the manual fallback | One broken resolver cannot abort a merge |
| `getFixPaths(Conflict)` guarantees a non-empty list | A reviewer always has at least the manual escape hatch |
| `newFixPath(Conflict)` pre-fills the conflict type | Fix paths are uniformly shaped |
| `autoResolution` / `reviewResolution` / `resolutionFor` | Correct `ResolutionKind` without restating it |
| `stickyByDefault()` derived from `Handling` | Remembering decisions is declared once, not per build site |
| `manualFixPath(Conflict)` | The standard "hand it to a human" option |

Override `stickyByDefault()` only to diverge from the table above — for example
to force a heuristic resolver to always ask again.

### Resolvers must be stateless

The registry holds one instance and shares it across resolutions, and a
`BranchConflictStore` may be used from several threads. Keep all state in local
variables or method parameters.

### Declining is a feature

Returning `null` from `doResolve` is how a resolver says "not mine". That is
better than guessing: the orchestrator falls back to a human with the resolver's
fix paths attached. Several resolvers deliberately always decline
(`StructuralChangeConflictResolver`, `ApiIncompatibilityConflictResolver`)
because no automatic answer can be safe.

## 3. Register it

Add one line to `ConflictResolvers.defaultResolvers()`:

```java
new ConstructorParamAddResolver(),
```

That is the whole change. Everything else reads the registry:

- `MergeConflictResolver` routes conflicts by type through
  `ConflictResolvers.index`, and fails loudly at construction if two resolvers
  claim the same type.
- `MergeConflictResolver.Builder` defaults to `ConflictResolvers.defaultResolvers()`,
  so new resolvers are picked up without touching the builder.
- `ConflictResolversTest.everyConflictTypeIsHandled()` fails and **names the type**
  if you added a `ConflictType` but forgot to register a resolver.
- `AbstractResolverTest` gives the new resolver its contract tests for free.

## 4. Test it

Extend `AbstractResolverTest` and supply three small factories:

```java
class ConstructorParamAddResolverTest extends AbstractResolverTest {

    private final ConstructorParamAddResolver resolver = new ConstructorParamAddResolver();

    @Override
    protected ConflictResolver resolverUnderTest() {
        return resolver;
    }

    @Override
    protected Conflict conflictFor(ConflictType type) {
        return ConflictFixtures.sample(type);   // add the type to ConflictFixtures
    }

    @Override
    protected List<ConflictType> unsupportedTypes() {
        return List.of(ConflictType.IMPORT_ADD, ConflictType.VARIABLE_RENAME);
    }

    @Test
    @DisplayName("keeps distinct added parameters")
    void keepsDistinctParameters() {
        // resolver-specific expectations only
    }
}
```

The inherited tests cover type ownership, null-safety, input preservation,
determinism, statelessness, resolution-kind consistency, and fix-path shape.

## 5. Extend detection

A resolver only runs when detection produces its conflict type. Add a
`detectXxxConflicts` method to `ConflictDetectionService` and call it from
`detect(...)`:

```java
add(conflicts, detectConstructorParamConflicts(filePath, baseCode, branch1Code, branch2Code));
```

Then extend `ConflictDetectionServiceTest.everyDetectedConflictIsActioned`,
which asserts that anything detection can produce has a registered resolver.

## 6. Document it

Create `docs/resolvers/<resolver-name-in-kebab-case>/README.md` — the folder
name is the resolver's simple class name in kebab-case
(`ConstructorParamAddResolver` → `constructor-param-add-resolver/`), and
`ResolverDocsTest` fails while a registered resolver has no folder or a folder
has no registered resolver. Copy the shape of any existing page there: what the
resolver decides, the canonical sample, worked examples, when it declines, what
it emits.

**Write no example code by hand.** Every fenced block in a resolver page is
materialized from test material through the repository's example-injection
mechanism (`scripts/inject-examples.mjs`): mark the test methods you want to
show with `//#region your-region-name` / `//#endregion` comments, add the
fixture regions the guide's steps already produced (`ConflictFixtures` holds
the canonical sample as a `//#region <type>-sample` block), and reference them
with *injection markers* — single lines that are nothing but a markdown link
labelled with its own target path (`[path](path#region:name)`), pointing at
material under `src/test/`. Then materialize with:

```
node scripts/inject-examples.mjs merge-java/docs/resolvers
```

`ResolverDocsTest` then re-verifies in the Java build that every rendered block
still equals its source, so a doc example can only change by changing the test
it came from. If a page needs an example the tests do not have, write the test
first — a doc example that is not a passing test's fixture is exactly the copy
that goes stale. See [docs/resolvers/README.md](docs/resolvers/README.md).

## Checklist

- [ ] `ConflictType` constant added with its `Handling`
- [ ] Resolver extends `AbstractConflictResolver`, implements three methods
- [ ] Resolver is stateless
- [ ] Registered in `ConflictResolvers.defaultResolvers()`
- [ ] Fixture added to `ConflictFixtures`
- [ ] Test extends `AbstractResolverTest`
- [ ] Detection added and covered
- [ ] `docs/resolvers/<resolver-name>/README.md` written, examples included from tests, injection script run
- [ ] `mvn -f merge-java/pom.xml test` passes
