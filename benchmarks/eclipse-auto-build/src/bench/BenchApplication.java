/*
 * Eclipse auto-build benchmark fixture.
 *
 * A headless Eclipse application (application id "bench.app.app") that builds a synthetic Java
 * project and measures exactly one JDT auto-build cycle:
 *
 *   1. a fresh project "bench" (Java nature + org.eclipse.jdt.core.javabuilder) whose .classpath
 *      holds source "src", output "bin" and the JavaSE-21 JRE container;
 *   2. N independent classes src/bench/C0.java ... C<N-1>.java, N from -Dbench.classes;
 *   3. workspace auto-build switched on, then one initial FULL_BUILD which is NOT measured;
 *   4. the ready marker (-Dbench.ready) written once that initial build has finished;
 *   5. one prepared edit applied to C0.java through IFile.setContents, a System.nanoTime()
 *      taken immediately afterwards, and the wait for the POST_BUILD resource-change event that
 *      this edit caused;
 *   6. the result JSON (-Dbench.result) written, then a clean exit.
 *
 * The fixture is driven by run.js, which compiles this class with a JDK 25 javac at
 * --release 21, jars it together with its manifest and plugin.xml, installs it into the Eclipse
 * installation for the run and removes it again afterwards. run.js owns everything outside this
 * class; this class knows only the Eclipse APIs.
 *
 * editNanos and buildNanos are System.nanoTime() values and a difference of them, so they are
 * nanoseconds from an arbitrary origin. They compare only against each other, never to wall time.
 */

package bench;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jdt.core.JavaCore;

/** The single entry point of the bench.app bundle; see the file comment for the contract. */
public final class BenchApplication implements IApplication {

    /** Application id declared in plugin.xml; also the driver's -application argument. */
    public static final String APPLICATION_ID = "bench.app.app";

    private static final String PROJECT_NAME = "bench";
    private static final String SOURCE_FOLDER = "src";
    private static final String SOURCE_PACKAGE = "bench";
    private static final String OUTPUT_FOLDER = "bin";

    /** The one file the prepared edit is applied to, and the class file it must produce. */
    private static final String EDIT_SOURCE = "C0.java";
    private static final String EDIT_CLASS = "C0.class";
    private static final String EDIT_SOURCE_TEXT = "private static final int VALUE = 0;";
    private static final String EDIT_RESULT_TEXT = "private static final int VALUE = 7;";

    private static final String CLASSPATH =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<classpath>\n"
            + "  <classpathentry kind=\"src\" path=\"" + SOURCE_FOLDER + "\"/>\n"
            + "  <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER"
            + "/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21\"/>\n"
            + "  <classpathentry kind=\"output\" path=\"" + OUTPUT_FOLDER + "\"/>\n"
            + "</classpath>\n";

    private static final String JDT_PREFS =
            "eclipse.preferences.version=1\n"
            + "org.eclipse.jdt.core.compiler.compliance=21\n"
            + "org.eclipse.jdt.core.compiler.source=21\n"
            + "org.eclipse.jdt.core.compiler.codegen.targetPlatform=21\n";

    /**
     * Entry point: reads the configuration, runs the measurement, and writes a result even when
     * the measurement fails.
     */
    @Override
    public Object start(IApplicationContext context) {
        final long appStartNanos = System.nanoTime();
        final Config config = Config.load();
        log("start classes=" + config.classes()
                + " timeoutMillis=" + config.timeoutMillis()
                + " result=" + config.resultFile()
                + " ready=" + config.readyFile());
        try {
            return execute(config, appStartNanos);
        } catch (Throwable failure) {
            log("FAILED " + failure);
            failure.printStackTrace(System.out);
            writeResult(config, Result.failed(config, failure));
            return Integer.valueOf(1);
        }
    }

    @Override
    public void stop() {
        // Nothing to clean up here: the workspace is the measurement and the bundle belongs to
        // the driver, which installs it before the run and removes it afterwards.
    }

