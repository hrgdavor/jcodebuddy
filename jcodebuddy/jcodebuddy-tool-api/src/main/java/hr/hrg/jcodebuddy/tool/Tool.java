package hr.hrg.jcodebuddy.tool;

/** A tool: it previews a change and leaves applying it to the coordinator. */
public interface Tool {

    String id();

    String title();

    default String version() {
        return "1";
    }

    /** A standalone page relative to the app root, or null when the tool has no UI yet. */
    default String standalonePage() {
        return null;
    }

    /**
     * The stable identity of one generation case, used for permanent refusal. Empty when this tool
     * cannot identify a case independently of the file's current content.
     */
    default String caseKey(ToolRequest request) {
        return "";
    }

    /** Whether refusing a case can be remembered and skipped on a later rebuild. */
    default boolean supportsPermanentRefusal() {
        return false;
    }

    /** Whether this tool can propose from a per-file fs-meta change without a manual request. */
    default boolean watchesFiles() {
        return supportsPermanentRefusal();
    }

    /** @throws ToolRefusal when the request is not valid for this tool */
    ToolProposal preview(ToolRequest request) throws ToolRefusal;
}
