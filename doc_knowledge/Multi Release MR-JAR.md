Building your own Multi-Release JAR (MR-JAR) with Maven allows you to write compatible with multiple JAva versions, but still using features from latest versions or preview features.

For example cutting-edge vector math or use Project Panama's SIMD primitives, while ensuring your parent Java application remains perfectly stable on an older baseline (like Java 11, 17, or 21).

To achieve this in Maven, you use a multi-module project setup combined with the **`maven-compiler-plugin`** and **`maven-jar-plugin`**.

Here is the exact step-by-step blueprint to build your own vector module using the MR-JAR pattern.

## 1. The Strategy

We will create a library that targets **Java 21** as the baseline, but contains specialized SIMD code for **Java 23**.

- Standard code goes to `src/main/java/`
    
- Java 23 optimized vector code goes to `src/main/java23/`
    
- Maven will compile both and output a single JAR containing the specific `META-INF/versions/23` layout.
    

## 2. The Complete `pom.xml` Setup

This single-module POM tells Maven to run a two-phase compilation pass and package it according to the MR-JAR specification.


```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" 
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.emailapp</groupId>
    <artifactId>vector-math-engine</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <executions>
                    <execution>
                        <id>default-compile</id>
                        <configuration>
                            <release>21</release>
                        </configuration>
                    </execution>
                    <execution>
                        <id>compile-java23</id>
                        <phase>compile</phase>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                        <configuration>
                            <release>23</release>
                            <compilerArgs>
                                <arg>--add-modules</arg>
                                <arg>jdk.incubator.vector</arg>
                            </compilerArgs>
                            <compileSourceRoots>
                                <compileSourceRoot>${project.basedir}/src/main/java23</compileSourceRoot>
                            </compileSourceRoots>
                            <outputDirectory>${project.build.outputDirectory}/META-INF/versions/23</outputDirectory>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.1</version>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Multi-Release>true</Multi-Release>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## 3. Directory Layout

Your project directory structure must look exactly like this:

```
vector-math-engine/
 ├── pom.xml
 └── src/
      └── main/
           ├── java/
           │    └── com/emailapp/vector/
           │         ├── VectorEngine.java (Interface / Factory)
           │         └── FallbackVectorEngine.java (Java 21 baseline implementation)
           └── java23/
                └── com/emailapp/vector/
                     └── SpecializedVectorEngine.java (Java 23 SIMD implementation)
```

## 4. Writing the Code: The Factory Pattern

To prevent the JRE 21 environment from crashing when it hits Java 23 classes, you must decouple instantiation using an interface and a factory loader.

### Step A: The Interface (`src/main/java/.../VectorEngine.java`)

```java
package com.emailapp.vector;

public interface VectorEngine {
    float calculateDotProduct(float[] a, float[] b);
}
```

### Step B: The Baseline (`src/main/java/.../FallbackVectorEngine.java`)

This code runs if the user is on Java 21, or if they forgot to pass the `--add-modules` flag.

```java
package com.emailapp.vector;

public class FallbackVectorEngine implements VectorEngine {
    @Override
    public float calculateDotProduct(float[] a, float[] b) {
        float sum = 0.0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
```

### Step C: The Advanced SIMD Core (`src/main/java23/.../SpecializedVectorEngine.java`)

This code uses the incubator features and _only_ executes on Java 23+.

```java
package com.emailapp.vector;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

public class SpecializedVectorEngine implements VectorEngine {
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    @Override
    public float calculateDotProduct(float[] a, float[] b) {
        float sum = 0.0f;
        int upperBound = SPECIES.loopBound(a.length);
        
        // SIMD Parallel Loop Execution
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            var va = FloatVector.fromArray(SPECIES, a, i);
            var vb = FloatVector.fromArray(SPECIES, b, i);
            sum += va.mul(vb).reduceLanes(VectorSpecies.ReductionOp.ADD);
        }
        
        // Clean up remaining array elements scalar-wise
        for (; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
```

### Step D: The Runtime Bootstrap Factory

Add this utility inside `src/main/java/` to allow your application to seamlessly request the best engine dynamically.

```Java
package com.emailapp.vector;

public class VectorEngineFactory {
    public static VectorEngine getEngine() {
        try {
            // Check if the Java 23 SIMD feature module is loaded and visible
            Class.forName("jdk.incubator.vector.FloatVector");
            
            // Instantiating via reflection bypasses static link errors on older JREs
            return (VectorEngine) Class.forName("com.emailapp.vector.SpecializedVectorEngine")
                                        .getDeclaredConstructor()
                                        .newInstance();
        } catch (Throwable t) {
            // Fallback safely to slow loop if on older JDK or missing runtime flags
            return new FallbackVectorEngine();
        }
    }
}
```

## 5. Building and Running

### Build Command

You must run your Maven build on a JDK version that is **equal to or greater than your highest target version** (meaning you must run the build using **JDK 23+** so the compiler has access to the incubator libraries).

```Bash
mvn clean package
```

### How to use your artifact in your main Email App

Add your newly built `vector-math-engine-1.0.0.jar` to your primary application's dependencies. When starting your main system, simply invoke the factory:

```Java
VectorEngine engine = VectorEngineFactory.getEngine();
float score = engine.calculateDotProduct(vectorA, vectorB);
```

If you start your app with `java -jar app.jar`, it runs the standard loop. If you start it with `java --add-modules jdk.incubator.vector -jar app.jar` on a Java 23 JRE, it will instantly switch over to the hardware SIMD pipeline.