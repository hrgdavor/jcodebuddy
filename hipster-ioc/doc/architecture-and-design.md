# Architecture and Design

## A Concept in Making: The LSP Sidecar Approach
Currently, the concepts outlined in this documentation are not yet fully implemented. 

The core implementation plan is to use an **LSP (Language Server Protocol) sidecar** to augment the code as it is written. This approach aims to provide Compile-Time IOC behavior similar to **Google Dagger**, but crucially, **without interfacing with Java Annotation Processing**.

Based on prior research and our success implementing a similar LSP sidecar for a `record builder generator`, this has proven to be an incredibly flexible approach. 

### Why not just use Google Dagger or Spring?
- **The Google Dagger Problem:** Dagger aggressively generates a distinct `Factory` class for *every single bean*, regardless of whether a full factory paradigm is actually needed. This creates a massive amount of class bloat in the build pipeline and pollutes the runtime application.
- **The Spring / Discovery Problem:** Spring relies heavily on runtime classpath scanning and reflection. While easy to write, this makes tracking the actual execution flow and dependency graph incredibly difficult (and slow) when debugging. 

### Performance and the Build Cycle
- **The Annotation Processor Overhead:** Standard Java Annotation Processing (JSR 269) runs as part of the formal compilation process. In large projects, this means that even minor changes can trigger a cascade of code regeneration and re-compilation on *every single build*, significantly slowing down the developer's "change-compile-test" loop.
- **The Sidecar Advantage:** Our LSP sidecar works *alongside* your editor, not *inside* the compiler. It generates code only when needed and in a highly concise format. By avoiding the generation of intermediate proxy/factory classes for every bean, we drastically reduce the total volume of code that needs to be compiled compared to google Dagger for example.
- **Readable & Debuggable:** Unlike heavy generators that produce hundreds of obscure files, our aim is to have concise, human-readable code that you can actually follow in a debugger. Smaller, tighter code means faster compilation and a project that is easier for both the human brain and the Java compiler to scan.

### Designing for Agentic Coding (AI-Assisted Development)
In the modern landscape of AI coding assistants, the clarity and predictability of your codebase are more critical than ever. 
- **LLM-Friendly Patterns:** Traditional IOC frameworks like Spring (dynamic discovery) or Dagger (nested factory chains) are notoriously difficult for AI agents to reason about. By generating "natural" boilerplate that follows simple, linear instantiation patterns, we make the dependency flow significantly easier for an LLM to parse and correctly modify.
- **Preventing Interference:** A key challenge in agentic coding is ensuring the AI doesn't "fight" the code generator. Our **Marker Strategy** (discussed below) serves a dual purpose: it tells the LSP Sidecar what to manage, but it also signals the AI Agent: *"This block is managed by the Sidecar; do not modify manually."* 
- **A 3-Way Collaborative Flow:** This architecture enables a cleaner collaboration between the **Human** (intent), the **Sidecar** (structural automation), and the **AI Agent** (logic implementation), where everyone has clear "jurisdiction" over the code.

#### Strategies for Reducing Agent Friction
To maximize the efficiency of AI agents working with this Sidecar-augmented codebase, we implement several key friction-reduction strategies:
- **Clear Semantic Boundaries:** Agents are instructed to treat `// @hipster:preserve` blocks as the only safe areas for manual logic. This prevents agents from attempting to redefine structural code that the Sidecar manages.
- **Validation Feedback Loop:** We provide a `hipster check` CLI command. This allows an AI agent to verify the integrity of the dependency graph immediately after making an edit, enabling rapid self-correction.
- **Structured Diagnostics:** The LSP Sidecar emits machine-readable "Fix-it" hints. When an agent introduces a dependency error, the Sidecar provides the exact structural fix in a format the agent can easily apply.
- **Predictable AST Patterns:** Generated code follows a strict, non-magical pattern (flat, ordered constructor calls). This makes it trivial for an agent's internal parser to reason about the current state of the application.
- **Context Discovery Manifest:** The Sidecar can optionally generate a `hipster-manifest.json`—a comprehensive "map" of the context that an agent can read at the start of a task to understand the entire dependency landscape without scanning every file.

**Our Goal: A Bridge to Natural Code**
Before heavy IOC frameworks became popular, developers manually wrote their dependency injection by simply instantiating classes in the correct order and passing them into constructors. This "old school" code was incredibly readable and transparent. 

The reason developers moved away from this pattern was **maintainability**. As projects grew, manually computing the topological graph and meticulously updating dozens of constructors during a refactor became an impossible chore.

Our approach attempts to bridge this gap. We want the emitted companion code to look *exactly* like the natural, lightweight initialization boilerplate a developer would write by hand. However, the **LSP sidecar** handles the heavy lifting—computing the graph, verifying correctness, and automatically updating the initialization flow as you code. This gives you the readability of manual injection with the zero-maintenance benefits of an automated heavy framework.

