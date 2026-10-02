package hr.hrg.hipster.entity.jackson;

import hr.hrg.hipster.entity.jackson.fixture.AccountRecord;
import hr.hrg.hipster.entity.jackson.fixture.AccountRecord_;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;

/**
 * The S4 "absent is not the same as explicit null" policy, pinned inside the module that implements
 * it (plan.dsflash § 10/5.2, § 8.4/3.11).
 *
 * <p>S4 exists because the positional array has to be able to distinguish three states that a
 * naive reader would collapse into one: a field the producer never sent, a field the producer
 * explicitly set to {@code null}, and a field the materialization cannot fill at all — a
 * {@code DERIVED} or {@code JOINED} column, which has an ordinal and a nullable slot but no payload
 * representation.</p>
 *
 * <p>The test asserts the consequence rather than the mechanism: an absent {@code DERIVED} field
 * leaves its slot {@code null} and the {@code RECORD}-level {@code create()} <strong>accepts</strong>
 * it. Adding a "missing required field" rejection here would be the wrong fix — it would break every
 * derived or joined view — so this test is the guard against someone deciding it is one.</p>
 */
class JacksonAbsentFieldPolicyTest {

    private static AccountRecord parse(String json) throws Exception {
        try (JsonParser parser = new ObjectMapper().createParser(json)) {
            return EntityJacksonMapper.fromJson(AccountRecord_.META, parser);
        }
    }

    @Test
    void anAbsentDerivedFieldStaysNullAndCreateAcceptsIt() throws Exception {
        AccountRecord view = parse("{\"id\":1,\"owner\":\"Alice\"}");

        Assertions.assertEquals(1L, view.id());
        Assertions.assertEquals("Alice", view.owner());
        Assertions.assertNull(view.auditCount(),
                "the DERIVED field has an ordinal but no payload source, so its slot is absent — "
                        + "which S4 distinguishes from an explicit null");
    }

    @Test
    void anExplicitJsonNullAlsoLandsAsNull() throws Exception {
        AccountRecord view = parse("{\"id\":2,\"owner\":\"Bob\",\"auditCount\":null}");

        Assertions.assertEquals("Bob", view.owner());
        Assertions.assertNull(view.auditCount(),
                "explicit null is accepted too — S4 keeps the two states distinguishable on the way "
                        + "in, and this materialization maps both to a null slot");
    }

    @Test
    void aPresentDerivedFieldIsCarriedThrough() throws Exception {
        AccountRecord view = parse("{\"id\":3,\"owner\":\"Carol\",\"auditCount\":17}");

        Assertions.assertEquals(17L, view.auditCount(),
                "a producer that does send the derived value is believed");
    }

    @Test
    void anEmptyObjectStillProducesAViewWithEverySlotNull() throws Exception {
        AccountRecord view = parse("{}");

        Assertions.assertNull(view.id());
        Assertions.assertNull(view.owner());
        Assertions.assertNull(view.auditCount());
    }
}
