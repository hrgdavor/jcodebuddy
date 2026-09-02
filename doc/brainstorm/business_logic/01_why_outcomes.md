# Why Outcomes (and Not Events / CQRS / Saga)

> Back to [README](README.md).

## The comparison

| Concern               | Event bus / Saga                 | Outcome pipeline (this concept)              |
| --------------------- | -------------------------------- | -------------------------------------------- |
| Reads like code       | No – handlers spread across files | **Yes** – top-down linear method body       |
| Bulk execution        | Awkward – one event per record    | **Natural** – loop producing outcomes        |
| Testability           | Heavy mocking of bus/handlers     | **Pure** – assert data on produced outcomes   |
| Reviewability         | Whole graph needed                | **One method body** – reviewers stay local  |
| Identifier control    | Usually DB-assigned downstream     | **Step assigns id**, marker propagates       |
| WAL / async persist   | Hand-rolled                       | **Outcomes are already the WAL record**      |

> `<!-- TODO/EXPLORE: validate this concept against existing frameworks
> (Axon, Eventuate, Temporal, etc.) and decide whether to borrow their
> terminology or stay deliberately distinct. -->`

## What we keep vs reject

**Reject** the parts of those frameworks that make hand-written code harder
to read:

- global event handlers,
- framework-managed retry / persistence,
- implicit ordering / sequencing rules,
- "magic" identifiers produced by the storage layer.

**Keep** what is genuinely useful:

- clear separation between "decide" and "act",
- replayable history of decisions,
- bulk-friendly execution,
- declarative composition of independent units.