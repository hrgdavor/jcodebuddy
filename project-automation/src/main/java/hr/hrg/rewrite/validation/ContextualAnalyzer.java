// {@link hr.hrg.rewrite.validation.ContextualAnalyzer} Performs contextual analysis.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.*;

/**
 * Performs contextual analysis on source files.
 *
 * <p>Analyzes the context around entities, methods, and fields to provide
 * additional information about relationships and dependencies.</p>
 *
 * <p>Original JavaParser location: java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class ContextualAnalyzer {

    /**
     * Analyze a source file for contextual information.
     *
     * @param sourceFile The source file AST
     * @return Analysis result
     */
    public AnalysisResult analyze(SourceFile sourceFile) {
        if (sourceFile == null) {
            return AnalysisResult.ok(Map.of(
                    "status", "error",
                    "message", "Source file is null"
            ));
        }

        Map<String, Object> analysis = new HashMap<>();

        try {
            // Analyze classes
            Map<String, Object> classes = analyzeClasses(sourceFile);
            analysis.put("classes", classes);

            // Analyze methods
            Map<String, List<Map<String, Object>>> methods = analyzeMethods(sourceFile);
            analysis.put("methods", methods);

            // Analyze fields
            Map<String, List<Map<String, Object>>> fields = analyzeFields(sourceFile);
            analysis.put("fields", fields);

            // Analyze annotations
            Map<String, List<Map<String, Object>>> annotations = analyzeAnnotations(sourceFile);
            analysis.put("annotations", annotations);

            // Analyze dependencies
            Map<String, Object> dependencies = analyzeDependencies(sourceFile);
            analysis.put("dependencies", dependencies);

            return AnalysisResult.ok(analysis);
        } catch (Exception e) {
            return AnalysisResult.ok(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Analyze classes in a source file.
     */
    private Map<String, Object> analyzeClasses(SourceFile sourceFile) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> classList = new ArrayList<>();

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                Map<String, Object> classInfo = new HashMap<>();
                classInfo.put("name", classTree.getIdentifier().getNameAsString());
                classInfo.put("isAbstract", classTree.getModifiers().contains(Modifiers.ABSTRACT));
                classInfo.put("isInterface", false);
                classInfo.put("isEnum", false);
                classInfo.put("annotations", extractAnnotationNames(classTree));
                classList.add(classInfo);
            } else if (type instanceof InterfaceTree interfaceTree) {
                Map<String, Object> interfaceInfo = new HashMap<>();
                interfaceInfo.put("name", interfaceTree.getIdentifier().getNameAsString());
                interfaceInfo.put("isAbstract", false);
                interfaceInfo.put("isInterface", true);
                interfaceInfo.put("isEnum", false);
                interfaceInfo.put("annotations", extractAnnotationNames(interfaceTree));
                classList.add(interfaceInfo);
            } else if (type instanceof EnumDeclarationTree enumTree) {
                Map<String, Object> enumInfo = new HashMap<>();
                enumInfo.put("name", enumTree.getIdentifier().getNameAsString());
                enumInfo.put("isAbstract", false);
                enumInfo.put("isInterface", false);
                enumInfo.put("isEnum", true);
                enumInfo.put("annotations", extractAnnotationNames(enumTree));
                classList.add(enumInfo);
            }
        }

        result.put("count", classList.size());
        result.put("classes", classList);
        return result;
    }

    /**
     * Analyze methods in a source file.
     */
    private Map<String, List<Map<String, Object>>> analyzeMethods(SourceFile sourceFile) {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        List<Map<String, Object>> methodList = new ArrayList<>();

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                for (MethodTree method : classTree.getMethods()) {
                    Map<String, Object> methodInfo = new HashMap<>();
                    methodInfo.put("name", method.getNameAsString());
                    methodInfo.put("returnType", method.getReturnType().describe());
                    methodInfo.put("annotations", extractAnnotationNames(method));
                    methodList.add(methodInfo);
                }
            } else if (type instanceof InterfaceTree interfaceTree) {
                for (MethodTree method : interfaceTree.getMethods()) {
                    Map<String, Object> methodInfo = new HashMap<>();
                    methodInfo.put("name", method.getNameAsString());
                    methodInfo.put("returnType", method.getReturnType().describe());
                    methodInfo.put("annotations", extractAnnotationNames(method));
                    methodList.add(methodInfo);
                }
            }
        }

        result.put("count", methodList.size());
        result.put("methods", methodList);
        return result;
    }

    /**
     * Analyze fields in a source file.
     */
    private Map<String, List<Map<String, Object>>> analyzeFields(SourceFile sourceFile) {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        List<Map<String, Object>> fieldList = new ArrayList<>();

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                for (FieldTree field : classTree.getFields()) {
                    Map<String, Object> fieldInfo = new HashMap<>();
                    fieldInfo.put("name", field.getNameAsString());
                    fieldInfo.put("type", field.getType().describe());
                    fieldInfo.put("modifiers", extractModifiers(field.getModifiers()));
                    fieldInfo.put("annotations", extractAnnotationNames(field));
                    fieldList.add(fieldInfo);
                }
            }
        }

        result.put("count", fieldList.size());
        result.put("fields", fieldList);
        return result;
    }

    /**
     * Analyze annotations in a source file.
     */
    private Map<String, List<Map<String, Object>>> analyzeAnnotations(SourceFile sourceFile) {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        List<Map<String, Object>> annotationList = new ArrayList<>();

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                for (AnnotationTree annotation : classTree.getLeadingAnnotations()) {
                    annotationList.add(extractAnnotationInfo(annotation));
                }
                for (AnnotationTree annotation : classTree.getTrailingAnnotations()) {
                    annotationList.add(extractAnnotationInfo(annotation));
                }

                for (MethodTree method : classTree.getMethods()) {
                    for (AnnotationTree annotation : method.getLeadingAnnotations()) {
                        annotationList.add(extractAnnotationInfo(annotation));
                    }
                    for (AnnotationTree annotation : method.getTrailingAnnotations()) {
                        annotationList.add(extractAnnotationInfo(annotation));
                    }
                }

                for (FieldTree field : classTree.getFields()) {
                    for (AnnotationTree annotation : field.getLeadingAnnotations()) {
                        annotationList.add(extractAnnotationInfo(annotation));
                    }
                    for (AnnotationTree annotation : field.getTrailingAnnotations()) {
                        annotationList.add(extractAnnotationInfo(annotation));
                    }
                }
            }
        }

        result.put("count", annotationList.size());
        result.put("annotations", annotationList);
        return result;
    }

    /**
     * Extract annotation info.
     */
    private Map<String, Object> extractAnnotationInfo(AnnotationTree annotation) {
        Map<String, Object> info = new HashMap<>();
        TypeTree annotationType = annotation.getAnnotation();
        if (annotationType instanceof IdentifierTree identifier) {
            info.put("name", identifier.getQualid().getNameAsString());
            info.put("arguments", annotation.getArguments().size());
        } else {
            info.put("name", annotationType.describe());
            info.put("arguments", annotation.getArguments().size());
        }
        return info;
    }

    /**
     * Extract annotation names from a type tree.
     */
    private List<String> extractAnnotationNames(TypeTree type) {
        List<String> names = new ArrayList<>();
        if (type instanceof ClassTree classTree) {
            for (AnnotationTree annotation : classTree.getLeadingAnnotations()) {
                names.add(extractAnnotationName(annotation.getAnnotation()));
            }
            for (AnnotationTree annotation : classTree.getTrailingAnnotations()) {
                names.add(extractAnnotationName(annotation.getAnnotation()));
            }

            for (MethodTree method : classTree.getMethods()) {
                for (AnnotationTree annotation : method.getLeadingAnnotations()) {
                    names.add(extractAnnotationName(annotation.getAnnotation()));
                }
                for (AnnotationTree annotation : method.getTrailingAnnotations()) {
                    names.add(extractAnnotationName(annotation.getAnnotation()));
                }
            }

            for (FieldTree field : classTree.getFields()) {
                for (AnnotationTree annotation : field.getLeadingAnnotations()) {
                    names.add(extractAnnotationName(annotation.getAnnotation()));
                }
                for (AnnotationTree annotation : field.getTrailingAnnotations()) {
                    names.add(extractAnnotationName(annotation.getAnnotation()));
                }
            }
        }
        return names;
    }

    /**
     * Extract annotation name.
     */
    private String extractAnnotationName(TypeTree annotationType) {
        if (annotationType instanceof IdentifierTree identifier) {
            return identifier.getQualid().getNameAsString();
        }
        return annotationType.describe();
    }

    /**
     * Extract modifiers as a comma-separated string.
     */
    private String extractModifiers(Iterable<Modifier> modifiers) {
        List<String> modList = new ArrayList<>();
        if (modifiers instanceof List list) {
            for (Modifier mod : list) {
                modList.add(mod.toString());
            }
        }
        return String.join(", ", modList);
    }

    /**
     * Analyze dependencies in a source file.
     */
    private Map<String, Object> analyzeDependencies(SourceFile sourceFile) {
        Map<String, Object> result = new HashMap<>();

        // This is a simplified implementation
        // In a real implementation, you would analyze import statements
        // and method calls to determine dependencies

        result.put("externalImports", 0);
        result.put("internalDependencies", 0);
        return result;
    }
}
