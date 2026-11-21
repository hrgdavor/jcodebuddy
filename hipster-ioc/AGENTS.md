# AGENTS.md — hipster-ioc

The repository root [`AGENTS.md`](../AGENTS.md) applies to this module, including § 1 (source-visible,
IDE-navigable wiring), § 1.1 (`project-automation` is private) and § 2 (Bun JavaScript for scripts and
checks). This file is the **nearer** file for the hipster-ioc modules and adds the context naming rule.

## Contexts are `CtxSomething`

Every `@HipsterContext` interface is named **`Ctx<Something>`**, with the `Ctx` prefix first:

```text
CtxMain          -> generated CtxMainImpl
CtxChatApp       -> generated CtxChatAppImpl
```

- The **module interface** is `<Something>Module` — `ChatAppModule` — and is not a
  context; it carries the `default build<Bean>` factories.
- The **generated implementation** is `<Context>Impl` — `CtxChatAppImpl`.
- Do **not** name a context `AppContext`, `ChatContext` or `<Domain>Context`. A context
  and a domain entity/application class must not read as the same kind of thing.

The context name is the stable identity of the object graph in source, in the generated
`.jcodebuddy/metadata/hipster-ioc/contexts.json`, and in the dependency graph page.

### Existing fixtures

The contexts inside `hipster-ioc/hipster-ioc-tooling` tests (`AppContext`, `CycleContext`,
`HookContext`, …) predate this rule. They are test fixtures, not a template: when a fixture is
touched it moves to the `Ctx<Something>` name, and new contexts use it from the start. The
worked consumer is the playground at `D:\wrk\java\jcodebuddy_proto\entity-ioc-playground`, whose contexts are
`CtxApp` and `CtxChatApp`.
