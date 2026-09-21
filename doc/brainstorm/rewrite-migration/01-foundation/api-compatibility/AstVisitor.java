// {@link hr.hrg.rewrite.api.AstVisitor} Base visitor interface for OpenRewrite migration.
// {enabled:true, blockMarker: "delete to regen"}
package hr.hrg.rewrite.api;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

/**
 * Base visitor interface for traversing OpenRewrite AST nodes.
 * Extends JavaIsoVisitor to provide type-safe visitor pattern.
 */
public abstract class AstVisitor<R, P> extends JavaIsoVisitor<R, P> {
    
    @Override
    public R visitIdentifier(JCIdentifier identifier, P p) {
        return super.visitIdentifier(identifier, p);
    }
    
    @Override
    public R visitLiteral(JCLiteral literal, P p) {
        return super.visitLiteral(literal, p);
    }
    
    @Override
    public R visitTypeParameter(JCTypeParameter typeParameter, P p) {
        return super.visitTypeParameter(typeParameter, p);
    }
    
    @Override
    public R visitParameterizedType(JCTypeParameterizedType type, P p) {
        return super.visitParameterizedType(type, p);
    }
    
    @Override
    public R visitTypeArgument(JCTypeArgument argument, P p) {
        return super.visitTypeArgument(argument, p);
    }
    
    @Override
    public R visitTypeAlias(JCTypeAlias alias, P p) {
        return super.visitTypeAlias(alias, p);
    }
    
    @Override
    public R visitIntersectionType(JCIntersectionType type, P p) {
        return super.visitIntersectionType(type, p);
    }
    
    @Override
    public R visitUnionType(JCUnionType type, P p) {
        return super.visitUnionType(type, p);
    }
    
    @Override
    public R visitArrayType(JCArrayType type, P p) {
        return super.visitArrayType(type, p);
    }
    
    @Override
    public R visitMethodInvocation(JCMethodInvocation invocation, P p) {
        return super.visitMethodInvocation(invocation, p);
    }
    
    @Override
    public R visitExecution(JCExecution invocation, P p) {
        return super.visitExecution(invocation, p);
    }
    
    @Override
    public R visitNewClass(JCNewClass creation, P p) {
        return super.visitNewClass(creation, p);
    }
    
    @Override
    public R visitSelector(JCFieldAccess selector, P p) {
        return super.visitSelector(selector, p);
    }
    
    @Override
    public R visitConditional(JCConditional condition, P p) {
        return super.visitConditional(condition, p);
    }
    
    @Override
    public R visitUnary(JCUnary expression, P p) {
        return super.visitUnary(expression, p);
    }
    
    @Override
    public R visitBinary(JCBinary expression, P p) {
        return super.visitBinary(expression, p);
    }
    
    @Override
    public R visitAssignment(JCAssignment assignment, P p) {
        return super.visitAssignment(assignment, p);
    }
    
    @Override
    public R visitVariable(JCVariable var, P p) {
        return super.visitVariable(var, p);
    }
    
    @Override
    public R visitBlock(JCBlock block, P p) {
        return super.visitBlock(block, p);
    }
    
    @Override
    public R visitLabelled(JCLabelled label, P p) {
        return super.visitLabelled(label, p);
    }
    
    @Override
    public R visitExpressionStatement(JCExpressionStatement expr, P p) {
        return super.visitExpressionStatement(expr, p);
    }
    
    @Override
    public R visitThrow(JCThrow throwStmt, P p) {
        return super.visitThrow(throwStmt, p);
    }
    
    @Override
    public R visitEnhancedFor(JCEnhancedFor forStmt, P p) {
        return super.visitEnhancedFor(forStmt, p);
    }
    
    @Override
    public R visitTry(JCTry tryStmt, P p) {
        return super.visitTry(tryStmt, p);
    }
    
    @Override
    public R visitCatch(JCCatch catchClause, P p) {
        return super.visitCatch(catchClause, p);
    }
    
    @Override
    public R visitSynchronized(JCSynchronized sync, P p) {
        return super.visitSynchronized(sync, p);
    }
    
    @Override
    public R visitWhile(JCWhile whileStmt, P p) {
        return super.visitWhile(whileStmt, p);
    }
    
    @Override
    public R visitDo(JCDo whileStmt, P p) {
        return super.visitDo(whileStmt, p);
    }
    
    @Override
    public R visitFor(JCFor forStmt, P p) {
        return super.visitFor(forStmt, p);
    }
    
    @Override
    public R visitIf(JCIf ifStmt, P p) {
        return super.visitIf(ifStmt, p);
    }
    
    @Override
    public R visitSwitch(JCSwitch switchStmt, P p) {
        return super.visitSwitch(switchStmt, p);
    }
    
    @Override
    public R visitCase(JCCase caseStmt, P p) {
        return super.visitCase(caseStmt, p);
    }
    
    @Override
    public R visitDefault(JCDefault defaultStmt, P p) {
        return super.visitDefault(defaultStmt, p);
    }
    
    @Override
    public R visitBreak(JCBreak breakStmt, P p) {
        return super.visitBreak(breakStmt, p);
    }
    
    @Override
    public R visitContinue(JCContinue cont, P p) {
        return super.visitContinue(cont, p);
    }
    
    @Override
    public R visitReturn(JCReturn ret, P p) {
        return super.visitReturn(ret, p);
    }
    
    @Override
    public R visitLabeledStatement(JCLabelled label, P p) {
        return super.visitLabeledStatement(label, p);
    }
    
