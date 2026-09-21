# Phase 6: Migration of Existing Tooling

## Overview

Complete migration of all remaining JavaParser usage in the codebase. This phase involves scanning for remaining JavaParser imports, creating a migration checklist, and systematically migrating each file.

## Prerequisites (Phase 5)

Phase 5 must be complete before starting Phase 6:
- Automation engine is functional
- All previous phases are complete
- Batch processing works correctly

## Deliverables

### 1. Migration Checklist

File: `doc/brainstorm/rewrite-migration/06-migration/Checklist.md`

### 2. Migration Scripts

Directory: `doc/brainstorm/rewrite-migration/06-migration/scripts/`

Scripts:
- `scan-remaining-javafiles.sh` - Find all JavaParser usage
- `migrate-file.sh` - Migrate a single file
- `verify-migration.sh` - Verify migration completeness
- `generate-migration-report.sh` - Generate migration report

### 3. Migration Tracker

File: `doc/brainstorm/rewrite-migration/06-migration/tracker.md`

### 4. Migration Guide

File: `doc/brainstorm/rewrite-migration/06-migration/MIGRATION-GUIDE.md`

## Implementation Strategy

### Step 1: Scan for Remaining JavaParser Usage

Run:
```bash
grep -r "import com.github.javaparser" --include="*.java" .
```

Or use the script:
```bash
./scripts/scan-remaining-javafiles.sh
```

### Step 2: Create Checklist

For each file found:
- [ ] File path
- [ ] JavaParser types used
- [ ] Migration status
- [ ] Notes

### Step 3: Prioritize Files

Priority order:
1. **High Priority**: Main source files in `src/main/java`
2. **Medium Priority**: Test files in `src/test/java`
3. **Low Priority**: Utility files, examples

### Step 4: Migrate Each File

For each file:

1. **Read file**: Understand current implementation
2. **Identify JavaParser calls**: List all JavaParser types and methods
3. **Create migration plan**: Document approach for that file
4. **Migrate**: Rewrite using OpenRewrite
5. **Test**: Ensure functionality is preserved
6. **Update checklist**: Mark as complete

### Step 5: Verify Migration

Run verification script:
```bash
./scripts/verify-migration.sh
```

## Files to Migrate

### High Priority

#### hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/

- `ViewInterfaceGenerator.java`
- `ViewBuilderGenerator.java`
- `FieldBoilerplateGenerator.java`
- `ValidationGenerator.java`
- `EntityMetadataGenerator.java`
- `TypeLiterals.java`
- `SourceReader.java`
- `CooperativeCodegen.java`
- `MetadataLocations.java`
- `GenLevelResolver.java`
- `index/ClassIndex.java`
- `index/TypeFacts.java`
- `meta/InterfaceInfo.java`
- `validation/*.java` (all validation rules)

#### jwa-builder/src/main/java/hr/hrg/watch2/builder/

- `RecordBuilderProcessor.java`
- `BuilderTransformationEngine.java`

#### java-watch-agent/src/main/java/hr/hrg/watch2/agent/

- `ContextualAnalyzer.java`
- `JavaParserFactory.java`
- `tools/AccessorGenerator.java`
- `tools/BuilderGenerator.java`
- `tools/ConstructorGenerator.java`

#### webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/

- `HttpBridgeStartupActivity.java`
- `JwaTextDocumentService.java`

#### project-automation/src/main/java/

- All files using JavaParser

### Medium Priority

#### hipster-entity-tooling/src/test/java/

- `ViewAnnotationReaderTest.java`
- `SourceReaderTest.java`
- `AddonAndInheritanceTest.java`
- `CompactionRoundTripTest.java`
- `DependencyBoundaryTest.java`
- `EnumCompactionCliTest.java`
- `validation/EnumCompactionCliTest.java`
- `validation/MarkerEntityRuleTest.java`
- `validation/JavaParserTool.java`

#### jwa-builder/src/test/java/

- `RecordBuilderProcessorTest.java`

### Low Priority

- Examples and demo code
- Documentation examples
- Commented-out code

## Migration Checklist Template

```markdown
## File: <file-path>

**JavaParser Types Used**:
- `JavaParser` - parse method
- `CompilationUnit` - type, findAll, toString
- `ClassOrInterfaceDeclaration` - type, getMethods, getFields
- ...

**OpenRewrite Equivalents**:
- `CompilationUnit` -> `SourceFile`
- `ClassOrInterfaceDeclaration` -> `TypeTree`
- ...

**Migration Notes**:
- ...

**Status**: [ ] Not Started [ ] In Progress [ ] Testing [ ] Complete
```

## Implementation Steps

1. Create directory: `doc/brainstorm/rewrite-migration/06-migration/`
2. Create migration scripts
3. Scan for remaining JavaParser usage
4. Generate initial checklist
5. Prioritize files for migration
6. Migrate high-priority files
7. Test each migration
8. Update checklist
9. Migrate medium-priority files
10. Migrate low-priority files
11. Run verification
12. Generate migration report

## Verification

### Check 1: No Remaining JavaParser Imports

```bash
grep -r "import com.github.javaparser" --include="*.java" . | grep -v "test" | grep -v "comment"
```

Expected: No matches (or only in test/commented code)

### Check 2: Build Passes

```bash
mvn clean compile
```

### Check 3: Tests Pass

```bash
mvn test
```

### Check 4: Functionality Preserved

For each migrated file:
- Compare behavior with original
- Test with sample inputs
- Verify output matches expected

## Migration Report Format

```markdown
# Migration Report

## Summary
- Total files scanned: X
- Files migrated: Y
- Files remaining: Z
- Success rate: Y/Z

## Files Migrated
- <file-path> - Complete
- ...

## Issues Encountered
- <issue-description>
- ...

## Recommendations
- <recommendation>
- ...
```

## Architecture Compliance

All migrated code must comply with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

## Status

**Current**: Ready for implementation
**Next**: After Phase 6 completion, proceed to Phase 7

## Quick Reference

Key files in this phase:
- `Checklist.md` - Migration checklist
- `tracker.md` - Migration tracker
- `MIGRATION-GUIDE.md` - Migration guide
- Scripts for automation
