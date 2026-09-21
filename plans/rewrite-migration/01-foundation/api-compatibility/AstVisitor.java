// {@link hr.hrg.rewrite.api.AstVisitor} Base visitor interface for OpenRewrite AST traversal.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.api;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import java.util.List;
import java.util.Set;

/**
 * Base visitor interface for traversing OpenRewrite Java AST.
 * 
 * This class provides a type-safe visitor pattern for traversing
 * Java source code represented as a tree structure in OpenRewrite.
 * 
 * @param <R> The result type of the visit method
 * @param <P> The visitor parameter type
 */
public abstract class AstVisitor<R, P> extends JavaIsoVisitor<R, P> {

    /**
     * Default implementation that visits child nodes first, then handles the node itself.
     */
    @Override
    public R visitToken(Token tree, P p) {
        return super.visitToken(tree, p);
    }

    @Override
    public R visitNumberLiteral(NumberLiteral tree, P p) {
        return super.visitNumberLiteral(tree, p);
    }

    @Override
    public R visitCharacterLiteral(CharacterLiteral tree, P p) {
        return super.visitCharacterLiteral(tree, p);
    }

    @Override
    public R visitStringLiteral(StringLiteral tree, P p) {
        return super.visitStringLiteral(tree, p);
    }

    @Override
    public R visitEscapedString(EscapedString tree, P p) {
        return super.visitEscapedString(tree, p);
    }

    @Override
    public R visitIdentifier(Identifier tree, P p) {
        return super.visitIdentifier(tree, p);
    }

    @Override
    public R visitLiteral(Literal tree, P p) {
        return super.visitLiteral(tree, p);
    }

    @Override
    public R visitTypeParameter(TypeParameter tree, P p) {
        return super.visitTypeParameter(tree, p);
    }

    @Override
    public R visitTypeParameterList(TypeParameterList tree, P p) {
        return super.visitTypeParameterList(tree, p);
    }

    @Override
    public R visitTypeArguments(TypeArguments tree, P p) {
        return super.visitTypeArguments(tree, p);
    }

    @Override
    public R visitTypeAnnotation(TypeAnnotation tree, P p) {
        return super.visitTypeAnnotation(tree, p);
    }

    @Override
    public R visitIntersectionType(IntersectionType tree, P p) {
        return super.visitIntersectionType(tree, p);
    }

    @Override
    public R visitUnionType(UnionType tree, P p) {
        return super.visitUnionType(tree, p);
    }

    @Override
    public R visitQualifiedType(QualifiedType tree, P p) {
        return super.visitQualifiedType(tree, p);
    }

    @Override
    public R visitPrimitiveTypeReference(PrimitiveTypeReference tree, P p) {
        return super.visitPrimitiveTypeReference(tree, p);
    }

    @Override
    public R visitClassTypeReference(ClassTypeReference tree, P p) {
        return super.visitClassTypeReference(tree, p);
    }

    @Override
    public R visitParenthesizedType(ParenthesizedType tree, P p) {
        return super.visitParenthesizedType(tree, p);
    }

    @Override
    public R visitArrayTypeReference(ArrayTypeReference tree, P p) {
        return super.visitArrayTypeReference(tree, p);
    }

    @Override
    public R visitWildcardTypeReference(WildcardTypeReference tree, P p) {
        return super.visitWildcardTypeReference(tree, p);
    }

    @Override
    public R visitParameterizedTypeReference(ParameterizedTypeReference tree, P p) {
        return super.visitParameterizedTypeReference(tree, p);
    }

    @Override
    public R visitAnnotation(Annotation tree, P p) {
        return super.visitAnnotation(tree, p);
    }

    @Override
    public R visitAnnotationMemberValue(AnnotationMemberValue tree, P p) {
        return super.visitAnnotationMemberValue(tree, p);
    }

    @Override
    public R visitAnnotationValue(AnnotationValue tree, P p) {
        return super.visitAnnotationValue(tree, p);
    }

    @Override
    public R visitAnnotationArgs(AnnotationArgs tree, P p) {
        return super.visitAnnotationArgs(tree, p);
    }

    @Override
    public R visitAnnotationArg(AnnotationArg tree, P p) {
        return super.visitAnnotationArg(tree, p);
    }

    @Override
    public R visitAnnotationArgsMarker(AnnotationArgsMarker tree, P p) {
        return super.visitAnnotationArgsMarker(tree, p);
    }

    @Override
    public R visitAnnotationArgsList(AnnotationArgsList tree, P p) {
        return super.visitAnnotationArgsList(tree, p);
    }

