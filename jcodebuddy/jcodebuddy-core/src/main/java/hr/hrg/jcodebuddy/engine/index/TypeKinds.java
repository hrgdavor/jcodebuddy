package hr.hrg.jcodebuddy.engine.index;

import org.openrewrite.java.tree.J;

/**
 * The one kind resolver: a declaration's Java kind as the class index records it (DEC-029).
 *
 * <p>It lives in the engine because the kind is a <em>row's</em> field, and because the module that used to
 * own it said so in its own javadoc: the method was public "only so {@code TypeFacts} can reuse it". A
 * resolver that exists for the index's sake belongs with the index; the entity model's
 * {@code MetadataLocations.kindOf} delegates here, so there is still exactly one implementation.</p>
 *
 * <p>JavaParser gave five distinct types here; the LST has <strong>one</strong>,
 * {@link J.ClassDeclaration}, whose {@link J.ClassDeclaration#getKind()} returns exactly those five kinds.
 * Getting it wrong is silent in the worst way: returning {@code "class"} for everything produces an index
 * that compiles, validates, and mislabels every record and enum in the tree. The vocabulary is DEC-029's
 * contract and must not change.</p>
 */
public final class TypeKinds {

    private TypeKinds() {
    }

    /** {@code class} / {@code interface} / {@code enum} / {@code record} / {@code annotation} (DEC-029). */
    public static String kindOf(J.ClassDeclaration declaration) {
        if (declaration == null) {
            return "class";
        }
        return switch (declaration.getKind()) {
            case Enum -> "enum";
            case Record -> "record";
            case Annotation -> "annotation";
            case Interface -> "interface";
            case Class -> "class";
            default -> "class";
        };
    }
}