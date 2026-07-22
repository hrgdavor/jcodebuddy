package hr.hrg.hipster.entity.tooling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import hr.hrg.hipster.entity.tooling.meta.EntityFieldMeta;
import hr.hrg.hipster.entity.tooling.meta.EntityMeta;
import hr.hrg.hipster.entity.tooling.meta.InterfaceInfo;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.TypeDescriptor;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class EntityMetadataGenerator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static EntityMeta fromJson(String json) throws java.io.IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);

        String entityName = root.path("entityName").asText();
        String packageName = root.path("package").asText();
        String markerInterface = root.path("markerInterface").asText();
        String idType = root.path("idType").asText();

        List<ViewMeta> views = new ArrayList<>();
        for (JsonNode viewNode : root.path("views")) {
            String viewName = viewNode.path("name").asText();
            List<String> extendsTypes = new ArrayList<>();
            for (JsonNode ext : viewNode.path("extends")) {
                extendsTypes.add(ext.asText());
            }
            Boolean read = viewNode.has("read") ? viewNode.path("read").asBoolean() : null;
            Boolean write = viewNode.has("write") ? viewNode.path("write").asBoolean() : null;
            int viewLineNumber = viewNode.path("lineNumber").asInt(-1);

            List<Property> properties = new ArrayList<>();
            for (JsonNode propNode : viewNode.path("properties")) {
                String name = propNode.path("name").asText();
                String type = parseTypeText(propNode.path("type"));
                String fieldKind = propNode.has("fieldKind") ? propNode.path("fieldKind").asText(null) : null;
                String column = propNode.has("column") ? propNode.path("column").asText(null) : null;
                String relation = propNode.has("relation") ? propNode.path("relation").asText(null) : null;
                String expression = propNode.has("expression") ? propNode.path("expression").asText(null) : null;
                int lineNumber = propNode.path("lineNumber").asInt(-1);
                properties.add(new Property(name, type, fieldKind, column, relation, expression, lineNumber));
            }
            views.add(new ViewMeta(viewName, extendsTypes, read, write, properties, viewLineNumber));
        }

        List<EntityFieldMeta> allFields = new ArrayList<>();
        for (JsonNode fieldNode : root.path("allFields")) {
            String name = fieldNode.path("name").asText();
            String type = parseTypeText(fieldNode.path("type"));
            String fieldKind = fieldNode.path("fieldKind").asText(null);
            String column = fieldNode.path("column").asText(null);
            String relation = fieldNode.path("relation").asText(null);
            String expression = fieldNode.path("expression").asText(null);
            int lineNumber = fieldNode.path("lineNumber").asInt(-1);

            List<String> fieldViews = new ArrayList<>();
            for (JsonNode v : fieldNode.path("views")) {
                fieldViews.add(v.asText());
            }

            Map<String,String> typeByView = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = fieldNode.path("typeByView").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                typeByView.put(entry.getKey(), parseTypeText(entry.getValue()));
            }

            allFields.add(new EntityFieldMeta(name, type, fieldKind, column, relation, expression, lineNumber, fieldViews, typeByView));
        }

        return new EntityMeta(entityName, packageName, markerInterface, idType, views, allFields);
    }

    private static String parseTypeText(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return typeNode.asText();
        }
        if (typeNode.isObject()) {
            return typeNode.path("type").asText();
        }
        return null;
    }

    // metadata classes are now top-level in same package (Property, ViewMeta, EntityMeta, EntityFieldMeta)

    private static TypeDescriptor parseTypeDescriptor(String rawType) {
        String type = rawType.trim();
        boolean array = false;
        while (type.endsWith("[]")) {
            array = true;
            type = type.substring(0, type.length() - 2).trim();
        }

        int genericStart = type.indexOf('<');
        if (genericStart < 0) {
            boolean primitive = isPrimitiveType(type) || isBoxedPrimitiveType(type);
            String typeName;
            if (primitive) {
                typeName = boxedPrimitiveClass(type);
                if (typeName == null) {
                    typeName = classLiteral(type);
                }
            } else {
                typeName = classLiteral(type);
            }
            return new TypeDescriptor(typeName, List.of(), array, primitive);
        }

        String raw = type.substring(0, genericStart).trim();
        String inner = type.substring(genericStart + 1, type.lastIndexOf('>'));
        String[] generics = splitGenerics(inner);
        List<TypeDescriptor> args = new ArrayList<>();

        for (String g : generics) {
            args.add(parseTypeDescriptor(g));
        }

        return new TypeDescriptor(classLiteral(raw), args, array, false);
    }

    private static void appendJsonType(StringBuilder sb, TypeDescriptor td, boolean trailingComma, int indent) {
        String indentStr = " ".repeat(indent);
        if (!td.isParameterized() && !td.array()) {
            if (td.primitive()) {
                sb.append(indentStr).append("{\n");
                sb.append(indentStr).append("  \"type\": \"").append(td.typeName()).append("\",").append("\n");
                sb.append(indentStr).append("  \"unboxed\": \"").append(unboxedPrimitiveType(td.typeName())).append("\",").append("\n");
                sb.append(indentStr).append("  \"primitive\": true\n");
                sb.append(indentStr).append("}");
                if (trailingComma) sb.append(",");
                sb.append("\n");
                return;
            }
            sb.append(indentStr).append("\"").append(td.typeName()).append("\"");
            if (trailingComma) sb.append(",");
            sb.append("\n");
            return;
        }

        sb.append(indentStr).append("{\n");
        sb.append(indentStr).append("  \"type\": \"").append(td.typeName()).append("\",").append("\n");
        if (td.array()) {
            sb.append(indentStr).append("  \"array\": true,").append("\n");
        }
        if (td.isParameterized()) {
            sb.append(indentStr).append("  \"genericArguments\": [\n");
            for (int i = 0; i < td.typeArguments().size(); i++) {
                appendJsonType(sb, td.typeArguments().get(i), i < td.typeArguments().size() - 1, indent + 4);
            }
            sb.append(indentStr).append("  ]\n");
        }
        sb.append(indentStr).append("}");
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        Path inputPath = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        Path sourceRoot = deriveSourceRoot(inputPath);
        System.out.println("Using source root: " + sourceRoot);
        if (inputPath.toString().endsWith(".java")) {
            // For single file inputs, generate java boilerplate back into the source tree
            generate(sourceRoot, outputDir, sourceRoot);
        } else {
            generate(sourceRoot, outputDir);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar hipster-entity-tooling.jar <source-root|java-source-file> <output-dir>");
        System.err.println("If the first argument is a .java file, the tool will locate the source root by searching for src/main/java or src/test/java.");
        System.err.println("Generated view boilerplate for a single Java file is written back into the source tree using the underscore suffix convention.");
    }

    private static Path deriveSourceRoot(Path inputPath) {
        if (Files.isDirectory(inputPath)) {
            return inputPath;
        }

        if (inputPath.toString().endsWith(".java")) {
            Path current = inputPath.getParent();
            while (current != null) {
                if (current.getFileName() != null && "java".equals(current.getFileName().toString())) {
                    Path parent = current.getParent();
                    if (parent != null && "main".equals(parent.getFileName().toString())
                            && parent.getParent() != null
                            && "src".equals(parent.getParent().getFileName().toString())) {
                        return current;
                    }
                    if (parent != null && "test".equals(parent.getFileName().toString())
                            && parent.getParent() != null
                            && "src".equals(parent.getParent().getFileName().toString())) {
                        return current;
                    }
                }
                current = current.getParent();
            }

            // If no standard Maven source root is found, derive the source root from the package declaration.
            try {
                String source = Files.readString(inputPath);
                ParseResult<CompilationUnit> parseResult = new JavaParser().parse(source);
                CompilationUnit cu = parseResult.getResult().orElse(null);
                if (cu != null && cu.getPackageDeclaration().isPresent()) {
                    String packageName = cu.getPackageDeclaration().get().getNameAsString();
                    Path parent = inputPath.getParent();
                    String[] segments = packageName.split("\\.");
                    for (int i = segments.length - 1; i >= 0 && parent != null; i--) {
                        if (segments[i].equals(parent.getFileName().toString())) {
                            parent = parent.getParent();
                        } else {
                            parent = null;
                        }
                    }
                    if (parent != null) {
                        return parent;
                    }
                }
            } catch (IOException ignored) {
                // Fall back to the file's parent directory if package parsing fails.
            }
            return inputPath.getParent();
        }

        return inputPath;
    }

    public static void generate(Path sourceRoot, Path outputDir) throws IOException {
        generate(sourceRoot, outputDir, outputDir);
    }

    public static void generate(Path sourceRoot, Path outputDir, Path javaOutputRoot) throws IOException {
        Map<String, InterfaceInfo> interfaceMap = new HashMap<>();

        Files.walk(sourceRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(filePath -> {
                    try {
                        String source = Files.readString(filePath);
                        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(source);
                        CompilationUnit cu = parseResult.getResult().orElse(null);
                        if (cu == null) {
                            return;
                        }

                        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                            if (!decl.isInterface()) {
                                continue;
                            }

                            InterfaceInfo info = new InterfaceInfo(
                                    packageName,
                                    decl.getNameAsString(),
                                    decl.getExtendedTypes().stream()
                                            .map(ClassOrInterfaceType::asString)
                                            .collect(Collectors.toList()),
                                    decl.getMethods().stream()
                                            .filter(method -> method.getParameters().isEmpty())
                                            .filter(method -> !method.getType().isVoidType())
                                            .map(method -> parseProperty(method))
                                            .collect(Collectors.toList()),
                                    parseViewAnnotation(decl),
                                    parseEntityBaseIdType(decl),
                                    decl.getBegin().map(pos -> pos.line).orElse(-1)
                            );
                            interfaceMap.put(getQualifiedName(packageName, info.name()), info);
                        }

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        Map<String, List<InterfaceInfo>> packageToMarkers = new HashMap<>();

        List<InterfaceInfo> markers = interfaceMap.values().stream()
                .filter(InterfaceInfo::isMarkerEntity)
                .toList();

        for (InterfaceInfo info : markers) {
            boolean derivedFromAnotherMarker = markers.stream()
                    .anyMatch(other -> other != info && isDerivedFrom(info, other, interfaceMap));
            if (!derivedFromAnotherMarker) {
                packageToMarkers.computeIfAbsent(info.packageName(), __ -> new ArrayList<>()).add(info);
            }
        }

        for (List<InterfaceInfo> roots : packageToMarkers.values()) {
            for (InterfaceInfo marker : roots) {
                String entityName = marker.name().endsWith("Entity") ? marker.name().substring(0, marker.name().length() - 6) : marker.name();

                List<ViewMeta> views = interfaceMap.values().stream()
                        .filter(i -> !i.name().equals(marker.name()))
                        .filter(i -> isDerivedFrom(i, marker, interfaceMap))
                        .map(i -> new ViewMeta(i.name(), i.extendsTypes(), i.view() == null ? null : i.view().read(),
                                i.view() == null ? null : i.view().write(), i.properties(), i.lineNumber()))
                        .collect(Collectors.toList());

                List<EntityFieldMeta> allFields = collectEntityFields(views, marker, interfaceMap);
                EntityMeta entityMeta = new EntityMeta(entityName, marker.packageName(), marker.name(), marker.entityBaseIdType(), views, allFields);
                String json = toJson(entityMeta);

                Files.createDirectories(outputDir);
                Path outFile = outputDir.resolve(entityName + ".metadata.json");
                Files.writeString(outFile, json);

                for (ViewMeta view : views) {
                    InterfaceInfo viewInfo = interfaceMap.get(getQualifiedName(marker.packageName(), view.name()));
                    List<Property> fullProperties = collectViewProperties(viewInfo, marker, interfaceMap);
                    generateViewPropertyEnum(javaOutputRoot, marker.packageName(), view, fullProperties);
                }
            }
        }
    }

    private static Property parseProperty(MethodDeclaration method) {
        String name = method.getNameAsString();
        String type = method.getType().asString();
        String fieldKind = null;
        String column = null;
        String relation = null;
        String expression = null;

        Optional<AnnotationExpr> fsOpt = method.getAnnotationByName("FieldSource");
        if (fsOpt.isPresent()) {
            AnnotationExpr fs = fsOpt.get();
            if (fs.isSingleMemberAnnotationExpr()) {
                fieldKind = extractEnumValue(fs.asSingleMemberAnnotationExpr().getMemberValue().toString());
            } else if (fs.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : fs.asNormalAnnotationExpr().getPairs()) {
                    switch (pair.getName().asString()) {
                        case "kind" -> fieldKind = extractEnumValue(pair.getValue().toString());
                        case "column" -> column = stripQuotes(pair.getValue().toString());
                        case "relation" -> relation = stripQuotes(pair.getValue().toString());
                        case "expression" -> expression = stripQuotes(pair.getValue().toString());
                    }
                }
            }
        }
        int lineNumber = method.getBegin().map(pos -> pos.line).orElse(-1);
        return new Property(name, type, fieldKind, column, relation, expression, lineNumber);
    }

    private static String extractEnumValue(String value) {
        // Handle FieldKind.DERIVED or just DERIVED
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static String stripQuotes(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    private static List<EntityFieldMeta> collectEntityFields(List<ViewMeta> views, InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap) {
        // Merge all fields from all views into entity-wide map
        LinkedHashMap<String, EntityFieldMeta> fieldMap = new LinkedHashMap<>();

        // id field is always first
        String idType = marker != null && marker.entityBaseIdType() != null ? toFullTypeName(marker.entityBaseIdType()) : "java.lang.Object";
        List<String> idViews = views.stream().map(ViewMeta::name).collect(Collectors.toList());
        EntityFieldMeta idField = new EntityFieldMeta("id", idType, "COLUMN", null, null, null, -1, idViews);
        for (String vn : idViews) idField.typeByView.put(vn, idType);
        fieldMap.put("id", idField);

        for (ViewMeta view : views) {
            InterfaceInfo viewInfo = interfaceMap.get(getQualifiedName(marker.packageName(), view.name()));
            List<Property> fullProps = collectViewProperties(viewInfo, marker, interfaceMap);
            for (Property prop : fullProps) {
                String propKind = prop.fieldKind() != null ? prop.fieldKind() : "COLUMN";
                if (fieldMap.containsKey(prop.name())) {
                    EntityFieldMeta existing = fieldMap.get(prop.name());
                    if (!existing.views.contains(view.name())) {
                        existing.views.add(view.name());
                    }
                    existing.typeByView.put(view.name(), prop.type());
                    // Non-derived field takes priority over derived for primary type
                    if ("DERIVED".equals(existing.fieldKind) && !"DERIVED".equals(propKind)) {
                        existing.type = prop.type();
                        existing.fieldKind = propKind;
                        existing.column = prop.column();
                        existing.relation = prop.relation();
                        existing.expression = prop.expression();
                    }
                } else {
                    List<String> viewList = new ArrayList<>();
                    viewList.add(view.name());
                    EntityFieldMeta fm = new EntityFieldMeta(prop.name(), prop.type(), propKind, prop.column(), prop.relation(), prop.expression(), prop.lineNumber(), viewList);
                    fm.typeByView.put(view.name(), prop.type());
                    fieldMap.put(prop.name(), fm);
                }
            }
        }

        return new ArrayList<>(fieldMap.values());
    }

    private static ViewAttributes parseViewAnnotation(ClassOrInterfaceDeclaration decl) {
        Optional<AnnotationExpr> viewOpt = decl.getAnnotationByName("View");
        if (viewOpt.isEmpty()) {
            return null;
        }

        Boolean read = null;
        Boolean write = null;

        AnnotationExpr view = viewOpt.get();
        if(view.isSingleMemberAnnotationExpr()) {
            // @View(true) or @View(false) - treat as read=true/false with write=null
            read = parseBooleanOption(view.asSingleMemberAnnotationExpr().getMemberValue().toString());
            return new ViewAttributes(read, null);
        }
        if(view.isMarkerAnnotationExpr()) {
            // @View without parameters - treat as read=true, write=true
            return new ViewAttributes(Boolean.TRUE, Boolean.TRUE);
        }
        if(view.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : view.asNormalAnnotationExpr().getPairs()) {
                switch (pair.getName().asString()) {
                    case "read" -> read = parseBooleanOption(pair.getValue().toString());
                    case "write" -> write = parseBooleanOption(pair.getValue().toString());
                }
            }
        }

        return new ViewAttributes(read, write);
    }

    private static Boolean parseBooleanOption(String value) {
        if (value.contains("TRUE")) return Boolean.TRUE;
        if (value.contains("FALSE")) return Boolean.FALSE;
        return null;
    }

    private static String parseEntityBaseIdType(ClassOrInterfaceDeclaration decl) {
        for (ClassOrInterfaceType ext : decl.getExtendedTypes()) {
            if ("EntityBase".equals(ext.getNameAsString())) {
                if (ext.getTypeArguments().isPresent()) {
                    return ext.getTypeArguments().get().stream().findFirst().map(Object::toString).orElse(null);
                }
            }
        }
        return null;
    }

    private static String getQualifiedName(String pkg, String name) {
        return (pkg.isEmpty() ? "" : pkg + ".") + name;
    }

    private static boolean isDerivedFrom(InterfaceInfo candidate, InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap) {
        if (candidate.name().equals(marker.name())) {
            return false;
        }

        Set<String> visited = new HashSet<>();
        return isDerivedFromRecursive(candidate, marker, interfaceMap, visited);
    }

    private static boolean isDerivedFromRecursive(InterfaceInfo candidate, InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap, Set<String> visited) {
        if (visited.contains(candidate.name())) {
            return false;
        }
        visited.add(candidate.name());

        for (String extName : candidate.extendsTypes()) {
            if (extName.contains("<")) {
                extName = extName.substring(0, extName.indexOf('<'));
            }

            if (extName.equals(marker.name()) || extName.equals(marker.packageName() + "." + marker.name())) {
                return true;
            }

            InterfaceInfo parent = interfaceMap.get(getQualifiedName(candidate.packageName(), extName));
            if (parent != null && isDerivedFromRecursive(parent, marker, interfaceMap, visited)) {
                return true;
            }
        }

        return false;
    }

    private static String toJson(EntityMeta entityMeta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendJsonField(sb, "entityName", entityMeta.entityName(), true);
        appendJsonField(sb, "package", entityMeta.packageName(), true);
        appendJsonField(sb, "markerInterface", entityMeta.markerInterface(), true);
        appendJsonField(sb, "idType", entityMeta.idType(), true, 2);

        sb.append("  \"views\": [\n");
        for (int i = 0; i < entityMeta.views().size(); i++) {
            ViewMeta view = entityMeta.views().get(i);
            sb.append("    {\n");
            appendJsonField(sb, "name", view.name(), true, 6);
            appendJsonField(sb, "lineNumber", view.lineNumber(), true, 6);
            sb.append("      \"extends\": [");

            sb.append(view.extendsTypes().stream().map(EntityMetadataGenerator::escapeJson).map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
            sb.append("],\n");
            appendJsonField(sb, "read", view.read(), true, 6);
            appendJsonField(sb, "write", view.write(), true, 6);
            sb.append("      \"properties\": [\n");
            for (int j = 0; j < view.properties().size(); j++) {
                Property prop = view.properties().get(j);
                sb.append("        {\n");
                appendJsonField(sb, "name", prop.name(), true, 8);
                sb.append("        \"type\": ");
                boolean hasFieldKind = prop.fieldKind() != null;
                appendJsonType(sb, parseTypeDescriptor(prop.type()), true, 0);
                appendJsonField(sb, "lineNumber", prop.lineNumber(), hasFieldKind, 8);
                if (prop.fieldKind() != null) {
                    appendJsonField(sb, "fieldKind", prop.fieldKind(), prop.column() != null || prop.relation() != null || prop.expression() != null, 8);
                    if (prop.column() != null) {
                        appendJsonField(sb, "column", prop.column(), prop.relation() != null || prop.expression() != null, 8);
                    }
                    if (prop.relation() != null) {
                        appendJsonField(sb, "relation", prop.relation(), prop.expression() != null, 8);
                    }
                    if (prop.expression() != null) {
                        appendJsonField(sb, "expression", prop.expression(), false, 8);
                    }
                }
                sb.append("        }");
                if (j < view.properties().size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }");
            if (i < entityMeta.views().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // Entity-wide field summary
        sb.append("  \"allFields\": [\n");
        for (int i = 0; i < entityMeta.allFields().size(); i++) {
            EntityFieldMeta f = entityMeta.allFields().get(i);
            sb.append("    {\n");
            appendJsonField(sb, "name", f.name, true, 6);
            sb.append("      \"type\": ");
            appendJsonType(sb, parseTypeDescriptor(f.type), true, 0);
            appendJsonField(sb, "lineNumber", f.lineNumber, true, 6);
            appendJsonField(sb, "fieldKind", f.fieldKind, true, 6);
            if (f.column != null) {
                appendJsonField(sb, "column", f.column, true, 6);
            }
            if (f.relation != null) {
                appendJsonField(sb, "relation", f.relation, true, 6);
            }
            if (f.expression != null) {
                appendJsonField(sb, "expression", f.expression, true, 6);
            }
            sb.append("      \"views\": [");
            sb.append(f.views.stream().map(v -> "\"" + escapeJson(v) + "\"").collect(Collectors.joining(", ")));
            sb.append("],\n");
            // typeByView: always present, shows type per view
            sb.append("      \"typeByView\": {\n");
            int tvIdx = 0;
            for (Map.Entry<String, String> entry : f.typeByView.entrySet()) {
                sb.append("        \"" + escapeJson(entry.getKey()) + "\": ");
                appendJsonType(sb, parseTypeDescriptor(entry.getValue()), tvIdx < f.typeByView.size() - 1, 0);
                tvIdx++;
            }
            sb.append("      }\n");
            sb.append("    }");
            if (i < entityMeta.allFields().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean trailingComma) {
        appendJsonField(sb, key, value, trailingComma, 2);
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean trailingComma, int indent) {
        String indentStr = " ".repeat(indent);
        sb.append(indentStr).append("\"").append(key).append("\": ");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeJson(value)).append("\"");
        }
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    private static void appendJsonField(StringBuilder sb, String key, Boolean value, boolean trailingComma, int indent) {
        String indentStr = " ".repeat(indent);
        sb.append(indentStr).append("\"").append(key).append("\": ");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    private static void appendJsonField(StringBuilder sb, String key, int value, boolean trailingComma, int indent) {
        String indentStr = " ".repeat(indent);
        sb.append(indentStr).append("\"").append(key).append("\": ").append(value);
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void generateViewPropertyEnum(Path outputDir, String packageName, ViewMeta view, List<Property> fullProperties) throws IOException {
        FieldBoilerplateGenerator.builder(packageName, view.name(), fullProperties)
                .withPropertyEnumMode()
                .withEnumTypeName(view.name() + "_")
                .generate(outputDir);
    }

    private static String toEnumConstant(String propertyName) {
        return propertyName
                .replaceAll("[^A-Za-z0-9]", "_")
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase();
    }

    private static List<Property> collectViewProperties(InterfaceInfo viewInfo, InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap) {
        LinkedHashMap<String, Property> merged = new LinkedHashMap<>();

        String idType = marker != null && marker.entityBaseIdType() != null ? toFullTypeName(marker.entityBaseIdType()) : "java.lang.Object";
        merged.put("id", new Property("id", idType, -1));

        Set<String> visited = new HashSet<>();
        collectInterfaceProperties(viewInfo, merged, interfaceMap, visited);

        return new ArrayList<>(merged.values());
    }

    private static void collectInterfaceProperties(InterfaceInfo current, LinkedHashMap<String, Property> merged, Map<String, InterfaceInfo> interfaceMap, Set<String> visited) {
        if (current == null || !visited.add(current.name())) {
            return;
        }

        for (String extName : current.extendsTypes()) {
            String qualified = extName.contains(".") ? extName : getQualifiedName(current.packageName(), extName.replaceAll("<.*>$", ""));
            InterfaceInfo parent = interfaceMap.get(qualified);
            collectInterfaceProperties(parent, merged, interfaceMap, visited);
        }

        for (Property prop : current.properties()) {
            if (merged.containsKey(prop.name())) {
                merged.remove(prop.name());
            }
            merged.put(prop.name(), prop);
        }
    }

    private static String typeExpression(String rawType) {
        String type = rawType.trim();
        if (type.endsWith("[]")) {
            String element = typeExpression(type.substring(0, type.length() - 2));
            return "java.lang.reflect.Array.newInstance(" + element + ", 0).getClass()";
        }

        int genericStart = type.indexOf('<');
        if (genericStart < 0) {
            if (isPrimitiveType(type)) {
                return boxedPrimitiveClass(type) + ".class";
            }
            String literal = classLiteral(type);
            return literal + ".class";
        }

        String raw = type.substring(0, genericStart).trim();
        String args = type.substring(genericStart + 1, type.lastIndexOf('>'));
        String[] generics = splitGenerics(args);
        StringBuilder argExpr = new StringBuilder();
        for (int i = 0; i < generics.length; i++) {
            argExpr.append(typeExpression(generics[i]));
            if (i < generics.length - 1) argExpr.append(", ");
        }

        return "TypeUtils.parameterizedType(" + classLiteral(raw) + ".class, " + argExpr + ")";
    }

    private static String[] splitGenerics(String args) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : args.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }

            if (c == ',' && depth == 0) {
                tokens.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString().trim());
        }
        return tokens.toArray(new String[0]);
    }

    private static boolean isPrimitiveType(String typeName) {
        return switch (typeName) {
            case "byte", "short", "int", "long", "float", "double", "boolean", "char" -> true;
            default -> false;
        };
    }

    private static boolean isBoxedPrimitiveType(String typeName) {
        return switch (typeName) {
            case "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character",
                 "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                 "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character" -> true;
            default -> false;
        };
    }

    private static String boxedPrimitiveClass(String typeName) {
        return switch (typeName) {
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            case "boolean" -> "java.lang.Boolean";
            case "char" -> "java.lang.Character";
            default -> null;
        };
    }

    private static String unboxedPrimitiveType(String boxedTypeName) {
        return switch (boxedTypeName) {
            case "java.lang.Byte" -> "byte";
            case "java.lang.Short" -> "short";
            case "java.lang.Integer" -> "int";
            case "java.lang.Long" -> "long";
            case "java.lang.Float" -> "float";
            case "java.lang.Double" -> "double";
            case "java.lang.Boolean" -> "boolean";
            case "java.lang.Character" -> "char";
            default -> null;
        };
    }

    private static String classLiteral(String typeName) {
        switch (typeName) {
            case "byte": return "byte";
            case "short": return "short";
            case "int": return "int";
            case "long": return "long";
            case "float": return "float";
            case "double": return "double";
            case "boolean": return "boolean";
            case "char": return "char";
            case "void": return "void";
            case "String": return "java.lang.String";
            case "Long": return "java.lang.Long";
            case "Integer": return "java.lang.Integer";
            case "Boolean": return "java.lang.Boolean";
            case "Double": return "java.lang.Double";
            case "Float": return "java.lang.Float";
            case "Character": return "java.lang.Character";
            case "Object": return "java.lang.Object";
            case "List": return "java.util.List";
            case "Set": return "java.util.Set";
            case "Map": return "java.util.Map";
            case "Collection": return "java.util.Collection";
            case "Optional": return "java.util.Optional";
            default:
                if (typeName.contains(".")) {
                    return typeName;
                }
                // Keep unqualified for same-package or imported types.
                return typeName;
        }
    }

    private static String toFullTypeName(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return rawType;
        }

        String type = rawType.trim();
        if (type.endsWith("[]")) {
            String element = toFullTypeName(type.substring(0, type.length() - 2));
            return element + "[]";
        }

        int genericStart = type.indexOf('<');
        if (genericStart < 0) {
            return classLiteral(type);
        }

        String raw = type.substring(0, genericStart).trim();
        String args = type.substring(genericStart + 1, type.lastIndexOf('>'));
        String[] generics = splitGenerics(args);
        StringBuilder builder = new StringBuilder();
        builder.append(classLiteral(raw)).append("<");
        for (int i = 0; i < generics.length; i++) {
            builder.append(toFullTypeName(generics[i]));
            if (i < generics.length - 1) builder.append(", ");
        }
        builder.append(">");
        return builder.toString();
    }
}



