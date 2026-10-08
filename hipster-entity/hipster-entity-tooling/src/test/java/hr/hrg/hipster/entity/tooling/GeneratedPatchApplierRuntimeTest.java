// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The patch applier applied at <b>runtime</b> — plan step 6.2's last gap, and the strongest form of its gate
 * (*"the RFC-6902-like patch can be applied"*).
 *
 * <p>The other tests compile the emitted class and assert its text, which proves it is well-formed and shaped right. It
 * does not prove that applying a document <em>works</em>: that the switch matches the names the serializer writes, that
 * `current` is where the value is read from, that a typed setter is called with the value the JSON held. This test does:
 * the generated applier is compiled, loaded in a class loader of its own, invoked on a document, and the values it
 * wrote are read back.</p>
 *
 * <p>The target is a {@link Proxy} over the view's own {@code Write} interface rather than a hand-written
 * implementation, because that is what makes the assertion exact: every setter call the applier makes is recorded with
 * its argument, so a wrong field name, a missed operation or a value converted the wrong way all show up as a difference
 * in one map — and no test stub can accidentally satisfy the applier by being permissive. (A proxy in a test is not the
 * reflective dispatch DEC-019 forbids in generated code; it is how a test observes a generated call.)</p>
 */
class GeneratedPatchApplierRuntimeTest {

    @TempDir
    Path tempDir;

    /** The calls a patch produced: setter name to the value it was called with. */
    private static final class RecordingWriter implements InvocationHandler {
        final Map<String, Object> calls = new LinkedHashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (args != null && args.length == 1) {
                calls.put(method.getName(), args[0]);
                return proxy;
            }
            return null;
        }
    }

    private static final String MARKER = """
            package runtime.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface Thing extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW = """
            package runtime.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            import hr.hrg.hipster.entity.api.ViewWriter;
            import java.math.BigDecimal;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends Thing {
                String firstName();
                Integer age();
                BigDecimal balance();
                interface Write extends PersonSummary, ViewWriter {
                    Write firstName(String value);
                    Write age(Integer value);
                    Write balance(BigDecimal value);
                }
            }
            """;

    /**
     * The document the deep serializer emits for three changed leaves: a map keyed by field name, each entry carrying
     * the leaf's {@code current} value. Written by hand here on purpose — it is the <b>contract</b> being tested, and
     * taking it from the emitter would make emitter and applier agree with each other rather than with the format.
     */
    private static JsonNode document() {
        var root = JsonNodeFactory.instance.objectNode();
        root.set("firstName", JsonNodeFactory.instance.objectNode()
                .put("op", "replace").put("current", "Grace"));
        root.set("age", JsonNodeFactory.instance.objectNode()
                .put("op", "replace").put("current", 37));
        root.set("balance", JsonNodeFactory.instance.objectNode()
                .put("op", "replace").put("current", "12.50"));
        root.set("noSuchField", JsonNodeFactory.instance.objectNode()
                .put("op", "replace").put("current", "ignored"));
        return root;
    }

    @Test
    void applyingADocumentWritesTheValuesItNames() throws Exception {
        Path root = tempDir.resolve("tree");
        Path pkg = root.resolve("runtime/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        Files.writeString(pkg.resolve("PersonSummary.java"), VIEW);

        EntityMetadataGenerator.setGeneratePatchAppliers(true);
        try {
            EntityMetadataGenerator.generate(root, root, root, new DivergenceReporter());
        } finally {
            EntityMetadataGenerator.setGeneratePatchAppliers(false);
        }

        Path classes = CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "patch-applier-runtime",
                CompileHarness.javaSourcesUnder(root), List.of());

        try (var loader = new java.net.URLClassLoader(new java.net.URL[] { classes.toUri().toURL() },
                getClass().getClassLoader())) {
            Class<?> applier = loader.loadClass("runtime.hr.PersonSummaryPatchApplier");
            Class<?> write = loader.loadClass("runtime.hr.PersonSummary$Write");

            RecordingWriter recorder = new RecordingWriter();
            Object target = Proxy.newProxyInstance(loader, new Class<?>[] { write }, recorder);

            @SuppressWarnings("unchecked")
            List<String> report = (List<String>) applier
                    .getMethod("apply", JsonNode.class, write)
                    .invoke(null, document(), target);

            Assertions.assertEquals("Grace", recorder.calls.get("firstName"),
                    "the String leaf is applied with the value the document carried");
            Assertions.assertEquals(37, recorder.calls.get("age"),
                    "and the numeric leaf as its declared type, not as text");
            Assertions.assertEquals(new java.math.BigDecimal("12.50"), recorder.calls.get("balance"),
                    "and a format-stable JDK type is converted rather than passed through as a string");
            Assertions.assertEquals(3, recorder.calls.size(),
                    "nothing else was written: " + recorder.calls);
            Assertions.assertEquals(List.of("unknown_field: noSuchField is not a writable field of PersonSummary"),
                    report,
                    "a field the view does not have is REPORTED and not applied (DEC-016), which is the half of the "
                            + "contract a compile check cannot see");
        }
    }
}
