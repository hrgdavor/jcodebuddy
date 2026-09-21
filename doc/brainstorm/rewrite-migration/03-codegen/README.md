# Phase 3: Code Generation Tools

This directory contains the OpenRewrite-based code generation tools that replace the JavaParser-based generators from `hipster-entity-tooling`.

## Overview

The code generation tools are migrated to use OpenRewrite instead of JavaParser, maintaining compatibility with the existing JavaParser API through wrapper classes.

## Architecture

### Wrapper Layer (project-automation)

All wrapper classes in `project-automation/src/main/java/hr/hrg/rewrite/tooling/` maintain the JavaParser API (`CompilationUnit` as return type) while internally using OpenRewrite.

### Original Layer (hipster-entity-tooling)

Original JavaParser-based generators remain in `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/`.

## Files

### Wrapper Classes (JavaParser API Compatibility)

- `OpenRewriteViewInterfaceGenerator.java` - Generates view interfaces
- `OpenRewriteViewBuilderGenerator.java` - Generates view builders  
- `OpenRewriteFieldBoilerplateGenerator.java` - Generates field boilerplate
- `OpenRewriteValidationGenerator.java` - Generates validation rules
- `OpenRewriteEntityMetadataGenerator.java` - Generates entity metadata
- `OpenRewriteTypeLiterals.java` - Type literal utilities

### Usage

See `USAGE.md` in this directory for examples.

## Compliance

All generated code complies with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen (preserve user edits)
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN
