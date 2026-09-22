# Phase 4: Validation & Analysis Tools

> **Rewritten on 2026-09-22.** This file used to open with "Phase 4 has been successfully implemented"
> and then link to eighteen files under `project-automation/src/main/java/hr/hrg/rewrite/validation/`.
> Those files no longer exist, and the reason matters: they were the *staging* copies, they never
> compiled, and one of the types they referenced — `hr.hrg.hipster.entity.tooling.TypeTree` — **exists
> nowhere in the repository**. Phase 6 deleted the package on those grounds (`06-Migration-Checklist.md`
> § *Delivery record*, `MIGRATION-CAVEATS.md` § 4.3). The API examples further down this file
> (`validator.validateAll(sourceFile)`, `analyzer.analyze(sourceFile)`,
> `generator.generateAccessors(sourceFile)`) were never real either: they describe the imagined surface
> of the staging package, which is what "written against an API that was imagined rather than read"
> means. What follows is what Phase 4 actually delivered.

## Status

**Delivered — in `hipster-entity-tooling`, not in `project-automation`.** Every validation rule the plan
names exists today as a ported class under
`hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/`, and the module's gate is
green (361 tests, including the ledger and rule tests). The scaffolding this document used to describe was
removed, not migrated.

## Where each named class lives now

| Name in the plan | Reality today |
| --- | --- |
| `EntityRulesValidator` | `hipster-entity-tooling/.../validation/EntityRulesValidator.java` — ported |
| `EntityRule` | `hipster-entity-tooling/.../validation/EntityRule.java` — the rule interface, ported |
| `AuditableRule` | `hipster-entity-tooling/.../validation/AuditableRule.java` — ported |
| `MarkerEntityRule` | `hipster-entity-tooling/.../validation/MarkerEntityRule.java` — ported |
| `ViewInterfaceRule` | `hipster-entity-tooling/.../validation/ViewInterfaceRule.java` — ported |
| `ViewAnnotationRule` | `hipster-entity-tooling/.../validation/ViewAnnotationRule.java` — ported |
| `EnumConstantOrderChecker` | `hipster-entity-tooling/.../validation/EnumConstantOrderChecker.java` — ported |
| `EnumCompactionCli` | `hipster-entity-tooling/.../validation/EnumCompactionCli.java` — ported (compaction) |
| `JavaParserTool` | Renamed to `hipster-entity-tooling/.../validation/SourceQuery.java` during the port |
| `ContextualAnalyzer` | `java-watch-agent/.../agent/core/ContextualAnalyzer.java` — ported (it is an agent tool, not a rule) |
| `AccessorGenerator`, `BuilderGenerator`, `ConstructorGenerator` | `java-watch-agent/.../agent/tools/` — ported; they now delegate to `jwa-builder`'s `ClassMemberProcessor` |
| `ValidationResult`, `AnalysisResult` | Exist only as sketches in `doc/brainstorm/rewrite-migration/05-automation/`; nothing compiles them, and the ported rules return their own types (`EntityRulesValidator.ValidationIssue`, `DivergenceReporter` entries) |
| `ToolResult`, `AnnotationChecker`, `MethodChecker`, `FieldChecker`, `README.md` | **Never existed in compilable form.** They were staging-only, or (for the checkers) plan sketches with no implementation anywhere |

The rule that replaced the last row's intent is worth naming, because it is better than what the plan
proposed: a "checker per node type" (`AnnotationChecker`, `MethodChecker`, `FieldChecker`) is exactly the
shape the LST makes unnecessary — one `J.ClassDeclaration` covers classes, records, enums, interfaces and
annotations, and one `J.MethodDeclaration` covers methods *and* constructors. `TreeQueries` owns those
kind and shape questions, and each rule states its own condition on top.

## What the port actually validated

Two things this plan did not anticipate, both of which the port had to answer before the rules could run:

1. **Positions.** Every rule that reports a location needs a line, and the LST exposes none at all. The
   answer is `JavaSyntaxCheck` (one javac parse per source text, memoised) behind
   `TreeQueries.lineOf` / `methodLineOf` / `annotationLineOf` / `memberLineOf` / `caseLines` — see
   `MIGRATION-CAVEATS.md` § 4.5.
2. **Readability.** A parser recovers from a syntax error and returns a well-formed tree, so "it parsed"
   is not "the file is readable". `SourceReader` asks javac for the syntax verdict, and every rule reads
   through it (`MIGRATION-CAVEATS.md` § 1.1).

## Next

Phase 6 is complete; Phase 7 is next and needs re-scoping, because its deliverables assume the Phase 5
automation layer that was never built. See `PLAN-SUMMARY.md` § *Phase status*.
