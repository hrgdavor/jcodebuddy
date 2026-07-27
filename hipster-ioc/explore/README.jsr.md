# Java standard annotations

The main Java Standards Requests (JSRs) related to Inversion of Control (IoC) and Dependency Injection (DI) in Java are JSR-330 and JSR-250, each defining a set of annotations used for IoC.

## JSR-330: Dependency Injection for Java

- `@Inject` – Marks injection points for fields, methods, and constructors.
- `@Qualifier` – Used on injectable fields or parameters to distinguish between multiple implementations.
- `@Named` – A specific type of qualifier, allows injection by name as well as type.
- `@Singleton` – Specifies that a single instance should be created by the DI container.
- `@Scope` – Allows definition of custom scopes for beans and injection contexts.

## JSR-250: Common Annotations

- `@Resource` – Injects a named resource (commonly used for beans or services).
- `@PostConstruct` – Marks a method to be executed after dependency injection is done.
- `@PreDestroy` – Marks a method to run before the bean is destroyed (for cleanup purposes).