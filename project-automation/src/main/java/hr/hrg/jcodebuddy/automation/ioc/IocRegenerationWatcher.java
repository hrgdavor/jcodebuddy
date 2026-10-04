package hr.hrg.jcodebuddy.automation.ioc;

import hr.hrg.jcodebuddy.automation.watch.WatchedRegeneration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Regenerates the hipster-ioc contexts when a source changes — the watch half of DEC-036 § 11 and the second half of
 * plan step 3.9.
 *
 * <p><strong>What this is:</strong> the pass ({@link IocRegeneration}) run on every save, so editing a context
 * interface rewrites its implementation without a build. <strong>What it is not:</strong> a second copy of the
 * watcher. The mechanism that makes this safe — the debounced batch watcher and the content-hash check that stops a
 * generator reacting to its own output — is {@link WatchedRegeneration}, shared with
 * {@code EntityRegenerationWatcher}, which is the same loop for the entity generator. Two copies of a subtle loop
 * breaker is how they drift apart; the plan's step 3.9 asks for reuse, and step 3.0n is the precedent.</p>
 *
 * <p><strong>Why the pass may simply be run on every save.</strong> The plan named the hazard — "a watch-mode pass
 * regenerates on save and would otherwise rewrite a prototype's output repeatedly" — and the answer is that
 * generation is idempotent: {@code IocRegenerationTest} shows a second pass over an unchanged tree writing nothing.
 * So this class carries no policy about when regeneration is appropriate; it decides only when the tree's
 * <em>content</em> changed, and leaves the rest to the generator.</p>
 */
public final class IocRegenerationWatcher implements AutoCloseable {

    /** The glob patterns that make a file worth reacting to, and the ones that must never re-enter the loop. */
    private static final List<String> INCLUDES = List.of("**/*.java");
    private static final List<String> EXCLUDES = List.of("**/target/**", "**/tmp/**", "**/.kilo/**", "**/.jcodebuddy/**");

    /** What to regenerate, and where. */
    public record Config(Path sourceRoot, Path moduleRoot, long debounceMs) {

        public Config {
            if (sourceRoot == null) {
                throw new IllegalArgumentException("a source root is required");
            }
            sourceRoot = sourceRoot.toAbsolutePath().normalize();
            moduleRoot = moduleRoot == null ? sourceRoot : moduleRoot.toAbsolutePath().normalize();
            if (debounceMs <= 0) {
                debounceMs = 300;
            }
        }

        /** The common case: the module is the root's own module, with the default debounce. */
        public static Config of(Path sourceRoot, Path moduleRoot) {
            return new Config(sourceRoot, moduleRoot, 300);
        }
    }

    private final Config config;
    private final IocRegeneration pass;
    private final WatchedRegeneration watch;

    public IocRegenerationWatcher(Config config) {
        this(config, new IocRegeneration(), pass -> { });
    }

    /**
     * @param config   what to regenerate
     * @param pass     the pass to run; injectable so a test can watch without a generator, and so a project can
     *                 compose its own generator list
     * @param observer called after every decision, including the ignored ones
     */
    public IocRegenerationWatcher(Config config, IocRegeneration pass,
                                  java.util.function.Consumer<WatchedRegeneration.Pass> observer) {
        this.config = config;
        this.pass = pass == null ? new IocRegeneration() : pass;
        this.watch = new WatchedRegeneration(config.sourceRoot(), INCLUDES, EXCLUDES, config.debounceMs(),
                this::regenerate, observer);
    }

    private List<String> regenerate() {
        try {
            IocRegeneration.Pass pass = this.pass.run(config.sourceRoot(), config.moduleRoot());
            return pass.divergences();
        } catch (IOException | RuntimeException failure) {
            // A watcher that dies on the first malformed edit is worse than one that says what is wrong and keeps
            // watching, so a failed pass is a reported divergence rather than a thrown exception.
            return List.of("kind=watcher_pass_failed, location=" + config.sourceRoot()
                    + ", cause=the generation pass threw instead of reporting"
                    + ", current=" + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    + ", canonical=a completed pass"
                    + ", action=fix the source that broke the pass; the watcher is still running");
        }
    }

    public void start() throws IOException {
        watch.start();
    }

    public void stop() {
        watch.stop();
    }

    @Override
    public void close() {
        watch.stop();
    }

    public long batchCount() {
        return watch.batchCount();
    }

    public long passCount() {
        return watch.passCount();
    }

    public Config config() {
        return config;
    }

    /** One batch, decided and acted on. The seam a test drives without a filesystem watcher. */
    public WatchedRegeneration.Pass onBatch(hr.hrg.watch2.core.ChangeSet changeSet) {
        return watch.onBatch(changeSet);
    }

    /**
     * The entry point, with the same two roots the pass takes.
     *
     * <pre>
     *   --source &lt;dir&gt;       the source root to watch and regenerate (required)
     *   --module &lt;dir&gt;       the module whose .jcodebuddy/ receives the graph (default: the source root)
     *   --debounce &lt;ms&gt;      quiet window before a batch is delivered (default 300)
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        Path source = null;
        Path module = null;
        long debounce = 300;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--source" -> source = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--module" -> module = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--debounce" -> debounce = i + 1 < args.length ? Long.parseLong(args[++i]) : debounce;
                default -> System.err.println("[ioc-watch] unknown argument: " + args[i]);
            }
        }
        if (source == null) {
            System.err.println("Usage: ioc-watch --source <dir> [--module <dir>] [--debounce <ms>]");
            System.exit(2);
            return;
        }

        List<String> lines = new ArrayList<>();
        IocRegenerationWatcher watcher = new IocRegenerationWatcher(
                new Config(source, module, debounce),
                new IocRegeneration(),
                pass -> {
                    if (pass.regenerated()) {
                        System.out.println("[ioc-watch] pass " + pass.sequence() + ": regenerated for "
                                + pass.triggeringFiles());
                        pass.divergences().forEach(line -> System.out.println("  " + line));
                    } else {
                        System.out.println("[ioc-watch] pass " + pass.sequence()
                                + ": no content change, nothing to do");
                    }
                });
        watcher.start();
        System.out.println("[ioc-watch] watching " + watcher.config().sourceRoot()
                + "; press Ctrl+C to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(watcher::stop));
        Thread.currentThread().join();
    }
}
