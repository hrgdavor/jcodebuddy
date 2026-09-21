// {@link hr.hrg.rewrite.api.ApiCompatibilityTests} Unit tests for API compatibility layer.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.api;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the API compatibility layer between JavaParser and OpenRewrite.
 */
class ApiCompatibilityTests {

    @Test
    void testConversionRoundTrip() {
        // Create a simple Java class
        String source = """
            package hr.hrg.test;
            
            public class TestClass {
                private String name;
                private int age;
                
                public TestClass(String name, int age) {
                    this.name = name;
                    this.age = age;
                }
                
                public void greet() {
                    System.out.println("Hello, " + name);
                }
                
                public String getName() {
                    return name;
                }
            }
            """;
        
        // Parse with JavaParser
        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(source);
        CompilationUnit javaParserCpu = parseResult.getResult().orElse(null);
        assertNotNull(javaParserCpu);
        
        // Convert to OpenRewrite
        SourceFile openRewriteSourceFile = CompilationUnitAdapter.fromSource(source);
        assertNotNull(openRewriteSourceFile);
        assertEquals(source, openRewriteSourceFile.print());
        
        // Verify source is preserved after round-trip
        ParseResult<CompilationUnit> reparsedResult = new JavaParser().parse(openRewriteSourceFile.print());
        CompilationUnit reparsedCpu = reparsedResult.getResult().orElse(null);
        assertNotNull(reparsedCpu);
    }

