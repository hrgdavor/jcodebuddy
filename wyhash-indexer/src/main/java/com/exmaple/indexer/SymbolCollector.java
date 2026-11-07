package com.example.indexer;

import org.openrewrite.java.tree.J;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.TypeUtils;

public class SymbolCollector extends JavaIsoVisitor<FileIndex> {

    @Override
    public J.Import visitImport(J.Import _import, FileIndex fileIndex) {
        String q = _import.getTypeName().toString();
        fileIndex.addSymbol(q);
        fileIndex.addFine("IMPORT:" + q);
        return super.visitImport(_import, fileIndex);
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, FileIndex fileIndex) {
        try {
            String name = method.getSimpleName();
            if (method.getMethodType() != null && method.getMethodType().getDeclaringType() != null) {
                String owner = method.getMethodType().getDeclaringType().getFullyQualifiedName();
                if (owner != null && !owner.isEmpty()) {
                    fileIndex.addSymbol(owner + "." + name);
                    fileIndex.addFine(owner + "." + name + method.getMethodType().getSignature());
                } else {
                    fileIndex.addSymbol(name);
                }
            } else {
                fileIndex.addSymbol(name);
            }
        } catch (Exception ignore) {}
        return super.visitMethodInvocation(method, fileIndex);
    }

    @Override
    public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, FileIndex fileIndex) {
        try {
            String name = fieldAccess.getSimpleName();
            var target = fieldAccess.getTarget();
            if (target != null && target.getType() != null) {
                var fq = TypeUtils.asFullyQualified(target.getType());
                if (fq != null) {
                    String owner = fq.getFullyQualifiedName();
                    fileIndex.addSymbol(owner + "." + name);
                    fileIndex.addSymbol(owner);
                }
            }
        } catch (Exception ignore) {}
        return super.visitFieldAccess(fieldAccess, fileIndex);
    }

    @Override
    public J.NewClass visitNewClass(J.NewClass newClass, FileIndex fileIndex) {
        try {
            if (newClass.getType() != null) {
                var fq = TypeUtils.asFullyQualified(newClass.getType());
                if (fq != null) fileIndex.addSymbol(fq.getFullyQualifiedName());
            }
        } catch (Exception ignore) {}
        return super.visitNewClass(newClass, fileIndex);
    }

    @Override
    public J.ParameterizedType visitParameterizedType(J.ParameterizedType type, FileIndex fileIndex) {
        try {
            if (type.getType() != null) {
                var fq = TypeUtils.asFullyQualified(type.getType());
                if (fq != null) fileIndex.addSymbol(fq.getFullyQualifiedName());
            }
        } catch (Exception ignore) {}
        return super.visitParameterizedType(type, fileIndex);
    }
}
