package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

/**
 * One {@code module-info.java} declaration. OpenRewrite's current Java model does not expose a module
 * declaration as a tree node, so this record is read from the file's directive lines and keeps them as
 * written; it never invents a module name or a directive.
 */
public record ModuleRecord(String name, String path, String checksum, List<String> requires,
                           List<String> exports, List<String> opens, List<String> uses,
                           List<String> provides) {

    public ModuleRecord {
        name = name == null ? "" : name;
        path = path == null ? "" : path;
        checksum = checksum == null ? "" : checksum;
        requires = requires == null ? List.of() : List.copyOf(requires);
        exports = exports == null ? List.of() : List.copyOf(exports);
        opens = opens == null ? List.of() : List.copyOf(opens);
        uses = uses == null ? List.of() : List.copyOf(uses);
        provides = provides == null ? List.of() : List.copyOf(provides);
    }
}
