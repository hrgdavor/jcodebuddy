package hr.hrg.hipster.ioc.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The command line the generator is run from: {@code IocTool --root <dir> [--indent <string>] [--quiet]}.
 *
 * <p>The entry point is this class, and {@code bun scripts/ioc-gen.js} is the documented way to reach it —
 * the script pins JDK 25 and exports the classpath, because none of that belongs in a Java main. The split
 * is the repository's own rule for scripts (AGENTS.md § 2): the build step is Maven, the thing an agent
 * runs is Bun JavaScript, and neither knows the other's toolchain.</p>
 *
 * <h3>Exit code</h3>
 *
 * <p>Non-zero when any context was <strong>refused</strong>. A refusal is a context the generator declined
 * to generate — a cycle, a lazy bean without a factory, a named implementation — and each one is a real
 * finding a build should not walk past. Everything else (a report-only divergence from the cooperative
 * writer) prints and exits 0, because those describe an edit the generator already handled.</p>
 */
public final class IocTool {

    private IocTool() {
    }

    public static void main(String[] args) throws IOException {
        Path root = null;
        String indent = "    ";
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(require(args, ++i, "--root"));
                case "--indent" -> indent = require(args, ++i, "--indent");
                case "--quiet" -> quiet = true;
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }
        if (root == null) {
            root = Path.of(".");
        }
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root.toAbsolutePath());
            System.exit(2);
        }

        IocGeneration.Result result = IocGeneration.generate(root, moduleRootOf(root), indent);

        if (!quiet) {
            System.out.println("contexts read     : " + result.contextsRead());
            System.out.println("implementations   : " + result.filesWritten() + " written");
            System.out.println("refused           : " + result.refused());
            System.out.println("dependency graph  : " + result.graphFile());
        }
        for (String divergence : result.divergences()) {
            System.out.println(divergence);
        }
        System.exit(result.refused() > 0 ? 1 : 0);
    }

    /**
     * The module root a graph belongs to: the source root's module, found by walking up to the directory
     * holding {@code pom.xml}. Falling back to the source root keeps a hand-run over a scratch tree working
     * — the graph then lands inside it, which is what a tree with no module deserves.
     */
    private static Path moduleRootOf(Path sourceRoot) {
        Path candidate = sourceRoot;
        while (candidate != null && !Files.exists(candidate.resolve("pom.xml"))) {
            candidate = candidate.getParent();
        }
        return candidate == null ? sourceRoot : candidate;
    }

    private static String require(String[] args, int index, String flag) {
        if (index >= args.length) {
            System.err.println(flag + " needs a value");
            System.exit(2);
        }
        return args[index];
    }

    private static void usage() {
        List<String> lines = new ArrayList<>(List.of(
                "IocTool — generate hipster-ioc context implementations (DEC-036)",
                "",
                "  --root <dir>      the source root to read and write (default: .)",
                "  --indent <text>   one indentation step for generated code (default: four spaces)",
                "  --quiet           print only the divergences",
                "  --help            this text",
                "",
                "Prefer `bun scripts/ioc-gen.js`, which pins JDK 25 and builds the classpath."));
        System.out.println(String.join(System.lineSeparator(), lines));
    }
}
