package hr.hrg.jcodebuddy.engine.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads {@code module-info.java} directives from the file's text. */
public final class ModuleExtractor {

    private static final Pattern MODULE = Pattern.compile("\\bmodule\\s+([\\w.]+)\\s*\\{");
    private static final Pattern DIRECTIVE = Pattern.compile(
            "^\\s*(requires|exports|opens|uses|provides)\\s+(.+?)\\s*;\\s*$");

    private ModuleExtractor() {
    }

    public static ModuleRecord of(Path moduleRoot, Path moduleInfo) throws IOException {
        String text = Files.readString(moduleInfo, StandardCharsets.UTF_8);
        Matcher module = MODULE.matcher(text);
        String name = module.find() ? module.group(1) : "";
        List<String> requires = new ArrayList<>();
        List<String> exports = new ArrayList<>();
        List<String> opens = new ArrayList<>();
        List<String> uses = new ArrayList<>();
        List<String> provides = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher directive = DIRECTIVE.matcher(line);
            if (!directive.find()) {
                continue;
            }
            String value = directive.group(2).trim();
            switch (directive.group(1)) {
                case "requires" -> requires.add(value);
                case "exports" -> exports.add(value);
                case "opens" -> opens.add(value);
                case "uses" -> uses.add(value);
                case "provides" -> provides.add(value);
                default -> {
                }
            }
        }
        return new ModuleRecord(name, moduleRoot.relativize(moduleInfo).toString().replace('\\', '/'),
                ContentHash.of(moduleInfo), requires, exports, opens, uses, provides);
    }
}
