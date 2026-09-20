package hr.hrg.hipster.entity.tooling;

import java.util.ArrayList;
import java.util.List;

/**
 * The stand-alone check that the tooling about to run is the one the invocation expects.
 *
 * <h3>What it is for</h3>
 * <p>JCodeBuddy is a side-car: no part of it is bound to a build lifecycle. A pass is started by a
 * person (or a script) that names a classpath — {@code scripts/gen.cmd} exports the reactor's
 * classpath, the module POM's explicit {@code exec:java} goals take theirs from Maven, and a manual
 * {@code java -cp ...} run takes whichever the operator built. Whenever a classpath is assembled by
 * hand, it can silently point at a tooling build that is older than the invocation.</p>
 *
 * <p>That matters because an older tooling does not <em>reject</em> a flag it does not know: it
 * treats {@code --java-out} and {@code --packages} as positional arguments and dutifully writes
 * generated Java into positional 2 — the module's metadata directory. The run then reports success,
 * the committed sources are never regenerated, and the debris lands in a git-ignored directory. A
 * silent, plausible, wrong result is the worst failure mode this project has, and it is the one
 * recorded in {@code hipster-entity-example/codebuddy.md} § 6.1.</p>
 *
 * <h3>Why a class, and not a flag on the generator</h3>
 * <p>A flag cannot work: an old tooling does not know it, so it becomes another positional argument
 * and the old code path runs anyway. A class that <em>does not exist</em> in the old build cannot be
 * ignored — the run fails on the missing class before anything is written. The check has to live in
 * the new revision, because the thing being checked is the absence of the new revision.</p>
 *
 * <p>So {@code scripts/gen.cmd} runs this class first, and the module POM exposes it as an explicit
 * {@code exec:java} goal. With a current tooling it prints one identity line and returns; with an
 * outdated one the run stops before the first write. The failure names the class, and
 * {@code codebuddy.md} § 3 names the remedy.</p>
 *
 * <h3>The second assertion</h3>
 * <p>{@link EntityMetadataGenerator#EXECUTION_FLAGS} is the flag surface every documented
 * invocation passes. Checking it here catches the other half of the same class of bug — a tooling
 * build new enough to have this class but too old to understand a flag the invocation now passes.
 * Without that check the flag would degrade silently into a positional argument, which is how the
 * original trap worked.</p>
 */
public final class GeneratorPreflight {

    private GeneratorPreflight() {
    }

    /**
     * @throws IllegalStateException when the tooling on the classpath does not support the flag
     *                               surface a documented invocation passes
     */
    public static void main(String[] args) {
        List<String> missing = new ArrayList<>();
        for (String flag : EntityMetadataGenerator.EXECUTION_FLAGS) {
            if (!EntityMetadataGenerator.supportsFlag(flag)) {
                missing.add(flag);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the hipster-entity-tooling on the classpath does not "
                    + "support " + missing + ", which a generation pass passes. It is older than this "
                    + "invocation expects, so those flags would degrade into positional arguments and "
                    + "generated Java would land in the metadata directory. Rebuild the tooling in the "
                    + "same reactor: scripts\\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am compile, "
                    + "or run scripts\\gen.cmd, which compiles and exports the classpath for you.");
        }
        System.out.println("[jcodebuddy] preflight ok: " + EntityMetadataGenerator.generatorIdentity());
    }
}
