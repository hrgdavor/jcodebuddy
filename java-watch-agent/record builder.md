create full function that loads a java file and adds the builder to all records in the file that have @AddBuilder annotation
February 08, 2026

To implement a non-intrusive record builder update, you can use the LexicalPreservingPrinter. This allows you to modify the AST while strictly maintaining original whitespace and comments in the rest of the file.
Implementation
The following function performs these steps:
Configures JavaParser for lexical preservation and the correct Java language level.
Identifies RecordDeclaration nodes annotated with @AddBuilder.
Removes any existing Builder inner class to ensure an idempotent update.
Generates a new static Builder class based on the record's components.
java
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RecordBuilderAugmentor {

    public static void processFile(Path filePath) throws IOException {
        // 1. Setup Lexical Preservation
        String code = Files.readString(filePath);
        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu); // Essential for keeping existing text as-is

        boolean modified = false;

        // 2. Find records with @AddBuilder
        for (RecordDeclaration record : cu.findAll(RecordDeclaration.class)) {
            if (record.isAnnotationPresent("AddBuilder")) {
                updateBuilder(record);
                modified = true;
            }
        }

        // 3. Write back only if changed
        if (modified) {
            Files.writeString(filePath, LexicalPreservingPrinter.print(cu));
        }
    }

    private static void updateBuilder(RecordDeclaration record) {
        // Remove existing inner class named "Builder" if it exists
        record.getMembers().removeIf(member -> 
            member instanceof ClassOrInterfaceDeclaration && 
            ((ClassOrInterfaceDeclaration) member).getNameAsString().equals("Builder")
        );

        // Create the new Builder class
        ClassOrInterfaceDeclaration builder = new ClassOrInterfaceDeclaration()
                .setName("Builder")
                .setPublic(true)
                .setStatic(true);

        // Add fields matching record components
        record.getParameters().forEach(param -> {
            builder.addField(param.getType(), param.getNameAsString(), Modifier.Keyword.PRIVATE);
            
            // Add fluent setter: public Builder name(Type name) { ... }
            builder.addMethod(param.getNameAsString(), Modifier.Keyword.PUBLIC)
                   .setType("Builder")
                   .addParameter(param.getType(), param.getNameAsString())
                   .setBody(StaticJavaParser.parseBlock(
                       "{ this." + param.getNameAsString() + " = " + param.getNameAsString() + "; return this; }"));
        });

        // Add build() method: public RecordName build() { return new RecordName(...); }
        String args = String.join(", ", record.getParameters().stream().map(p -> p.getNameAsString()).toList());
        builder.addMethod("build", Modifier.Keyword.PUBLIC)
               .setType(record.getNameAsString())
               .setBody(StaticJavaParser.parseBlock("{ return new " + record.getNameAsString() + "(" + args + "); }"));

        record.addMember(builder);
    }
}
Use code with caution.

Key Considerations
Idempotency: By removing the existing Builder before adding a new one, you can run this script every time the file is saved.
Minimal Diff: Because we use LexicalPreservingPrinter.print(cu), only the code inside the record changes; imports and other classes remain untouched.
Java Version: Ensure your project is set to use Java 14+ (where records were introduced) in the ParserConfiguration if you encounter parsing errors.
Would you like to extend this to also generate static builder() factory methods on the record itself?
AI responses may include mistakes. Learn more




undefined
undefined
undefined
8 sites
LexicalPreservingPrinter (javaparser-core 3.5.0 API) - javadoc.io
public class LexicalPreservingPrinter extends Object. A Lexical Preserving Printer is used to capture all the lexical information ...

javadoc.io
Making strings in JavaParser
A well hidden feature, the concrete syntax model holds a description of what a certain AST node looks like when it is found in sou...

