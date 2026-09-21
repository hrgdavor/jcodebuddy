# Quick start

## 1. Build and test

```bash
mvn -f merge-java/pom.xml test
```

431 tests, all passing. To use the module from another Maven module:

```xml
<dependency>
    <groupId>hr.hrg.jcodebuddy</groupId>
    <artifactId>merge-java</artifactId>
    <version>${project.version}</version>
</dependency>
```

## 2. Resolve three versions of a file

```java
MergeUtil util = MergeUtil.create();

MergeConflictResolver.MergeReport report = util.resolve(
    "src/main/java/com/example/Payment.java",   // recorded in history
    "base/Payment.java",                        // merge base
    "ours/Payment.java",                        // our branch
    "theirs/Payment.java",                      // their branch
    ".jcodebuddy/merge-history/feature-payments/");

System.out.println(report.summarize());
// src/main/java/com/example/Payment.java: 1 conflict(s) - 1 auto, 0 for review, 0 manual.
```

The three readings are the versions on disk. If the code is already in memory,
pass it directly — that call is side-effect free and does not touch history:

```java
report = util.resolve(
    "Payment.java",
    "import java.util.List;",
    "import java.util.List;\nimport java.math.BigDecimal;",
    "import java.util.List;\nimport java.time.Instant;");
```

## 3. Act on the outcome

```java
// Apply without asking.
for (ConflictResolution auto : report.getAutoResolutions()) {
    write(auto.getFilePath(), auto.getResolvedCode());
}

// Show a reviewer what needs confirming.
for (ConflictResolution review : report.getReviewResolutions()) {
    System.out.println(review.getExplanation());
    for (FixPath option : review.getAlternativePaths()) {
        System.out.println("  " + option.getDescription()
            + " -> " + option.getOptions()
            + " | recommended: " + option.getRecommended()
            + " | cost: " + option.getImpact());
    }
}

// A human is required.
if (report.hasUnresolvedConflicts()) {
    report.getManualResolutions().forEach(r -> System.out.println(r.getExplanation()));
}
```

## 4. The motivating case, end to end

Two branches each add a different import next to the same neighbourhood. A merge
tool reports a conflict; `merge-java` keeps both:

```java
Conflict conflict = new Conflict(
    ConflictType.IMPORT_ADD,
    "src/main/java/com/example/Payment.java",
    "both branches added imports",
    "import java.util.List;",
    "import java.util.List;\nimport java.math.BigDecimal;",
    "import java.util.List;\nimport java.time.Instant;");

ConflictResolution resolution = MergeConflictResolver.create().resolve(conflict);

resolution.getKind();            // AUTO
resolution.getResolutionStrategy(); // KEEP_BOTH
resolution.getResolvedCode();
// import java.util.List;
// import java.math.BigDecimal;
// import java.time.Instant;
```

Note that the two branches added *different* imports. That is exactly the case
worth removing, and it needs no human.

## 5. Remember a decision

A rename is a choice, not a fact. Offer the options, then record the answer so
the next update does not ask again:

```java
Conflict conflict = new Conflict(ConflictType.VARIABLE_RENAME, "Payment.java",
    "renamed differently", "int order = 1;", "int purchase = 1;", "int invoice = 1;");

MergeConflictResolver resolver = new MergeConflictResolver.Builder()
    .setBranchName("feature-payments")
    .build();

ConflictResolution first = resolver.resolve(conflict);
first.getKind();                    // REVIEW - never renames silently
first.getAlternativePaths().get(0).getRecommended();  // "Rename to 'order' everywhere"

// The reviewer agrees with the recommendation, and wants it remembered:
resolver.recordDecision(conflict, ConflictResolution.builder()
    .filePath(conflict.getFilePath())
    .type(conflict.getType())
    .resolvedCode(conflict.getBranch1Code())
    .resolutionStrategy(ConflictResolution.ResolutionStrategy.KEEP_BOTH)
    .kind(ConflictResolution.ResolutionKind.AUTO)
    .sticky(true)
    .explanation("Use 'order' consistently")
    .build());

// Next base-branch update:
ConflictResolution replayed = resolver.resolve(conflict);
replayed.getKind();                 // DEFERRED
replayed.getResolutionStrategy();   // STICKY_REPLAY
```

Decisions live in `.jcodebuddy/merge-history/<branch>/decisions/` and are meant to
be checked in.

## 6. Configure the resolver set

The built-in set covers every conflict type. To add or override one:

```java
MergeConflictResolver resolver = new MergeConflictResolver.Builder()
    .setBranchName("feature-payments")
    .addResolver(new MyConflictResolver())   // takes ownership of its type
    .build();
```

See [ADDING_A_RESOLVER.md](ADDING_A_RESOLVER.md).

## 7. Inspect the options without resolving

```java
List<FixPath> options = MergeUtil.create().fixPathsFor(ConflictType.PACKAGE_CHANGE);
// Every conflict type offers at least one path - the manual escape hatch.
```

## Next steps

- [README.md](README.md) — what each conflict type does and how history works
- [ADDING_A_RESOLVER.md](ADDING_A_RESOLVER.md) — the extension pattern
- [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) — what remains (OpenRewrite
  recipes, JGit working-tree integration)
