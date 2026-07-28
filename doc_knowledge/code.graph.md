# Code graph

Several free, open-source projects rely directly on **JavaParser** to build code property graphs, dependency graphs, and knowledge graphs. Because JavaParser supports both **reading 
(parsing/symbol solving)** and **writing (manipulating/generating code)**, using it as your graph engine makes it seamless to generate boilerplate code in the same pipeline.

---

## Top Free Open-Source Java Graph Projects Using JavaParser

### 1. [Fraunhofer-AISEC / cpg (Code Property Graph)](https://github.com/Fraunhofer-AISEC/cpg)

* **What it is:** A comprehensive open-source library that constructs a **Code Property Graph** (combining AST, Control Flow, and Data Flow). It uses **JavaParser** under the hood as its primary engine for Java.
* **Graph Backend:** Exports directly to **Neo4j**, **Apache TinkerGraph**, or in-memory graph stores.
* **Why it fits:** Highly maintained, enterprise-grade, and free under Apache 2.0.

### 2. [daanvdh / JavaDataFlow](https://github.com/daanvdh/JavaDataFlow)

* **What it is:** A lightweight Java library that uses JavaParser to extract fine-grained **Data Flow Graphs** (tracking parameters, fields, return types, and method calls across classes).
* **Why it fits:** It is written entirely in Java and designed for targeted node-level updates and graph traversals without forcing a full database dependency.

### 3. [rsatrioadi / javapers](https://github.com/rsatrioadi/javapers)

* **What it is:** A open-source Java tool using JavaParser to parse Java source directories and emit a **Labeled Property Graph (LPG)**.
* **Graph Schema:** Models classes, methods, imports, annotations, and inheritance hierarchies into standard graph nodes and edges.

### 4. [scootafew / java-parse-to-graph](https://github.com/scootafew/java-parse-to-graph)

* **What it is:** A clean, minimal Java project that parses a Java repository using JavaParser and streams the resulting AST and call-hierarchy directly into a **Neo4j** instance via Cypher queries.

---

## How to Handle Fast Incremental Updates with JavaParser

When a developer edits a single `.java` file, parsing the entire project again is inefficient. JavaParser doesn't manage persistent storage out of the box, but you can build an **incremental update strategy** in Java with a few lines of logic:

```
[ IDE Save / File Watcher ]
           │
           ▼ (File Path Changed)
[ Parse Single File: StaticJavaParser.parse(file) ]
           │
           ▼
[ Delete Existing Graph Subtree for File URI ]
           │
           ▼
[ Resolve Symbol References (JavaSymbolSolver) ]
           │
           ▼
[ Insert / Patch New Graph Nodes & Edges ]

```

1. **File Watcher Hook:** Attach a `WatchService` (or IDE plugin trigger) to monitor modified `.java` source files.
2. **Selective AST Invalidation:** When `UserService.java` changes, query your graph database to drop only the nodes attached to `file == 'UserService.java'`.
3. **Re-parse & Symbol Solve:** Call `StaticJavaParser.parse(modifiedFile)` to produce an updated `CompilationUnit`. Use `JavaSymbolSolver` to resolve new dependencies.
4. **Patch Graph Delta:** Write the updated `CompilationUnit` nodes back to your graph in **~50–150 ms**.

---

## Using JavaParser for Boilerplate Code Generation

The biggest advantage of using JavaParser for both graph extraction and code generation is that you operate on the exact same `CompilationUnit` data structure.

### Programmatic AST Builder Pattern

You can construct classes, interfaces, methods, annotations, and fields dynamically using JavaParser's fluent builder API:

```java
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.Modifier;

public class BoilerplateGenerator {
    public static String generateDTO(String className, String fieldName, Class<?> fieldType) {
        CompilationUnit cu = new CompilationUnit();
        cu.setPackageDeclaration("com.example.generated");

        // Create Class
        ClassOrInterfaceDeclaration dtoClass = cu.addClass(className)
                .setPublic(true);

        // Add Private Field
        dtoClass.addField(fieldType, fieldName, Modifier.Keyword.PRIVATE);

        // Add Getter & Setter Automatically
        dtoClass.addGetter(fieldType, fieldName);
        dtoClass.addSetter(fieldType, fieldName);

        return cu.toString(); // Output formatted Java code
    }
}

```

### Preserving Code Formatting

When modifying existing files to inject boilerplate (e.g., adding an annotation or interface implementation), wrap the parsing with `LexicalPreservingPrinter`:

```java
CompilationUnit cu = StaticJavaParser.parse(file);
LexicalPreservingPrinter.setup(cu);

// Modify the AST (e.g., inject a method or annotation)
ClassOrInterfaceDeclaration clazz = cu.getClassByName("UserService").get();
clazz.addAnnotation("Service");

// Prints updated code while preserving original comments and formatting
String updatedCode = LexicalPreservingPrinter.print(cu);

```