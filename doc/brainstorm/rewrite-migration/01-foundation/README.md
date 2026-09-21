# Phase 1: Foundation & Infrastructure

## Overview

This directory contains the foundation artifacts for OpenRewrite migration:
1. Maven dependency fragment
2. API compatibility layer
3. Conversion utilities

## Files

### pom-fragment-rewrite.xml
Maven dependency fragment to include in parent pom.xml. Contains:
- OpenRewrite dependencies (core, java-11, java-17, java-21)
- JavaParser (kept for migration period)
- Test dependencies

### api-compatibility/
Contains the API compatibility layer:
- `AstVisitor.java` - Base visitor interface extending OpenRewrite's JavaIsoVisitor
- `AstManipulator.java` - High-level manipulation utilities
- `CompilationUnitAdapter.java` - AST conversion utilities

## Status

**Current**: Files created but implementation incomplete
**Next**: Phase 2 implementation requires completing Phase 1 with working implementations

## Notes

Phase 1 files are scaffolding. The actual implementation of conversion methods
needs to be completed before Phase 2 can proceed. This is intentional - the
files provide the structure and method signatures, but the complex conversion
logic will be implemented as part of the migration work.

## Next Steps

1. Complete Phase 1 implementation details
2. Verify Phase 1 is functional
3. Proceed to Phase 2: Core AST Utilities
