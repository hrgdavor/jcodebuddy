package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The strict half of E10's type-parameter recovery: what {@link EntityMetadataGenerator.DeclarationReader}
 * reads, and what it refuses.
 *
 * <p>The decoder exists because a declaration's type-parameter names are not a field in
 * {@code FileMetadata} and DEC-041 D6 forbids adding one; the ranges the entry already records are the
 * way to reach them. Its contract is asymmetric on purpose: a <strong>false decode is a wrong
 * answer</strong> (a bare name becomes a class literal or does not), while a <strong>false refusal is
 * only a parse</strong>. So every shape below is either read exactly — annotations, comments, bounds and
 * nested generics included — or answered with {@code null}, and the assertions say which.</p>
 *
 * <p>These cases run against the reader directly rather than through a pass: the pass-level measurement
 * is {@link ParseReuseTest}'s job, and a decoded shape that never appears in the example would otherwise
 * be untested until a project used it.</p>
 */
@Tag("generator")
class TypeParameterRangeDecoderTest {

    // ── a type declaration: the list after the declared name ──────────────────────────────────────────

    @Test
    void readsTheDeclaredNamesOfATypeAndIgnoresItsBounds() {
        Assertions.assertEquals(List.of("ID"), typeParameters(
                "public interface Auditable<ID> extends EntityBase<ID> {", "Auditable", "interface"),
                "the example's marker: the name is the type parameter, the bound is not a name");
        Assertions.assertEquals(List.of("K", "V"), typeParameters(
                "public final class Box<K, V extends Map<String, List<Long>>> {", "Box", "class"),
                "a bound's nested generics must not be read as further names or end the list early");
        Assertions.assertEquals(List.of("T"), typeParameters(
                "public record Holder<T extends Comparable<T>>(T value) {", "Holder", "record"),
                "a record's header sits after the list, and its self-referential bound is ignored");
        Assertions.assertEquals(List.of("E"), typeParameters(
                "public enum Sized<E> implements Sized2 {", "Sized", "enum"));
        Assertions.assertEquals(List.of("A", "B"), typeParameters(
                "class Pair<A, B extends A & Comparable<B>> {", "Pair", "class"),
                "a multi-bound parameter contributes its first name once, and the `&` bounds are skipped");
    }

    @Test
    void readsADeclarationThatHasNoTypeParametersAsAnEmptyList() {
        Assertions.assertEquals(List.of(), typeParameters(
                "@View(gen = GenLevel.BUILDER_ALL)\npublic interface PersonSummary extends Person {",
                "PersonSummary", "interface"));
        Assertions.assertEquals(List.of(), typeParameters(
                "public sealed interface Shape permits Circle, Square {", "Shape", "interface"),
                "the permitted-subtype list is a header token, not a type-parameter list");
        Assertions.assertEquals(List.of(), typeParameters(
                "public non-sealed class Open extends Shape {", "Open", "class"),
                "`non-sealed` is one hyphenated modifier, not a word the reader stops at");
        Assertions.assertEquals(List.of(), typeParameters(
                "@Documented\npublic @interface Marker {", "Marker", "annotation"),
                "an annotation type's header is `@interface Name {`, and its name is still verified");
        Assertions.assertNull(typeParameters(
                "public @interface Marker<T> {", "Marker", "annotation"),
                "an annotation type cannot declare type parameters, so a list there is refused");
    }

    @Test
    void skipsAnnotationsCommentsAndModifiersBeforeTheKeywordAndName() {
        Assertions.assertEquals(List.of("Q"), typeParameters(
                "@View(name = \"a<b>\", addons = {Thing.class})\n"
                        + "  @Deprecated /* <not a name> */ public final interface Weird<Q> extends Base<Q> {",
                "Weird", "interface"),
                "a '<' or a declared name inside an annotation argument or a comment is not the list");
        Assertions.assertEquals(List.of("Y"), typeParameters(
                "public /* <X> */ interface Commented<Y> /* <Z> */ extends Base {",
                "Commented", "interface"));
        Assertions.assertEquals(List.of(), typeParameters(
                "public interface Qualified extends com.example.Base<Foo> {", "Qualified", "interface"),
                "a supertype's type arguments are not the declaration's parameters");
    }

    @Test
    void refusesATypeDeclarationItCannotReadFaithfully() {
        Assertions.assertNull(typeParameters(
                "public class NotAnInterface {", "NotAnInterface", "interface"),
                "the keyword must agree with the row's kind: a class row is not an interface row");
        Assertions.assertNull(typeParameters(
                "public interface Other<ID> {", "Auditable", "interface"),
                "the declared name must be the row's name, or the range points at another declaration");
        Assertions.assertNull(typeParameters(
                "public interface Empty<> {", "Empty", "interface"), "an empty list is not Java");
        Assertions.assertNull(typeParameters(
                "public interface Broken<K,> {", "Broken", "interface"), "a trailing comma is not a name");
        Assertions.assertNull(typeParameters(
                "public interface Unterminated<K", "Unterminated", "interface"),
                "a list with no closing '>' is refused rather than guessed at");
        Assertions.assertNull(typeParameters(
                "@Doc(value = \"\"\"<T>\"\"\") public interface TextBlock<T> {", "TextBlock", "interface"),
                "a text block is refused rather than read past: its terminator is not this reader's to guess");
        Assertions.assertNull(typeParameters(
                "public <T> T method() { return null; }", "method", "interface"),
                "a method declaration is not a type declaration");
    }

    // ── a callable: the optional list before its return type ─────────────────────────────────────────

    @Test
    void readsTheDeclaredNamesOfAMethodOrConstructor() {
        Assertions.assertEquals(List.of("T"),
                callable("public <T> T value()"), "the shape the step's plan names");
        Assertions.assertEquals(List.of("K", "V"), callable(
                "@Override\n  default <K, V extends Number> java.util.Map<K, V> map()"),
                "annotations and modifiers precede the list; the return type follows it");
        Assertions.assertEquals(List.of("T"), callable(
                "@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n  <T> T cast(Object value)"),
                "an annotation argument list with a string and a nested brace is skipped, not scanned for '<'");
        Assertions.assertEquals(List.of("T"), callable(
                "@Doc(hint = \"a)b\") <T> T ok()"),
                "a ')' inside a string does not end the annotation's argument list");
        Assertions.assertEquals(List.of(), callable("String name()"),
                "a plain method has no list, and its return type is not a keyword");
        Assertions.assertEquals(List.of(), callable("public void clear()"));
        Assertions.assertEquals(List.of(), callable("PersonSummary(String first, String last)"),
                "a constructor's first word is the type's own name, not a list");
    }

    @Test
    void refusesACallableItCannotReadFaithfully() {
        Assertions.assertNull(callable("<T> @NotNull T annotated()"),
                "a type-use annotation between the list and the return type is a shape this reader refuses");
        Assertions.assertNull(callable("<T T value()"), "a list with no closing '>' is refused");
        Assertions.assertNull(callable("<T,> T value()"), "a trailing comma is not a name");
        Assertions.assertNull(callable("<T extends> T value()"),
                "a bound with no type after `extends` is refused");
        Assertions.assertNull(callable("+broken"),
                "text that does not start a declaration cannot be read as one");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static List<String> typeParameters(String declaration, String simpleName, String kind) {
        return EntityMetadataGenerator.DeclarationReader.typeParameters(declaration, simpleName, kind);
    }

    private static List<String> callable(String declaration) {
        return EntityMetadataGenerator.DeclarationReader.callableTypeParameters(declaration);
    }
}
