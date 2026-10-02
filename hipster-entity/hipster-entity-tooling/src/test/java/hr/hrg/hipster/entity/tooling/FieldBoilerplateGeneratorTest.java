package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import hr.hrg.hipster.entity.tooling.meta.Property;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class FieldBoilerplateGeneratorTest {

    @Test
    public void generateFieldEnumWithViewMeta() throws Exception {
        Path outputRoot = Files.createTempDirectory("field-boilerplate-generator");
        List<Property> properties = List.of(
                new Property("id", "Long"),
                new Property("type", "String"),
                new Property("amount", "java.math.BigDecimal")
        );

        FieldBoilerplateGenerator.builder("hr.hrg.hipster.entity.paymentmethod", "PaymentMethod", properties)
                .withEnumTypeName("PaymentMethodField")
                .withMetaCreatorBody(
                        "values -> {\n" +
                        "            hr.hrg.hipster.entity.core.EntityReadArray<Long, PaymentMethod, PaymentMethodField> readArray =\n" +
                        "                    new hr.hrg.hipster.entity.core.EntityReadArray<>(PaymentMethodField.class, values);\n" +
                        "            return hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory.createRead(\n" +
                        "                    PaymentMethod.class,\n" +
                        "                    readArray,\n" +
                        "                    NAME_MAPPER);\n" +
                        "        }"
                )
                .withDiscriminatorField("PaymentMethodField.type")
                .withDiscriminatorValue("PAYMENT")
                .withPermittedSubtypeClassNames("BankTransferPaymentMethod.class", "PayPalPaymentMethod.class")
                .withAdditionalImports("hr.hrg.hipster.entity.core.EntityReadArray", "hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory")
                .build()
                .generate(outputRoot);

        Path generatedFile = outputRoot.resolve("hr/hrg/hipster/entity/paymentmethod/PaymentMethodField.java");
        Assertions.assertTrue(Files.exists(generatedFile), "Generated enum file should exist");

        String source = Files.readString(generatedFile);
        Assertions.assertTrue(source.contains("implements FieldDef"));
        Assertions.assertTrue(source.contains("public static final ViewMeta<PaymentMethod, PaymentMethodField> META"));
        Assertions.assertTrue(source.contains("PaymentMethodField.type"));
        Assertions.assertTrue(source.contains("PAYMENT"));
        Assertions.assertTrue(source.contains("NAME_MAPPER"));
    }

    @Test
    public void generatePropertyEnumWithGenericType() throws Exception {
        Path outputRoot = Files.createTempDirectory("field-boilerplate-generator-generic");
        List<Property> properties = List.of(
                new Property("metadata", "Map<String, List<Long>>")
        );

        FieldBoilerplateGenerator.builder("hr.hrg.hipster.entity.test", "ExampleView", properties)
                .withEnumTypeName("ExampleViewProperty")
                .withPropertyEnumMode()
                .generate(outputRoot);

        Path generatedFile = outputRoot.resolve("hr/hrg/hipster/entity/test/ExampleViewProperty.java");
        Assertions.assertTrue(Files.exists(generatedFile));

        String source = Files.readString(generatedFile);
        Assertions.assertTrue(source.contains("public Type getPropertyType()"));
        Assertions.assertTrue(source.contains("return switch (name)"));
        Assertions.assertTrue(source.contains("case \"metadata\" -> metadata;"));
        Assertions.assertTrue(source.contains("TypeUtils.parameterizedType(java.util.Map.class, java.lang.String.class, TypeUtils.parameterizedType(java.util.List.class, java.lang.Long.class))"),
                "Generated code should express the parameterized generic type");
    }

    @Test
    public void generatePropertyEnumWithCustomNamePreservesCustomName() throws Exception {
        Path outputRoot = Files.createTempDirectory("field-boilerplate-generator-custom-name");
        List<Property> properties = List.of(
                new Property("id", "Long")
        );

        FieldBoilerplateGenerator.builder("hr.hrg.hipster.entity.test", "PersonSummary", properties)
                .withPropertyEnumMode()
                .withEnumTypeName("PersonSummary_")
                .generate(outputRoot);

        Path generatedFile = outputRoot.resolve("hr/hrg/hipster/entity/test/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(generatedFile));

        String source = Files.readString(generatedFile);
        Assertions.assertTrue(source.contains("enum PersonSummary_"));
    }

    @Test
    public void generateFieldEnumWithPrimitiveAndArrayTypes() throws Exception {
        Path outputRoot = Files.createTempDirectory("field-boilerplate-generator-primitive");
        List<Property> properties = List.of(
                new Property("count", "int"),
                new Property("tags", "String[]")
        );

        FieldBoilerplateGenerator.builder("hr.hrg.hipster.entity.test", "PrimitiveFieldView", properties)
                .withEnumTypeName("PrimitiveFieldViewField")
                .generate(outputRoot);

        Path generatedFile = outputRoot.resolve("hr/hrg/hipster/entity/test/PrimitiveFieldViewField.java");
        Assertions.assertTrue(Files.exists(generatedFile));

        String source = Files.readString(generatedFile);
        Assertions.assertTrue(source.contains("java.lang.Integer.class"), "int should box to java.lang.Integer.class");
        Assertions.assertTrue(source.contains("java.lang.reflect.Array.newInstance(java.lang.String.class, 0).getClass()"),
                "array types should generate reflection-based class expressions");
    }
}
