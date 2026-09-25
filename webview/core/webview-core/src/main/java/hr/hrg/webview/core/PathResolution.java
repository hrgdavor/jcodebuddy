package hr.hrg.webview.core;

/**
 * The outcome of turning a page's path into an absolute one, and whether it stayed inside the project.
 *
 * <p>{@code confined} is false when the resolved path escaped the project root. That is not always an
 * error: an IDE host lets the user open any absolute path, and the frozen contract says a host
 * resolves a path "against the project, refusing paths outside it" only for its own HTTP surface. A
 * host that serves pages to a browser (and therefore to any page the user happens to have open) must
 * require {@code confined}; an in-IDE tool window may accept either. Making the answer a value rather
 * than an exception lets each host apply the rule it needs while the resolution logic stays in one
 * place.
 *
 * @param absolute  forward-slashed absolute path, ready for an editor API or a host call
 * @param confined  true when the path is inside the project root (or when no root was configured)
 */
public record PathResolution(String absolute, boolean confined) {

    /** True when a project root was configured and the path escaped it. */
    public boolean escaped() {
        return !confined;
    }
}