    @Override
    public R visitClassDeclaration(JCClassDecl classDecl, P p) {
        return super.visitClassDeclaration(classDecl, p);
    }
    
    @Override
    public R visitInterfaceDeclaration(JCInterfaceDecl iface, P p) {
        return super.visitInterfaceDeclaration(iface, p);
    }
    
    @Override
    public R visitRecordDeclaration(JCRecordDecl record, P p) {
        return super.visitRecordDeclaration(record, p);
    }
    
    @Override
    public R visitAnnotation(JCAnnotation annotation, P p) {
        return super.visitAnnotation(annotation, p);
    }
    
    @Override
    public R visitTypeAnnotation(JCTypeAnnotation typeAnno, P p) {
        return super.visitTypeAnnotation(typeAnno, p);
    }
    
    @Override
    public R visitTypeParameter(JCTypeParameter typeParam, P p) {
        return super.visitTypeParameter(typeParam, p);
    }
    
    @Override
    public R visitAnnotationUse(JCAnnotationUse annotation, P p) {
        return super.visitAnnotationUse(annotation, p);
    }
    
    @Override
    public R visitRecordComponent(JCRecordComponent field, P p) {
        return super.visitRecordComponent(field, p);
    }
    
    @Override
    public R visitMethodDeclaration(JCMethodDecl method, P p) {
        return super.visitMethodDeclaration(method, p);
    }
    
    @Override
    public R visitMethodInvocation(JCMethodInvocation invocation, P p) {
        return super.visitMethodInvocation(invocation, p);
    }
    
    @Override
    public R visitLambda(JCLambda lambda, P p) {
        return super.visitLambda(lambda, p);
    }
    
    @Override
    public R visitConstructorDeclaration(JCMethodDecl constructor, P p) {
        return super.visitMethodDeclaration(constructor, p);
    }
    
    @Override
    public R visitArrayType(JCArrayType type, P p) {
        return super.visitArrayType(type, p);
    }
    
    @Override
    public R visitVariable(JCVariable var, P p) {
        return super.visitVariable(var, p);
    }
    
    @Override
    public R visitType(JCType type, P p) {
        return super.visitType(type, p);
    }
    
    @Override
    public R visitAnnotation(JCAnnotation annotation, P p) {
        return super.visitAnnotation(annotation, p);
    }
    
    @Override
    public R visitTypeParameter(JCTypeParameter param, P p) {
        return super.visitTypeParameter(param, p);
    }
    
    @Override
    public R visitRecordComponent(JCRecordComponent record, P p) {
        return super.visitRecordComponent(record, p);
    }
    
    @Override
    public R visitRecordDeclaration(JCRecordDecl decl, P p) {
        return super.visitRecordDeclaration(decl, p);
    }
    
    @Override
    public R visitEnumConstant(JCEnclosingEnvironment env, P p) {
        return super.visitEnumConstant(env, p);
    }
    
    @Override
    public R visitEnumConstant(JCIdentifier id, P p) {
        return super.visitEnumConstant(id, p);
    }
    
    @Override
    public R visitEnumDeclaration(JCClassDecl enumDecl, P p) {
        return super.visitClassDeclaration(enumDecl, p);
    }
    
    @Override
    public R visitCompilationUnit(JCCompilationUnit unit, P p) {
        return super.visitCompilationUnit(unit, p);
    }
    
    @Override
    public R visitImport(JCImport importStmt, P p) {
        return super.visitImport(importStmt, p);
    }
    
    @Override
    public R visitLiteral(JCLiteral literal, P p) {
        return super.visitLiteral(literal, p);
    }
    
    @Override
    public R visitBinary(JCBinary binary, P p) {
        return super.visitBinary(binary, p);
    }
    
    @Override
    public R visitUnary(JCUnary unary, P p) {
        return super.visitUnary(unary, p);
    }
    
    @Override
    public R visitArrayInitializer(JCArrayInitializer init, P p) {
        return super.visitArrayInitializer(init, p);
    }
    
    @Override
    public R visitVarargs(JCVarargs varargs, P p) {
        return super.visitVarargs(varargs, p);
    }
    
    @Override
    public R visitExpressionStatement(JCExpressionStatement stmt, P p) {
        return super.visitExpressionStatement(stmt, p);
    }
    
    @Override
    public R visitThrow(JCThrow throwStmt, P p) {
        return super.visitThrow(throwStmt, p);
    }
    
    @Override
    public R visitBlock(JCBlock block, P p) {
        return super.visitBlock(block, p);
    }
    
    @Override
    public R visitLabeledStatement(JCLabelled label, P p) {
        return super.visitLabeledStatement(label, p);
    }
    
    @Override
    public R visitStatement(JCStatement stmt, P p) {
        return super.visitStatement(stmt, p);
    }
    
    @Override
    public R visitExpression(JCExpression expr, P p) {
        return super.visitExpression(expr, p);
    }
    
    @Override
    public R visitType(JCType type, P p) {
        return super.visitType(type, p);
    }
    
    @Override
    public R visitCompilationUnit(JCCompilationUnit unit, P p) {
        return super.visitCompilationUnit(unit, p);
    }
    
    @Override
    public R visitFieldAccess(JCFieldAccess field, P p) {
        return super.visitFieldAccess(field, p);
    }
    
    @Override
    public R visitMethodInvocation(JCMethodInvocation invocation, P p) {
        return super.visitMethodInvocation(invocation, p);
    }
    
    @Override
    public R visitType(JCType type, P p) {
        return super.visitType(type, p);
    }
}
