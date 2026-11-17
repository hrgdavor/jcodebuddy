package hr.hrg.hipster.sql.tooling.emit;

import java.util.ArrayList;
import java.util.List;

/** Small type-text helpers shared by the emitters. */
final class TypeTexts {

    private TypeTexts() {
    }

    /**
     * The boxed spelling of a primitive, or the type unchanged. Used for the {@code setValue(int,
     * Object)} cast: casting to {@code int} is not legal, casting to {@code Integer} then assigning
     * to an {@code int} field is.
     */
    static String boxed(String type) {
        return switch (type) {
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "char" -> "Character";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> type;
        };
    }

    /** The raw type of a spelled type: {@code Map<String,Long>} to {@code Map}. */
    static String rawType(String type) {
        int generic = type.indexOf('<');
        return (generic < 0 ? type : type.substring(0, generic)).trim();
    }

    /** The top-level type arguments, or an empty list. */
    static List<String> typeArguments(String type) {
        int open = type.indexOf('<');
        int close = type.lastIndexOf('>');
        if (open < 0 || close <= open) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = start; i < close; i++) {
            char c = type.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(type.substring(start, i).trim());
                start = i + 1;
            }
        }
        arguments.add(type.substring(start, close).trim());
        return arguments;
    }

    /** A class literal for a spelled type: {@code int.class}, {@code List.class}, {@code String[].class}. */
    static String classLiteral(String type) {
        String raw = rawType(type);
        return switch (raw) {
            case "int", "long", "short", "byte", "char", "boolean", "float", "double", "void" -> raw + ".class";
            default -> raw + ".class";
        };
    }

    static boolean isPrimitive(String type) {
        return switch (type) {
            case "int", "long", "short", "byte", "char", "boolean", "float", "double", "void" -> true;
            default -> false;
        };
    }

    /** The Java initializer text for a source-written {@code initial}, or {@code ""}. */
    static String initialValue(String type, String initial) {
        if (initial == null || initial.isEmpty()) {
            return "";
        }
        if ("String".equals(type) || "java.lang.String".equals(type)) {
            return " = \"" + initial.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return " = " + initial;
    }
}
