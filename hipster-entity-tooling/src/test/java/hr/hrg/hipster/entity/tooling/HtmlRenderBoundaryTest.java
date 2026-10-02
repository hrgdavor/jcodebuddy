package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * DEC-027's boundary, asserted from the Java side: HTML is rendered by the Bun scripts in
 * {@code scripts/entity-html/}, and no Java generator in this module emits it.
 *
 * <p>The rule is easy to state and easy to erode. The first draft of the entity index was a Java
 * emitter in this module, and it was deleted when the decision was taken; without an assertion, the
 * next person who wants "just one small report" re-adds exactly that class, and the property DEC-027
 * buys — presentation without a Maven rebuild, in a file anyone can grep — quietly disappears.</p>
 *
 * <p>The same reason the repository's other conformance tests exist (F-43, F-47): a rule that is true
 * but unasserted decays into a rule that is false. These tests read the tree as text, which is what
 * makes them cheap enough to run in the normal gate.</p>
 */
class HtmlRenderBoundaryTest {

    /** The renderer, and the docs a reader is sent to from `AGENTS.md` and the ADR. */
    private static final List<String> RENDERER_FILES = List.of(
            "scripts/entity-html/index.js",
            "scripts/entity-html/render.js",
            "scripts/entity-html/sources.js",
            "scripts/entity-html/links.js",
            "scripts/entity-html/metadata.js",
            "scripts/entity-html/entity-html.test.js",
            "scripts/entity-html/README.md");

    /**
     * The renderer's own sources — everything except the test, which names the frameworks it forbids
     * and would therefore match a naive scan.
     */
    private static final List<String> RENDERER_CODE = List.of(
            "scripts/entity-html/index.js",
            "scripts/entity-html/render.js",
            "scripts/entity-html/sources.js",
            "scripts/entity-html/links.js",
            "scripts/entity-html/metadata.js");

    private static Path repoRoot() {
        return CompileHarness.findRepoRoot();
    }

    private static String read(String relative) throws Exception {
        return read(repoRoot().resolve(relative));
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /**
     * No Java source in this module writes HTML.
     *
     * <p>The check is on the artefacts of emitting HTML rather than on the word: a doc comment may
     * legitimately discuss a report, and it does, while a doctype, an {@code <html>} tag or a
     * {@code text/html} content type can only be an emitter.</p>
     */
    @Test
    void noJavaGeneratorInThisModuleEmitsHtml() throws Exception {
        Path main = repoRoot().resolve("hipster-entity-tooling/src/main/java");
        Assertions.assertTrue(Files.isDirectory(main), "the tooling's main sources must exist");

        StringBuilder offenders = new StringBuilder();
        try (var walk = Files.walk(main)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = read(file);
                for (String marker : List.of("<!DOCTYPE", "<html", "text/html")) {
                    if (source.contains(marker)) {
                        offenders.append(repoRoot().relativize(file)).append(" contains ")
                                .append(marker).append("; ");
                    }
                }
            }
        }
        Assertions.assertEquals("", offenders.toString(),
                "DEC-027: HTML is rendered by scripts/entity-html, so a fact a report needs is added "
                        + "to the JSON metadata instead of an emitter in the tooling");
    }

    /** The renderer exists, is Bun JavaScript, and reads the metadata JSON the generator writes. */
    @Test
    void theRendererExistsAndReadsTheMetadataJson() throws Exception {
        for (String file : RENDERER_FILES) {
            Assertions.assertTrue(Files.exists(repoRoot().resolve(file)),
                    "DEC-027 names this file; it must exist: " + file);
        }
        String metadata = read("scripts/entity-html/metadata.js");
        Assertions.assertTrue(metadata.contains(".metadata.json"),
                "the renderer's model must come from <Marker>.metadata.json (DEC-027 section 2)");
        Assertions.assertTrue(metadata.contains("sourcePath"),
                "and it must read the module-relative sourcePath the generator records, rather than "
                        + "resolving a class's file by name (DEC-027 amendment)");
        // DEC-029: a document names its files by the fully qualified name of the type that file declares,
        // and the artifact inventory and field maps are the model rather than something to infer.
        Assertions.assertTrue(metadata.contains("classes.json"),
                "the renderer must resolve a document's FQN through the module class index "
                        + "(.jcodebuddy/index/classes.json), not render an FQN as if it were a path");
        Assertions.assertTrue(metadata.contains("fully qualified"),
                "and its own documentation must say what a `file` value is, so the next reader does not "
                        + "have to infer it from the code");
        Assertions.assertTrue(metadata.contains("files.json"),
                "and it must still read the DEC-028 addressing table for one revision, or a document "
                        + "written before the class index can no longer be rendered at all");
        Assertions.assertTrue(metadata.contains("artifacts") && metadata.contains("fields"),
                "and it must read the artifact inventory and the per-field location maps instead of "
                        + "recognising artifacts by naming convention (DEC-028)");
        Assertions.assertTrue(metadata.contains("html_index_missing") || read("scripts/entity-html/links.js")
                        .contains("html_index_missing"),
                "and a missing or unrecognised index must be a loud diagnostic, never a page of dead links");
        Assertions.assertTrue(read("scripts/entity-html/index.js").contains("bun run scripts/entity-html/index.js"),
                "the renderer's usage line must be the command the docs give");
    }

