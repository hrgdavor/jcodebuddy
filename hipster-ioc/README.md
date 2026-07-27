# hipster-ioc (compile-time IOC for Java)

> [!WARNING]
> **Concept in Progress:** The features described in this repository are currently in the planning and conceptual phase. 
> The plan is to implement an **LSP sidecar** to augment code during development. This provides compile-time IOC similar to Google Dagger, but avoids the bloat and complexity of Java Annotation Processors.

hipster-ioc is now a first-class module of the [jcodebuddy](https://github.com/your-org/jcodebuddy) project. It lives under the `hipster-ioc/` directory with the following module structure:

- `hipster-ioc-api/` — annotations, interfaces, and marker types
- `hipster-ioc-tooling/` — code generator, dependency graph computation, and metadata projections
- `hipster-ioc-test/` — integration tests

Makes dependency injection contexts almost as simple as regular beans. Less bloat, more fun, and more speed (build, start, runtime).

Some goals (ATM it guides development, and list will change as code settles a bit)
- No discovery at runtime
- items that are discovered written into actual Java code to be compiled
- generates less code and aims to be readable
- things that are connected must be navigable (static listeners, not held in list, but actaully executed)
  - it is ok to have annotations for discovery, but it then must result in code where discovered thing is used and thus visible to IDE introspection
- Main concern is helping connect dependencies inside context, not go too fancy beyond that
- make it easier to discover/navigate how code is connected
- faster CI
- No considerable effort will be made to be compatible with older Java. 25 is min Java considered at the moment. 
- No `@Scope` for now, all methods without parameters return singletons, factory methods return new instance each time
- circular dependencies are not allowed between beans
- circular dependencies not allowed between contexts
- Bean is not allowed to depend on the context in which it is defined
- BeanFactory is separate interface that context implementation implements, but must not the context itself
  - this allows for simpler code inside implementation ,as it has access to dependencies for the factory methods
- strict mode, disallow mixing module implementation methods and public abstract methods that expose beans,
  - to avoid temptation to call them in module code
  - calling them while context is not yet created (build helper method called from constructor) will cause freaky errors
  - maybe enforce this by analyzing AST (more complicated to implement)
  - or more naive version: search source file for method call `getBean1(`


## Documentation

For a deeper dive into the architecture, best practices, and advanced context configurations, please refer to the following documentation files:

*   [**Architecture & Design Decisions**](doc/architecture-and-design.md) - Why things are built the way they are, non-goals, and generated code styling.
*   [**Advanced Contexts**](doc/advanced-contexts.md) - How to link multiple contexts together, expand dependencies, handle lifecycle caveats, and use strict mode.
*   [**Best Practices**](doc/best-practices.md) - Structuring Maven modules, live reloading, and the expected developer flow.
*   [**Entity Companion Project**](doc/entity.md) - A data-oriented paradigm for persistent objects using views and metadata extraction.
*   [**IOC Problem Catalog & Sample Apps**](doc/ioc-problems-catalog.md) - A catalog of the specific injection problems this tool solves and the roadmap for simulated usage.
*   [**Roadmap & Ideation**](doc/ROADMAP.md) - Upcoming features like dependency graph generation and JSON outputs.

## Context definition

Define a public interface that is public facing part of your context. Other transitive dependencies that their dependencies resolved will
be part of the context, but not exposed.
```java
@HipsterContext(factory=CtxMainBeanFactory.class)
public abstract class CtxMain{
    
  public abstract ObjectMapper mapper();// getters are so yesterday
  public abstract SomeBean someBean(); // just do it like records :D

  // build method, when simply calling a constructor is not good enough
  protected ObjectMapper buildMapper(){
    ObjectMapper out = new ObjectMapper();
    out.addModule();
    return out;
  }
  
  // init method for beans that can be auto-created
  protected void initObjectMapper(ObjectMapper mapper){
    // name must follow convention "init"+classSimpleName
    // first parameter must be the bean, 
    // extra parameters if present, must be dependencies that can be resolved
    mapper.init();
  }
  
}
```

Define a public interface for bean factory to be able to inject it without creating dependency on the context itself.

```java
public interface CtxMainBeanFactory {
    // factory method for beans auto-created
    // name must follow convention "create"+classSimpleName
    // first parameter must be the bean, 
    // extra parameters if present, must be dependencies that can be resolved
    ReportWorker createReportTask(ReportConfig config);
}
```

The generated context implementation would be like this:

```java
public class CtxMainImpl extends CtxMain implements CtxMainBeanFactory{
  protected final ObjectMapper mapper;
  protected final SomeBean someBean;

  public CtxMainImpl(){
    mapper = buildMapper();
    someBean = new SomeBean(mapper);
    initObjectMapper(mapper);
  }
  /** ReportWorker factory */
  @Override ReportWorker createReportTask(ReportConfig config){
      return new ReportWorker(mapper, someBean, config);
  }
  @Override public ObjectMapper mapper(){ return mapper; }
  @Override public SomeBean someBean(){ return someBean; }
}
```

You can declare a bean as Context even if there are no unimplemented methods (in that case, `Impl` class will not be created, but it will behave like
any auto-created context)

Factories with assisted Injection
 - convention : ContextName+(BeanFactory|Beans)
 - define in annotation
 - Similar to Assisted Injection - https://avaje.io/inject/#assistInject
