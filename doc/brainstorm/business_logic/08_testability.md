# Testability

> Back to [README](README.md).

The key insight: **if a pure function only describes effects into the
unit, the test asserts on the unit** instead of mocking the effects.
Tests can be **scoped** to the slot that matters (core vs
side-effect vs audit), so a test that wants to verify the business
outcome doesn't have to assert on every email body.

## Asserting on the core

```java
@Test
void recomputeTotals_contributesExpectedCoreWrite() {
    var unit = new ProcessingUnit<>(OrderContext.class, testConfig(), new ManualClock(...));
    var order = sampleOrder();

    recomputeTotals(unit, order);

    assertThat(unit.coreWrites().updatesFor(order.id()))
        .singleElement()
        .satisfies(w -> {
            assertThat(w.entity().totals().total()).isEqualTo(new BigDecimal("97.50"));
        });
}
```

## Asserting on side effects separately

```java
@Test
void recalcOrder_emitsReceiptEmailAndNextStepTrigger() {
    var unit = runRecalcOrder(sampleOrder());

    assertThat(unit.sideEffects().queue())
        .hasSize(2)
        .anySatisfy(e -> assertThat(e).isInstanceOf(EmailOutcome.class))
        .anySatisfy(e -> assertThat(e).isInstanceOf(NextStepTrigger.class));
}
```

## No mocks

`EmailService`, `OrderRepository`, `Clock`, etc. are not mocked —
they are not called from the pure function at all. The unit IS the
faked collaborator.

## Golden snapshots

For complex graphs, a **golden-file test** can serialize the entire
unit (core writes, side effects, audit, snapshots, diffs) into a
file that is reviewed alongside the code. Changes to the unit's
contents are visible as diffs in code review.

The golden file can be split per category so reviewers can approve a
"core diff" separately from a "side-effect diff".

> `<!-- TODO/EXPLORE: golden-file format (JSON, custom text), where to
> store the files, and how to make diffs reviewer-friendly. -->`