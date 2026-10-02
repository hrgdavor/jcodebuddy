package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The privacy rule, as an executable assertion: a {@code project-automation} module is never published
 * and is never depended on by another module.
 *
 * <p>The rule is {@code AGENTS.md} § 1.1, and it needs a guard rather than a paragraph for a reason this
 * repository demonstrated on itself. Both halves of the rule were written down — the root README said the
 * module "does not participate in the project's packaged artifact", and DEC-W003 said it must not be a
 * transitive dependency of a runtime module — and both were true, and the rule still failed:
 * {@code java-watch-agent} depended on {@code project-automation}, that dependency was only satisfiable
 * because the artifact was being installed into {@code ~/.m2}, and a bare {@code mvn install} put it
 * there. Each fault made the other possible, so neither read as wrong on its own.
 *
 * <p>That is the lesson this class encodes. "Not in the packaged artifact" and "not in a repository" are
 * different guarantees, and an unenforced promise in a document is how the violation survived. So the
 * check reads the POMs directly rather than trusting a convention:
 *
 * <ul>
 *   <li>every module whose artifactId ends in {@code project-automation} skips {@code install} and
 *       {@code deploy};</li>
 *   <li>no other module declares a dependency on one, in any scope and in any profile;</li>
 *   <li>and no publishing route bypasses the skip — no {@code distributionManagement}, no
 *       {@code altDeploymentRepository}.</li>
 * </ul>
 *
 * <p>The scope of the walk is deliberately shallow, like {@code GeneratorGuardTest}'s: this repository's
 * own modules. A driver project under {@code proto/} is a separate repository and is not walked — its own
 * build enforces its own copy of the rule, and this test cannot see into another project's git. What it
 * <i>can</i> do is refuse to let this repository break the rule again.
 */
class ProjectAutomationIsolationTest {

    /** The suffix that makes a module private, whichever project it belongs to. */
    private static final String PRIVATE_SUFFIX = "project-automation";

    /** A `<dependency>` block that names project-automation as its artifactId. */
    private static final Pattern DEPENDS_ON_IT = Pattern.compile(
            "<dependency>(?:(?!</dependency>).)*?<artifactId>([^<]*" + PRIVATE_SUFFIX
                    + "[^<]*)</artifactId>(?:(?!</dependency>).)*?</dependency>",
            Pattern.DOTALL);

    /** An artifactId declaration, used to find the private modules themselves. */
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    @Test
    void everyPrivateModuleSkipsInstallAndDeploy() throws Exception {
        Path repoRoot = CompileHarness.findRepoRoot();
        List<Path> privatePoms = pomsOfPrivateModules(repoRoot);

        Assertions.assertFalse(privatePoms.isEmpty(),
                "no project-automation module found under " + repoRoot + " — if it was renamed, this guard "
                        + "is now checking nothing, which is worse than failing. Update PRIVATE_SUFFIX.");

        List<String> problems = new ArrayList<>();
        for (Path pom : privatePoms) {
            String text = Files.readString(pom);
            String where = repoRoot.relativize(pom).toString().replace('\\', '/');
            requireSkip(text, where, "maven-install-plugin", problems);
            requireSkip(text, where, "maven-deploy-plugin", problems);
        }

        Assertions.assertEquals(List.of(), problems,
                "a project-automation module must never reach a repository, local or remote (AGENTS.md "
                        + "§ 1.1). Both publishing goals are switched off with <skip>true</skip>: a bare "
                        + "`mvn install` is the command a newcomer is told to run, and without these it "
                        + "publishes this module's private automation for anyone to resolve.");
    }

    @Test
    void noModuleDependsOnAPrivateModule() throws Exception {
        Path repoRoot = CompileHarness.findRepoRoot();
        List<String> offenders = new ArrayList<>();

        for (Path pom : allPoms(repoRoot)) {
            String text = Files.readString(pom);
            String where = repoRoot.relativize(pom).toString().replace('\\', '/');

            Matcher matcher = DEPENDS_ON_IT.matcher(text);
            while (matcher.find()) {
                // A `<dependencyManagement>` entry is not a dependency: it supplies a version for a
                // dependency somebody else may declare. Maven 3.9 refuses a versionless reactor
                // dependency outright, so this repository must manage `project-automation` even though
                // nothing is allowed to depend on it — and the management block is therefore the one
                // legitimate place its artifactId appears. Distinguished structurally rather than by
                // looking for a version, because both carry one.
                if (insideDependencyManagement(text, matcher.start())) {
                    continue;
                }
                String named = matcher.group(1);
                if (!where.endsWith("/" + named + "/pom.xml") && !named.equals(moduleArtifactId(text))) {
                    offenders.add(where + " depends on " + named);
                }
            }
        }

        Assertions.assertEquals(List.of(), offenders,
                "no other project may depend on a project-automation module (AGENTS.md § 1.1). If a second "
                        + "place needs something that lives in one, the reusable part was never "
                        + "project-specific: promote it to a JCodeBuddy library and let both depend on "
                        + "that. jcodebuddy-codegen-api is the precedent.");
    }

    @Test
    void noPublishingRouteBypassesTheSkip() throws Exception {
        Path repoRoot = CompileHarness.findRepoRoot();
        List<String> problems = new ArrayList<>();

        for (Path pom : allPoms(repoRoot)) {
            String text = Files.readString(pom);
            String where = repoRoot.relativize(pom).toString().replace('\\', '/');
            // Where the module ends up, as opposed to how it is built. Either of these would let a
            // private module reach a repository even with both skip flags honoured elsewhere.
            if (text.contains("<distributionManagement>")) {
                problems.add(where + " declares <distributionManagement>");
            }
            if (text.contains("altDeploymentRepository")) {
                problems.add(where + " sets altDeploymentRepository");
            }
        }

        Assertions.assertEquals(List.of(), problems,
                "a publishing destination is the other way a private module leaves the machine "
                        + "(AGENTS.md § 1.1). The fix is not to point the destination somewhere safer — it "
                        + "is that a project-automation module has no destination at all.");
    }