    @Override
    public R visitAnnotationClassRef(AnnotationClassRef tree, P p) {
        return super.visitAnnotationClassRef(tree, p);
    }

    @Override
    public R visitAnnotationMarker(AnnotationMarker tree, P p) {
        return super.visitAnnotationMarker(tree, p);
    }

    @Override
    public R visitAnnotationMarkers(AnnotationMarkers tree, P p) {
        return super.visitAnnotationMarkers(tree, p);
    }

    @Override
    public R visitAnnotationArgs(AnnotationArgs tree, P p) {
        return super.visitAnnotationArgs(tree, p);
    }

    @Override
    public R visitAnnotationArg(AnnotationArg tree, P p) {
        return super.visitAnnotationArg(tree, p);
    }

    @Override
    public R visitAnnotationMemberValue(AnnotationMemberValue tree, P p) {
        return super.visitAnnotationMemberValue(tree, p);
    }

    @Override
    public R visitAnnotationValue(AnnotationValue tree, P p) {
        return super.visitAnnotationValue(tree, p);
    }

    @Override
    public R visitName(Name tree, P p) {
        return super.visitName(tree, p);
    }

    @Override
    public R visitSimpleName(Name tree, P p) {
        return super.visitSimpleName(tree, p);
    }

    @Override
    public R visitFullyQualifiedName(FullyQualifiedName tree, P p) {
        return super.visitFullyQualifiedName(tree, p);
    }

    @Override
    public R visitNameReference(NameReference tree, P p) {
        return super.visitNameReference(tree, p);
    }

    @Override
    public R visitFieldAccess(FieldAccess tree, P p) {
        return super.visitFieldAccess(tree, p);
    }

    @Override
    public R visitDotQualifiedExpression(DotQualifiedExpression tree, P p) {
        return super.visitDotQualifiedExpression(tree, p);
    }

    @Override
    public R visitThisReference(This tree, P p) {
        return super.visitThis(tree, p);
    }

    @Override
    public R visitImplicitReference(Implicit tree, P p) {
        return super.visitImplicit(tree, p);
    }

    @Override
    public R visitNewClass(NewClass tree, P p) {
        return super.visitNewClass(tree, p);
    }

    @Override
    public R visitObjectCreation(ObjectCreation tree, P p) {
        return super.visitObjectCreation(tree, p);
    }

    @Override
    public R visitAnonymousClassCreation(AnonymousClassCreation tree, P p) {
        return super.visitAnonymousClassCreation(tree, p);
    }

    @Override
    public R visitRecordCreation(RecordCreation tree, P p) {
        return super.visitRecordCreation(tree, p);
    }

    @Override
    public R visitTypeCast(TypeCast tree, P p) {
        return super.visitTypeCast(tree, p);
    }

    @Override
    public R visitUnary(Unary tree, P p) {
        return super.visitUnary(tree, p);
    }

    @Override
    public R visitBinary(Binary tree, P p) {
        return super.visitBinary(tree, p);
    }

    @Override
    public R visitAssignment(Assignment tree, P p) {
        return super.visitAssignment(tree, p);
    }

    @Override
    public R visitCompoundAssignment(CompoundAssignment tree, P p) {
        return super.visitCompoundAssignment(tree, p);
    }

    @Override
    public R visitConditionalExpression(ConditionalExpression tree, P p) {
        return super.visitConditionalExpression(tree, p);
    }

    @Override
    public R visitConditional(Conditional tree, P p) {
        return super.visitConditional(tree, p);
    }

    @Override
    public R visitConditionalBinary(ConditionalBinary tree, P p) {
        return super.visitConditionalBinary(tree, p);
    }

    @Override
    public R visitConditionalTernary(ConditionalTernary tree, P p) {
        return super.visitConditionalTernary(tree, p);
    }

    @Override
    public R visitConditionalSwitch(ConditionalSwitch tree, P p) {
        return super.visitConditionalSwitch(tree, p);
    }

    @Override
    public R visitConditionalIf(ConditionalIf tree, P p) {
        return super.visitConditionalIf(tree, p);
    }

    @Override
    public R visitConditionalElse(ConditionalElse tree, P p) {
        return super.visitConditionalElse(tree, p);
    }

    @Override
    public R visitConditionalCatch(ConditionalCatch tree, P p) {
        return super.visitConditionalCatch(tree, p);
    }

    @Override
    public R visitConditionalFinally(ConditionalFinally tree, P p) {
        return super.visitConditionalFinally(tree, p);
    }