    /** The page is vanilla JavaScript with no framework and no dependency to pull one in. */
    @Test
    void thePageIsVanillaJavaScriptWithNoFramework() throws Exception {
        String render = read("scripts/entity-html/render.js");
        Assertions.assertTrue(render.contains("<script>"),
                "the page carries one inline script, not an external one");
        Assertions.assertFalse(render.contains("<script src="),
                "the page must not load an external script");

        // A framework would arrive as an import, a require, or a global namespace: those are the
        // shapes that matter, and unlike the bare word they cannot be a legitimate mention.
        for (String framework : List.of("react", "svelte", "solid-js", "preact", "vue", "angular",
                "jquery", "lit-html")) {
            for (String file : RENDERER_CODE) {
                String source = read(file).toLowerCase();
                for (String shape : List.of("from '" + framework, "from \"" + framework,
                        "require('" + framework, "require(\"" + framework, framework + ".")) {
                    Assertions.assertFalse(source.contains(shape),
                            "DEC-027 section 3: " + file + " must not reach for " + framework);
                }
            }
        }

        // The dependency-free claim, checked where a dependency would actually have to live. The rule is
        // about the whole subtree rather than two paths: DEC-027's 2026-10-01 amendment lets a page be a
        // jsx6 application, and such a page is *built* — but its build belongs in a home of its own,
        // because this subtree must render with Bun and nothing else. Naming two directories would let the
        // obvious mistake through, which is a new `scripts/<page>/package.json`.
        Path scripts = repoRoot().resolve("scripts");
        StringBuilder installed = new StringBuilder();
        try (var walk = Files.walk(scripts)) {
            for (Path path : walk.toList()) {
                String relative = repoRoot().relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path) && path.getFileName().toString().equals("node_modules")) {
                    installed.append(relative).append(" exists (a package manager ran here); ");
                } else if (path.getFileName().toString().equals("package.json")
                        && read(path).contains("\"dependencies\"")) {
                    installed.append(relative).append(" declares dependencies; ");
                }
            }
        }
        Assertions.assertEquals("", installed.toString(),
                "scripts/ must stay dependency-free: the vanilla renderer runs on Bun alone, and a jsx6 "
                        + "page is built in a home of its own (DEC-027's amendment). Remove the dependency, "
                        + "or move the page's package out of scripts/");
        String pkg = read("scripts/package.json");
        Assertions.assertTrue(pkg.contains("entity-html"),
                "and the package scripts must expose the renderer");
    }

    /**
     * A generation pass renders the page. The launcher is {@code scripts/gen.js} and not a batch file — the byte
     * discipline that used to be asserted here (pure ASCII, CRLF-only, "cmd.exe mis-parses otherwise") existed
     * because the pass was driven by a {@code .cmd}; AGENTS.md § 2 replaced those with Bun JavaScript, so the
     * assertions are now about the script that exists: that it renders the page, and that no shell wrapper has
     * crept back beside it.
     */
    @Test
    void thePassRendersThePage() throws Exception {
        String gen = read("scripts/gen.js");
        Assertions.assertTrue(gen.contains("'entity-html', 'index.js'"),
                "scripts/gen.js renders the page as the last step of a pass (DEC-027 section 6)");
        Assertions.assertTrue(gen.contains("IS fatal"),
                "and it treats a page that cannot verify its links as fatal, which is the rule it inherited");

        Assertions.assertFalse(Files.exists(repoRoot().resolve("scripts/gen.cmd")),
                "scripts/gen.cmd was replaced by scripts/gen.js");
        for (String wrapper : List.of("scripts/gen.sh", "scripts/gen.ps1", "scripts/gen.bat")) {
            Assertions.assertFalse(Files.exists(repoRoot().resolve(wrapper)),
                    "and no shell twin may be added beside it (AGENTS.md section 2)");
        }
    }

    /** The rule is stated where both a human and an agent will read it, and indexed. */
    @Test
    void theRuleIsRecordedInAgentsMdAndIndexedInTheDecisionsReadme() throws Exception {
        String agents = read("AGENTS.md");
        Assertions.assertTrue(agents.contains("DEC-027.md"),
                "AGENTS.md must point at DEC-027: it is the file an agent reads first");
        Assertions.assertTrue(agents.contains("vanilla JavaScript") || agents.contains("framework-free"),
                "and it must carry the vanilla-JavaScript rule, not just the pointer");
        // DEC-027's 2026-10-01 amendment split the rule by what a page is: a page with no or minimal UI
        // stays vanilla, an interactive or advanced page is built with jsx6, and relations or diagrams
        // use jsx6/nodditor. Before the split this test was satisfied by the vanilla half alone, which
        // would leave the *other* half of the rule stated nowhere an agent reads first — the same decay
        // this class exists to prevent, one level up.
        Assertions.assertTrue(agents.contains("jsx6"),
                "and the other half of the split: an interactive page is a jsx6 page (DEC-027's "
                        + "amendment), so AGENTS.md must say so rather than only the vanilla half");

        String index = read("doc-hipster-entity/architecture/decisions/README.md");
        Assertions.assertTrue(index.contains("[DEC-027](DEC-027.md)"),
                "the decisions index must list DEC-027, or a reader cannot find it");

        String adr = read("doc-hipster-entity/architecture/decisions/DEC-027.md");
        Assertions.assertTrue(adr.contains("Status: Accepted"), "the ADR must be accepted");
        Assertions.assertTrue(adr.contains("vanilla JavaScript"),
                "and must state the framework-free rule");
        Assertions.assertTrue(adr.contains("jsx6") && adr.contains("nodditor"),
                "and the amendment that classifies pages: the vanilla rule's subject is the minimal "
                        + "page, interactive pages are jsx6, and diagrams use jsx6/nodditor");
        Assertions.assertTrue(adr.contains("MUST be verified"),
                "and the link-verification rule");
    }
}
