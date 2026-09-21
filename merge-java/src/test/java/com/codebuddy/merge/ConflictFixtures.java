// {@link com.codebuddy.merge.ConflictFixtures} Shared sample conflicts used by the tests.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

/**
 * One place that defines a representative conflict for every
 * {@link ConflictType}, so resolver tests describe behaviour instead of
 * copy-pasting sample code.
 *
 * <p>Each sample is a realistic minimum: enough structure for the resolver's
 * parsing to engage, and shaped so that the two branches genuinely disagree.
 */
final class ConflictFixtures {

    static final String FILE = "src/main/java/com/example/PaymentProcessor.java";

    private ConflictFixtures() {
    }

    /**
     * A representative conflict of the given type, anchored to {@link #FILE}.
     */
    static Conflict sample(ConflictType type) {
        return new Conflict(type, FILE, describe(type), baseCode(type),
            branch1Code(type), branch2Code(type));
    }

    /**
     * A short human description, used as the conflict's description.
     */
    static String describe(ConflictType type) {
        return switch (type) {
            case IMPORT_ADD -> "both branches added an import";
            case COMMENT_ADD -> "both branches added documentation";
            case CONSTANT_ADD -> "both branches added constants";
            case METHOD_BODY_CHANGE -> "both branches edited the same method";
            case VARIABLE_RENAME -> "the same declaration was renamed differently";
            case TYPE_CHANGE -> "the declared type differs between branches";
            case PACKAGE_CHANGE -> "the class was moved to a different package";
            case OVERLOAD_ADD -> "both branches added a method with the same name";
            case STRUCTURAL_CHANGE -> "both branches restructured the same member";
            case API_INCOMPATIBILITY -> "the public contract differs";
        };
    }

    static String baseCode(ConflictType type) {
        return switch (type) {
            case IMPORT_ADD -> "import java.util.List;";
            case COMMENT_ADD -> "int total = 0;";
            case CONSTANT_ADD -> "static final int MAX_RETRIES = 3;";
            case METHOD_BODY_CHANGE -> "int total = 0;\nreturn total;";
            case VARIABLE_RENAME -> "int order = 1;";
            case TYPE_CHANGE -> "int count = 0;";
            case PACKAGE_CHANGE -> "package com.example.payments;";
            case OVERLOAD_ADD -> "void process() { }";
            case STRUCTURAL_CHANGE -> "void process() { audit(); }\nvoid audit() { }";
            case API_INCOMPATIBILITY -> "public void process() throws IOException { }";
        };
    }

    static String branch1Code(ConflictType type) {
        return switch (type) {
            case IMPORT_ADD -> "import java.util.List;\nimport java.math.BigDecimal;";
            case COMMENT_ADD -> "// branch 1 explains the running total\nint total = 0;";
            case CONSTANT_ADD -> "static final int MAX_RETRIES = 3;\nstatic final int TIMEOUT_MS = 500;";
            case METHOD_BODY_CHANGE -> "int total = 0;\ntotal += 1;\nreturn total;";
            case VARIABLE_RENAME -> "int purchase = 1;";
            case TYPE_CHANGE -> "long count = 0;";
            case PACKAGE_CHANGE -> "package com.example.billing;";
            case OVERLOAD_ADD -> "void process() { }\nvoid process(String id) { }";
            case STRUCTURAL_CHANGE -> "void process() { audit(); charge(); }\nvoid audit() { }";
            case API_INCOMPATIBILITY -> "public int process() { }";
        };
    }

    static String branch2Code(ConflictType type) {
        return switch (type) {
            case IMPORT_ADD -> "import java.util.List;\nimport java.time.Instant;";
            case COMMENT_ADD -> "// branch 2 records the currency\nint total = 0;";
            case CONSTANT_ADD -> "static final int MAX_RETRIES = 3;\nstatic final int RETRY_DELAY_MS = 250;";
            case METHOD_BODY_CHANGE -> "int total = 0;\ntotal *= 2;\nreturn total;";
            case VARIABLE_RENAME -> "int invoice = 1;";
            case TYPE_CHANGE -> "double count = 0;";
            case PACKAGE_CHANGE -> "package com.example.ledger;";
            case OVERLOAD_ADD -> "void process() { }\nvoid process(String id, boolean force) { }";
            case STRUCTURAL_CHANGE -> "void process() { audit(); refund(); }\nvoid audit() { }";
            case API_INCOMPATIBILITY -> "public long process() { }";
        };
    }
}
