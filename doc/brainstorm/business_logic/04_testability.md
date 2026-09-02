# Testability (Without Mocks)

> Back to [README](README.md).

The key insight: **if a step fully populates the data needed for its
effect, the test asserts on the produced outcome** instead of mocking
the effect itself.

```java
@Test
void applyPromotion_emitsPatchAndEmail() {
    var order    = UpdatePair.of(sampleOrder(), emptyUpdate());
    var promo    = UpdatePair.of(tenPercentOff(), emptyUpdate());
    var sink     = new RecordingOutcomeSink();

    applyPromotion(order, promo, sink);

    assertThat(sink.outcomes())
        .hasSize(1)
        .first()
        .isInstanceOfSatisfying(UpdateOrderOutcome.class, o -> {
            assertThat(o.orderId()).isEqualTo(42L);
            assertThat(o.patch().total()).isEqualTo(new BigDecimal("90.00"));
        });
}
```

> `<!-- TODO/EXPLORE: golden-file tests that snapshot the *full* outcome
> list of a pipeline for visual diffing in code review. -->`