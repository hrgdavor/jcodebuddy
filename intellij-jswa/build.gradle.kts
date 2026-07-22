plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "hr.hrg.jswa"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform()
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        // JavaScript/TypeScript support is often in Ultimate, but we can provide the LSP client anyway
        pluginVerifier()
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "hr.hrg.jswa.intellij"
        name = "JSWA IntelliJ Bridge"
        vendor {
            name = "Davor Hrg"
        }
        description = "IntelliJ Bridge for JSWA (JavaScript Watch). Provides LSP support for signals and boilerplate reduction."
    }
}
