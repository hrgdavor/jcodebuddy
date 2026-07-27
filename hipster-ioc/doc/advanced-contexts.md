# Advanced Contexts

## Expanding dependencies

When we make a context that depends on another context, all public beans from it will be made available to the new context.
(Manually created contexts will be expanded too)

**Visibility is one-way (Parent-Child relationship):** 
The context that defines the dependency (the "child") can access all the public beans of the injected context (the "parent"). However, the "parent" context cannot see any beans from the "child" context.

Similar can be useful if we have a complex configuration class that has few/many sections defined as properties
Expanding dependencies does not generate more code by itself, just enables auto-generate to use the expanded dependencies when needed.

You can manually expand parts of an object as dependency easily by declaring a method in module. This exposes new bean for injection as dependency.
```java
public abstract class CtxMain{ 
    //...
    protected DbConfig dbConfig(MainConfig mc){ return mc.db();}
    protected EmailConfig emailConfig(MainConfig mc){ return mc.email();}
    //...
}
```

if we have another context visible inside the module as dependency, or a bean marked by `@HipsterContext(expandOnly=true)`, it is then allowed to expand public methods as bean suppliers. It then gives more straight forward generated code, and no manual boilerplate for this use-case.

```java
  someBean = new SomeBean(dep1,dep2, mainConfig.email());
```

## Contexts, structured and many

Contexts can also have dependencies like beans. In fact, injecting other contexts is a powerful way to organize your application.

### Typical Use Cases for Multiple Contexts
*   **Modular Applications:** Each module manages its own context to isolate configuration and dependencies.
*   **Shared Core Configuration:** Common services (like database connections or security objects) can reside in a "parent" or "core" context, which is then injected into more specialized "child" contexts (like web controllers).
*   **Testing:** Creating separate isolated contexts for tests allows for finer-grained unit and integration testing where certain beans can be easily stubbed or mocked.
*   **Plugin Architectures:** Separate contexts can serve as isolated environments for plugins or multi-tenant systems.

### Context Lifecycle and Rules
When linking multiple contexts, they each manage their own lifecycle. **Be mindful of the proper startup and shutdown sequence**, especially when dealing with `AutoCloseable` beans across dependent contexts.

#### Context Injection Principles
*   **Injecting Self:** Beans are allowed to inject the specific context in which they are defined as a dependency. The main reason to do this is for classes that need to call factory methods directly from the context.
*   **No Active Calls During Init:** Beans **must not** call any methods of injected dependencies (other beans or contexts) during their constructor/initialization phase, except if the dependency is an immutable record (like a configuration) that is guaranteed to be loaded beforehand.

## Circular dependencies

This topic is a bit circular in nature, I keep running in circles, to use or not to use circular dependencies.

I can say there at least should be effort to reduce.

Possible use-case for circular dependency 
 - splitting a large class into few classes (could be temporary until code is split to not need circular dep)

Formalize circular dependencies to be declared as such, so they can be configured to throw warning if intention is to have it as temporary fix, ot throw error when project is strict about it.

It must be via setter, constructor are not allowed to cause circular dependencies as it will not be feasible to combine them.

```java
@Circular 
public void setOtherDep(OtherDep other){
    this.other = other;
} 
```

## Strict mode
 - no circular deps
 - no combining public method declaration for context and context implementation
   - or generate test implementation that throws exception at runtime if pub methods are called while constructing context
   - or add null check and throw a message that explains what happened
