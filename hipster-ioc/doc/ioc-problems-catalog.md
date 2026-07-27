# IOC Problem Catalog & Sample Applications

## Introduction
Before we can design an elegant LSP sidecar that generates lightweight, readable boilerplate, we must define the exact use cases it needs to handle. 
This document catalogs the primary "problems" that existing heavy inversion-of-control (IOC) frameworks—like Spring, Google Dagger, and Guice—are built to solve. 

By analyzing these use cases, we can simulate them in sample applications and iteratively refine the generated companion code until it mirrors what a human would reasonably write by hand.

---

## 1. Catalog of IOC Use Cases (The Problems)

### 1.1 Deep Dependency Trees (Wiring)
*   **The Problem:** Manually instantiating a bean that requires 5 dependencies, where each of those dependencies requires 3 of their own, results in a massive, brittle wall of `new A(new B(new C(), new D()), new E())`.
*   **Heavy Framework Solution:** The framework traverses the graph and dynamically wires everything together at runtime or compile time.
*   **Our Goal:** Provide context initializers. The **LSP sidecar** computes the dependency graph and topological ordering during development, generating straightforward, flat code that simply instantiates the classes in that exact order and passes them directly into constructors.

### 1.2 Singleton Management
*   **The Problem:** Ensuring that expensive resources (like Database connection pools or HTTP Clients) are instantiated exactly once across the entire application lifecycle, while avoiding static globals.
*   **Heavy Framework Solution:** Default bean scopes are `Singleton`. The container caches instances in a registry/map.
*   **Our Goal:** Generate eager instantiation fields within the root `Context` class so they behave as thread-safe singletons naturally.

### 1.3 Factories vs. Instances
*   **The Problem:** Some beans need to be created fresh every time they are requested (e.g., prototype scoped beans or context-specific workers). 
*   **Heavy Framework Solution:** `@Scope("prototype")`, or using massive amounts of generated `Provider<T>` / `Factory<T>` classes (like Dagger).
*   **Our Goal:** Provide distinct `createX()` static factory methods alongside `getX()` instance methods on the context interface.

### 1.4 Interface to Implementation Binding
*   **The Problem:** We define a generic interface `PaymentService` but at runtime we need to provide the concrete `StripePaymentService` implementation to dependents.
*   **Heavy Framework Solution:** Explicit binding modules (`bind(PaymentService.class).to(StripePaymentService.class)`) or `@Qualifier` annotations.
*   **Our Goal:** Simple method implementations on the context that declare the Interface return type but internally return `new ConcreteClass()`.

### 1.5 Configuration / Properties Injection
*   **The Problem:** Injecting environment variables or parsed configuration files into specific beans without passing a massive `GlobalConfig` object everywhere.
*   **Heavy Framework Solution:** `@Value("${db.host}")` scattered throughout business logic.
*   **Our Goal:** A configuration bean is loaded early in the context, and the generated context maps specific fields (`config.dbHost()`) precisely to the parameterized constructors of the beans requiring them.

### 1.6 Component/Context Hierarchies (Sub-contexts)
*   **The Problem:** An application has shared singletons (Core) and short-lived request-scoped beans (HTTP Request).
*   **Heavy Framework Solution:** Parent-child contexts or Dagger Subcomponents.
*   **Our Goal:** "Expanding Dependencies" where a `WebContext` explicitly takes a `CoreContext` in its constructor, generating code that pulls necessary beans through the `CoreContext` reference.

---

## 2. Sample Applications Roadmap
To refine the boilerplate, we must build reference applications. These samples will simulate exactly what the LSP sidecar will generate.

### Sample App A: The Basics (Configuration & Singletons)
*   **Goal:** A small CLI tool that reads a configuration file and initializes a database connection.
*   **Simulated Boilerplate:** A single `Context` interface with 2-3 beans. Demonstrates eager instantiation field generation and configuration mapping.

### Sample App B: Factories & Workers
*   **Goal:** A queue processor. Contains a singleton Connection Pool, but must generate a *new* Worker instance for every job pulled from the queue.
*   **Simulated Boilerplate:** Demonstrates the generation of Factory methods (`createWorker()`) that accept runtime parameters combined with context singletons.

### Sample App C: Hierarchical Contexts (Web App)
*   **Goal:** A simulated web server with shared `CoreContext` (database, security) and an expanded `RequestContext` (user profile, request metadata) instantiated on every hit.
*   **Simulated Boilerplate:** Demonstrates how the generated `RequestContextImpl` accesses and utilizes instances stored on the parent `CoreContext`.

### Sample App D: The Circular Dependency Trap
*   **Goal:** A complex billing engine where an invoice generator and an email notifier inadvertently depend on each other.
*   **Simulated Boilerplate:** Proves how our generated setter-injection warning/resolution code functions when circular references map strictly to `setX()` methods.
