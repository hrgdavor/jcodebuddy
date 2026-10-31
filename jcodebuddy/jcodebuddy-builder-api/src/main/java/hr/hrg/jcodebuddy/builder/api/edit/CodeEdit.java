package hr.hrg.jcodebuddy.builder.api.edit;

/**
 * Data model for a surgical code edit. Coordinates are 1-based, the contract the builder engine and the LSP sidecar
 * share.
 *
 * <p>This is JCodeBuddy's copy, moved out of the frozen {@code java-watch-core} so the builder, the watch tools and the
 * sidecar do not depend on the watcher family for an edit value. The watcher keeps its own copy for its own modules;
 * the two sets do not interact. The value is deliberately a plain record: it is carried over LSP as well as used
 * inside the builder, so it has no dependency and no behavior.</p>
 */
public record CodeEdit(
        String uri,
        int startLine,
        int startCol,
        int endLine,
        int endCol,
        String newText) {
}
