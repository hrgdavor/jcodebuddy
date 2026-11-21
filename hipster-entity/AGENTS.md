# AGENTS.md — hipster-entity

The repository root [`AGENTS.md`](../AGENTS.md) applies to this module, including § 1 (source-visible,
IDE-navigable wiring), § 1.1 (`project-automation` is private) and § 2 (Bun JavaScript for scripts and
checks). This file is the **nearer** file for the hipster-entity modules and adds one layout rule.

## One folder per domain; each entity/aggregate in its own subpackage

A module root groups **domain subpackages**. Each subpackage holds one entity/aggregate and everything that
belongs to it:

- entity definitions and `@View` interfaces;
- the generated `_` field metadata and generated JDBC `RowAdapter` / `Binder` pair;
- repositories, services, controllers, mappers and whatever else belongs to the aggregate.

The **application-wide** classes live in an application subpackage of the same domain — for the chat example,
`demo.chat.chatapp` holds the `ChatApp*` classes and `CtxChatApp`, while `demo.chat.chatmessage` holds
`ChatMessage` and its view/adapters and `demo.chat.chatroom` holds `ChatRoom` and its view/adapters.

**A completely flat domain package is not acceptable, ever.** Do not put all entities and application classes
in one package, and do not split an aggregate into layer subpackages (`entity/`, `ioc/`, `store/`,
`controller/`, `mapper/`). Subfolders inside an aggregate package may be allowed later by an explicit decision;
they are not allowed now.

Generated output belongs **in the entity/aggregate package too**: the entity metadata (`*_`), JDBC adapters and
the generated context implementation are ordinary committed source next to the hand-written aggregate source,
not in a separate layer or `generated/` package.

### Existing fixture

[`hipster-entity-example`](hipster-entity-example/README.md) predates this convention and keeps its historical
`person/entity`, `person/iface` and `paymentMethod/entity` layer packages. It is a migration target when it is
next touched; **do not copy it as the template for a new domain.** New code, and a domain being refactored
anyway, uses an entity/aggregate subpackage and an application subpackage.

See the worked example in the JCodeBuddy consumer at `D:\wrk\java\jcodebuddy_proto\entity-ioc-playground`:
`core/src/main/java/demo/playground/{customer,order,app}/` and
`chat-app/src/main/java/demo/chat/{chatmessage,chatroom,chatapp}/`.
