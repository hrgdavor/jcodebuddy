plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "hr.hrg.watch2"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform()
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugins("com.intellij.java")
        pluginVerifier()
        instrumentationTools()
    }
}

// Automatically bundle the sidecar JAR during build
val copySidecarJar by tasks.registering(Copy::class) {
    from("../jwa-sidecar/target/jwa-sidecar.jar")
    into("build/resources/main/sidecar")
}

tasks.processResources {
    dependsOn(copySidecarJar)
}

intellijPlatform {
    pluginConfiguration {
        id = "hr.hrg.watch2.intellij"
        name = "JWA IntelliJ Bridge"
        vendor {
            name = "Davor Hrg"
        }
        description = "IntelliJ Bridge for Java Watch 2 (JWA). Provides LSP support for record builders and push-jump navigation."
    }
}
