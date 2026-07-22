// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class WatchConstants {
    public static final Set<String> TEXT_FILE_EXTENSIONS;

    static {
        Set<String> extensions = new HashSet<>();
        // Core plain text
        Collections.addAll(extensions, ".txt", ".log", ".csv", ".tsv", ".ini", ".cfg", ".conf", ".env", ".properties", ".lst", ".list");
        // Source code - C-family
        Collections.addAll(extensions, ".c", ".h", ".hpp", ".hh", ".hxx", ".cpp", ".cc", ".cxx");
        // Source code - JVM
        Collections.addAll(extensions, ".java", ".kt", ".kts");
        // Source code - .NET
        Collections.addAll(extensions, ".cs", ".fs");
        // Source code - Scripting
        Collections.addAll(extensions, ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".py", ".rb", ".php", ".pl", ".sh", ".bash", ".zsh", ".ps1");
        // Source code - Other
        Collections.addAll(extensions, ".go", ".rs", ".swift", ".scala", ".clj", ".lua", ".r", ".hs", ".sql");
        // Build files
        Collections.addAll(extensions, ".gradle", ".pom", ".csproj", ".fsproj", ".vbproj", ".sln", ".cmake", ".make", ".mk");
        // Markup & docs
        Collections.addAll(extensions, ".html", ".htm", ".xhtml", ".xml", ".xsd", ".xsl", ".xslt", ".svg", ".md", ".markdown", ".mkd", ".rst", ".adoc", ".org", ".tex", ".sty", ".cls");
        // Data formats
        Collections.addAll(extensions, ".json", ".jsonl", ".yml", ".yaml", ".toml");
        // VCS & tools
        Collections.addAll(extensions, ".gitignore", ".gitattributes", ".gitmodules", ".gitkeep", ".editorconfig");
        // Containers
        Collections.addAll(extensions, "Dockerfile", ".dockerignore", ".compose", ".k8s.yaml", ".k8s.yml", ".helmignore");
        // Linting & tooling
        Collections.addAll(extensions, ".eslintrc", ".prettierrc", ".babelrc", ".npmrc", ".yarnrc", ".pnp.js", ".clang-format", ".clang-tidy");
        
        TEXT_FILE_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    public static boolean isTextFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        for (String ext : TEXT_FILE_EXTENSIONS) {
            if (lower.endsWith(ext.toLowerCase())) return true;
        }
        return false;
    }
}