    /**
     * Builds the fixture, warms it up with a full build, then measures one auto-build cycle.
     *
     * @return {@link IApplication#EXIT_OK}, or an exit code of 1 when the measured build never
     *     reported (the result JSON then records {@code timedOut} instead of a number)
     */
    private Object execute(Config config, long appStartNanos) throws Exception {
        final Deadline deadline =
                new Deadline(appStartNanos + TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis()));
        final IWorkspace workspace = ResourcesPlugin.getWorkspace();

        // No build while the fixture is materialised: the first build is deliberately explicit,
        // so that the edit below is the only change the measured build can be responding to.
        setAutoBuilding(workspace, false);

        final IProject project = createProject(workspace, new NullProgressMonitor());
        createSources(project, config.classes(), new NullProgressMonitor());

        setAutoBuilding(workspace, true);

        // The initial FULL_BUILD is the warm-up. It is never part of the measurement.
        final long initialStartNanos = System.nanoTime();
        project.build(IncrementalProjectBuilder.FULL_BUILD, deadline.monitor());
        final long initialBuildNanos = System.nanoTime() - initialStartNanos;
        awaitQuiescence(deadline);

        final int initialErrors = countErrors(project);
        final int initialClassFiles = countClassFiles(project);
        log("initial build done in " + millis(initialBuildNanos) + " ms"
                + " errors=" + initialErrors + " classFiles=" + initialClassFiles);

        // Ready means the fixture exists, its initial build is complete and nothing is running.
        final long readyNanos = System.nanoTime();
        writeText(config.readyFile(), "ready\n");

        final IFile source =
                project.getFile(SOURCE_FOLDER + "/" + SOURCE_PACKAGE + "/" + EDIT_SOURCE);
        final IFile classFile =
                project.getFile(OUTPUT_FOLDER + "/" + SOURCE_PACKAGE + "/" + EDIT_CLASS);
        final long classFileStampBefore = classFile.getModificationStamp();

        final byte[] preparedEdit = prepareEdit(config.classes());
        final PostBuildProbe probe = new PostBuildProbe();
        long editNanos = -1L;
        long setContentsNanos = -1L;
        long buildNanos = -1L;
        long queueNanos = -1L;
        long builderNanos = -1L;
        int buildKind = -1;
        boolean sourceChanged = false;
        boolean classFileChanged = false;
        boolean classFileStampChanged = false;
        boolean timedOut = false;

        // PRE_BUILD opens the window the builder itself occupies; POST_BUILD closes the one the
        // whole auto-build cycle occupies.
        workspace.addResourceChangeListener(probe,
                IResourceChangeEvent.PRE_BUILD | IResourceChangeEvent.POST_BUILD);
        try {
            probe.arm();
            final long setContentsStartNanos = System.nanoTime();
            source.setContents(new ByteArrayInputStream(preparedEdit), IFile.FORCE,
                    new NullProgressMonitor());
            editNanos = System.nanoTime();
            setContentsNanos = editNanos - setContentsStartNanos;

            if (probe.await(deadline.remainingMillis())) {
                buildNanos = probe.eventNanos() - editNanos;
                final long preBuildNanos = probe.preBuildNanos();
                if (preBuildNanos > 0L) {
                    queueNanos = preBuildNanos - editNanos;
                    builderNanos = probe.eventNanos() - preBuildNanos;
                }
                buildKind = probe.buildKind();
                sourceChanged = probe.sourceChanged();
                classFileChanged = probe.classFileChanged();
                classFileStampChanged = classFile.getModificationStamp() != classFileStampBefore;
                log("edit -> post-build in " + millis(buildNanos) + " ms"
                        + " (queued " + millis(queueNanos) + " ms, builder "
                        + millis(builderNanos) + " ms, setContents " + millis(setContentsNanos)
                        + " ms) classFileChanged=" + classFileChanged
                        + " stampChanged=" + classFileStampChanged
                        + " buildKind=" + buildKind);
            } else {
                timedOut = true;
                log("TIMEOUT waiting for the post-build event of project " + PROJECT_NAME);
            }
        } finally {
            workspace.removeResourceChangeListener(probe);
        }

