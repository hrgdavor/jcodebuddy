// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import hr.hrg.watch2.core.CodeEdit;
import hr.hrg.watch2.core.TransformationResult;

import java.util.List;
import java.util.Map;

/**
 * High-level engine for Record Builder transformations.
 *
 * <p>Returns surgical edits — a range and its replacement text — rather than the whole file, so a
 * caller can apply them to an editor buffer without disturbing anything else.</p>
 *
 * <h3>Phase 6: what changed, and what deliberately did not</h3>
 * <p>The JavaParser version parsed with {@code LexicalPreservingPrinter} enabled, mutated the record
 * through {@code RecordBuilderProcessor}, printed <em>just the record</em>, and then re-indented that
 * text line by line while tracking whether it was inside the builder with a boolean flag. It located
 * the record through JavaParser's {@code Range}.</p>
 *
 * <p>Three of those four steps are gone, and the reason is the same in each case: they existed to work
 * around the printer, not to express the feature.</p>
 * <ul>
 *   <li><strong>No lexical preservation.</strong> It was there to protect the file <em>around</em> the
 *       record. The edit is a range replacement whose replacement text is generated, so the
 *       surrounding text is never touched — preserved by construction rather than by a printer
 *       setting.</li>
 *   <li><strong>No post-hoc indentation pass.</strong> {@code SourceSplicer} applies the indent as it
 *       builds the text, reading the record's own indentation so a nested record comes out right.</li>
 *   <li><strong>No {@code Range}.</strong> The LST exposes no positions, so the record's span and line
 *       come from javac's line map ({@link LineLookup}).</li>
 * </ul>
 *
 * <p>What did <em>not</em> change is the public contract: the returned {@link CodeEdit} replaces the
 * <strong>record's own span</strong> with the completed record, which is exactly the shape the
 * JavaParser version produced — so {@code CodeEditApplier} and the callers in {@code jwa-sidecar} and
 * {@code java-watch-agent} keep working while they are ported in turn.</p>
 */
public class BuilderTransformationEngine {

    private final String indent;

    public BuilderTransformationEngine(String indent) {
        this.indent = indent;
    }

    /**
     * The edits that complete the record nearest {@code line} with a builder.
     *
     * @return an empty result when the source cannot be read, declares no record, or the record's span
     *         cannot be located — the same fail-safe the JavaParser version had, and deliberately not
     *         an exception: this runs on an editor request, and "this file is not ready to be
     *         completed" is an ordinary outcome rather than a failure.
     */
    public TransformationResult generate(String uri, String source, int line) {
        if (source == null || source.isBlank()) {
            return TransformationResult.empty();
        }

        RecordBuilderProcessor processor = new RecordBuilderProcessor(indent);
        RecordBuilderProcessor.Target target = processor.target(source, line);
        if (target == null) {
            return TransformationResult.empty();
        }

        // The replacement is the record's own text with its builder completed, so the edit's range and
        // its text describe the same span. A whole-file replacement would be simpler to produce and
        // wrong to apply: it would move the cursor and rewrite lines the pass never looked at.
        String recordText = source.substring(target.startOffset(), target.endOffset());
        String completed = processor.complete(recordText, target.recordName(), target.components());

        CodeEdit edit = new CodeEdit(uri,
                target.startLine(), LineLookup.columnOf(source, target.startOffset()),
                target.endLine(), LineLookup.columnOf(source, target.endOffset()),
                completed);
        return new TransformationResult(List.of(edit), Map.of());
    }
}