### The Ultimate Destination: Interactive Dependency Graphs
Because the LSP sidecar acts as an ever-watchful arbiter of your dependency graph, it will continuously generate and maintain structured dependency metadata alongside the boilerplate code.

Currently, navigating dependency flow in large Spring/Dagger applications requires tracing through scattered annotations or factory classes. Our goal is to feed this sidecar-produced JSON metadata into a simpler, editor-agnostic visual experience.
- **Lightweight HTTP Server:** The LSP sidecar will embed a light HTTP server to serve the interactive HTML documentation directly.
### Editor-Agnostic Code Navigation
By serving it through the sidecar's HTTP interface, we allow users to view the visual dependency presentation in their browser and click directly back into specific lines of code in their IDE, completely demystifying the IOC flow regardless of which editor they use.

## Marker Strategy: Bridging the Gap (Annotations vs. Comments)

To power the sidecar's code generation, we need to mark "customization zones" (areas the user owns) and "generated zones" (areas the sidecar overwrites). We rely on a strict **hybrid approach** to avoid unnecessary boilerplate while maintaining high flexibility:

### 1. Minimal Structural Annotations (The Java Advantage)
We avoid annotation bloat ("The Dagger Problem") by keeping the annotation footprint as small as possible. However, we do not restrict ourselves to *only* one annotation.

We use actual Java annotations (e.g., `@HipsterContext`) specifically when we need the **compiler's structural type safety**—most notably for defining `Class<?>` dependencies or `Enum` values. This ensures that refactorings (like renaming a class) are instantly caught by the Java compiler before the sidecar even needs to process the comments.

These annotations are placed primarily at the Class or Interface level. Their purpose is structural discovery and safe type-linking: telling the Sidecar, *"Look at this file, I want you to augment it, and here are strict references to the classes it builds off of."*

```java
@HipsterContext // The ONLY annotation needed to trigger sidecar processing
public abstract class MyContext { 
    // ...
}
```

### 2. Magic Comments for Procedural Boundaries
Annotations are structurally limited; they cannot be placed on arbitrary blocks of code or empty lines. Therefore, for everything *inside* the class, we use specially formatted Javadoc or line comments (e.g., `// @hipster:preserve-start`).

This provides total freedom of placement:
*   Protecting manual, custom wiring logic from being overwritten.
*   Providing inline options or instructions to the sidecar generator.
*   Keeping the codebase completely free of heavy `.jar` classpath dependencies on countless micro-annotations.

```java
@HipsterContext
public abstract class MyContext {
    
    // @hipster:factory
    public DbConnection createDb() {
       // @hipster:preserve-start
       DbConnection conn = new DbConnection();
       conn.setCustomTimeout(5000); 
       return conn;
       // @hipster:preserve-end
    }
}
```

### 3. LSP Safety for Comments
The classic flaw of "Magic Comments" is that if a user makes a typo (e.g., `// @hipstur:preserve`), the compiler doesn't care, and the code generator silently fails.
Because our engine is an **LSP sidecar**, we solve this perfectly. The sidecar treats these comments as first-class syntax, providing instant yellow warning squiggles in the IDE if a directive is unknown or misspelled.

## Philosophy and General Notes
- I like to call contexts `CtxFoo CtxBar ...` for faster search/jump in IDE.
- I prefer to put generated classes in git (faster builds, generate is called when needed during development)
- I prefer generating classes over runtime byte code generation
- I hate Lombok for the fact it changes code directly as it compiles, and obscures what happens
- I use jackson for JSON stuff
- I wanted to try generated serializers and deserializers for jackson versus runtime ones.
- [micronaut-serde-jackson](../README.json.serialization.md) looks very promising for jackson serializers generation

For server with web, websocket, servlets:
  - jetty:12 has 33 jars, and 4.5MB
  - undertow:2.3 has 13 jars, and 4.5MB

## Non-goals as it stands, not written in stone

- Lazy loading, 
  - closely following [lazy constant](../explore/lazy.constants.md) (previously called stable values) as alternative
  - beans in context are created immediately (also means there is no need for eagerLoad)
  - allow dependency of type: Supplier<Bean>
  - implement stable value polyfill and generate that code until JEP is finalised. 
    - use parameter to decide to generate stable value or polyfill based code.
    - this way no big refactor is needed, just flip the option or switch to version where stable value is default

## Generated code style
- generate region and endregion for sections
  - instance fields ( count > 5)
  - methods exposing public Beans  (count > 3)
  - BeanFactory methods (count > 3)
- beans without dependencies are created in field initializer of ContextImpl
- beans dependencies will decide sorting order for creating, and same sorting order must be for init* methods

## How about not forcing interfaces for all beans

There is a constant push to turn everything into an interface, often followed by making up excuses like "testability" to justify it. 
However, interfaces often make code more obfuscated and harder to navigate. Just define your beans as classes. You will likely test 
their interoperability in E2E tests anyway. Instead, split your code so that the logic doing the heavy lifting resides 
in standalone functions or "workhorse" classes that remain independent.
