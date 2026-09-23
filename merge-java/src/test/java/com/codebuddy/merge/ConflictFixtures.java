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
 *
 * <h2>The samples are documentation fixtures too</h2>
 *
 * <p>Every sample lives in a named {@code //#region <type>-sample} block as
 * three constants - base, branch 1 (ours), branch 2 (theirs). The resolver
 * documentation under {@code docs/resolvers/} includes those regions verbatim
 * through the repository's example-injection markers
 * ({@code npm run inject:examples} from the repository root, backed by the
 * published {@code @hrg/inject-examples} package), and {@code ResolverDocsTest}
 * fails the build when a rendered example and the fixture here disagree. Editing a
 * string below therefore edits the documentation example with it; there is no
 * second copy to forget.
 */
final class ConflictFixtures {

    static final String FILE = "src/main/java/com/example/PaymentProcessor.java";

    //#region import-add-sample
    static final String IMPORT_ADD_BASE = "import java.util.List;";
    static final String IMPORT_ADD_BRANCH1 = "import java.util.List;\nimport java.math.BigDecimal;";
    static final String IMPORT_ADD_BRANCH2 = "import java.util.List;\nimport java.time.Instant;";
    //#endregion

    //#region comment-add-sample
    static final String COMMENT_ADD_BASE = "int total = 0;";
    static final String COMMENT_ADD_BRANCH1 = "// branch 1 explains the running total\nint total = 0;";
    static final String COMMENT_ADD_BRANCH2 = "// branch 2 records the currency\nint total = 0;";
    //#endregion

    //#region constant-add-sample
    static final String CONSTANT_ADD_BASE = "static final int MAX_RETRIES = 3;";
    static final String CONSTANT_ADD_BRANCH1 = "static final int MAX_RETRIES = 3;\nstatic final int TIMEOUT_MS = 500;";
    static final String CONSTANT_ADD_BRANCH2 = "static final int MAX_RETRIES = 3;\nstatic final int RETRY_DELAY_MS = 250;";
    //#endregion

    //#region method-body-change-sample
    static final String METHOD_BODY_CHANGE_BASE = "int total = 0;\nreturn total;";
    static final String METHOD_BODY_CHANGE_BRANCH1 = "int total = 0;\ntotal += 1;\nreturn total;";
    static final String METHOD_BODY_CHANGE_BRANCH2 = "int total = 0;\ntotal *= 2;\nreturn total;";
    //#endregion

    //#region variable-rename-sample
    static final String VARIABLE_RENAME_BASE = "int order = 1;";
    static final String VARIABLE_RENAME_BRANCH1 = "int purchase = 1;";
    static final String VARIABLE_RENAME_BRANCH2 = "int invoice = 1;";
    //#endregion

    //#region type-change-sample
    static final String TYPE_CHANGE_BASE = "int count = 0;";
    static final String TYPE_CHANGE_BRANCH1 = "long count = 0;";
    static final String TYPE_CHANGE_BRANCH2 = "double count = 0;";
    //#endregion

    //#region package-change-sample
    static final String PACKAGE_CHANGE_BASE = "package com.example.payments;";
    static final String PACKAGE_CHANGE_BRANCH1 = "package com.example.billing;";
    static final String PACKAGE_CHANGE_BRANCH2 = "package com.example.ledger;";
    //#endregion

    //#region overload-add-sample
    static final String OVERLOAD_ADD_BASE = "void process() { }";
    static final String OVERLOAD_ADD_BRANCH1 = "void process() { }\nvoid process(String id) { }";
    static final String OVERLOAD_ADD_BRANCH2 = "void process() { }\nvoid process(String id, boolean force) { }";
    //#endregion

    //#region structural-change-sample
    static final String STRUCTURAL_CHANGE_BASE = "void process() { audit(); }\nvoid audit() { }";
    static final String STRUCTURAL_CHANGE_BRANCH1 = "void process() { audit(); charge(); }\nvoid audit() { }";
    static final String STRUCTURAL_CHANGE_BRANCH2 = "void process() { audit(); refund(); }\nvoid audit() { }";
    //#endregion

    //#region api-incompatibility-sample
    static final String API_INCOMPATIBILITY_BASE = "public void process() throws IOException { }";
    static final String API_INCOMPATIBILITY_BRANCH1 = "public int process() { }";
    static final String API_INCOMPATIBILITY_BRANCH2 = "public long process() { }";
    //#endregion

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
            case IMPORT_ADD -> IMPORT_ADD_BASE;
            case COMMENT_ADD -> COMMENT_ADD_BASE;
            case CONSTANT_ADD -> CONSTANT_ADD_BASE;
            case METHOD_BODY_CHANGE -> METHOD_BODY_CHANGE_BASE;
            case VARIABLE_RENAME -> VARIABLE_RENAME_BASE;
            case TYPE_CHANGE -> TYPE_CHANGE_BASE;
            case PACKAGE_CHANGE -> PACKAGE_CHANGE_BASE;
            case OVERLOAD_ADD -> OVERLOAD_ADD_BASE;
            case STRUCTURAL_CHANGE -> STRUCTURAL_CHANGE_BASE;
            case API_INCOMPATIBILITY -> API_INCOMPATIBILITY_BASE;
        };
    }

    static String branch1Code(ConflictType type) {
        return switch (type) {
            case IMPORT_ADD -> IMPORT_ADD_BRANCH1;
            case COMMENT_ADD -> COMMENT_ADD_BRANCH1;
            case CONSTANT_ADD -> CONSTANT_ADD_BRANCH1;
            case METHOD_BODY_CHANGE -> METHOD_BODY_CHANGE_BRANCH1;
            case VARIABLE_RENAME -> VARIABLE_RENAME_BRANCH1;
            case TYPE_CHANGE -> TYPE_CHANGE_BRANCH1;
            case PACKAGE_CHANGE -> PACKAGE_CHANGE_BRANCH1;
            case OVERLOAD_ADD -> OVERLOAD_ADD_BRANCH1;
            case STRUCTURAL_CHANGE -> STRUCTURAL_CHANGE_BRANCH1;
            case API_INCOMPATIBILITY -> API_INCOMPATIBILITY_BRANCH1;
        };
    }

    static String branch2Code(ConflictType type) {
        return switch (type) {
            case IMPORT_ADD -> IMPORT_ADD_BRANCH2;
            case COMMENT_ADD -> COMMENT_ADD_BRANCH2;
            case CONSTANT_ADD -> CONSTANT_ADD_BRANCH2;
            case METHOD_BODY_CHANGE -> METHOD_BODY_CHANGE_BRANCH2;
            case VARIABLE_RENAME -> VARIABLE_RENAME_BRANCH2;
            case TYPE_CHANGE -> TYPE_CHANGE_BRANCH2;
            case PACKAGE_CHANGE -> PACKAGE_CHANGE_BRANCH2;
            case OVERLOAD_ADD -> OVERLOAD_ADD_BRANCH2;
            case STRUCTURAL_CHANGE -> STRUCTURAL_CHANGE_BRANCH2;
            case API_INCOMPATIBILITY -> API_INCOMPATIBILITY_BRANCH2;
        };
    }
}
