# JWA Sidecar: Addon Development Guide

This guide explains how to create and configure addon modules (like `jwa-builder`) for the Java Watch Agent (JWA) Sidecar.

## Recommended Project Structure

To avoid "dependency leaks" and keep user projects lightweight, we strictly recommend a **Multi-Module Maven Project** structure for all addons.

### 1. The API Module (`-api`)
This module contains only the markers (annotations, interfaces) that the user needs to apply in their code.

- **Dependencies**: Zero or extremely minimal.
- **Target**: The user project's `compile` classpath.
- **Example**: `jwa-builder-api` contains the `@GenerateBuilder` annotation.

### 2. The Worker Module (Implementation)
This module contains the heavy logic, AST parsers, and transformation engines.

- **Dependencies**: Heavy libraries (e.g., `JavaParser`, `Jackson`).
- **Target**: Only the **Sidecar's runtime**.
- **Example**: `jwa-builder` contains the `BuilderTransformationEngine`.

---

## Why Two Modules?

Maven's dependency management handles sub-modules as distinct units. By separating them:
1.  **Hygiene**: User projects depending on the `-api` module will **never** accidentally pull in heavy implementation dependencies.
2.  **No "Provided" Hacks**: You don't need to manually exclude dependencies or mark them as `optional`.
3.  **Encapsulation**: The user only sees the public API, while the implementation details are hidden within the sidecar's runtime.

---

## Configuration: `jwa-sidecar.txt`

The sidecar dynamically loads addons based on a project-level configuration file named `jwa-sidecar.txt`.

### Format
One artifact per line (standard Maven GAV format) or a local path.

```text
# Remote Maven artifacts (fetched from .m2)
hr.hrg.watch2:jwa-builder:1.0-SNAPSHOT

# Local development paths
./libs/my-custom-addon-impl.jar
./my-addon/target/classes
```

---

## Reference Implementation

The `jwa-builder` project in this repository serves as the reference implementation for this pattern:
- [jwa-builder-api](../jwa-builder-api/pom.xml)
- [jwa-builder](../jwa-builder/pom.xml)

## Development Workflow

1.  **Define Annotations**: Create your markers in the `-api` module.
2.  **Implement Logic**: Create your transformation logic in the worker module, depending on the `-api`.
3.  **Install**: Run `mvn install` to place the artifacts in your local `.m2`.
4.  **Register**: Add your worker's GAV to `jwa-sidecar.txt` in the target project.
5.  **Run**: Launch the sidecar. It will resolve the worker and its dependencies from `.m2`.
