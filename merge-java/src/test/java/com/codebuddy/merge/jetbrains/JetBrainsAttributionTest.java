// {@link com.codebuddy.merge.jetbrains.JetBrainsAttributionTest} Proves every derived file declares its licence and its upstream revision.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge.jetbrains;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The attribution rule, as an executable assertion (unified plan step 4.7).
 *
 * <p>Every file under {@code com.codebuddy.merge.jetbrains} is derived from Apache-2.0-licensed
 * JetBrains code, so Apache 2.0 § 4(b) requires it to carry a prominent notice that it was changed, and
 * § 4(c) requires the upstream attribution to be retained. A rule that is only written down is a rule
 * that rots, so this test reads the files.
 *
 * <p>What it holds, in the order a failure would be found:
 *
 * <ul>
 *   <li>every derived file opens with the four required fragments, in that order ({@code @derived}
 *       last);</li>
 *   <li>the commit it records is {@link JetBrainsProvenance#PINNED_COMMIT} — a file citing a
 *       <em>different</em> revision was translated from a revision nobody checked;</li>
 *   <li>each file names its upstream path, because a derivation without the path it came from can be
 *       checked by neither a reader nor {@code scripts/verify-jetbrains-sources.js};</li>
 *   <li>every derived file is listed in {@code THIRD_PARTY_NOTICES.md}, since that is the record the
 *       licence obligations point at;</li>
 *   <li>the pin appears in {@code THIRD_PARTY_NOTICES.md} and in {@code docs/JETBRAINS_PORT.md}, the two
 *       places that cannot read a Java constant.</li>
 * </ul>
 *
 * <h2>Why the checks are callable on a directory</h2>
 *
 * <p>{@link #checkPackage(Path, String)} takes the directory to check, so the rule itself can be tested
 * against a temporary tree instead of by breaking a real file and hoping. The "the rule can fail" test
 * below does exactly that: a rule nobody has watched fail is a rule nobody knows is running.
 *
 * <h2>What this test deliberately does not do</h2>
 *
 * <p>It does not fetch the upstream repository. A build that reaches the network is a build that fails
 * when the network does, so the checkout check lives in {@code scripts/verify-jetbrains-sources.js} —
 * run deliberately, where a missing checkout produces a message rather than a broken build.
 */
class JetBrainsAttributionTest {

    /** The package this test guards, relative to the module root. */
    private static final String PACKAGE_PATH = "src/main/java/com/codebuddy/merge/jetbrains";

    /** Upstream's modules, which is how a path in a header is recognised as one. */
    private static final List<String> UPSTREAM_MODULE_ROOTS =
        List.of("platform/util/diff/", "platform/diff-impl/", "platform/diff-api/", "platform/util/src/");

    private static final Pattern UPSTREAM_PATH = Pattern.compile(
        "(" + String.join("|", UPSTREAM_MODULE_ROOTS).replace(".", "\\.") + ")[A-Za-z0-9_./-]+\\.(?:java|kt)");

    private static final Pattern RECORDED_COMMIT = Pattern.compile("\\bat commit ([0-9a-fA-F]{7,40})\\b");

    @Test
    @DisplayName("every file in the ported package declares its licence and its upstream revision")
    void everyDerivedFileIsAttributed() throws IOException {
        checkPackage(moduleRoot().resolve(PACKAGE_PATH), "the ported package");
    }

    @Test
    @DisplayName("the rule fails on a missing change notice, on a different commit, and on no upstream path")
    void theRuleCanFail(@TempDir Path temp) throws IOException {
        Path packageDir = Files.createDirectories(temp.resolve(PACKAGE_PATH));

        // A well-formed file is accepted, so the failures below are the rule firing rather than a fixture
        // that was wrong to begin with.
        Path file = packageDir.resolve("Thing.java");
        write(file, upstreamPath(), JetBrainsProvenance.PINNED_COMMIT, "// @derived ");
        checkPackage(packageDir, "a well-formed fixture");

        // The Apache 2.0 § 4(b) change notice, removed: fatal, not cosmetic.
        write(file, upstreamPath(), JetBrainsProvenance.PINNED_COMMIT, "// no change notice here ");
        assertRefused(packageDir, "@derived", "a file without its change notice");

        // A different revision: a file translated from a revision nobody checked.
        write(file, upstreamPath(), "0".repeat(40), "// @derived ");
        assertRefused(packageDir, "revision", "a file citing a different upstream commit");

        // No upstream path at all: the derivation cannot be checked against upstream.
        write(file, "", JetBrainsProvenance.PINNED_COMMIT, "// @derived ");
        assertRefused(packageDir, "upstream path", "a file with no upstream path");
    }

    @Test
    @DisplayName("a derived file the notices do not list is refused, and the failure names it")
    void noticesMustListEveryDerivedFile(@TempDir Path temp) throws IOException {
        Path packageDir = Files.createDirectories(temp.resolve(PACKAGE_PATH));
        Path notices = temp.resolve(JetBrainsProvenance.NOTICES_FILE);
        Files.writeString(notices, "# notices\n\nThe pinned commit is "
            + JetBrainsProvenance.PINNED_COMMIT + ".\n", StandardCharsets.UTF_8);

        write(packageDir.resolve("Unlisted.java"), upstreamPath(),
            JetBrainsProvenance.PINNED_COMMIT, "// @derived ");

        IOException failure = assertThrows(IOException.class,
            () -> assertNoticesListDerivedFiles(packageDir, notices));
        assertTrue(failure.getMessage().contains("MergeResolveUtil.kt"),
            "the failure must name the unlisted derivation, not merely count one: " + failure.getMessage());
    }

    @Test
    @DisplayName("the two documents that cannot read a Java constant carry the pin")
    void documentsCarryThePin() throws IOException {
        String notices = read(moduleRoot().resolve(JetBrainsProvenance.NOTICES_FILE));
        assertTrue(notices.contains(JetBrainsProvenance.PINNED_COMMIT),
            JetBrainsProvenance.NOTICES_FILE + " must record the pinned commit");

        String portDoc = read(moduleRoot().resolve("docs/JETBRAINS_PORT.md"));
        assertTrue(portDoc.contains(JetBrainsProvenance.PINNED_COMMIT),
            "docs/JETBRAINS_PORT.md must record the pinned commit");
    }

    /**
     * Check every derived file under {@code packageDir} against the rule.
     *
     * <p>Every problem is collected before failing, so one run reports all of them rather than the first.
     */
    static void checkPackage(Path packageDir, String description) throws IOException {
        List<Path> sources = javaSources(packageDir);
        if (sources.isEmpty()) {
            throw new IOException("no Java sources under " + packageDir + ", so " + description
                + " asserts nothing. Step 4.7 creates the package files; a walk that finds none has lost"
                + " them rather than found them compliant.");
        }

        List<String> problems = new ArrayList<>();

        for (Path source : sources) {
            String text = read(source);
            String where = describe(source);
            List<String> found = headerProblems(text, where);
            problems.addAll(found);
            if (!found.isEmpty()) {
                continue;
            }

            if (text.contains(JetBrainsProvenance.NOT_DERIVED_MARKER)) {
                // This module's own bookkeeping about upstream, not a translation of it. It must name no
                // upstream file, so a path here would be a false claim of derivation.
                if (UPSTREAM_PATH.matcher(text).find()) {
                    problems.add(where + " carries " + JetBrainsProvenance.NOT_DERIVED_MARKER
                        + " and also names an upstream path; one of the two is wrong");
                }
                continue;
            }

            // Every file names **one** upstream file: the one it is primarily derived from. Several
            // derived files may name the same upstream file — the root and `text` package docs both
            // restate `ComparisonMergeUtil.kt` — so uniqueness is not the rule and demanding it would
            // force a false attribution on one of them.
            Matcher matcher = UPSTREAM_PATH.matcher(text);
            if (!matcher.find()) {
                problems.add(where + " records no upstream path (expected one under "
                    + UPSTREAM_MODULE_ROOTS + ")");
            } else if (matcher.find()) {
                problems.add(where + " names more than one upstream path (" + matcher.group()
                    + " and others); a derived file states the one it came from");
            }
        }

        if (!problems.isEmpty()) {
            throw new IOException(description + " has unattributed or misattributed files: " + problems);
        }
    }

    /**
     * The header defects in one file, as messages.
     *
     * <p>The fragments must appear <em>in order</em>: a header whose lines are shuffled is not the header
     * this rule describes, and the {@code @derived} notice in particular must follow the attribution
     * rather than precede it.
     */
    private static List<String> headerProblems(String text, String where) {
        List<String> problems = new ArrayList<>();
        int previous = -1;
        for (String required : JetBrainsProvenance.requiredHeaderLines()) {
            int at = text.indexOf(required);
            if (at < 0) {
                problems.add(where + " does not contain '" + required.trim() + "'");
                return problems;
            }
            if (at < previous) {
                problems.add(where + " has '" + required.trim() + "' before the line it must follow");
                return problems;
            }
            previous = at;
        }

        // Checked apart from the fragments above, so a wrong revision is reported as a wrong revision.
        Matcher commit = RECORDED_COMMIT.matcher(text);
        if (!commit.find()) {
            problems.add(where + " records no upstream commit revision");
        } else if (!JetBrainsProvenance.PINNED_COMMIT.startsWith(commit.group(1))) {
            problems.add(where + " was read at revision " + commit.group(1)
                + ", but the port is pinned at " + JetBrainsProvenance.PINNED_COMMIT);
        }
        return problems;
    }

    /**
     * Check that {@code THIRD_PARTY_NOTICES.md} lists every derived file the package contains.
     *
     * <p>Read from the notices' own text rather than from a second list: the obligation is that the
     * notices are complete, so the notices are what is compared.
     */
    static void assertNoticesListDerivedFiles(Path packageDir, Path notices) throws IOException {
        String recorded = read(notices);
        List<String> missing = new ArrayList<>();
        for (Path source : javaSources(packageDir)) {
            String text = read(source);
            if (text.contains(JetBrainsProvenance.NOT_DERIVED_MARKER)) {
                continue;
            }
            Matcher matcher = UPSTREAM_PATH.matcher(text);
            if (matcher.find() && !recorded.contains(matcher.group())) {
                missing.add(describe(source) + " derives from " + matcher.group()
                    + ", which " + notices.getFileName() + " does not list");
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("the attribution record is incomplete: " + missing);
        }
    }

    private static void assertRefused(Path packageDir, String expectedInMessage, String what)
        throws IOException {
        IOException failure = assertThrows(IOException.class,
            () -> checkPackage(packageDir, what), what + " must be refused");
        assertTrue(failure.getMessage().contains(expectedInMessage),
            what + ": the failure must mention '" + expectedInMessage + "', was: " + failure.getMessage());
    }

    /** One upstream file, for the fixtures above. */
    private static String upstreamPath() {
        return "platform/util/diff/src/com/intellij/diff/comparison/MergeResolveUtil.kt";
    }

    /** Write a fixture file whose header is the rule's own, with one line optionally replaced. */
    private static void write(Path file, String upstreamPath, String commit, String derivedMarker)
        throws IOException {
        String sourceClause = upstreamPath.isBlank() ? "." : " (" + upstreamPath + ").";
        Files.writeString(file, "// Licensed under the Apache License 2.0; see THIRD_PARTY_NOTICES.md"
            + " — Copyright (C) JetBrains s.r.o.\n"
            + "// Derived from " + JetBrainsProvenance.UPSTREAM_NAME + " at commit " + commit
            + sourceClause + "\n"
            + derivedMarker + "Translated from Kotlin to Java, and stripped of the IntelliJ Platform"
            + " dependency.\n"
            + "\nfinal class Fixture {\n}\n", StandardCharsets.UTF_8);
    }

    private static List<Path> javaSources(Path packageDir) throws IOException {
        if (!Files.isDirectory(packageDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(packageDir)) {
            return walk.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Name a file in a failure message, relative to the module when it is inside it.
     *
     * <p>{@link Path#relativize} throws when the two paths have different roots — which is exactly what a
     * temporary directory is on Windows — so the rule's tests could not report a problem at all. Absolute
     * is the fallback, and it still names the file, which is what the message is for.
     */
    private static String describe(Path file) {
        Path absolute = file.toAbsolutePath();
        Path root = moduleRoot();
        try {
            return root.relativize(absolute).toString();
        } catch (IllegalArgumentException differentRoots) {
            return absolute.toString();
        }
    }

    /**
     * The module root, from the test's working directory.
     *
     * <p>Asserted rather than assumed: a walk that silently found the wrong directory would report every
     * file compliant by finding none.
     */
    private static Path moduleRoot() {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve(JetBrainsProvenance.NOTICES_FILE))
            || !Files.isDirectory(root.resolve(PACKAGE_PATH))) {
            fail("expected to run in the merge-java module root, but " + root
                + " has neither " + JetBrainsProvenance.NOTICES_FILE + " nor " + PACKAGE_PATH);
        }
        return root;
    }
}
