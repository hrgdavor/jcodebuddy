# OpenRewrite Migration Project

## Overview

This directory contains the plan and implementation artifacts for migrating JCodeBuddy from JavaParser to OpenRewrite's core module.

## Directory Structure

```
rewrite-migration/
├── REWRITE-MIGRATION-PLAN.md      # Main migration plan (this document)
├── README.md                      # This file
├── 01-foundation/                 # Phase 1 artifacts
│   ├── pom-fragment-rewrite.xml   # Maven dependency fragment
│   └── api-compatibility/         # API compatibility layer
├── 02-utilities/                  # Phase 2 artifacts
│   └── util/                      # Utility classes
├── 03-codegen/                    # Phase 3 artifacts
│   ├── view-interface/            # View interface generator migration
│   ├── field-boilerplate/         # Field boilerplate generator migration
│   └── ...
├── 04-validation/                 # Phase 4 artifacts
│   └── rules/                     # Validation rules migration
├── 05-automation/                 # Phase 5 artifacts
│   └── engine/                    # Automation engine
├── 06-migration/                  # Phase 6 artifacts
│   └── checklist/                 # Migration checklist
└── 07-testing/                    # Phase 7 artifacts
    └── tests/                     # Test suites
```

## Quick Start

1. Review `REWRITE-MIGRATION-PLAN.md` for the complete migration strategy
2. Start with Phase 1: Foundation
3. Follow the phased approach to minimize risk
4. Test thoroughly at each phase

## Getting Help

- Check OpenRewrite documentation: https://docs.openrewrite.org/
- Review the OpenRewrite GitHub repository: https://github.com/openrewrite/rewrite
- See JavaParser to OpenRewrite migration guides

## Architecture Compliance

All migrations must comply with JCodeBuddy architecture decisions:

- DEC-019: Source-visible wiring (no reflection-driven discovery)
- DEC-020: Cooperative codegen (preserve user edits)
- DEC-021: Generator class-file header format
- DEC-022: Refactor-sensitive naming contracts
- DEC-029: Class index by FQN

## Status

**Current Phase**: Planning (Phase 1 ready to start)

**Next Action**: Review the migration plan and approve to begin Phase 1 implementation.

---

*For questions or issues, contact the migration team or review the architecture decisions in `doc-hipster-entity/architecture/decisions/`.*
