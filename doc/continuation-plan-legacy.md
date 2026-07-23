> **Deprecated**: This continuation plan is superseded by the unified JCodeBuddy merge plan. It contains outdated assumptions (Phase 1.0 "completed" work that does not exist on disk). Refer to the current plan at the repo root `README.md` and the unified build structure.

# Code-Buddy Submodule Continuation Plan

**Created:** 2026-07-21 (based on handoff document)  
**Status:** Phase 1.0 Complete | Starting Phase 2.0  
**Next Priority:** Wire up java_watch2 generators into jcodebuddy framework

---

## Current State Summary

### ✅ Completed Work (Phase 1.0)
- **jcodebuddy Maven module**: Created with proper pom.xml structure
- **CodeGenerator<T> interface**: Minimal API with `generate(CodeContext context)` method
- **CodeContext interface & impls**: CodeContext, CodeContextImpl, DefaultSourceTree, TypeResolver, TypeDefinition
- **HelloWorldGenerator demo**: Working example showing pure code generation
- **Parent POM updated**: Added `<module>jcodebuddy</module>` to modules list
- **Dependencies configured**: hipster-entity-api (provided), jackson-databind, JUnit 5 test scope

### 🎯 Immediate Goal: Wire Up java_watch2 Codegen Base
The user explicitly stated:
> "java-watch-agent from java_watch2 is the codegen base that should be used in code buddy"

**What exists in java_watch2:**
- **ActionTool interface**: getName(), isApplicable(ToolContext), execute(ToolContext)
- **SimpleToolContext record**: Path root, file, line, indent  
- **Working generators**: AccessorGenerator, BuilderGenerator, ConstructorGenerator, RecordBuilderGenerator, etc.
- **JavaParser usage**: All generators parse Java source and modify AST nodes
- **Dependencies from java_watch2/pom.xml:**
  - javaparser-core:3.28.0
  - slf4j-api/simple:2.0.9
  - jackson-databind:2.15.2
  - ecj:3.37.0

### 📋 Next Steps in Order of Priority

#### Phase 2.0: Refactor java_watch2 Generators for Code-Buddy (Week 1)
1. **Create `java-watch-agent` submodule** under jcodebuddy parent:
   - `jcodebuddy/java-watch-agent/pom.xml`
     - Parent: jcodebuddy
     - Dependencies: hipster-entity-api, jackson-databind, javaparser-core (3.28.0)
   - **Copy & refactor generators** to use CodeContext interface instead of SimpleToolContext
   - Convert ActionTool → extend plain Java class implementing CodeGenerator

2. **Refactor AccessorGenerator:**
   - Extract its AST manipulation logic
   - Replace `SimpleToolContext` with `CodeContextImpl`
   - Make it a standalone module that can be reused by other projects

3. **Refactor BuilderGenerator:**  
   - Same process, but preserve fluent builder pattern
   - Test that generated builders work correctly

4. **Wire up hipster-entity APIs**:
   - In `CodeContextImpl`, inject TypeResolver for entity metadata queries
   - Allow generators to optionally query hipster-entity type definitions
   - Maintain backward compatibility: generators can choose to use or ignore this feature

#### Phase 2.1: Create Hybrid Config+Code Demo Generators (Week 2)
5. **Build demo combining config + code**:
   ```java
   public class HybridGenerator implements CodeGenerator<String> {
       @Override
       public String generate(CodeContext context) {
           // Read JSON/YAML configuration from context.getMavenProject()
           // Apply imperative transformations using JavaParser AST
           // Example: Generate a ViewProxy based on config fields + code logic
       }
   }
   ```

6. **Demonstrate flexibility**:
   - Config-driven field selection (which attributes to proxy)
   - Code-driven implementation generation (JDK 16+ record patterns, etc.)
   - Show how this beats annotation processing limitations

#### Phase 3.0: Integration with hipster-entity Core Generators (Week 3-4)
7. **Refactor existing core generators**:
   - ViewProxyFactory from hipster-entity-core → CodeGenerator implementation
   - Builder pattern generator → HybridGenerator example
   - Move to jcodebuddy module, keeping hipster-entity modules clean

8. **Create migration guide** for users who want to switch from annotation processing:
   - Explain why jcodebuddy approach is easier (no compiler restrictions)
   - Show simple Hello World example comparing both approaches

#### Documentation & Testing
9. **Write README.md** for jcodebuddy submodule explaining:
   - What problem it solves vs Java annotation processing
   - How to add custom generators (extend CodeGenerator, implement generate())
   - Hybrid config+code usage examples
   - Integration with java_watch2 agents

10. **Add comprehensive test suite:**
    - Unit tests for each refactored generator  
    - Integration tests simulating real build scenarios
    - Benchmarks comparing jcodebuddy vs annotation processing approach

---

## File Structure Plan

```
jcodebuddy/
├── pom.xml (already exists)
├── src/main/java/hr/hrg/hipster/jcodebuddy/
│   ├── CodeGenerator.java         # Core interface (done)
│   ├── HelloWorldGenerator.java  # Demo example (done)
│   
├── java-watch-agent/              # NEW: Refactored from java_watch2
│   └── pom.xml                    # Will have hipster-entity-api dependency
│
├── src/main/java/hr/hrg/hipster/jcodebuddy/context/
│   ├── CodeContext.java           # Interface (done)
│   ├── CodeContextImpl.java       # Default implementation (done)
│   ├── SourceTree.java            # File read/write utility (done)
│   ├── TypeResolver.java          # Entity metadata queries (done)
│   └── TypeDefinition.java        # Type metadata model (done)
│
├── src/test/java/hr/hrg/hipster/jcodebuddy/
│   └── ...

```

---

## Critical Decisions Made Earlier
- **jcodebuddy is a standalone Maven module** ✅ (not just feature in existing modules)
- **Generators extend plain Java classes** ✅ (no annotation processing fight)
- **Any mix of config + code allowed** ✅ (explicit design goal)
- **Works as dependency alongside hipster-entity** ✅

---

## Open Questions for User Input
1. What configuration format do you prefer for hybrid generators? JSON vs YAML?
2. Should we expose java_watch2's ToolRegistry pattern in jcodebuddy?
3. Do you want the initial demo to generate a real ViewProxy or stay with HelloWorld?
4. Timeline expectation: "Week 1-2" seems tight - is this realistic?

---

**Ready to proceed:** All infrastructure is ready. The next step is creating `java-watch-agent` submodule and refactoring the AccessorGenerator/BuilderGenerator for use in jcodebuddy framework.
