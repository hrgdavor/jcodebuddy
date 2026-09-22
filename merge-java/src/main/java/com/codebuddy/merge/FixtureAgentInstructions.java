// {@link com.codebuddy.merge.FixtureAgentInstructions} Loads and writes the AGENTS.md instructions copied into every fixture workspace.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The agent instructions that {@link MergeFileTool} copies into every fixture
 * workspace it prepares.
 *
 * <p>The canonical text lives in this module as the resource
 * {@value #RESOURCE_PATH} and is written into the workspace as
 * {@value #FILE_NAME}, where any LLM agent opening the folder reads it the way
 * it reads a repository's own {@code AGENTS.md}. It states the confidentiality
 * rules for the proprietary original, the anonymization step that produces the
 * only fixture allowed to enter the repository, and the re-verification of the
 * finished resolver against the original case.
 *
 * <p>Loading is a plain resource read of a bundled text asset with a fixed,
 * navigable path - the class <em>is</em> the single place that knows where the
 * instructions live, and there is no scanning or discovery involved.
 */
public final class FixtureAgentInstructions {

    /** The name the instructions are given inside a fixture workspace. */
    public static final String FILE_NAME = "AGENTS.md";

    /** The classpath resource holding the canonical text. */
    public static final String RESOURCE_PATH = "/com/codebuddy/merge/FIXTURE_AGENTS.md";

    private static volatile String cached;

    private FixtureAgentInstructions() {
    }

    /**
     * The canonical instruction text.
     *
     * @throws IllegalStateException when the bundled resource is missing, which
     *         would mean this module was packaged without the file every fixture
     *         workspace depends on - failing loudly is better than writing a
     *         workspace with no rules in it
     */
    public static String content() {
        String local = cached;
        if (local != null) {
            return local;
        }
        try (InputStream in = FixtureAgentInstructions.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("the fixture agent instructions resource "
                    + RESOURCE_PATH + " is missing from the classpath; the merge-java jar"
                    + " must bundle it because every fixture workspace is written with a"
                    + " copy of it as " + FILE_NAME);
            }
            local = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + RESOURCE_PATH, e);
        }
        if (local.isBlank()) {
            throw new IllegalStateException(RESOURCE_PATH + " is empty");
        }
        cached = local;
        return local;
    }

    /**
     * Write the instructions into {@code directory} as {@value #FILE_NAME},
     * creating the directory when needed, and return the written path.
     */
    public static Path writeTo(Path directory) {
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(FILE_NAME);
            Files.writeString(target, content(), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(
                "could not write " + FILE_NAME + " into " + directory, e);
        }
    }
}
