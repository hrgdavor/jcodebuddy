package hr.hrg.hipster.entity.jackson;

import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.ViewReader;
import hr.hrg.hipster.entity.jackson.fixture.Card;
import hr.hrg.hipster.entity.jackson.fixture.Card_;
import hr.hrg.hipster.entity.jackson.fixture.Payment;
import hr.hrg.hipster.entity.jackson.fixture.Payment_;
import hr.hrg.hipster.entity.jackson.fixture.Wallet;
import hr.hrg.hipster.entity.jackson.fixture.Wallet_;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;

import java.io.StringWriter;

/**
 * The polymorphic family, from the Jackson module's point of view (plan.dsflash § 10/5.1, § 9/4.9).
 *
 * <p>Two halves of the wiring are asserted, because they live in different places and either can rot
 * independently:</p>
 * <ul>
 *   <li>the <strong>root</strong> enum owns the discriminator field constant and the permitted
 *       subtypes — the hand-written per-family artifact generation deliberately never emits;</li>
 *   <li>each <strong>concrete</strong> enum owns its discriminator value and an empty subtype
 *       array, and leaves the field slot {@code null}, because
 *       {@code DefaultViewMeta}'s field parameter is typed as the concrete member's own enum and
 *       cannot name the root's constant.</li>
 * </ul>
 *
 * <p>Serialization of a member then works exactly as for any other view — the discriminator is an
 * ordinary field with an ordinal, so it round-trips through the same code path. What the family adds
 * is the metadata a caller uses to pick a member from a payload; this test asserts that metadata is
 * actually reachable, rather than trusting that a generated {@code META} line looks right.</p>
 */
class JacksonPolymorphicViewTest {

    private static Card card(Long id, String masked) {
        Object[] values = new Object[Card_.values().length];
        values[Card_.id.ordinal()] = id;
        values[Card_.type.ordinal()] = "CARD";
        values[Card_.maskedCardNumber.ordinal()] = masked;
        return Card_.META.create(values);
    }

    @Test
    void theRootEnumOwnsTheFieldAndThePermittedSubtypes() {
        ViewMeta<Payment, Payment_> meta = Payment_.META;

        Assertions.assertEquals(Payment_.type, meta.discriminatorField(),
                "the family's field constant is the root enum's own, which is the only type that can "
                        + "name it");
        Assertions.assertEquals("", meta.discriminatorValue(),
                "the root is not itself a member of the family, so it carries no value");
        Assertions.assertArrayEquals(new Class<?>[] { Card.class, Wallet.class },
                meta.permittedSubtypes());
    }

    @Test
    void aConcreteMemberCarriesItsValueAndAnEmptySubtypeArray() {
        Assertions.assertNull(Card_.META.discriminatorField(),
                "a concrete member cannot name its root's constant: the parameter is typed as this "
                        + "member's own enum, which is a different type");
        Assertions.assertEquals("CARD", Card_.META.discriminatorValue());
        Assertions.assertEquals(0, Card_.META.permittedSubtypes().length);

        Assertions.assertEquals("WALLET", Wallet_.META.discriminatorValue());
        Assertions.assertNull(Wallet_.META.discriminatorField());
    }

    @Test
    void theDeclaredValueMatchesWhatTheViewActuallyAnswers() {
        // The generator reads the value out of the view's own `default type()` accessor, so the two
        // must agree by construction; this asserts the construction rather than the generator.
        Card view = card(1L, "****1111");

        Assertions.assertEquals(Card_.META.discriminatorValue(), view.type(),
                "the metadata's value is the view's own answer, not a second hard-coded label");
    }

    @Test
    void aMemberRoundTripsThroughJsonLikeAnyOtherView() throws Exception {
        Card source = card(4L, "****4242");

        StringWriter writer = new StringWriter();
        EntityJacksonMapper.toJson(Card_.META, (ViewReader) source, writer);
        String json = writer.toString();

        Assertions.assertTrue(json.contains("\"type\":\"CARD\""),
                "the discriminator is an ordinary ordinaled field: " + json);
        Assertions.assertTrue(json.contains("\"maskedCardNumber\":\"****4242\""), json);

        try (JsonParser parser = new ObjectMapper().createParser(json)) {
            Card back = EntityJacksonMapper.fromJson(Card_.META, parser);
            Assertions.assertEquals("CARD", back.type());
            Assertions.assertEquals("****4242", back.maskedCardNumber());
            Assertions.assertEquals(4L, back.id());
        }
    }

    @Test
    void aMemberIsDeserializableFromADiscriminatorOnlyPayloadWhenTheRestIsNullable() throws Exception {
        // The shape a dispatcher produces after reading the discriminator: it knows which member to
        // build, and the member's own fields may legitimately be absent.
        try (JsonParser parser = new ObjectMapper().createParser("{\"type\":\"WALLET\"}")) {
            Wallet back = EntityJacksonMapper.fromJson(Wallet_.META, parser);
            Assertions.assertEquals("WALLET", back.type());
            Assertions.assertNull(back.walletAddress());
            Assertions.assertNull(back.id());
        }
    }
}
