package hr.hrg.hipster.sql.tooling.emit;

import hr.hrg.hipster.sql.tooling.model.AnnotationModel;
import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits the minimal {@code <Entity>Meta} and the package registry, enough to compile against the
 * untouched runtime and expose columns, implementations and copy methods.
 *
 * <p><strong>Scope of this slice:</strong> SQL's {@code fromResultSet}/{@code visitResult} and the
 * Jackson/Mongo sections are not emitted yet. The class inherits the runtime's
 * {@code UnsupportedOperationException} stubs for those; the next phase fills them. The column
 * objects, ordinal order and implementation-class list are real, so a consumer can compile and
 * inspect the metadata even before row decoding lands.</p>
 */
public final class MetaEmitter {

    public static final String GENERATOR = "hr.hrg.hipster.sql.tooling.HipsterSqlGenerator";

    private MetaEmitter() {
    }

    public static String render(EntityModel entity) {
        return render(entity, false, false, false);
    }

    public static String render(EntityModel entity, boolean genJson, boolean genMongo) {
        return render(entity, genJson, genMongo, false);
    }

    public static String render(EntityModel entity, boolean genJson, boolean genMongo,
                                boolean genAnnotations) {
        String meta = entity.simpleName() + "Meta";
        PropertyModel primary = entity.primary();
        String idType = primary == null ? "Object" : TypeTexts.boxed(primary.typeText());
        JavaSourceWriter out = JavaSourceWriter.defaultWriter();
        out.line("// @generated file " + GENERATOR + " \u2014 Meta implementation for the "
                + entity.simpleName() + " entity.");
        out.line("// {enabled:true, blockMarker: \"implicit\"}");
        if (!entity.packageName().isEmpty()) {
            out.line("package " + entity.packageName() + ";");
            out.blank();
        }

        ImportCollector imports = new ImportCollector(entity.packageName());
        for (PropertyModel property : entity.properties()) {
            imports.collect(property.typeText(), entity.imports());
        }
        if (genAnnotations) {
            collectAnnotationImports(imports, entity);
        }
        imports.add("hr.hrg.hipster.entity.ColumnMeta");
        imports.add("hr.hrg.hipster.entity.EntityMeta");
        imports.add("hr.hrg.hipster.sql.HipsterSql");
        imports.add("hr.hrg.hipster.sql.ImmutableList");
        imports.add("hr.hrg.hipster.type.ICustomType");
        imports.add("hr.hrg.hipster.type.TypeSource");
        imports.add("java.sql.ResultSet");
        imports.add("java.sql.SQLException");
        if (genAnnotations) {
            imports.add("hr.hrg.hipster.sql.HipsterSqlUtil");
        }
        if (genJson) {
            imports.add("com.fasterxml.jackson.databind.ObjectMapper");
            imports.add("hr.hrg.hipster.jackson.WithMapper");
        }
        if (genMongo) {
            imports.add("hr.hrg.hipster.entity.MongoEntityMeta");
            imports.add("org.bson.BsonReader");
            imports.add("org.bson.BsonType");
            imports.add("org.bson.BsonWriter");
            imports.add("org.bson.codecs.Codec");
            imports.add("org.bson.codecs.DecoderContext");
            imports.add("org.bson.codecs.EncoderContext");
            imports.add("org.bson.codecs.configuration.CodecRegistry");
        }
        for (String imported : imports.imports()) {
            out.line("import " + imported + ";");
        }
        if (!imports.imports().isEmpty()) {
            out.blank();
        }

        out.line("/** Metadata for {@link " + entity.simpleName() + "}. */");
        out.line("public class " + meta + " extends "
                + (genMongo ? "MongoEntityMeta" : "EntityMeta") + "<" + entity.simpleName()
                + ", " + idType + ", ColumnMeta, " + entity.visitorName() + ">"
                + (genJson ? " implements WithMapper" : "") + " {");
        out.indent();

        out.line("public static final Class<" + entity.simpleName() + "> ENTITY_CLASS = "
                + entity.simpleName() + ".class;");
        out.line("public static final String ENTITY_NAME = \"" + entity.simpleName() + "\";");
        out.line("public static final int COLUMN_COUNT = " + entity.properties().size() + ";");
        out.blank();

        if (genJson) {
            out.line("private ObjectMapper mapper;");
            out.blank();
        }

        for (PropertyModel property : entity.properties()) {
            out.line("public final ColumnMeta<" + columnType(property.typeText()) + "> "
                    + property.name() + ";");
        }
        out.blank();

        out.line("private final int _ordinal;");
        out.blank();

        out.line("public " + meta + "(HipsterSql hipster, int _ordinal) {");
        out.indent();
        out.line("super(_ordinal, \"" + entity.tableName() + "\", ENTITY_CLASS, hipster);");
        out.line("this._ordinal = _ordinal;");
        out.blank();
        for (PropertyModel property : entity.properties()) {
            out.line(property.name() + " = new ColumnMeta<" + columnType(property.typeText())
                    + ">(" + property.ordinal() + ", \"" + property.name() + "\", \""
                    + property.name() + "\", \"" + property.getterName() + "\", false, this, "
                    + TypeTexts.classLiteral(property.typeText()) + ", "
                    + (TypeTexts.isPrimitive(property.typeText())
                        ? TypeTexts.classLiteral(property.typeText()) : "null")
                    + ", \"\", null"
                    + typeParameters(property.typeText()) + ")"
                    + annotationsSuffix(entity, property, genAnnotations) + ";");
        }
        out.blank();
        out.line("_columnArray = new ColumnMeta[]{" + names(entity.properties()) + "};");
        out.line("_columns = ImmutableList.safe(_columnArray);");
        out.line("_columnCount = COLUMN_COUNT;");
        out.line("_typeHandler = new ICustomType<?>[COLUMN_COUNT];");
        out.line("if (hipster != null) {");
        out.indent();
        out.line("TypeSource _typeSource = hipster.getTypeSource();");
        for (PropertyModel property : entity.properties()) {
            out.line("_typeHandler[" + property.ordinal() + "] = _typeSource.getForRequired("
                    + columnType(property.typeText()) + ".class"
                    + typeParameters(property.typeText()) + ");");
        }
        out.unindent();
        out.line("}");
        if (genMongo) {
            out.line("_codecs = new Codec<?>[COLUMN_COUNT];");
        }

        List<PropertyModel> sorted = new ArrayList<>(entity.properties());
        sorted.sort(Comparator.comparing(PropertyModel::name));
        out.line("_columnArraySorted = new ColumnMeta[]{" + names(sorted) + "};");
        out.line("_columnArraySortedStr = new String[]{" + quotedNames(sorted) + "};");
        out.line("_fieldArraySorted = new ColumnMeta[]{" + names(sorted) + "};");
        out.line("_fieldArraySortedStr = new String[]{" + quotedNames(sorted) + "};");
        PropertyModel keepRest = entity.properties().stream()
                .filter(PropertyModel::keepRest).findFirst().orElse(null);
        if (keepRest != null) {
            out.line("_keepRestColumn = " + keepRest.name() + ";");
        }
        out.unindent();
        out.line("}");
        out.blank();

        if (genJson) {
            out.line("@Override");
            out.line("public ObjectMapper getMapper() {");
            out.indent();
            out.line("return mapper;");
            out.unindent();
            out.line("}");
            out.blank();

            out.line("@Override");
            out.line("public void setMapper(ObjectMapper v) {");
            out.indent();
            out.line("this.mapper = v;");
            out.unindent();
            out.line("}");
            out.blank();
        }

        if (genMongo) {
            out.line("@Override");
            out.line("public void setCodecRegistry(CodecRegistry registry) {");
            out.indent();
            out.line("super.setCodecRegistry(registry);");
            for (PropertyModel property : entity.properties()) {
                if (TypeTexts.isPrimitive(property.typeText())) {
                    continue;
                }
                out.line("_codecs[" + property.ordinal() + "] = registry.get("
                        + columnType(property.typeText()) + ".class);");
            }
            out.unindent();
            out.line("}");
            out.blank();

            emitMongoDecode(out, entity);
            emitMongoEncode(out, entity);
        }

        out.line("@Override");
        out.line("public final " + entity.simpleName() + " fromResultSet(ResultSet rs) throws SQLException {");
        out.indent();
        out.line("String __col = \"\";");
        for (PropertyModel property : entity.properties()) {
            String getter = resultSetGetter(property.typeText());
            if (getter != null) {
                out.line("__col = \"" + property.name() + "\"; " + property.typeText() + " "
                        + property.name() + " = rs." + getter + "(" + (property.ordinal() + 1) + ");");
            } else {
                out.line("__col = \"" + property.name() + "\"; " + property.typeText() + " "
                        + property.name() + " = (" + property.typeText() + ") _typeHandler["
                        + property.ordinal() + "].get(rs, " + (property.ordinal() + 1) + ");");
            }
        }
        out.line(entity.simpleName() + "Immutable out = new " + entity.simpleName() + "Immutable("
                + names(entity.properties()) + ");");
        out.line("this.init(out);");
        out.line("return out;");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public void visitResult(ResultSet rs, " + entity.visitorName()
                + " visitor) throws SQLException {");
        out.indent();
        out.line("String __col = \"\";");
        List<PropertyModel> visited = new ArrayList<>();
        for (PropertyModel property : entity.properties()) {
            if (property.transient_() || property.keepRest()) {
                continue;
            }
            visited.add(property);
            String getter = resultSetGetter(property.typeText());
            if (getter != null) {
                out.line("__col = \"" + property.name() + "\"; " + property.typeText() + " "
                        + property.name() + " = rs." + getter + "(" + (property.ordinal() + 1) + ");");
            } else {
                out.line("__col = \"" + property.name() + "\"; " + property.typeText() + " "
                        + property.name() + " = (" + property.typeText() + ") _typeHandler["
                        + property.ordinal() + "].get(rs, " + (property.ordinal() + 1) + ");");
            }
        }
        out.line("visitor.visit(" + names(visited) + ");");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public Class[] getImplClasses() {");
        out.indent();
        out.line("return new Class[]{" + entity.simpleName() + "Update.class, "
                + entity.simpleName() + "Immutable.class};");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public Class getImmutableClass() {");
        out.indent();
        out.line("return " + entity.simpleName() + "Immutable.class;");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public Class getUpdateClass() {");
        out.indent();
        out.line("return " + entity.simpleName() + "Update.class;");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public String getEntityName() {");
        out.indent();
        out.line("return ENTITY_NAME;");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public ColumnMeta getPrimaryColumn() {");
        out.indent();
        out.line(primary == null ? "return null;" : "return " + primary.name() + ";");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public " + idType + " entityGetPrimary(" + entity.simpleName() + " instance) {");
        out.indent();
        out.line(primary == null ? "return null;"
                : "return instance." + primary.getterName() + "();");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public boolean containsColumn(String columnName) {");
        out.indent();
        out.line("return getColumn(columnName) != null;");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public boolean containsField(String fieldName) {");
        out.indent();
        out.line("return getField(fieldName) != null;");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public " + entity.simpleName() + "Update mutableCopy(" + entity.simpleName()
                + " v) {");
        out.indent();
        out.line("return new " + entity.simpleName() + "Update(v);");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public " + entity.simpleName() + "Immutable immutableCopy("
                + entity.simpleName() + " v) {");
        out.indent();
        out.line("return new " + entity.simpleName() + "Immutable(v);");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public boolean isImmutableVariant(" + entity.simpleName() + " v) {");
        out.indent();
        out.line("return v instanceof " + entity.simpleName() + "Immutable;");
        out.unindent();
        out.line("}");

        out.unindent();
        out.line("}");
        return out.finish();
    }

    /** The {@code ResultSet} getter for a scalar JDBC type, or {@code null} for a handler type. */
    private static String resultSetGetter(String type) {
        return switch (type) {
            case "int", "Integer", "java.lang.Integer" -> "getInt";
            case "long", "Long", "java.lang.Long" -> "getLong";
            case "short", "Short", "java.lang.Short" -> "getShort";
            case "byte", "Byte", "java.lang.Byte" -> "getByte";
            case "float", "Float", "java.lang.Float" -> "getFloat";
            case "double", "Double", "java.lang.Double" -> "getDouble";
            case "boolean", "Boolean", "java.lang.Boolean" -> "getBoolean";
            case "String", "java.lang.String" -> "getString";
            default -> null;
        };
    }

    private static void emitMongoDecode(JavaSourceWriter out, EntityModel entity) {
        out.line("@Override");
        out.line("public " + entity.simpleName() + " decode(BsonReader reader, DecoderContext decoderContext) {");
        out.indent();
        out.line("reader.readStartDocument();");
        out.line("String __field = \"\";");
        for (PropertyModel property : entity.properties()) {
            out.line(localDeclaration(property));
        }
        out.line("while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {");
        out.indent();
        out.line("__field = reader.readName();");
        out.line("switch (__field) {");
        out.indent();
        for (PropertyModel property : entity.properties()) {
            String local = localName(property);
            out.line("case \"" + property.name() + "\":");
            out.indent();
            boolean primitive = TypeTexts.isPrimitive(property.typeText());
            if (!primitive) {
                out.line("if (reader.getCurrentBsonType() == BsonType.NULL) {");
                out.indent();
                out.line("reader.readNull();");
                out.line(local + " = null;");
                out.unindent();
                out.line("} else {");
                out.indent();
            }
            String readerMethod = mongoReader(property.typeText());
            if (readerMethod != null) {
                out.line(local + " = reader." + readerMethod + "();");
            } else {
                out.line(local + " = (" + property.typeText() + ") _codecs["
                        + property.ordinal() + "].decode(reader, decoderContext);");
            }
            if (!primitive) {
                out.unindent();
                out.line("}");
            }
            out.line("break;");
            out.unindent();
        }
        out.line("default: reader.skipValue(); break;");
        out.unindent();
        out.line("}");
        out.unindent();
        out.line("}");
        out.line("reader.readEndDocument();");
        out.line(entity.simpleName() + "Immutable out = new " + entity.simpleName() + "Immutable("
                + localNames(entity.properties()) + ");");
        out.line("this.init(out);");
        out.line("return out;");
        out.unindent();
        out.line("}");
        out.blank();
    }

    private static void emitMongoEncode(JavaSourceWriter out, EntityModel entity) {
        out.line("@Override");
        out.line("public void encode(BsonWriter writer, " + entity.simpleName()
                + " value, EncoderContext encoderContext) {");
        out.indent();
        out.line("writer.writeStartDocument();");
        for (PropertyModel property : entity.properties()) {
            out.line("writer.writeName(\"" + property.name() + "\");");
            boolean primitive = TypeTexts.isPrimitive(property.typeText());
            String writerMethod = mongoWriter(property.typeText());
            if (!primitive) {
                out.line("if (value." + property.getterName() + "() == null) {");
                out.indent();
                out.line("writer.writeNull();");
                out.unindent();
                out.line("} else {");
                out.indent();
            }
            if (writerMethod != null) {
                out.line("writer." + writerMethod + "(value." + property.getterName() + "());");
            } else {
                out.line("((Codec) _codecs[" + property.ordinal() + "]).encode(writer, value."
                        + property.getterName() + "(), encoderContext);");
            }
            if (!primitive) {
                out.unindent();
                out.line("}");
            }
        }
        out.line("writer.writeEndDocument();");
        out.unindent();
        out.line("}");
        out.blank();
    }

    private static String localName(PropertyModel property) {
        return "v_" + property.name();
    }

    private static String localDeclaration(PropertyModel property) {
        String defaultValue = switch (property.typeText()) {
            case "int", "short", "byte" -> "0";
            case "long" -> "0L";
            case "float" -> "0f";
            case "double" -> "0d";
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            default -> "null";
        };
        String suffix = TypeTexts.initialValue(property.typeText(), property.initial());
        if (suffix.isEmpty()) {
            suffix = " = " + defaultValue;
        }
        return property.typeText() + " " + localName(property) + suffix + ";";
    }

    private static String localNames(List<PropertyModel> properties) {
        return properties.stream().map(MetaEmitter::localName)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String mongoReader(String type) {
        return switch (type) {
            case "int", "Integer", "short", "Short", "byte", "Byte" -> "readInt32";
            case "long", "Long" -> "readInt64";
            case "float", "Float", "double", "Double" -> "readDouble";
            case "boolean", "Boolean" -> "readBoolean";
            case "String", "java.lang.String" -> "readString";
            default -> null;
        };
    }

    private static String mongoWriter(String type) {
        return switch (type) {
            case "int", "Integer", "short", "Short", "byte", "Byte" -> "writeInt32";
            case "long", "Long" -> "writeInt64";
            case "float", "Float", "double", "Double" -> "writeDouble";
            case "boolean", "Boolean" -> "writeBoolean";
            case "String", "java.lang.String" -> "writeString";
            default -> null;
        };
    }

    /** The raw Java type used for {@code ColumnMeta<T>}; generics are erased to their raw type. */
    private static String columnType(String type) {
        return TypeTexts.rawType(TypeTexts.boxed(type));
    }

    private static void collectAnnotationImports(ImportCollector imports, EntityModel entity) {
        for (PropertyModel property : entity.properties()) {
            for (AnnotationModel annotation : property.annotations()) {
                if (annotation.name().contains(".")) {
                    continue;
                }
                String fqn = entity.imports().get(annotation.name());
                if (fqn != null) {
                    imports.add(fqn);
                }
            }
        }
    }

    /** The {@code .withAnnotations(...)} suffix for a column, when annotations were requested. */
    private static String annotationsSuffix(EntityModel entity, PropertyModel property,
                                            boolean genAnnotations) {
        if (!genAnnotations || property.annotations().isEmpty()) {
            return "";
        }
        List<String> expressions = new ArrayList<>();
        for (AnnotationModel annotation : property.annotations()) {
            List<String> values = new ArrayList<>();
            boolean supported = true;
            for (String argument : annotation.arguments()) {
                int equals = argument.indexOf('=');
                if (equals < 0 || argument.substring(equals + 1).trim().startsWith("{")) {
                    supported = false;
                    break;
                }
                String name = argument.substring(0, equals).trim();
                String value = argument.substring(equals + 1).trim();
                values.add("\"" + name + "\"");
                values.add(value);
            }
            if (!supported) {
                continue;
            }
            StringBuilder expression = new StringBuilder("HipsterSqlUtil.annotation(")
                    .append(annotationClass(entity, annotation.name()));
            for (String value : values) {
                expression.append(", ").append(value);
            }
            expressions.add(expression.append(")").toString());
        }
        if (expressions.isEmpty()) {
            return "";
        }
        return ".withAnnotations(" + String.join(", ", expressions) + ")";
    }

    private static String annotationClass(EntityModel entity, String name) {
        if (name.contains(".")) {
            return name + ".class";
        }
        String imported = entity.imports().get(name);
        return (imported == null ? name : imported) + ".class";
    }

    private static String typeParameters(String type) {
        List<String> arguments = TypeTexts.typeArguments(type);
        if (arguments.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String argument : arguments) {
            out.append(", ").append(TypeTexts.classLiteral(argument));
        }
        return out.toString();
    }

    private static String names(List<PropertyModel> properties) {
        return properties.stream().map(PropertyModel::name)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String quotedNames(List<PropertyModel> properties) {
        return properties.stream().map(property -> "\"" + property.name() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }
}