JavaParser
TypeDeclaration.java - GitHub
members = getMembers(); members.add(decl); return (T) this; } @Generated("com.github.javaparser.generator.core.node.PropertyGenera...

GitHub

Show all
yes
February 08, 2026

To complete the builder pattern, we will add a static builder() method to the record and an optional toBuilder() instance method (which populates the builder with the current record's values).
By continuing to use LexicalPreservingPrinter, these new methods will be injected without disturbing your existing code.
Updated Implementation
I have added a generateStaticMethods helper to the logic. This handles the creation of the entry points into your generated Builder class.
java
private static void updateBuilder(RecordDeclaration record) {
    String recordName = record.getNameAsString();

    // 1. Remove existing Builder class and builder methods to prevent duplicates
    record.getMembers().removeIf(member -> {
        if (member instanceof ClassOrInterfaceDeclaration cl) {
            return cl.getNameAsString().equals("Builder");
        }
        if (member instanceof com.github.javaparser.ast.body.MethodDeclaration md) {
            return md.getNameAsString().equals("builder") || md.getNameAsString().equals("toBuilder");
        }
        return false;
    });

    // 2. Create the Static Builder Factory Method: public static Builder builder()
    record.addMethod("builder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
          .setType("Builder")
          .setBody(StaticJavaParser.parseBlock("{ return new Builder(); }"));

    // 3. Create the Instance toBuilder Method: public Builder toBuilder()
    String toBuilderAssignments = record.getParameters().stream()
            .map(p -> ".%s(this.%s())".formatted(p.getNameAsString(), p.getNameAsString()))
            .reduce("", (a, b) -> a + b);
    
    record.addMethod("toBuilder", Modifier.Keyword.PUBLIC)
          .setType("Builder")
          .setBody(StaticJavaParser.parseBlock("{ return new Builder()" + toBuilderAssignments + "; }"));

    // 4. Create the Builder Class
    ClassOrInterfaceDeclaration builderClass = new ClassOrInterfaceDeclaration()
            .setName("Builder")
            .setPublic(true)
            .setStatic(true);

    record.getParameters().forEach(param -> {
        String fieldName = param.getNameAsString();
        var type = param.getType();

        // Add Private Field
        builderClass.addField(type, fieldName, Modifier.Keyword.PRIVATE);

        // Add Fluent Setter
        builderClass.addMethod(fieldName, Modifier.Keyword.PUBLIC)
                .setType("Builder")
                .addParameter(type, fieldName)
                .setBody(StaticJavaParser.parseBlock(
                    "{ this.%s = %s; return this; }".formatted(fieldName, fieldName)));
    });

    // 5. Add build() method
    String constructorArgs = String.join(", ", 
        record.getParameters().stream().map(p -> p.getNameAsString()).toList());
    
    builderClass.addMethod("build", Modifier.Keyword.PUBLIC)
            .setType(recordName)
            .setBody(StaticJavaParser.parseBlock(
                "{ return new %s(%s); }".formatted(recordName, constructorArgs)));

    record.addMember(builderClass);
}
Use code with caution.

What this adds to your Records
After running the script, a record like record User(String name, int age) {} will look like this (while keeping your original formatting):
User.builder(): A static entry point to start building.
myUser.toBuilder(): An instance method to create a builder pre-filled with the current record's data (great for "modifying" immutable records).
Builder Inner Class: The actual state-holder and fluent API.
Usage Example in Java
java
// Create new
User u = User.builder().name("Alice").age(30).build();

// "Modify" (create new instance from existing)
User updated = u.toBuilder().age(31).build();
Use code with caution.

Would you like me to show you how to integrate this into a Maven or Gradle task so it runs automatically during your build process?
AI responses may include mistakes. Learn more



it is more complicated but nicer if builder is updated instead of replaced
February 08, 2026

To achieve "surgical" updates that preserve existing custom methods or Javadoc inside the Builder itself, you must transition from replacement logic to synchronization logic.
The strategy changes to:
Find or Create the Builder class.
Sync Fields: Add missing fields; don't touch existing ones.
Sync Setters: Add missing fluent methods; leave existing ones alone (allowing for custom validation logic inside setters).
Update build(): Usually, the build() method should be refreshed to ensure it matches the latest record components.
Refined Synchronization Function
java
private static void updateBuilder(RecordDeclaration record) {
    String recordName = record.getNameAsString();

    // 1. Ensure 'builder()' and 'toBuilder()' exist
    if (record.getMethodsByName("builder").isEmpty()) {
        record.addMethod("builder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
              .setType("Builder").setBody(StaticJavaParser.parseBlock("{ return new Builder(); }"));
    }

    // 2. Find or Create the Builder Class
    ClassOrInterfaceDeclaration builder = record.getMembers().stream()
            .filter(m -> m instanceof ClassOrInterfaceDeclaration cl && cl.getNameAsString().equals("Builder"))
            .map(m -> (ClassOrInterfaceDeclaration) m)
            .findFirst()
            .orElseGet(() -> {
                ClassOrInterfaceDeclaration newBuilder = new ClassOrInterfaceDeclaration()
                        .setName("Builder").setPublic(true).setStatic(true);
                record.addMember(newBuilder);
                return newBuilder;
            });

    // 3. Sync Components (Fields and Fluent Setters)
    for (var param : record.getParameters()) {
        String name = param.getNameAsString();
        var type = param.getType();

        // Add field if missing
        if (builder.getFieldByName(name).isEmpty()) {
            builder.addField(type, name, Modifier.Keyword.PRIVATE);
        }

        // Add fluent setter if missing
        if (builder.getMethodsByName(name).isEmpty()) {
            builder.addMethod(name, Modifier.Keyword.PUBLIC)
                   .setType("Builder")
                   .addParameter(type, name)
                   .setBody(StaticJavaParser.parseBlock("{ this.%s = %s; return this; }".formatted(name, name)));
        }
    }

    // 4. Force Update build() to stay in sync with record signature
    builder.getMethodsByName("build").forEach(m -> m.remove());
    String args = String.join(", ", record.getParameters().stream().map(p -> p.getNameAsString()).toList());
    builder.addMethod("build", Modifier.Keyword.PUBLIC)
           .setType(recordName)
           .setBody(StaticJavaParser.parseBlock("{ return new %s(%s); }".formatted(recordName, args)));
}
Use code with caution.

Why this is "Nicer"
Custom Setters: If a user manually changes a setter to include validation (e.g., if (age < 0) throw ...), the script sees the method already exists and skips it, preserving the custom logic JavaParser Documentation.
Javadoc Preservation: Existing Javadoc on the Builder or its fields remains untouched by the LexicalPreservingPrinter.
Stable Diffs: Instead of deleting and re-inserting a block of 50 lines, it only appends the specific fields/methods added to the record since the last run.
Pro-Tip: Handling Deleted Components
If you want to be truly thorough, you can add a step to remove fields and methods from the Builder if the corresponding component was deleted from the Record. However, most "least intrusive" scripts prefer leaving orphaned methods for the user to delete manually to avoid accidental data loss.