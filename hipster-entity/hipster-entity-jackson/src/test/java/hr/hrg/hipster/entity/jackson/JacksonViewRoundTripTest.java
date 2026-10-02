package hr.hrg.hipster.entity.jackson;

import hr.hrg.hipster.entity.api.ViewReader;
import hr.hrg.hipster.entity.jackson.fixture.Account;
import hr.hrg.hipster.entity.jackson.fixture.AccountRecord;
import hr.hrg.hipster.entity.jackson.fixture.AccountRecord_;
import hr.hrg.hipster.entity.jackson.fixture.Account_;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/**
 * Round-trip coverage <strong>inside</strong> {@code hipster-entity-jackson} (plan.dsflash § 10/5.1,
 * § 10/5.3).
 *
 * <p>The module had no tests of its own before this class: every Jackson assertion in the repository
 * lived in {@code hipster-entity-test}, which is an integration module. That is a coverage hole in
 * exactly the direction that hurts — a change to the serializer or deserializer here was only
 * detected one module away, through the example's view rather than through the contract the module
 * itself publishes.</p>
 *
 * <p>Both materializations § 10/5.1 names are covered, because they do not share a code path: the
 * {@code META} level produces a {@link ViewReader} proxy whose positional array the serializer walks
 * directly, while the {@code RECORD} level produces a plain record that is <em>not</em> a
 * {@code ViewReader} and therefore needs the caller-supplied positional mapping. The write-path
 * wrappers (§ 10/5.3) are exercised both directly and through module registration.</p>
 */
class JacksonViewRoundTripTest {

    private static Account account(Long id, String owner, Long balance, Map<String, List<Long>> tags) {
        Object[] values = new Object[Account_.values().length];
        values[Account_.id.ordinal()] = id;
        values[Account_.owner.ordinal()] = owner;
        values[Account_.balance.ordinal()] = balance;
        values[Account_.tags.ordinal()] = tags;
        return Account_.META.create(values);
    }

    @Test
    void metaLevelViewSurvivesAJsonRoundTrip() throws Exception {
        Account source = account(7L, "Alice", 120L, Map.of("role", List.of(1L, 2L)));

        StringWriter writer = new StringWriter();
        EntityJacksonMapper.toJson(Account_.META, (ViewReader) source, writer);
        String json = writer.toString();

        Assertions.assertTrue(json.contains("\"owner\":\"Alice\""),
                "the serializer walks fieldNameAt(i), so the accessor name is the JSON name: " + json);
        Assertions.assertTrue(json.contains("\"balance\":120"), json);

        try (JsonParser parser = new ObjectMapper().createParser(json)) {
            Account back = EntityJacksonMapper.fromJson(Account_.META, parser);
            Assertions.assertEquals(source.id(), back.id());
            Assertions.assertEquals(source.owner(), back.owner());
            Assertions.assertEquals(source.balance(), back.balance());
            Assertions.assertEquals(source.tags(), back.tags());
        }
    }

    @Test
    void anUnknownJsonFieldIsSkippedRatherThanFailing() throws Exception {
        // The deserializer resolves names through the field enum, never through a HashMap, and an
        // unrecognised name is skipped (DEC-016). A payload from a newer producer must not explode.
        String json = "{\"id\":9,\"owner\":\"Bob\",\"notAField\":{\"nested\":[1,2]},\"balance\":5}";

        Account back;
        try (JsonParser parser = new ObjectMapper().createParser(json)) {
            back = EntityJacksonMapper.fromJson(Account_.META, parser);
        }
        Assertions.assertEquals(9L, back.id());
        Assertions.assertEquals("Bob", back.owner());
        Assertions.assertEquals(5L, back.balance());
    }

    @Test
    void recordLevelViewIsSerializedThroughThePositionalOverload() throws Exception {
        AccountRecord source = new AccountRecord.Record(3L, "Carol", 42L);

        StringWriter writer = new StringWriter();
        EntityJacksonMapper.toJson(AccountRecord_.META, source,
                view -> new Object[] { view.id(), view.owner(), view.auditCount() },
                writer);
        String json = writer.toString();

        Assertions.assertTrue(json.contains("\"owner\":\"Carol\""), json);
        Assertions.assertTrue(json.contains("\"auditCount\":42"), json);

        try (JsonParser parser = new ObjectMapper().createParser(json)) {
            AccountRecord back = EntityJacksonMapper.fromJson(AccountRecord_.META, parser);
            Assertions.assertEquals(source.id(), back.id());
            Assertions.assertEquals(source.owner(), back.owner());
            Assertions.assertEquals(source.auditCount(), back.auditCount());
        }
    }

    @Test
    void thePositionalOverloadRefusesAnArrayThatDoesNotCoverEveryField() {
        AccountRecord source = new AccountRecord.Record(3L, "Carol", 42L);

        IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
                () -> EntityJacksonMapper.toJson(AccountRecord_.META, source,
                        view -> new Object[] { view.id(), view.owner() },
                        new StringWriter()));

        Assertions.assertTrue(failure.getMessage().contains("3"),
                "the message names the field count it needs: " + failure.getMessage());
    }

    @Test
    void moduleRegistrationDrivesBothDirectionsThroughStockJackson() throws Exception {
        Account source = account(11L, "Dave", 1L, null);

        ObjectMapper mapper = EntityJacksonMapper.registerModule(new ObjectMapper(), Account_.META);
        String json = mapper.writeValueAsString(source);
        Account back = mapper.readValue(json, Account.class);

        Assertions.assertEquals(source.id(), back.id());
        Assertions.assertEquals(source.owner(), back.owner());
        Assertions.assertEquals(source.balance(), back.balance());
        Assertions.assertNull(back.tags(), "an explicit JSON null stays null (S4)");
    }

    @Test
    void theJsonWrappersSerializeAndDeserializeTheSameBytesAsTheMapper() throws Exception {
        Account source = account(5L, "Erin", null, Map.of());

        // The module installs EntityJacksonViewJsonSerializer/JsonDeserializer; the mapper helpers
        // drive EntityJacksonViewSerializer/EntityJacksonViewDeserializer directly. Both routes exist
        // in the public API, so they must agree on the bytes.
        ObjectMapper mapper = EntityJacksonMapper.registerModule(new ObjectMapper(), Account_.META);
        String viaWrapper = mapper.writeValueAsString(source);

        StringWriter writer = new StringWriter();
        EntityJacksonMapper.toJson(Account_.META, (ViewReader) source, writer);
        String viaMapper = writer.toString();

        Assertions.assertEquals(viaMapper, viaWrapper,
                "the same view must serialize identically through both public routes");

        Account fromWrapper = mapper.readValue(viaWrapper, Account.class);
        Assertions.assertEquals("Erin", fromWrapper.owner());

        try (JsonParser parser = new ObjectMapper().createParser(viaWrapper)) {
            Assertions.assertEquals("Erin", EntityJacksonMapper.fromJson(Account_.META, parser).owner());
        }
    }
}
