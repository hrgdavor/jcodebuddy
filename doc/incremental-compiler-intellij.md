# Using the incremental compiler from IntelliJ IDEA

This guide connects the fs-meta + ECJ incremental loop to IntelliJ IDEA. It covers two separate things:

1. **Build ownership** — IntelliJ stops auto-building, and the fs-meta/ECJ loop owns the class output. This is
   what removes the sluggish IDE build from the edit loop.
2. **Error hints** — the external compiler's diagnostics appear as red editor annotations and disappear again
   when a later compile succeeds. IntelliJ has **no configuration-only way** to do this; it needs a small
   `ExternalAnnotator` plugin. The plugin, its report contract and its build/install steps are below.

The example referenced throughout is [`examples/fsmeta-reload`](../examples/fsmeta-reload/README.md).

## What IntelliJ can and cannot do

| integration                                     | build ownership                     | editor error hints                            |
| ----------------------------------------------- | ----------------------------------- | --------------------------------------------- |
| IntelliJ build with auto-build on               | IntelliJ                            | IntelliJ's own errors                         |
| IntelliJ build with auto-build off              | IntelliJ, only on an explicit Build | IntelliJ's own errors                         |
| external compiler in a terminal / External Tool | fs-meta + ECJ                       | **none** by default; output is only text      |
| external compiler + the plugin below            | fs-meta + ECJ                       | report-driven annotations, cleared on success |