    /**
     * No POM comment contains {@code --}, which makes the POM unparseable.
     *
     * <p>Not about privacy, but it is the same class of failure and it belongs next to the others: this
     * check was added because the change that introduced the two {@code <skip>} blocks broke the build
     * twice, in `project-automation/pom.xml` and in the business-logic project's, both times by writing a
     * prose dash pair inside an XML comment. Maven's error names the line but not the cause, so the
     * diagnosis cost more than the fix.
     *
     * <p>The rule is that an XML comment may not contain {@code --}, which every one of these comments
     * naturally wants to write. Em dashes or a single hyphen are the fix.
     */
    @Test
    void noPomCommentContainsADoubleDash() throws Exception {
        Path repoRoot = CompileHarness.findRepoRoot();
        List<String> problems = new ArrayList<>();

        for (Path pom : allPoms(repoRoot)) {
            String text = Files.readString(pom);
            String where = repoRoot.relativize(pom).toString().replace('\\', '/');
            Matcher comments = Pattern.compile("(?s)<!--(.*?)-->").matcher(text);
            while (comments.find()) {
                if (comments.group(1).contains("--")) {
                    String excerpt = comments.group(1).replaceAll("\\s+", " ").trim();
                    problems.add(where + " comment contains '--': "
                            + excerpt.substring(0, Math.min(60, excerpt.length())));
                }
            }
        }

        Assertions.assertEquals(List.of(), problems,
                "an XML comment may not contain '--' (XML 1.0 section 2.5), so Maven refuses the whole POM "
                        + "with a message that names the line and not the cause. Use an em dash or a "
                        + "single hyphen.");
    }

    /**
     * The POMs of the private modules, found by artifactId rather than by directory name.
     *
     * <p>By artifactId on purpose: a module may be named anything on disk, and the rule is about what the
     * artifact <i>is</i>. A rename that kept the directory and changed the artifactId, or the reverse,
     * would slip past a path-based search.
     */
    private static List<Path> pomsOfPrivateModules(Path repoRoot) throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path pom : allPoms(repoRoot)) {
            String artifactId = moduleArtifactId(Files.readString(pom));
            if (artifactId != null && artifactId.contains(PRIVATE_SUFFIX)) {
                found.add(pom);
            }
        }
        return found;
    }

    /** This POM's own artifactId — the first one that is not the parent's. */
    private static String moduleArtifactId(String pomText) {
        int parentEnd = pomText.indexOf("</parent>");
        String body = parentEnd >= 0 ? pomText.substring(parentEnd) : pomText;
        Matcher matcher = ARTIFACT_ID.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Whether an offset in the POM text falls inside an open {@code <dependencyManagement>}.
     *
     * <p>A managed entry is not a dependency: it supplies a version for a dependency somebody else may
     * declare. The root POM no longer manages {@code project-automation} at all — a managed entry is an
     * invitation to add the dependency the rule forbids, and the build does not need one, since Maven 3.9
     * only demands a version for a <i>dependency</i> on a reactor module and no module declares one — so
     * this exemption is currently unexercised. It stays because the check must not depend on that
     * remaining true: were the entry to come back, reporting it as a violation would be a false positive,
     * and a guard that cries wolf gets deleted.
     *
     * <p>"Is the last section tag opened before this offset the management block?" would be the cheap
     * version, and it is wrong: this root POM puts {@code <dependencyManagement>} <i>before</i>
     * {@code <dependencies>}, so the last {@code <dependencies>} opened before a managed entry can be the
     * closed sibling further down the file. The first version of this check did exactly that and reported
     * every managed entry in the reactor as a real dependency. The section is tracked as a span instead.
     */
    private static boolean insideDependencyManagement(String pomText, int offset) {
        int opened = pomText.lastIndexOf("<dependencyManagement>", offset);
        if (opened < 0) {
            return false;
        }
        int closed = pomText.lastIndexOf("</dependencyManagement>", offset);
        return opened > closed;
    }

    /**
     * Every {@code pom.xml} in this repository, excluding build output and any nested checkout.
     *
     * <p>{@code target/} holds copies of POMs a build has staged, and {@code .kilo/} holds git worktrees
     * of this same repository — walking either would report the same POM twice, or report a worktree's
     * older revision as this tree's state.
     */
    private static List<Path> allPoms(Path repoRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> "pom.xml".equals(path.getFileName().toString()))
                    .filter(path -> {
                        String relative = repoRoot.relativize(path).toString().replace('\\', '/');
                        return !relative.contains("/target/")
                                && !relative.startsWith("target/")
                                && !relative.startsWith(".kilo/")
                                && !relative.contains("/.kilo/");
                    })
                    .sorted()
                    .toList();
        }
    }

    /** Assert that one plugin's configuration in this POM switches it off. */
    private static void requireSkip(String pomText, String where, String plugin, List<String> problems) {
        Matcher matcher = Pattern.compile(
                        "<artifactId>" + Pattern.quote(plugin) + "</artifactId>((?:(?!</plugin>).)*)",
                        Pattern.DOTALL)
                .matcher(pomText);
        if (!matcher.find()) {
            problems.add(where + " does not configure " + plugin + " at all");
            return;
        }
        if (!matcher.group(1).contains("<skip>true</skip>")) {
            problems.add(where + " configures " + plugin + " without <skip>true</skip>");
        }
    }
}
