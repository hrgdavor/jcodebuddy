package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.project.Project;
import hr.hrg.jcodebuddy.ipc.ToolDescriptor;
import hr.hrg.jcodebuddy.ipc.ToolEndpoint;
import hr.hrg.jcodebuddy.ipc.ToolException;
import hr.hrg.jcodebuddy.ipc.ToolMethods;
import hr.hrg.jcodebuddy.ipc.ToolProtocol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The project's live tool endpoint (DEC-053): the incremental build's state and actions on the Unix socket
 * {@code <project>/.jcodebuddy/ipc/jcodebuddy-incremental-build.sock}.
 *
 * <p>This class is the adapter and nothing else: the transport, the descriptor and the JSON-RPC framing are
 * {@code jcodebuddy-ipc}, and every command is the same public method the tool window's button calls. A
 * consumer therefore sees exactly what the log page shows and can do exactly what its buttons do, with no
 * second implementation of the build loop.</p>
 *
 * <p>Read methods answer from the same volatile/in-memory state the page renders ({@code build.status},
 * {@code build.log}, {@code build.diagnostics}, {@code build.roots}); commands ({@code build.rebuild},
 * {@code build.watch.stop/start}, {@code build.refreshErrors}, {@code build.refreshRoots},
 * {@code build.listClasspath}) are accepted and run on the service's own worker thread, so a slow compile
 * never blocks the socket.</p>
 */
final class IncrementalToolSocket {

    static final String TOOL = "jcodebuddy-incremental-build";
    static final String TITLE = "JCodeBuddy Incremental Build";

    private static final int DEFAULT_LOG_LIMIT = 200;
    private static final int MAX_LOG_LIMIT = 1_000;

    private final Project project;
    private final IncrementalBuildService service;
    private final IncrementalLogService logs;
    private final IncrementalDiagnosticsService diagnostics;
    private volatile ToolEndpoint endpoint;

    IncrementalToolSocket(Project project, IncrementalBuildService service) {
        this.project = project;
        this.service = service;
        this.logs = project.getService(IncrementalLogService.class);
        this.diagnostics = project.getService(IncrementalDiagnosticsService.class);
    }

    /** Starts the endpoint when nothing is running; a second instance's live socket is reported, not stolen. */
    void start() {
        if (endpoint != null) {
            return;
        }
        String base = project.getBasePath();
        if (base == null) {
            return;
        }
        ToolEndpoint created = new ToolEndpoint(Path.of(base), TOOL, TITLE, ideName(), ideVersion(), methods());
        try {
            ToolDescriptor descriptor = created.start();
            endpoint = created;
            service.log("Tool socket: " + descriptor.socket());
            service.log("Tool socket descriptor: " + ToolProtocol.descriptorFile(Path.of(base), TOOL));
            service.log("Tool socket methods: " + descriptor.capabilities());
        } catch (ToolEndpoint.BusyException busy) {
            created.stop();
            service.log("Tool socket: another instance already serves this project on " + busy.socket()
                    + "; not exposing a second endpoint");
        } catch (IOException | UnsupportedOperationException unavailable) {
            created.stop();
            service.log("Tool socket unavailable: " + unavailable);
        }
    }

    /** Stops the endpoint and leaves no socket or descriptor behind. */
    void stop() {
        ToolEndpoint current = endpoint;
        endpoint = null;
        if (current == null) {
            return;
        }
        current.stop();
        service.log("Tool socket stopped");
    }

    boolean isRunning() {
        ToolEndpoint current = endpoint;
        return current != null && current.isRunning();
    }