An IntelliJ **External Tool** can show compiler text in the Run tool window and parse `path:line` links, but it
does **not** add or remove editor error annotations. IntelliJ's documented extension point for external
validation is [`ExternalAnnotator`](https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html.md#external-annotator);
the plugin below implements exactly that.

## 1. Configure the project

1. Set the project SDK to **JDK 25** (the version the example compiles with).
2. Keep the normal Java source roots. Do **not** add the runtime output directory as a source root:
   `examples/fsmeta-reload/project/classes` (the example) or your project's configured `binDir`.
3. Add that output directory as a **module dependency** if IntelliJ needs it to resolve project classes:
   `File | Project Structure | Modules | Dependencies | + | JARs or directories`. The external compiler must be
   the only writer of that directory.
4. Exclude the output and report directories from indexing if you prefer:
   - `project/classes/`
   - `project/target/`

If your project uses Maven/Gradle for dependencies, keep that import; only the **class output** is moved to the
external compiler.

## 2. Disable IntelliJ's auto-build

The slow part is IntelliJ scheduling its own build after a save:

1. `Settings | Build, Execution, Deployment | Compiler`
2. Uncheck **Build project automatically**.
3. In each Run/Debug configuration, open **Before launch** and remove the **Build** step. Otherwise starting a
   test or application still triggers an IntelliJ build.
4. Leave IntelliJ's inspections enabled; they are the editor's own analysis, separate from compilation.

IntelliJ may still show stale compiler errors until its analysis catches up. That is why the external
diagnostics are reported as their own annotations; see § 4.

## 3. Run the external compile loop

The example:

```sh
bun examples/fsmeta-reload/run.js
```

For a real project, point the runner at your source root and class output, or call the same two pieces directly:

```java
EcjIncrementalCompiler compiler = new EcjIncrementalCompiler(sourceRoot, binDir, classpath);
compiler.index();
compiler.compile(changedFiles);
```

The loop is fs-meta-driven:

```text
save -> fsmeta batch -> EcjIncrementalCompiler -> .class files
```

`FSMETA_TICK_MS` sets the root scheduler tick (default 50 ms, range 5–1000). `FSMETA_EARLY_COMPILE=1` opts into
the internal ECJ `beginToCompile`/`processCompiledUnits` split for a one-file batch; leave it off unless you have
measured a benefit for your editor and project.

Keep the watcher running in a terminal. Do not run it as a blocking IntelliJ **External Tool** if you want the
IDE usable; use the terminal or a background task outside the IDE.

## 4. Error hints via the diagnostics report

The example writes after every compile:

```text
<project>/target/jcodebuddy-diagnostics.json
```

The write is atomic (temporary file + replace), so the IDE never reads a half-written report. The schema is:

```json
{
  "schema": "jcodebuddy-diagnostics/1",
  "success": false,
  "generatedAt": "2026-11-08T10:00:00Z",
  "files": [
    {
      "path": "D:/wrk/java/jcodebuddy/examples/fsmeta-reload/project/src/main/java/demo/project/Calculation.java",
      "line": 24,
      "column": 0,
      "severity": "error",
      "message": "Type mismatch: cannot convert from int to boolean"
    }
  ]
}
```

- `success: true` and `files: []` means **the last compile succeeded**; the next annotation pass removes the
  previous errors.
- On failure, every entry is a real editor annotation. `line` is 1-based; `column: 0` means "whole line".
- Paths are absolute and normalized by the plugin before comparison.

For your own compiler, write the same document from the same place that knows the compile result and the
diagnostics. The report is output: it must not be inside a watched source root, and it must be replaced
atomically.

## 5. Plugin: add/remove editor errors

Create a small IntelliJ plugin in any directory (not in this Maven reactor). It needs two classes and
`plugin.xml`.

### `plugin.xml`

```xml
<idea-plugin>
  <id>local.jcodebuddy.diagnostics</id>
  <name>JCodeBuddy Incremental Diagnostics</name>
  <vendor>local</vendor>
  <depends>com.intellij.modules.java</depends>

  <extensions defaultExtensionNs="com.intellij">
    <externalAnnotator language="JAVA"
                       implementationClass="local.jcodebuddy.JcodeBuddyAnnotator"/>
    <postStartupActivity implementationClass="local.jcodebuddy.JcodeBuddyRefresh"/>
  </extensions>
</idea-plugin>
```

### `JcodeBuddyAnnotator`

Use the IntelliJ Platform's `ExternalAnnotator`. It reads the report for the current PSI file, then applies one
`HighlightSeverity.ERROR` annotation per diagnostic. On an empty report it returns an empty list; IntelliJ
clears the previous external annotations on the next highlighting pass.

```java
package local.jcodebuddy;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JcodeBuddyAnnotator
        extends ExternalAnnotator<List<JcodeBuddyAnnotator.Diagnostic>,
                                  List<JcodeBuddyAnnotator.Diagnostic>> {

    record Diagnostic(Path path, int line, String message) {
    }

    @Override
    public @Nullable List<Diagnostic> collectInformation(@NotNull PsiFile file) {
        Path report = reportPath(file.getProject());
        if (report == null || !Files.isRegularFile(report)) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
            if (!root.has("files") || !root.get("files").isJsonArray()) {
                return List.of();
            }
            Path current = Path.of(file.getVirtualFile().getPath()).toAbsolutePath().normalize();
            List<Diagnostic> result = new ArrayList<>();
            JsonArray files = root.getAsJsonArray("files");
            for (JsonElement element : files) {
                JsonObject entry = element.getAsJsonObject();
                Path path = Path.of(entry.get("path").getAsString()).toAbsolutePath().normalize();
                if (!path.equals(current)) {
                    continue;
                }
                result.add(new Diagnostic(path, entry.get("line").getAsInt(),
                        entry.get("message").getAsString()));
            }
            return result;
        } catch (Exception ignored) {
            // A report being replaced or a malformed report must not break editor highlighting.
            return List.of();
        }
    }

    @Override
    public @Nullable List<Diagnostic> doAnnotate(List<Diagnostic> information) {
        return information;
    }

    @Override
    public void apply(@NotNull PsiFile file, List<Diagnostic> diagnostics,
                      @NotNull AnnotationHolder holder) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null || document.getLineCount() == 0) {
            return;
        }
        for (Diagnostic diagnostic : diagnostics) {
            int line = Math.max(0, Math.min(diagnostic.line() - 1, document.getLineCount() - 1));
            int start = document.getLineStartOffset(line);
            int end = document.getLineEndOffset(line);
            holder.newAnnotation(HighlightSeverity.ERROR, diagnostic.message())
                    .range(new TextRange(start, end))
                    .create();
        }
    }

    private static @Nullable Path reportPath(Project project) {
        String configured = System.getProperty("jcodebuddy.diagnostics");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String base = project.getBasePath();
        return base == null ? null : Path.of(base, "project", "target", "jcodebuddy-diagnostics.json")
                .toAbsolutePath().normalize();
    }
}
```

`column` is intentionally ignored in this reference implementation: a whole-line error is stable even when the
external compiler reports a line without a column. If your compiler produces columns, clamp the start/end to the
line and use `document.getLineStartOffset(line) + column`.

### Trigger re-highlighting when the report changes

The annotator is not called just because a file outside the edited source tree changed. The example report is
written on every compile, so a small startup activity watches it and restarts the daemon analysis:

```java
package local.jcodebuddy;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.VirtualFileAdapter;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class JcodeBuddyRefresh implements StartupActivity {

    @Override
    public void runActivity(@NotNull Project project) {
        VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileAdapter() {
            @Override
            public void contentsChanged(@NotNull VirtualFileEvent event) {
                refreshIfReport(project, event);
            }

            @Override
            public void fileCreated(@NotNull VirtualFileEvent event) {
                refreshIfReport(project, event);
            }
        }, project);
    }

    private static void refreshIfReport(Project project, VirtualFileEvent event) {
        String configured = System.getProperty("jcodebuddy.diagnostics");
        Path report;
        if (configured == null || configured.isBlank()) {
            String base = project.getBasePath();
            if (base == null) {
                return;
            }
            report = Path.of(base, "project", "target", "jcodebuddy-diagnostics.json");
        } else {
            report = Path.of(configured);
        }
        if (event.getFile().getPath().equals(report.toAbsolutePath().normalize().toString())) {
            ApplicationManager.getApplication().invokeLater(
                    () -> DaemonCodeAnalyzer.getInstance(project).restart());
        }
    }
}
```

### Build and install

Create a Gradle project with the [IntelliJ Platform Gradle plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
and:

```kotlin
plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform { intellijIdeaCommunity("2025.3") }
}
```

Use the IntelliJ Platform plugin version and IDE version that match the IDE you run. Then:

```text
gradlew.bat buildPlugin
```

Install `build/distributions/*.zip` via `Settings | Plugins | ⚙ | Install Plugin from Disk...`, then restart
IntelliJ. The version numbers above are illustrative; use the current IntelliJ Platform Gradle plugin and the
IDE build you actually run. For development, use `gradlew.bat runIde`; set
`-Djcodebuddy.diagnostics=<path>` in the run configuration if your report is not at the default path.

## 6. What the user sees

- **Compile fails:** the report contains diagnostics; the next analysis pass adds red squiggles on the reported
  lines. The file does not need to be edited to see them.
- **Compile succeeds:** the compiler writes `success: true` with an empty `files` array; the report VFS event
  restarts analysis, and the old annotations are removed.
- **Compile fails, then a different file succeeds:** only the files with entries are annotated; the successful
  file's entries are absent, so its old annotations clear when its analysis runs.

IntelliJ's **own** unresolved-symbol errors are separate. If those remain after a successful external compile,
the module dependency/output path is wrong, not the external compiler.

## 7. Common setup problems

| symptom                                                  | cause                                                                           | fix                                                                                       |
| -------------------------------------------------------- | ------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| IDE still builds on save                                 | "Build project automatically" or a Run configuration's Before-launch Build step | turn both off                                                                             |
| red errors from our compiler remain after a good compile | report was not replaced, or the plugin did not see the VFS event                | check the report has `"files": []`; ensure the plugin path matches; restart analysis      |
| errors appear at the wrong line                          | line is 1-based in the report; editor offsets are 0-based                       | keep the `line - 1` conversion                                                            |
| plugin reports nothing                                   | report path is not under the project base, or plugin's project SDK differs      | set `-Djcodebuddy.diagnostics` to the absolute path                                       |
| IntelliJ cannot resolve project classes                  | external output not on the module classpath                                     | add the output directory as a module dependency; do not add it as a source root           |
| IntelliJ build overwrites external classes               | explicit Build still owns the same output directory                             | remove the Build before-launch step, or use a separate output directory for the IDE build |

The external compiler is the authority for the incremental loop. IntelliJ remains the editor, test runner and
debugger; its own build is used only when you explicitly ask for it.
