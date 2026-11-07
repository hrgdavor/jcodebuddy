package bench;

import hr.hrg.jcodebuddy.compile.EcjIncrementalCompiler;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot fs-meta batch → ECJ incremental compile measurement.
 *
 * <p>Arguments: {@code <project-root> <source-root> <bin-dir> <result-file> <ready-file>
 * <edit-start-file> [timeout-ms]}. The runner starts an fs-meta daemon subscription at
 * {@code LATEST}, compiles the source tree metadata once ({@link EcjIncrementalCompiler#index()}),
 * writes the ready file, waits for one batch that touches a {@code .java} file, compiles exactly
 * those files, and writes a JSON result. It never starts a watcher of its own: the daemon owns
 * the batch boundary.</p>
 */
public final class CompileBatchRunner {

    private CompileBatchRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: CompileBatchRunner <project-root> <source-root> <bin-dir> "
                    + "<result-file> <ready-file> <edit-start-file> [timeout-ms]");
            System.exit(2);
        }
        Path projectRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path sourceRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Path binDir = Path.of(args[2]).toAbsolutePath().normalize();
        Path resultFile = Path.of(args[3]).toAbsolutePath().normalize();
        Path readyFile = Path.of(args[4]).toAbsolutePath().normalize();
        Path startFile = Path.of(args[5]).toAbsolutePath().normalize();
        long timeoutMs = args.length > 6 ? Long.parseLong(args[6]) : 120_000L;

        FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discoverAncestor(sourceRoot);
        if (attach == null || !attach.attached()) {
            System.err.println("no fs-meta daemon serves " + sourceRoot + ": "
                    + (attach == null ? "no discovery result" : attach.problem()));
            System.exit(3);
        }
        FsMetaDaemonDiscovery.Location location = attach.location();
        String prefix = prefix(location.root(), sourceRoot);
        List<String> includes = prefix.isEmpty()
                ? List.of("**/*.java")
                : List.of(prefix + "/**/*.java");
        PathFilter filter = PathFilter.of(includes, List.of("**/.jcodebuddy/**"));

        String classpath = EcjIncrementalCompiler.buildClasspath(binDir,
                List.of(System.getProperty("java.class.path", "")));
        EcjIncrementalCompiler compiler = new EcjIncrementalCompiler(sourceRoot, binDir, classpath);
        compiler.index();
        long indexNanos = compiler.lastIndexNanos();
        // Warm ECJ's JIT and the source/name-environment paths before the measured edit. This is
        // outside the measured interval, exactly as Eclipse's initial full build is outside it.
        Path warmupDir = sourceRoot.resolve("bench");
        Files.createDirectories(warmupDir);
        Path warmup = warmupDir.resolve("Warmup.java");
        if (!Files.exists(warmup)) {
            Files.writeString(warmup,
                    "package bench;\nfinal class Warmup { static int value() { return 0; } }\n",
                    StandardCharsets.UTF_8);
        }
        compiler.compile(Set.of(warmup));

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch compiled = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<Result> result = new AtomicReference<>();

        FsMetaDaemonWatch watch = new FsMetaDaemonWatch(filter, location.socket(),
                hr.hrg.jcodebuddy.watch.FsMetaClient.LATEST, batch -> {
                    if (batch.isEmpty() || compiled.getCount() == 0) {
                        return;
                    }
                    try {
                        Result value = compile(batch, projectRoot, sourceRoot, startFile, compiler, indexNanos);
                        result.set(value);
                    } catch (Exception error) {
                        failure.set(error.getClass().getSimpleName() + ": " + error.getMessage());
                    } finally {
                        compiled.countDown();
                    }
                });
        try {
            watch.start();
            Files.writeString(readyFile, "ready\n", StandardCharsets.UTF_8);
            ready.countDown();
            if (!compiled.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                failure.set("no fs-meta batch within " + timeoutMs + " ms");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure.set("interrupted");
        } finally {
            watch.stop();
        }

        Result value = result.get();
        if (value == null) {
            value = new Result(projectRoot, sourceRoot, binDir, location.root(), location.socket(),
                    location.id(), indexNanos, 0, 0, 0, List.of(), failure.get());
        }
        Files.writeString(resultFile, value.json(), StandardCharsets.UTF_8);
        System.out.println(value.json());
        if (!value.ok()) {
            System.exit(1);
        }
    }

    private static Result compile(WatchBatch batch, Path projectRoot, Path sourceRoot, Path startFile,
                                  EcjIncrementalCompiler compiler, long indexNanos) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        List<String> changed = new ArrayList<>(batch.changed());
        changed.addAll(batch.deleted());
        for (String name : changed) {
            if (!name.endsWith(".java")) {
                continue;
            }
            Path file = projectRoot.resolve(name.replace('/', java.io.File.separatorChar)).normalize();
            if (file.startsWith(sourceRoot) && Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        long started = System.nanoTime();
        boolean ok = compiler.compile(files);
        long compileNanos = compiler.lastCompileNanos();
        long elapsedMs = elapsedSince(startFile);
        return new Result(null, sourceRoot, null, null, null, null, indexNanos, compileNanos,
                elapsedMs, files.size(), compiler.lastErrors(), ok ? null : "compile failed");
    }

    private static long elapsedSince(Path startFile) {
        try {
            String text = Files.readString(startFile, StandardCharsets.UTF_8).trim();
            long start = Long.parseLong(text);
            return Math.max(0, System.currentTimeMillis() - start);
        } catch (Exception missing) {
            return -1;
        }
    }

    private static String prefix(Path daemonRoot, Path sourceRoot) {
        try {
            return daemonRoot.relativize(sourceRoot).toString().replace('\\', '/');
        } catch (IllegalArgumentException outside) {
            return "";
        }
    }

    private record Result(Path projectRoot, Path sourceRoot, Path binDir, Path daemonRoot,
                          Path socket, String daemonId, long indexNanos, long compileNanos,
                          long elapsedMs, int changedFiles, List<String> errors, String failure) {

        boolean ok() {
            return failure == null;
        }

        String json() {
            StringBuilder out = new StringBuilder("{\"schema\":\"fsmeta-auto-build/1\",\"ok\":")
                    .append(ok()).append(",\"indexNanos\":").append(indexNanos)
                    .append(",\"compileNanos\":").append(compileNanos)
                    .append(",\"elapsedMs\":").append(elapsedMs)
                    .append(",\"changedFiles\":").append(changedFiles)
                    .append(",\"daemonId\":\"").append(escape(daemonId)).append('"')
                    .append(",\"socket\":\"").append(escape(path(socket))).append('"')
                    .append(",\"sourceRoot\":\"").append(escape(path(sourceRoot))).append('"')
                    .append(",\"errors\":[");
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append('"').append(escape(errors.get(i))).append('"');
            }
            out.append(']');
            if (failure != null) {
                out.append(",\"failure\":\"").append(escape(failure)).append('"');
            }
            return out.append('}').toString();
        }

        private static String path(Path value) {
            return value == null ? "" : value.toString().replace('\\', '/');
        }

        private static String escape(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