    @Override
    public R visitConditionalTry(ConditionalTry tree, P p) {
        return super.visitConditionalTry(tree, p);
    }

    @Override
    public R visitConditionalWith(ConditionalWith tree, P p) {
        return super.visitConditionalWith(tree, p);
    }

    @Override
    public R visitConditionalAssert(ConditionalAssert tree, P p) {
        return super.visitConditionalAssert(tree, p);
    }

    @Override
    public R visitConditionalSynchronized(ConditionalSynchronized tree, P p) {
        return super.visitConditionalSynchronized(tree, p);
    }

    @Override
    public R visitConditionalVolatile(ConditionalVolatile tree, P p) {
        return super.visitConditionalVolatile(tree, p);
    }

    @Override
    public R visitConditionalAtomic(ConditionalAtomic tree, P p) {
        return super.visitConditionalAtomic(tree, p);
    }

    @Override
    public R visitConditionalVarHandle(ConditionalVarHandle tree, P p) {
        return super.visitConditionalVarHandle(tree, p);
    }

    @Override
    public R visitConditionalVarHandleField(ConditionalVarHandleField tree, P p) {
        return super.visitConditionalVarHandleField(tree, p);
    }

    @Override
    public R visitConditionalVarHandleInvoke(ConditionalVarHandleInvoke tree, P p) {
        return super.visitConditionalVarHandleInvoke(tree, p);
    }

    @Override
    public R visitConditionalVarHandleGet(ConditionalVarHandleGet tree, P p) {
        return super.visitConditionalVarHandleGet(tree, p);
    }

    @Override
    public R visitConditionalVarHandlePut(ConditionalVarHandlePut tree, P p) {
        return super.visitConditionalVarHandlePut(tree, p);
    }

    @Override
    public R visitConditionalVarHandleCompareAndSet(ConditionalVarHandleCompareAndSet tree, P p) {
        return super.visitConditionalVarHandleCompareAndSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleSet(ConditionalVarHandleSet tree, P p) {
        return super.visitConditionalVarHandleSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleGetAndSet(ConditionalVarHandleGetAndSet tree, P p) {
        return super.visitConditionalVarHandleGetAndSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleCompareAndSwap(ConditionalVarHandleCompareAndSwap tree, P p) {
        return super.visitConditionalVarHandleCompareAndSwap(tree, p);
    }

    @Override
    public R visitConditionalVarHandleCompareAndSet(ConditionalVarHandleCompareAndSet tree, P p) {
        return super.visitConditionalVarHandleCompareAndSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleGetAndSet(ConditionalVarHandleGetAndSet tree, P p) {
        return super.visitConditionalVarHandleGetAndSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleCompareAndSwap(ConditionalVarHandleCompareAndSwap tree, P p) {
        return super.visitConditionalVarHandleCompareAndSwap(tree, p);
    }

    @Override
    public R visitConditionalVarHandleGetAndSet(ConditionalVarHandleGetAndSet tree, P p) {
        return super.visitConditionalVarHandleGetAndSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleCompareAndSwap(ConditionalVarHandleCompareAndSwap tree, P p) {
        return super.visitConditionalVarHandleCompareAndSwap(tree, p);
    }

    @Override
    public R visitConditionalVarHandleGetAndSet(ConditionalVarHandleGetAndSet tree, P p) {
        return super.visitConditionalVarHandleGetAndSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleCompareAndSwap(ConditionalVarHandleCompareAndSwap tree, P p) {
        return super.visitConditionalVarHandleCompareAndSwap(tree, p);
    }

    @Override
    public R visitConditionalVarHandleGetAndSet(ConditionalVarHandleGetAndSet tree, P p) {
        return super.visitConditionalVarHandleGetAndSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleCompareAndSwap(ConditionalVarHandleCompareAndSwap tree, P p) {
        return super.visitConditionalVarHandleCompareAndSwap(tree, p);
    }

    @Override
    public R visitConditionalVarHandleGetAndSet(ConditionalVarHandleGetAndSet tree, P p) {
        return super.visitConditionalVarHandleGetAndSet(tree, p);
    }

    @Override
    public R visitConditionalVarHandleCompareAndSwap(ConditionalVarHandleCompareAndSwap tree, P p) {
        return super.visitConditionalVarHandleCompareAndSwap(tree, p);
    }

    @Override
    public R visitConditionalVarHandleGetAndSet(ConditionalVarHandleGetAndSet tree, P p) {
        return super.visitConditionalVarHandleGetAndSet(tree, p);
    }
}
