# Phase 2: Unit Tests

## Overview

This directory contains unit tests for all Phase 2 utility classes.

## Test Files

- `NodeTraversalTests.java` - Tests for NodeTraversal
- `AstPrinterTests.java` - Tests for AstPrinter
- `SourceManipulationTests.java` - Tests for SourceManipulation
- `TypeUtilsTests.java` - Tests for TypeUtils

## Test Coverage Requirements

- All public methods must have unit tests
- Round-trip tests: JavaParser -> OpenRewrite -> JavaParser
- Edge cases: empty files, malformed code, large files
- Performance tests for large source files
