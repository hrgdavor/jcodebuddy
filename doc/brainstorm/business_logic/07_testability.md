# Testability

> Back to [README](README.md).

The key insight: **if a pure function only describes effects into the
unit, the test asserts on the unit** instead of mocking the effects.

```java
@Test
void recomputeTotals_contributesExpectedWritesAndNotifications() {
    var unit = new ProcessingUnit<>(OrderContext.class, testConfig(), new ManualClock(...));
    var order = sampleOrder();

    recomputeTotals(unit, order);

    assertThat(unit.entityWrites().updatesFor(order.id()))
        .singleElement()
        .satisfies(w -> {
            assertThat(w.entity().totals().total()).isEqualTo(new BigDecimal("97.50"));
        });

    assertThat(unit.notifications().queue())
        .singleElement()
        .isInstanceOf(EmailOutcome.class)
        .satisfies(e -> assertThat(e.to()).isEqualTo(order.customerEmail()));

    assertThat(unit.audit().entries())
        .extracting(AuditEntry::kind)
        .containsExactly("totals.recomputed");
}
```

No mocks for `EmailService`, no mocks for the database, no mocks for
the clock — they are all in the unit.

## Golden snapshots

For complex graphs, a **golden-file test** can serialize the entire
unit (entity writes, notifications, audit, snapshots, diffs) into a
file that is reviewed alongside the code. Changes to the unit's
contents are visible as diffs in code review.

> `<!-- TODO/EXPLORE: golden-file format (JSON, custom text), where to
> store the files, and how to make diffs reviewer-friendly. -->`