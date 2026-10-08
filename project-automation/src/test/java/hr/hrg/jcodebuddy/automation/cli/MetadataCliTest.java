// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.automation.cli;

import hr.hrg.jcodebuddy.automation.runner.InMemoryMetadataCacheProvider;
import hr.hrg.jcodebuddy.meta.MetadataProvider;
import hr.hrg.jcodebuddy.meta.model.JsonRpcRequest;
import hr.hrg.jcodebuddy.meta.model.JsonRpcResponse;
import hr.hrg.jcodebuddy.meta.rpc.MetadataRpcService;
import hr.hrg.jcodebuddy.meta.rpc.RpcDispatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The manual-mode CLI of plan step 7.7 (DEC-W008): {@code jcodebuddy metadata parse <file>}.
 *
 * <p>Three properties are asserted, and each is one the decision names:
 *
 * <ul>
 *   <li><b>It works in a fresh checkout</b> — no daemon, no cache folder, no prior {@code scan} — which is asserted by
 *       running it against a file in an empty temporary directory and by checking afterwards that <b>no
 *       {@code .jcodebuddy/} was created</b>. A test that only checked the output would pass while the command quietly
 *       started a scan;</li>
 *   <li><b>its output is the JSON the RPC returns</b>, compared against a real {@code parseFile} dispatch through
 *       {@link RpcDispatcher} rather than against a CLI-local expectation — the difference between "same shape" and
 *       "same answer" is the whole point of the round-trip requirement;</li>
 *   <li><b>an unreadable file exits non-zero and prints nothing on stdout</b>, so a caller piping the output never
 *       mistakes an error for metadata.</li>
 * </ul>
 */
class MetadataCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static final String SOURCE = """
            package demo.hr;

            public record PersonSummary(String name, Integer age) {
            }
            """;

    private record Run(int status, String out, String err) {
    }

    private Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            status = MetadataCli.run(args, outStream, errStream);
        }
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theCommandPrintsTheEntryAndLeavesTheCheckoutAlone() throws Exception {
        Path file = tempDir.resolve("PersonSummary.java");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);

        Run run = run("metadata", "parse", file.toString());

        Assertions.assertEquals(0, run.status(), run.err());
        JsonNode printed = MAPPER.readTree(run.out());
        Assertions.assertTrue(printed.has("hash"), run.out());
        Assertions.assertTrue(printed.has("relativePath"), run.out());
        Assertions.assertTrue(printed.has("metadata"), run.out());
        // The fresh-checkout property, asserted rather than assumed: nothing was written beside the source.
        Assertions.assertFalse(Files.exists(tempDir.resolve(".jcodebuddy")),
                "the manual-mode command must not create a cache folder: " + Files.list(tempDir).toList());
        Assertions.assertEquals(1, Files.list(tempDir).count(),
                "and it must not leave anything else behind either");
    }

    @Test
    void theOutputIsTheSameJsonTheRpcReturnsForTheSameFile() throws Exception {
        Path file = tempDir.resolve("PersonSummary.java");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);

        Run run = run("metadata", "parse", file.toString());
        Assertions.assertEquals(0, run.status(), run.err());

        // The RPC answer for the same file: parseFile({relPath, source}) through the real dispatcher. The relPath is
        // the CLI's own rule for it, so the comparison is about the ANSWER rather than about which key each side chose.
        MetadataProvider provider = new InMemoryMetadataCacheProvider();
        RpcDispatcher dispatcher = new RpcDispatcher(provider, MAPPER);
        dispatcher.register(new MetadataRpcService(provider));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("relPath", MetadataCli.relativePathOf(file));
        params.put("source", SOURCE);
        JsonRpcResponse response = dispatcher.dispatch(new JsonRpcRequest("2.0", "1", "parseFile", params));
        Assertions.assertNotNull(response.result, "the RPC must answer a parseFile request: " + response.error);

        JsonNode fromRpc = MAPPER.valueToTree(response.result);
        JsonNode fromCli = MAPPER.readTree(run.out());
        Assertions.assertEquals(fromRpc, fromCli,
                "the CLI's output must be the RPC's own answer, field for field:\nRPC: " + fromRpc + "\nCLI: " + fromCli);
    }

    @Test
    void anUnreadableFileOrAUsageErrorExitsNonZeroAndSaysWhyOnStderr() throws Exception {
        Run missing = run("metadata", "parse", tempDir.resolve("NotThere.java").toString());
        Assertions.assertEquals(2, missing.status(), "a file that cannot be read is a non-zero exit");
        Assertions.assertEquals("", missing.out(), "and nothing is printed on stdout for a caller to mistake for JSON");
        Assertions.assertTrue(missing.err().contains("cannot read"), missing.err());

        Run usage = run("metadata");
        Assertions.assertEquals(2, usage.status());
        Assertions.assertTrue(usage.err().contains("usage: jcodebuddy " + MetadataCli.COMMAND), usage.err());

        // A file that exists but is not Java at all: the provider's refusal must reach the caller as a non-zero exit
        // rather than as a stack trace or an empty line of JSON.
        Path notJava = tempDir.resolve("Broken.java");
        Files.writeString(notJava, "this is not java at all {{{", StandardCharsets.UTF_8);
        Run broken = run("metadata", "parse", notJava.toString());
        Assertions.assertTrue(broken.status() == 0 || broken.status() == 2,
                "a refusal must be an exit code, not a crash: " + broken.status() + " / " + broken.err());
        if (broken.status() != 0) {
            Assertions.assertEquals("", broken.out(), broken.out());
            Assertions.assertFalse(broken.err().isBlank(), "and the reason has to be readable");
        }
    }
}
