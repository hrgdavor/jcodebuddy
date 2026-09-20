package hr.hrg.hipster.entity.tooling;

import java.util.ArrayList;
import java.util.List;

/**
 * The build-time canary for a module whose generation is wired to this tooling (plan-level fix for the
 * stale-artifact trap recorded in {@code hipster-entity-example/codebuddy.md} section 6.1).
 *
 * <h3>The failure it exists for</h3>
 * <p>A module binds {@code EntityMetadataGenerator} to {@code generate-sources} through
 * {@code exec-maven-plugin}. That phase runs <em>before</em> {@code compile}, so a reactor invocation
 * that stops at {@code generate-sources} — and any invocation without {@code -am} — never builds this
 * module's classes, and Maven resolves the dependency to whatever is installed in the local
 * repository. An older artifact does not know {@code --java-out}, treats it and {@code --packages} as
 * positional arguments, and dutifully writes generated Java into positional 2: the module's metadata
 * directory. The build then reports success, the committed sources are never regenerated, and the
 * debris is git-ignored — a silent, plausible, wrong result.</p>
 *
 * <h3>Why a separate class, and not a flag on the generator</h3>
 * <p>A flag cannot work: an old artifact does not know it, so it becomes another positional argument
 * and the old code path runs anyway. A class that <em>does not exist</em> in the old artifact cannot
 * be ignored. Or, to put it the way the project states its other guards: the check has to live in the
 * new revision, because the thing being checked is the absence of the new revision.</p>
 *
 * <p>So the binding runs this class in an execution <em>before</em> the generator. With a current
 * tooling build it prints one identity line and returns; with an outdated one {@code exec:java} fails
 * on the missing class and the build stops before anything is written. That also keeps the remedy in
 * the reader's hands: the failure names the class, and {@code codebuddy.md} names the command.</p>
 *
 * <h3>The second assertion</h3>
 * <p>{@link EntityMetadataGenerator#BINDING_FLAGS} is the flag surface the build passes. Checking it
 * here catches the other half of the same class of bug — a tooling build new enough to have this
 * class but too old to understand a flag the binding now passes. Without that check the flag would
 * degrade silently into a positional argument, which is how the original trap worked.</p>
 */
public final class GeneratorPreflight {

    private GeneratorPreflight() {
    }

    /**
     * @throws IllegalStateException when the tooling on the classpath does not support the flag
     *                               surface a build binding passes
     */
    public static void main(String[] args) {
        List<String> missing = new ArrayList<>();
        for (String flag : EntityMetadataGenerator.BINDING_FLAGS) {
            if (!EntityMetadataGenerator.supportsFlag(flag)) {
                missing.add(flag);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the hipster-entity-tooling on the classpath does not "
                    + "support " + missing + ", which this build binding passes. It is older than the "
                    + "binding expects, so those flags would degrade into positional arguments and "
                    + "generated Java would land in the metadata directory. Refresh the tooling: "
                    + "scripts\\mvn-jdk25.cmd hipster-entity install");
        }
        System.out.println("[jcodebuddy] preflight ok: " + EntityMetadataGenerator.generatorIdentity());
    }
}
