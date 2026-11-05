package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * E3 of {@code plans/hipster-entity-mvp.md}: every view records the FQNs its output was generated from.
 *
 * <p>The assertions are deliberately about the document a consumer reads, not about the Java model: the
 * contract is that {@code views[].inputs} and {@code artifacts[].inputs} are present, sorted, path-free,
 * and resolvable through the module's class index. A second pass over the same tree must produce the same
 * sets, because E4 compares a pass with a previous one and a non-deterministic input list would make every
 * view look affected.</p>
 */
@Tag("generator")
class ViewInputsMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ExamplePassFixture fixture;
    private static JsonNode person;
    private static JsonNode index;

    @BeforeAll
    static void generateOnce() throws Exception {
        fixture = ExamplePassFixture.shared();
        person = MAPPER.readTree(fixture.documentText("Person"));
        index = MAPPER.readTree(fixture.indexText());
    }

    @Test
    void everyViewAndArtifactCarriesResolvablePathFreeInputs() throws Exception {
        Set<String> classes = new LinkedHashSet<>();
        index.path("classes").propertyStream().forEach(entry -> classes.add(entry.getKey()));

        for (JsonNode view : person.path("views")) {
            JsonNode inputs = view.path("inputs");
            Assertions.assertTrue(inputs.isArray() && !inputs.isEmpty(),
                    view.path("name").asText() + " must carry its generated-from inputs");
            assertInputs(view.path("name").asText(), inputs, classes);

            Assertions.assertTrue(view.path("artifacts").isArray() && !view.path("artifacts").isEmpty(),
                    view.path("name").asText() + " must carry its artifact inventory");
            for (JsonNode artifact : view.path("artifacts")) {
                Assertions.assertTrue(artifact.path("inputs").isArray()
                                && artifact.path("inputs").size() == inputs.size(),
                        "every artifact carries the view's input set: " + artifact.path("name").asText());
                assertInputs(artifact.path("name").asText(), artifact.path("inputs"), classes);
            }
        }
    }

    @Test
    void theMarkerAddonAndSupertypesAreRecorded() throws Exception {
        Assertions.assertTrue(inputs("PersonSummary").contains(
                        "hr.hrg.hipster.entityexample.person.entity.Person"),
                "the marker is an input even when the view's own accessors are all local");
        Assertions.assertTrue(inputs("PersonSummary").contains(
                        "hr.hrg.hipster.entityexample.person.entity.PersonSummary"),
                "the view's own file is an input");
        Assertions.assertTrue(inputs("PersonDetails").contains(
                        "hr.hrg.hipster.entityexample.example.Auditable"),
                "a cross-package addon is an input");
        Assertions.assertTrue(inputs("PersonDetails").contains(
                        "hr.hrg.hipster.entityexample.person.entity.Person"),
                "the marker is an input for a view that inherits through it");
    }

    @Test
    void everyInputIsAnFqnNotAPath() throws Exception {
        for (JsonNode view : person.path("views")) {
            for (JsonNode input : view.path("inputs")) {
                String fqn = input.asText();
                Assertions.assertTrue(fqn.startsWith("hr.hrg."),
                        "an input is a project FQN: " + fqn);
                Assertions.assertFalse(fqn.contains("/"), "no module-relative path in an input: " + fqn);
                Assertions.assertFalse(fqn.contains("\\"), "no Windows path in an input: " + fqn);
                Assertions.assertFalse(fqn.endsWith(".java"), "no file name in an input: " + fqn);
            }
        }
    }

    @Test
    void aSecondPassProducesTheSameInputSets() throws Exception {
        JsonNode second = MAPPER.readTree(fixture.secondPass().documentText("Person"));
        Assertions.assertEquals(inputsByName(person), inputsByName(second),
                "input sets are a deterministic function of the tree, not of pass order");
    }

    private static void assertInputs(String owner, JsonNode inputs, Set<String> classes) {
        List<String> values = new ArrayList<>();
        inputs.forEach(node -> values.add(node.asText()));
        Assertions.assertEquals(values.stream().sorted().toList(), values,
                owner + " inputs are sorted");
        for (String fqn : values) {
            Assertions.assertTrue(classes.contains(fqn),
                    owner + " names " + fqn + " but the class index has no row for it");
        }
    }

    private static Set<String> inputs(String viewName) {
        for (JsonNode view : person.path("views")) {
            if (viewName.equals(view.path("name").asText())) {
                Set<String> values = new LinkedHashSet<>();
                view.path("inputs").forEach(node -> values.add(node.asText()));
                return values;
            }
        }
        throw new AssertionError("no view named " + viewName);
    }

    private static List<String> inputsByName(JsonNode document) {
        List<String> names = new ArrayList<>();
        for (JsonNode view : document.path("views")) {
            Set<String> inputs = new LinkedHashSet<>();
            view.path("inputs").forEach(node -> inputs.add(node.asText()));
            names.add(view.path("name").asText() + "=" + new ArrayList<>(inputs));
        }
        names.sort(String::compareTo);
        return names;
    }
}
