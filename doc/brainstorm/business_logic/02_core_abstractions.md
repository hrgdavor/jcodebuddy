# Core Abstractions

> Back to [README](README.md).

## `Outcome`

A small, **immutable, fully-populated record** describing what should happen,
including every piece of data needed to actually do it.

```java
public interface Outcome {
    /** Stable discriminator used by the dispatcher. */
    String kind();
}
```

Examples:

```java
public record SendEmailOutcome(
    String kind,
    String to, String from, String subject, String bodyHtml,
    List<Attachment> attachments,
    String correlationId
) implements Outcome {}

public record InsertOrderOutcome(
    String kind,
    long orderId,        // assigned by step, NOT by DB
    OrderState state,
    List<OrderLine> lines
) implements Outcome {}

public record UpdateOrderOutcome(
    String kind,
    long orderId,
    OrderPatch patch
) implements Outcome {}
```

> `<!-- TODO/EXPLORE: code-generate `kind()` constants and a `Kinds` registry
> from a single source-of-truth annotated on the record via JCodeBuddy. -->`

## `UpdatePair<Immutable, Update>`

The basic **read/write pair** a step may declare as a dependency.

```java
public record UpdatePair<I, U>(I view, U update) {}
```

Rules:

- `I` (the *view* / *immutable*) is what the step is allowed to read.
- `U` (the *update*) is what the step may return and what will be merged
  back into the entity container.
- A step may declare **multiple** `UpdatePair`s; JCodeBuddy validates
  they do not overlap in fields they write.
- The generator combines several step-local `UpdatePair`s into one
  container that the developer reads as ordinary code.

```java
// Hand-written step (looks like a plain method)
OrderStepResult applyPromotion(
    UpdatePair<OrderView, OrderUpdate> order,
    UpdatePair<PromotionView, PromotionUpdate> promo,
    OutcomeSink out
) {
    var newTotal = order.view().total().apply(promo.view().discount());
    out.emit(new UpdateOrderOutcome(order.update().id(), newTotal));
    return OrderStepResult.cont(order.update().withTotal(newTotal));
}
```

> `<!-- TODO/EXPLORE: how does the generated container materialize as a
> Java type? builder, wither record, sealed interface? -->`

## `EntityWrite<E>` – marker for add vs update

To **own identifiers inside code** rather than relying on the database to
generate them, entity update containers carry an explicit operation marker.

```java
public sealed interface EntityWrite<E>
    permits EntityInsert<E>, EntityUpdate<E>, EntityDelete<E> {}

public record EntityInsert<E>(long id, E entity) implements EntityWrite<E> {}
public record EntityUpdate<E>(long id, E entity) implements EntityWrite<E> {}
public record EntityDelete<E>(long id)               implements EntityWrite<E> {}
```

The dispatcher uses this marker to decide what SQL/operation to run. It
also makes bulk operations and diffs trivial to inspect.

## Arrays of entities – marker propagation

When a top-level entity owns a collection (e.g. `Order.lines`), changes to
elements must force a change marker on the **owner** so the dispatcher
emits a single coherent `UpdateOrderOutcome` even if the head fields were
not touched.

```java
class ArrayChangeMarker<T> {
    boolean dirty();
    void markDirty();
    // specialized impl propagates `markDirty()` to the owner container
}
```

> `<!-- TODO/EXPLORE: precise propagation rules for nested arrays
> (List<List<Item>>, maps, sets, primitive arrays). -->`