        final Result result = new Result(config, editNanos, buildNanos, initialBuildNanos,
                readyNanos - appStartNanos, sourceChanged, classFileChanged,
                classFileStampChanged, timedOut, buildKind, queueNanos, builderNanos,
                initialErrors, initialClassFiles, null);
        writeResult(config, result);
        log("done classes=" + config.classes()
                + " buildNanos=" + buildNanos
                + " classFileChanged=" + classFileChanged
                + " ok=" + (result.ok()));
        if (timedOut) {
            return Integer.valueOf(1);
        }
        // Outside the measurement window: leaves the workspace on disk consistent, so a run that
        // is kept for inspection does not end on "unsaved changes".
        try {
            workspace.save(true, new NullProgressMonitor());
        } catch (CoreException unsaved) {
            log("could not save the workspace: " + unsaved.getMessage());
        }
        return IApplication.EXIT_OK;
    }

    /**
     * The single prepared edit, computed before the measurement starts: C0's initializer becomes 7.
     * The replacement must occur exactly once, so a drift in the generated source fails loudly
     * instead of silently measuring a file that never changed.
     */
    private static byte[] prepareEdit(int classes) {
        if (classes < 1) {
            throw new IllegalArgumentException("bench.classes must be >= 1 to have a C0 to edit");
        }
        final String original = classSource(0);
        final String edited = original.replace(EDIT_SOURCE_TEXT, EDIT_RESULT_TEXT);
        if (edited.equals(original) || edited.contains(EDIT_SOURCE_TEXT)) {
            throw new IllegalStateException(
                    "the prepared edit did not apply exactly once to " + EDIT_SOURCE);
        }
        return edited.getBytes(StandardCharsets.UTF_8);
    }

    private static IProject createProject(IWorkspace workspace, IProgressMonitor monitor)
            throws CoreException {
        final IProject project = workspace.getRoot().getProject(PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        final IProjectDescription description = workspace.newProjectDescription(PROJECT_NAME);
        description.setNatureIds(new String[]{JavaCore.NATURE_ID});
        final ICommand command = description.newCommand();
        command.setBuilderName(JavaCore.BUILDER_ID);
        description.setBuildSpec(new ICommand[]{command});
        project.create(description, monitor);
        project.open(monitor);

        // Written after the Java nature has been configured, so these entries are the project's
        // own rather than the defaults the nature set up.
        writeFile(project.getFile(".classpath"), CLASSPATH);
        final IFolder settings = project.getFolder(".settings");
        if (!settings.exists()) {
            settings.create(false, true, monitor);
        }
        writeFile(project.getFile(".settings/org.eclipse.jdt.core.prefs"), JDT_PREFS);
        return project;
    }

    private static void createSources(IProject project, int classes, IProgressMonitor monitor)
            throws CoreException {
        final IFolder sourceFolder = project.getFolder(SOURCE_FOLDER);
        if (!sourceFolder.exists()) {
            sourceFolder.create(false, true, monitor);
        }
        final IFolder packageFolder = project.getFolder(SOURCE_FOLDER + "/" + SOURCE_PACKAGE);
        if (!packageFolder.exists()) {
            packageFolder.create(false, true, monitor);
        }
        for (int index = 0; index < classes; index++) {
            final IFile file = packageFolder.getFile("C" + index + ".java");
            file.create(new ByteArrayInputStream(
                    classSource(index).getBytes(StandardCharsets.UTF_8)), true, monitor);
        }
    }

    /** N independent classes: no type here refers to another, so an edit recompiles one file. */
    private static String classSource(int index) {
        return "package " + SOURCE_PACKAGE + ";\n"
                + "\n"
                + "public final class C" + index + " {\n"
                + "\n"
                + "    private static final int VALUE = " + index + ";\n"
                + "\n"
                + "    private C" + index + "() {\n"
                + "    }\n"
                + "\n"
                + "    public static int value() {\n"
                + "        return VALUE;\n"
                + "    }\n"
                + "}\n";
    }

    private static void writeFile(IFile file, String content) throws CoreException {
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (file.exists()) {
            file.setContents(new ByteArrayInputStream(bytes), IFile.FORCE,
                    new NullProgressMonitor());
        } else {
            file.create(new ByteArrayInputStream(bytes), true, new NullProgressMonitor());
        }
    }

    /** Applies a change to the auto-build flag, doing nothing when it already has that value. */
    private static void setAutoBuilding(IWorkspace workspace, boolean enabled)
            throws CoreException {
        final IWorkspaceDescription description = workspace.getDescription();
        if (description.isAutoBuilding() == enabled) {
            return;
        }
        description.setAutoBuilding(enabled);
        workspace.setDescription(description);
        log("auto-build " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Waits until no manual or automatic build is running. Switching auto-build on posts a
     * workspace-wide build request of its own, and that build must be over before the measured
     * edit is applied, or the POST_BUILD event we latch onto could belong to it.
     */
    private static void awaitQuiescence(Deadline deadline) throws CoreException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, deadline.monitor());
                Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, deadline.monitor());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new OperationCanceledException(
                        "interrupted while waiting for builds to finish");
            }
            if (isBuildIdle()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new OperationCanceledException(
                        "interrupted while waiting for builds to finish");
            }
        }
        throw new OperationCanceledException("builds did not become idle");
    }

    private static boolean isBuildIdle() {
        return !isBuildJobRunning(ResourcesPlugin.FAMILY_AUTO_BUILD)
                && !isBuildJobRunning(ResourcesPlugin.FAMILY_MANUAL_BUILD);
    }

    private static boolean isBuildJobRunning(Object family) {
        for (Job job : Job.getJobManager().find(family)) {
            final int state = job.getState();
            if (state == Job.RUNNING || state == Job.WAITING) {
                return true;
            }
        }
        return false;
    }

    private static int countErrors(IProject project) throws CoreException {
        int errors = 0;
        for (IMarker marker : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
            if (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)
                    == IMarker.SEVERITY_ERROR) {
                errors++;
            }
        }
        return errors;
    }

    private static int countClassFiles(IProject project) {
        int classFiles = 0;
        final IResource container = project.findMember(OUTPUT_FOLDER + "/" + SOURCE_PACKAGE);
        if (container instanceof IFolder) {
            try {
                for (IResource member : ((IFolder) container).members()) {
                    if (member.getName().endsWith(".class")) {
                        classFiles++;
                    }
                }
            } catch (CoreException unreadable) {
                log("could not list " + OUTPUT_FOLDER + "/" + SOURCE_PACKAGE
                        + ": " + unreadable.getMessage());
            }
        }
        return classFiles;
    }

    /**
     * Times the build the prepared edit caused: PRE_BUILD opens the builder's own window, POST_BUILD
     * closes the whole cycle. The POST_BUILD delta is the build's change set, so it is filtered to
     * this project and then to the edited source and its class file: a build that started before
     * the edit carries neither.
     */
    private static final class PostBuildProbe implements IResourceChangeListener {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean armed;
        private volatile long preBuildNanos;
        private volatile long eventNanos;
        private volatile boolean sourceChanged;
        private volatile boolean classFileChanged;
        private volatile int buildKind = -1;

        void arm() {
            armed = true;
        }

        boolean await(long timeoutMillis) throws InterruptedException {
            return latch.await(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        }

        long preBuildNanos() {
            return preBuildNanos;
        }

        long eventNanos() {
            return eventNanos;
        }

        boolean sourceChanged() {
            return sourceChanged;
        }

        boolean classFileChanged() {
            return classFileChanged;
        }

        int buildKind() {
            return buildKind;
        }

        @Override
        public void resourceChanged(IResourceChangeEvent event) {
            // Timestamp first: everything below costs time that is not part of the build.
            final long now = System.nanoTime();
            if (!armed) {
                return;
            }
            if (event.getType() == IResourceChangeEvent.PRE_BUILD) {
                if (preBuildNanos == 0L && touchesProject(event.getSource())) {
                    preBuildNanos = now;
                }
                return;
            }
            if (event.getType() != IResourceChangeEvent.POST_BUILD || latch.getCount() == 0L) {
                return;
            }
            final IResourceDelta delta = event.getDelta();
            if (delta == null) {
                return;
            }
            final IResourceDelta projectDelta = delta.findMember(
                    new org.eclipse.core.runtime.Path("/" + PROJECT_NAME));
            if (projectDelta == null) {
                return;
            }
            final boolean source = changed(delta.findMember(new org.eclipse.core.runtime.Path(
                    "/" + PROJECT_NAME + "/" + SOURCE_FOLDER + "/" + SOURCE_PACKAGE + "/"
                            + EDIT_SOURCE)));
            final boolean classFile = changed(delta.findMember(new org.eclipse.core.runtime.Path(
                    "/" + PROJECT_NAME + "/" + OUTPUT_FOLDER + "/" + SOURCE_PACKAGE + "/"
                            + EDIT_CLASS)));
            if (!source && !classFile) {
                return;
            }
            this.eventNanos = now;
            this.sourceChanged = source;
            this.classFileChanged = classFile;
            this.buildKind = event.getBuildKind();
            latch.countDown();
        }

        private static boolean changed(IResourceDelta delta) {
            return delta != null && delta.getKind() != IResourceDelta.NO_CHANGE;
        }

        /**
         * True when a build event belongs to the benchmark project. A PRE_BUILD event carries no
         * delta and names the workspace as its source, and this workspace holds exactly this one
         * project; a POST_BUILD event is filtered by its delta, which is the precise test.
         */
        private static boolean touchesProject(Object source) {
            if (source instanceof IWorkspace) {
                return true;
            }
            if (source instanceof IProject[] projects) {
                for (IProject project : projects) {
                    if (PROJECT_NAME.equals(project.getName())) {
                        return true;
                    }
                }
                return false;
            }
            return source instanceof IProject project && PROJECT_NAME.equals(project.getName());
        }
    }

    /** A progress monitor that cancels itself once the application's time budget is spent. */
    private static final class Deadline {

        private final long deadlineNanos;

        Deadline(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        IProgressMonitor monitor() {
            return new NullProgressMonitor() {
                @Override
                public boolean isCanceled() {
                    return System.nanoTime() >= deadlineNanos;
                }
            };
        }

        long remainingMillis() {
            return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        }
    }

    /** The four values the driver passes in as system properties, with their defaults. */
    private static final class Config {

        private final int classes;
        private final Path resultFile;
        private final Path readyFile;
        private final long timeoutMillis;

        private Config(int classes, Path resultFile, Path readyFile, long timeoutMillis) {
            this.classes = classes;
            this.resultFile = resultFile;
            this.readyFile = readyFile;
            this.timeoutMillis = timeoutMillis;
        }

        static Config load() {
            final Path result = Path.of(System.getProperty("bench.result", "bench.result"));
            final Path ready = Path.of(System.getProperty("bench.ready", "bench.ready"));
            final int classes = readInt("bench.classes", 42);
            final long timeoutMillis = readLong("bench.timeoutMs", 120_000L);
            if (classes < 1) {
                throw new IllegalArgumentException("bench.classes must be >= 1, was " + classes);
            }
            if (timeoutMillis < 1L) {
                throw new IllegalArgumentException(
                        "bench.timeoutMs must be >= 1, was " + timeoutMillis);
            }
            return new Config(classes, result, ready, timeoutMillis);
        }

        int classes() {
            return classes;
        }

        Path resultFile() {
            return resultFile;
        }

        Path readyFile() {
            return readyFile;
        }

        long timeoutMillis() {
            return timeoutMillis;
        }

        private static int readInt(String name, int fallback) {
            final String value = System.getProperty(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException(
                        "system property " + name + " is not an integer: " + value, malformed);
            }
        }

        private static long readLong(String name, long fallback) {
            final String value = System.getProperty(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException(
                        "system property " + name + " is not a long: " + value, malformed);
            }
        }
    }

    /** Everything one run observed, serialized as the result JSON by {@link #writeTo(Path)}. */
    private static final class Result {

        private final Config config;
        private final long editNanos;
        private final long buildNanos;
        private final long initialBuildNanos;
        private final long startupNanos;
        private final boolean sourceChanged;
        private final boolean classFileChanged;
        private final boolean classFileStampChanged;
        private final boolean timedOut;
        private final int buildKind;
        private final long queueNanos;
        private final long builderNanos;
        private final int initialErrors;
        private final int initialClassFiles;
        private final String error;
        private final long totalNanos = System.nanoTime();

        Result(Config config, long editNanos, long buildNanos, long initialBuildNanos,
                long startupNanos, boolean sourceChanged, boolean classFileChanged,
                boolean classFileStampChanged, boolean timedOut, int buildKind, long queueNanos,
                long builderNanos, int initialErrors, int initialClassFiles, String error) {
            this.config = config;
            this.editNanos = editNanos;
            this.buildNanos = buildNanos;
            this.initialBuildNanos = initialBuildNanos;
            this.startupNanos = startupNanos;
            this.sourceChanged = sourceChanged;
            this.classFileChanged = classFileChanged;
            this.classFileStampChanged = classFileStampChanged;
            this.timedOut = timedOut;
            this.buildKind = buildKind;
            this.queueNanos = queueNanos;
            this.builderNanos = builderNanos;
            this.initialErrors = initialErrors;
            this.initialClassFiles = initialClassFiles;
            this.error = error;
        }

        static Result failed(Config config, Throwable failure) {
            return new Result(config, -1L, -1L, -1L, -1L, false, false, false, true, -1, -1L, -1L,
                    -1, -1, failure.getClass().getName() + ": " + failure.getMessage());
        }

        boolean ok() {
            return error == null && !timedOut && buildNanos >= 0L;
        }

        void writeTo(Path file) {
            final Json json = new Json().begin();
            json.field("schema", "eclipse-auto-build/1");
            json.field("ok", ok());
            json.field("classes", config.classes());
            json.field("editNanos", editNanos);
            json.field("buildNanos", buildNanos);
            json.field("classFileChanged", classFileChanged);
            json.field("workspace",
                    ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString());
            json.field("timedOut", timedOut);
            json.field("sourceChanged", sourceChanged);
            json.field("classFileStampChanged", classFileStampChanged);
            json.field("buildKind", buildKind);
            // The cycle split: waiting for the build to start, then the builder's own work.
            json.field("queueNanos", queueNanos);
            json.field("builderNanos", builderNanos);
            json.field("initialBuildNanos", initialBuildNanos);
            json.field("initialErrors", initialErrors);
            json.field("initialClassFiles", initialClassFiles);
            json.field("startupNanos", startupNanos);
            json.field("totalNanos", totalNanos);
            if (error != null) {
                json.field("error", error);
            }
            final String text = json.end();
            try {
                writeText(file, text + "\n");
            } catch (Exception unwritable) {
                log("could not write the result file " + file + ": " + unwritable);
            }
            log("result " + text);
        }
    }

    /** Just enough JSON for the fixed shape above: names, strings, longs and booleans. */
    private static final class Json {

        private final StringBuilder out = new StringBuilder(512);
        private boolean first = true;

        Json begin() {
            out.append('{');
            return this;
        }

        Json field(String name, String value) {
            separator();
            quote(name);
            out.append(':');
            quote(value);
            return this;
        }

        Json field(String name, long value) {
            separator();
            quote(name);
            out.append(':').append(value);
            return this;
        }

        Json field(String name, boolean value) {
            separator();
            quote(name);
            out.append(':').append(value);
            return this;
        }

        String end() {
            return out.append('}').toString();
        }

        private void separator() {
            if (!first) {
                out.append(',');
            }
            first = false;
        }

        private void quote(String value) {
            out.append('"');
            for (int index = 0; index < value.length(); index++) {
                final char character = value.charAt(index);
                switch (character) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            out.append(String.format("\\u%04x", (int) character));
                        } else {
                            out.append(character);
                        }
                    }
                }
            }
            out.append('"');
        }
    }

    /** Writes a small UTF-8 file, creating the parent directories the driver may not have made. */
    private static void writeText(Path file, String text) throws Exception {
        final Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void writeResult(Config config, Result result) {
        result.writeTo(config.resultFile());
    }

    private static String millis(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }

    private static void log(String message) {
        System.out.println("[bench] " + message);
        System.out.flush();
    }
}