    private ToolMethods methods() {
        return new ToolMethods() {
            @Override
            public Object call(String method, Map<String, Object> params) throws ToolException {
                return switch (method) {
                    case "build.status" -> status();
                    case "build.log" -> logPage(params);
                    case "build.diagnostics" -> diagnostics();
                    case "build.roots" -> roots();
                    case "build.rebuild" -> command(service::rebuild);
                    case "build.retryFailed" -> command(service::retryFailed);
                    case "build.listFailed" -> command(service::listFailed);
                    case "build.watch.stop" -> command(service::pauseWatch);
                    case "build.watch.start" -> command(service::resumeWatch);
                    case "build.refreshErrors" -> command(service::refreshErrors);
                    case "build.refreshRoots" -> command(service::refreshRoots);
                    case "build.listClasspath" -> command(service::logDetailedClasspath);
                    default -> throw ToolException.methodNotFound(method);
                };
            }

            @Override
            public List<String> capabilities() {
                return List.of("build.status", "build.log", "build.diagnostics", "build.roots", "build.rebuild",
                        "build.retryFailed", "build.listFailed",
                        "build.watch.stop", "build.watch.start", "build.refreshErrors", "build.refreshRoots",
                        "build.listClasspath");
            }

            @Override
            public List<String> topics() {
                return List.of("log");
            }

            @Override
            public AutoCloseable subscribe(String topic, Long since, java.util.function.Consumer<Map<String, Object>> sink)
                    throws ToolException {
                if (!"log".equals(topic)) {
                    throw ToolException.invalidParams("no topic '" + topic + "'; this tool streams log");
                }
                long from = since == null ? logs.lastSequence() : since;
                return logs.subscribe(from, line -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("sequence", line.sequence());
                    event.put("text", line.text());
                    sink.accept(event);
                });
            }
        };
    }

    private Object command(Runnable action) {
        action.run();
        return Map.of("accepted", true);
    }

    private Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("tool", TOOL);
        status.put("protocol", ToolProtocol.VERSION);
        status.put("project", forward(project.getBasePath()));
        status.put("enabled", service.isEnabledForProject());
        status.put("running", service.isRunning());
        status.put("watch", service.watchState());
        status.put("batchMarker", service.appliedBatch());
        Map<String, Object> daemon = new LinkedHashMap<>();
        daemon.put("root", forward(service.daemonRootPath()));
        daemon.put("socket", forward(service.daemonSocketPath()));
        status.put("daemon", daemon);
        status.put("failedFiles", service.failedFileCount());
        List<Map<String, Object>> roots = roots();
        status.put("roots", roots);

        List<Diagnostic> all = diagnostics.snapshot();
        Set<Path> files = new java.util.HashSet<>();
        for (Diagnostic diagnostic : all) {
            files.add(diagnostic.file());
        }
        Map<String, Object> problemCounts = new LinkedHashMap<>();
        problemCounts.put("files", files.size());
        problemCounts.put("entries", all.size());
        status.put("diagnostics", problemCounts);

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("file", forward(logs.file().toString()));
        log.put("size", logs.size());
        log.put("last", logs.lastSequence());
        status.put("log", log);
        return status;
    }

    private Map<String, Object> logPage(Map<String, Object> params) throws ToolException {
        int limit = intParam(params, "limit", DEFAULT_LOG_LIMIT);
        limit = Math.max(1, Math.min(MAX_LOG_LIMIT, limit));
        Long since = longParam(params, "since");
        List<IncrementalLogService.LogLine> page = since == null
                ? logs.tail(limit)
                : logs.after(since, limit);
        List<Map<String, Object>> lines = new ArrayList<>(page.size());
        for (IncrementalLogService.LogLine line : page) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sequence", line.sequence());
            entry.put("text", line.text());
            lines.add(entry);
        }
        long next = since == null
                ? logs.lastSequence()
                : since;
        if (!page.isEmpty()) {
            next = page.get(page.size() - 1).sequence();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", lines);
        result.put("next", next);
        return result;
    }

    private List<Map<String, Object>> diagnostics() {
        List<Diagnostic> all = new ArrayList<>(diagnostics.snapshot());
        all.sort(java.util.Comparator.comparing((Diagnostic diagnostic) -> diagnostic.file().toString())
                .thenComparingInt(Diagnostic::line));
        List<Map<String, Object>> entries = new ArrayList<>(all.size());
        for (Diagnostic diagnostic : all) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", forward(diagnostic.file().toString()));
            entry.put("line", diagnostic.line());
            entry.put("column", diagnostic.column());
            entry.put("message", diagnostic.message());
            entries.add(entry);
        }
        return entries;
    }

    private List<Map<String, Object>> roots() {
        List<Map<String, Object>> roots = new ArrayList<>();
        for (IncrementalBuildService.SourceRootInfo root : service.sourceRoots()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("module", root.module());
            entry.put("root", root.root());
            entry.put("output", root.output());
            entry.put("test", root.test());
            roots.add(entry);
        }
        return roots;
    }

    private static int intParam(Map<String, Object> params, String name, int fallback) {
        Long value = longParam(params, name);
        if (value == null) {
            return fallback;
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static Long longParam(Map<String, Object> params, String name) {
        Object value = params == null ? null : params.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private static String forward(Path path) {
        return path == null ? "" : forward(path.toString());
    }

    private static String forward(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String ideName() {
        try {
            return ApplicationInfo.getInstance().getFullApplicationName();
        } catch (RuntimeException unavailable) {
            return "IntelliJ IDEA";
        }
    }

    private static String ideVersion() {
        try {
            return ApplicationInfo.getInstance().getBuild().asString();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }
}
