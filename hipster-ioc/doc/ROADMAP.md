# Roadmap / Todo

## Phase 1: Define the Problem Space & Refine Boilerplate
To be able to define good, readable boilerplate, we first need to explicitly catalog the "problems" that existing IOC frameworks (like Dagger, Spring, Guice) attempt to solve.

1. **Catalog IOC Use Cases:** Create a comprehensive catalog of the specific problems and patterns solved by heavy IOC frameworks. This is the first step toward finding patterns for light, readable boilerplate code that the LSP sidecar can augment and maintain as a project grows or refactors.
2. **Build Sample Applications (Simulate Usage):** Construct a collection of sample applications to simulate usage. 
   - Start with really small, basic use-cases where IOC begins to become useful.
   - Progressively build on these with more complex, real-world examples.
   *Without simulating actual usage, the generated boilerplate cannot be properly refined.*

## Phase 2: Feature Backlog

### Dependency Discovery & Visual Graphs
- **Generate Dependency Metadata:** The LSP sidecar will compute and continuously maintain a structured JSON representation of the dependency graph during development.
- **Embedded Light HTTP Server:** The LSP sidecar will embed a light HTTP server to serve interactive HTML dependency graphs directly to the browser.
- **Editor-Agnostic Context Navigation:** Because the sidecar serves the visual presentation locally, it allows for editor-agnostic integration. Users can navigate the generated HTML visualizations and click directly back into specific lines of Java code in their preferred IDE.

### Secondary Backlog
- list contexts
  - dependencies
  - exposed beans
  - expanded beans
  - factories
  - initializers
  - AutoCloseable beans
- dependencies that are exposed and those used in factories must be instance fields
- produce a markdown that is clickable and explains each module where you can click each class if you need more details
- can be used to produce a dependency graph
- maybe some nice HTML interface to explore dependencies
- make sure generated context does not call methods in methods that return a bean, to guarantee singleton as expected
  - if return value from an expanded dependency or a custom build method changes (like maybe config) we store a snapshot
  - it may be limiting, but is potential source of freaky bugs. dealing with mutable values should be done outside hipster-ioc
- make sure any dependency used for factories are placed in fields, and not just a local var in constructor
- explore if it would be a good practice to extract factory methods from context into a separate interface (this could be enforced)
- explore enforcing some rules that are deemed a good practice (with ability to  disable them via config or annotation if annoying to user)

dependency graph generation exploration ideas
- group by context, 
- mark dependencies for factory method separately from additional parameters.
