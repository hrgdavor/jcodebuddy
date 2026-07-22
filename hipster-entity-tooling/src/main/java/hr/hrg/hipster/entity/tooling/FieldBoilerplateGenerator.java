package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;

import hr.hrg.hipster.entity.tooling.meta.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FieldBoilerplateGenerator {

    private final String packageName;
    private final String viewName;
    private final String enumName;
    private final List<Property> properties;
    private final boolean fieldEnumMode;
    private final String metaCreatorBody;
    private final String discriminatorFieldReference;
    private final String discriminatorValue;
    private final String[] permittedSubtypeClassNames;
    private final List<String> additionalImports;

    private FieldBoilerplateGenerator(Builder builder) {
        this.packageName = builder.packageName;
        this.viewName = builder.viewName;
        this.enumName = builder.enumName;
        this.properties = List.copyOf(builder.properties);
        this.fieldEnumMode = builder.fieldEnumMode;
        this.metaCreatorBody = builder.metaCreatorBody;
        this.discriminatorFieldReference = builder.discriminatorFieldReference;
        this.discriminatorValue = builder.discriminatorValue;
        this.permittedSubtypeClassNames = builder.permittedSubtypeClassNames.clone();
        this.additionalImports = List.copyOf(builder.additionalImports);
    }

    public static Builder builder(String packageName, String viewName, List<Property> properties) {
        return new Builder(packageName, viewName, properties);
    }

    public void generate(Path outputRoot) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);

        Path enumFile = packageDir.resolve(enumName + ".java");
        Files.writeString(enumFile, buildSource());
    }

    private String buildSource() {
        CompilationUnit cu = buildCompilationUnit();
        PrettyPrinterConfiguration printerConfig = new PrettyPrinterConfiguration();
        printerConfig.setIndentSize(4);
        String source = cu.toString(printerConfig).replace("switch(", "switch (");
        return source.replaceAll("->\\s*\\r?\\n\\s*", "-> ");
    }

    private CompilationUnit buildCompilationUnit() {
        CompilationUnit cu = new CompilationUnit();
        if (packageName != null && !packageName.isBlank()) {
            cu.setPackageDeclaration(packageName);
        }

        Set<String> imports = new LinkedHashSet<>();
        if (fieldEnumMode) {
            imports.add("hr.hrg.hipster.entity.api.FieldNameMapper");
            imports.add("hr.hrg.hipster.entity.api.FieldDef");
        } else {
            imports.add("java.lang.reflect.Type");
            imports.add("hr.hrg.hipster.entity.api.TypeUtils");
        }
        if (metaCreatorBody != null) {
            imports.add("hr.hrg.hipster.entity.api.ViewMeta");
            imports.add("hr.hrg.hipster.entity.api.DefaultViewMeta");
        }
        imports.addAll(additionalImports);

        imports.forEach(cu::addImport);

        EnumDeclaration enumDecl = cu.addEnum(enumName);
        if (fieldEnumMode) {
            enumDecl.addImplementedType("FieldDef");
        }

        for (Property prop : properties) {
            Expression initializer = parseTypeExpression(prop.type());
            EnumConstantDeclaration constant = new EnumConstantDeclaration(prop.name());
            constant.getArguments().add(initializer);
            enumDecl.addEntry(constant);
        }

        if (fieldEnumMode) {
            enumDecl.addMember(new FieldDeclaration(NodeList.nodeList(Modifier.privateModifier(), Modifier.finalModifier()),
                    new VariableDeclarator(classTypeWithWildcard(), "javaType")));
        } else {
            enumDecl.addMember(new FieldDeclaration(NodeList.nodeList(Modifier.privateModifier(), Modifier.finalModifier()),
                    new VariableDeclarator(new ClassOrInterfaceType(null, "Type"), "propertyType")));
        }

        addConstructor(enumDecl);
        if (fieldEnumMode) {
            addJavaTypeMethod(enumDecl);
        } else {
            addPropertyMethods(enumDecl);
        }
        addForNameMethod(enumDecl);

        if (fieldEnumMode) {
            FieldDeclaration mapperField = new FieldDeclaration(NodeList.nodeList(Modifier.privateModifier(), Modifier.staticModifier(), Modifier.finalModifier()),
                    new VariableDeclarator(new ClassOrInterfaceType(null, "FieldNameMapper").setTypeArguments(NodeList.nodeList(new ClassOrInterfaceType(null, enumName))), "NAME_MAPPER", parseExpression(enumName + "::forName")));
            enumDecl.addMember(mapperField);
        }

        if (metaCreatorBody != null) {
            if (!fieldEnumMode) {
                throw new IllegalStateException("ViewMeta generation requires field enum mode");
            }

            ClassOrInterfaceType metaType = new ClassOrInterfaceType(null, "ViewMeta")
                    .setTypeArguments(NodeList.nodeList(new ClassOrInterfaceType(null, viewName), new ClassOrInterfaceType(null, enumName)));
            ObjectCreationExpr initializer = new ObjectCreationExpr(null,
                    new ClassOrInterfaceType(null, "DefaultViewMeta").setTypeArguments(NodeList.nodeList(new ClassOrInterfaceType(null, viewName), new ClassOrInterfaceType(null, enumName))),
                    NodeList.nodeList(
                            new ClassExpr(new ClassOrInterfaceType(null, viewName)),
                            new ClassExpr(new ClassOrInterfaceType(null, enumName)),
                            new NameExpr("NAME_MAPPER"),
                            parseExpression(metaCreatorBody),
                            discriminatorFieldReference == null ? new NullLiteralExpr() : parseExpression(discriminatorFieldReference),
                            new StringLiteralExpr(discriminatorValue),
                            permittedSubtypeArrayExpression()
                    ));

            FieldDeclaration metaField = new FieldDeclaration(NodeList.nodeList(Modifier.publicModifier(), Modifier.staticModifier(), Modifier.finalModifier()),
                    new VariableDeclarator(metaType, "META", initializer));
            enumDecl.addMember(metaField);
        }

        return cu;
    }

    private Type classTypeWithWildcard() {
        return new ClassOrInterfaceType(null, "Class").setTypeArguments(NodeList.nodeList(new WildcardType()));
    }

    private void addConstructor(EnumDeclaration enumDecl) {
        String parameterName = fieldEnumMode ? "javaType" : "propertyType";
        Type parameterType = fieldEnumMode ? classTypeWithWildcard() : new ClassOrInterfaceType(null, "Type");
        ConstructorDeclaration ctor = enumDecl.addConstructor(Modifier.Keyword.PRIVATE);
        ctor.addParameter(parameterType, parameterName);
        ctor.getBody().addStatement(new AssignExpr(
                new FieldAccessExpr(new ThisExpr(), parameterName),
                new NameExpr(parameterName),
                AssignExpr.Operator.ASSIGN));
    }

    private void addJavaTypeMethod(EnumDeclaration enumDecl) {
        MethodDeclaration method = enumDecl.addMethod("javaType", Modifier.Keyword.PUBLIC);
        method.setType(classTypeWithWildcard());
        method.setBody(new BlockStmt(NodeList.nodeList(new ReturnStmt(new NameExpr("javaType")))));
    }

    private void addPropertyMethods(EnumDeclaration enumDecl) {
        MethodDeclaration nameMethod = enumDecl.addMethod("getPropertyName", Modifier.Keyword.PUBLIC);
        nameMethod.setType("String");
        nameMethod.setBody(new BlockStmt(NodeList.nodeList(new ReturnStmt(new MethodCallExpr("name")))));

        MethodDeclaration typeMethod = enumDecl.addMethod("getPropertyType", Modifier.Keyword.PUBLIC);
        typeMethod.setType(new ClassOrInterfaceType(null, "Type"));
        typeMethod.setBody(new BlockStmt(NodeList.nodeList(new ReturnStmt(new NameExpr("propertyType")))));
    }

    private void addForNameMethod(EnumDeclaration enumDecl) {
        MethodDeclaration method = enumDecl.addMethod("forName", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);
        method.setType(enumName);
        method.addParameter(new ClassOrInterfaceType(null, "String"), "name");

        // Tooling must use JavaParser AST nodes directly, not string building.
        // The desired generated code is:
        //   public static PersonSummary_ forName(String name) {
        //       if (name == null) return null;
        //       return switch (name) {
        //           case "id" -> id;
        //           case "firstName" -> firstName;
        //           case "lastName" -> lastName;
        //           case "age" -> age;
        //           case "departmentName" -> departmentName;
        //           case "metadata" -> metadata;
        //           default -> null;
        //       };
        //   }

        BlockStmt body = new BlockStmt();
        body.addStatement(new IfStmt(
                new BinaryExpr(new NameExpr("name"), new NullLiteralExpr(), BinaryExpr.Operator.EQUALS),
                new ReturnStmt(new NullLiteralExpr()),
                null));

        SwitchExpr switchExpr = new SwitchExpr();
        switchExpr.setSelector(new NameExpr("name"));

        for (Property prop : properties) {
            SwitchEntry entry;
            if (fieldEnumMode) {
                entry = new SwitchEntry(
                        NodeList.nodeList(new StringLiteralExpr(prop.name())),
                        SwitchEntry.Type.STATEMENT_GROUP,
                        NodeList.nodeList(new ReturnStmt(new NameExpr(prop.name()))));
            } else {
                entry = new SwitchEntry(
                        NodeList.nodeList(new StringLiteralExpr(prop.name())),
                        SwitchEntry.Type.EXPRESSION,
                        NodeList.nodeList(new ExpressionStmt(new NameExpr(prop.name()))));
            }
            switchExpr.getEntries().add(entry);
        }

        SwitchEntry defaultEntry = new SwitchEntry(
                NodeList.nodeList(),
                fieldEnumMode ? SwitchEntry.Type.STATEMENT_GROUP : SwitchEntry.Type.EXPRESSION,
                NodeList.nodeList(fieldEnumMode
                        ? new ReturnStmt(new NullLiteralExpr())
                        : new ExpressionStmt(new NullLiteralExpr())));
        switchExpr.getEntries().add(defaultEntry);

        body.addStatement(new ReturnStmt(switchExpr));
        method.setBody(body);
    }

    private Expression permittedSubtypeArrayExpression() {
        if (permittedSubtypeClassNames.length == 0) {
            return parseExpression("new Class<?>[0]");
        }
        ArrayCreationExpr arrayCreation = new ArrayCreationExpr();
        arrayCreation.setElementType(new ClassOrInterfaceType(null, "Class").setTypeArguments(new NodeList<>(new WildcardType())));
        arrayCreation.setLevels(new NodeList<>(new ArrayCreationLevel()));
        ArrayInitializerExpr initializer = new ArrayInitializerExpr();
        NodeList<Expression> values = new NodeList<>();
        for (String subtype : permittedSubtypeClassNames) {
            values.add(parseExpression(subtype));
        }
        initializer.setValues(values);
        arrayCreation.setInitializer(initializer);
        return arrayCreation;
    }

    private Expression parseExpression(String source) {
        return new JavaParser().parseExpression(source)
                .getResult()
                .orElseThrow(() -> new IllegalArgumentException("Unable to parse expression: " + source));
    }

    private Expression parseTypeExpression(String rawType) {
        return parseExpression(typeExpression(rawType));
    }

    private String typeExpression(String rawType) {
        String type = rawType.trim();
        if (type.endsWith("[]")) {
            return "java.lang.reflect.Array.newInstance(" + typeExpression(type.substring(0, type.length() - 2)) + ", 0).getClass()";
        }

        int genericStart = type.indexOf('<');
        if (genericStart < 0) {
            if (isPrimitiveType(type)) {
                return boxedPrimitiveClass(type) + ".class";
            }
            return classLiteral(type) + ".class";
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
            case "BigDecimal": return "java.math.BigDecimal";
            case "Instant": return "java.time.Instant";
            case "LocalDate": return "java.time.LocalDate";
            case "LocalDateTime": return "java.time.LocalDateTime";
            case "OffsetDateTime": return "java.time.OffsetDateTime";
            case "UUID": return "java.util.UUID";
            case "EEnumSet": return "hr.hrg.hipster.entity.core.EEnumSet";
            case "ID": return "java.lang.Object";
            default:
                if (typeName.contains(".")) {
                    return typeName;
                }
                return typeName;
        }
    }

    private static String stringLiteral(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static final class Builder {
        private final String packageName;
        private final String viewName;
        private final List<Property> properties;
        private String enumName;
        private boolean enumNameExplicitlySet = false;
        private boolean fieldEnumMode = true;
        private String metaCreatorBody;
        private String discriminatorFieldReference;
        private String discriminatorValue = "";
        private String[] permittedSubtypeClassNames = new String[0];
        private final List<String> additionalImports = new ArrayList<>();

        private Builder(String packageName, String viewName, List<Property> properties) {
            this.packageName = packageName == null ? "" : packageName;
            this.viewName = Objects.requireNonNull(viewName, "viewName");
            this.properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
            this.enumName = viewName + "Field";
        }

        public Builder withEnumTypeName(String enumName) {
            this.enumName = Objects.requireNonNull(enumName, "enumName");
            this.enumNameExplicitlySet = true;
            return this;
        }

        public Builder withPropertyEnumMode() {
            this.fieldEnumMode = false;
            if (!enumNameExplicitlySet) {
                this.enumName = viewName + "Property";
            }
            return this;
        }

        public Builder withMetaCreatorBody(String metaCreatorBody) {
            this.metaCreatorBody = Objects.requireNonNull(metaCreatorBody, "metaCreatorBody");
            return this;
        }

        public Builder withDiscriminatorField(String discriminatorFieldReference) {
            this.discriminatorFieldReference = Objects.requireNonNull(discriminatorFieldReference, "discriminatorFieldReference");
            return this;
        }

        public Builder withDiscriminatorValue(String discriminatorValue) {
            this.discriminatorValue = Objects.requireNonNull(discriminatorValue, "discriminatorValue");
            return this;
        }

        public Builder withPermittedSubtypeClassNames(String... permittedSubtypeClassNames) {
            this.permittedSubtypeClassNames = permittedSubtypeClassNames == null ? new String[0] : permittedSubtypeClassNames.clone();
            return this;
        }

        public Builder withAdditionalImports(String... additionalImports) {
            if (additionalImports != null) {
                for (String importName : additionalImports) {
                    this.additionalImports.add(importName);
                }
            }
            return this;
        }

        public FieldBoilerplateGenerator build() {
            return new FieldBoilerplateGenerator(this);
        }

        public void generate(Path outputRoot) throws IOException {
            build().generate(outputRoot);
        }
    }
}