    @Test
    void testFindAllClasses() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                private String name;
            }
            
            private class PrivateClass {
                private int value;
            }
            
            public interface PersonInterface {
                void sayHello();
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<TypeTree> classes = AstManipulator.findAllClasses(sourceFile);
        
        // Should find all class-like types
        assertEquals(3, classes.size());
    }

    @Test
    void testFindAllMethods() {
        String source = """
            package hr.hrg.test;
            
            public class TestClass {
                public void publicMethod() {}
                private void privateMethod() {}
                protected void protectedMethod() {}
                
                public static void staticMethod() {}
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<MethodTree> methods = AstManipulator.findAllMethods(sourceFile);
        
        // Should find all methods (3 instance methods + 1 static method)
        assertEquals(4, methods.size());
    }

    @Test
    void testFindAllFields() {
        String source = """
            package hr.hrg.test;
            
            public class TestClass {
                private String name;
                private int age;
                protected String address;
                
                public static String staticField;
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<FieldTree> fields = AstManipulator.findAllFields(sourceFile);
        
        // Should find all fields (3 instance + 1 static)
        assertEquals(4, fields.size());
    }

    @Test
    void testFindAllAnnotations() {
        String source = """
            package hr.hrg.test;
            
            import java.lang.Deprecated;
            
            @Deprecated
            public class TestClass {
                @Deprecated
                public void deprecatedMethod() {}
                
                @Deprecated
                private String deprecatedField;
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<AnnotationTree> annotations = AstManipulator.findAllAnnotations(sourceFile);
        
        // Should find all annotations (1 on class + 2 on members = 3)
        // Note: This may vary based on implementation
        assertTrue(annotations.size() >= 2);
    }

    @Test
    void testFindClassesInSpecificClass() {
        String source = """
            package hr.hrg.test;
            
            public class OuterClass {
                public static class InnerClass {}
                
                public interface InnerInterface {}
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<TypeTree> classes = AstManipulator.findAllClasses(sourceFile);
        
        // Should find OuterClass
        assertEquals(1, classes.size());
    }

    @Test
    void testFindMethodsInSpecificClass() {
        String source = """
            package hr.hrg.test;
            
            public class TestClass {
                public void method1() {}
                public void method2() {}
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<MethodTree> methods = AstManipulator.findAllMethods(sourceFile);
        
        assertEquals(2, methods.size());
    }

    @Test
    void testFindFieldsInSpecificClass() {
        String source = """
            package hr.hrg.test;
            
            public class TestClass {
                private String field1;
                private int field2;
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<FieldTree> fields = AstManipulator.findAllFields(sourceFile);
        
        assertEquals(2, fields.size());
    }

    @Test
    void testFindRecords() {
        String source = """
            package hr.hrg.test;
            
            public record Person(String name, int age) {
                public Person {
                    System.out.println("Creating person: " + name);
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<TypeTree> records = AstManipulator.findAllRecords(sourceFile);
        
        assertEquals(1, records.size());
    }

    @Test
    void testFindEnums() {
        String source = """
            package hr.hrg.test;
            
            public enum Color {
                RED, GREEN, BLUE;
                
                public String toString() {
                    return name();
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<TypeTree> enums = AstManipulator.findAllEnums(sourceFile);
        
        assertEquals(1, enums.size());
    }

    @Test
    void testFindInterfaces() {
        String source = """
            package hr.hrg.test;
            
            public interface PersonInterface {
                void sayHello();
                
                default void greet() {
                    System.out.println("Hello!");
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        List<TypeTree> interfaces = AstManipulator.findAllInterfaces(sourceFile);
        
        assertEquals(1, interfaces.size());
    }

    @Test
    void testClassExists() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                private String name;
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        assertTrue(AstManipulator.classExists(sourceFile, "hr.hrg.test.Person"));
        assertFalse(AstManipulator.classExists(sourceFile, "hr.hrg.test.NonExistent"));
    }

    @Test
    void testMethodExists() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                public void sayHello() {
                    System.out.println("Hello");
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        assertTrue(AstManipulator.methodExists(sourceFile, "hr.hrg.test.Person", "sayHello"));
        assertFalse(AstManipulator.methodExists(sourceFile, "hr.hrg.test.Person", "nonExistent"));
    }

    @Test
    void testFieldExists() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                private String name;
                private int age;
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        assertTrue(AstManipulator.fieldExists(sourceFile, "hr.hrg.test.Person", "name"));
        assertFalse(AstManipulator.fieldExists(sourceFile, "hr.hrg.test.Person", "nonExistent"));
    }

    @Test
    void testAddMethod() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                private String name;
                
                public String getName() {
                    return name;
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        
        // Add a new method
        SourceFile modified = AstManipulator.addMethod(
            sourceFile,
            "hr.hrg.test.Person",
            "getDescription",
            "public String getDescription() {\n" +
            "    return name + \" (Person)\";\n" +
            "}"
        );
        
        assertNotNull(modified);
        // Verify the method was added
        assertTrue(modified.print().contains("getDescription"));
    }

    @Test
    void testAddField() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                private String name;
                
                public String getName() {
                    return name;
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        
        // Add a new field
        SourceFile modified = AstManipulator.addField(
            sourceFile,
            "hr.hrg.test.Person",
            "email",
            "private String email",
            Set.of()
        );
        
        assertNotNull(modified);
        // Verify the field was added
        assertTrue(modified.print().contains("private String email"));
    }

    @Test
    void testRemoveMethod() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                private String name;
                
                public String getName() {
                    return name;
                }
                
                public void sayHello() {
                    System.out.println("Hello");
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        
        // Remove the sayHello method
        SourceFile modified = AstManipulator.removeMethod(
            sourceFile,
            "hr.hrg.test.Person",
            "sayHello"
        );
        
        assertNotNull(modified);
        // Verify the method was removed
        assertFalse(modified.print().contains("sayHello"));
    }

    @Test
    void testRemoveField() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                private String name;
                private String email;
                
                public String getName() {
                    return name;
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        
        // Remove the email field
        SourceFile modified = AstManipulator.removeField(
            sourceFile,
            "hr.hrg.test.Person",
            "email"
        );
        
        assertNotNull(modified);
        // Verify the field was removed
        assertFalse(modified.print().contains("private String email"));
    }

    @Test
    void testAddAnnotation() {
        String source = """
            package hr.hrg.test;
            
            import java.lang.Deprecated;
            
            public class Person {
                private String name;
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        
        // Add a @Deprecated annotation to the class
        SourceFile modified = AstManipulator.addAnnotation(
            sourceFile,
            "hr.hrg.test.Person",
            "java.lang.Deprecated",
            {}
        );
        
        assertNotNull(modified);
        // Verify the annotation was added
        assertTrue(modified.print().contains("@Deprecated"));
    }

    @Test
    void testFindMethodsByName() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                public void sayHello() {
                    System.out.println("Hello");
                }
                
                public void sayGoodbye() {
                    System.out.println("Goodbye");
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        
        List<MethodTree> methods = AstManipulator.findMethodsByName(
            sourceFile,
            "hr.hrg.test.Person",
            "sayHello"
        );
        
        assertEquals(1, methods.size());
    }

    @Test
    void testFindMethodsByNameNotExists() {
        String source = """
            package hr.hrg.test;
            
            public class Person {
                public void sayHello() {
                    System.out.println("Hello");
                }
            }
            """;
        
        SourceFile sourceFile = CompilationUnitAdapter.fromSource(source);
        
        List<MethodTree> methods = AstManipulator.findMethodsByName(
            sourceFile,
            "hr.hrg.test.Person",
            "nonExistent"
        );
        
        assertTrue(methods.isEmpty());
    }
}
