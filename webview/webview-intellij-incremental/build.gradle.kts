plugins {
    java
    alias(libs.plugins.intelliJPlatform)
}

group = "hr.hrg.jetbrains.jcodebuddy"
version = providers.gradleProperty("version").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // jcodebuddy-compile, jcodebuddy-watch and jcodebuddy-ipc are this repository's Maven modules. Install
    // them first:
    //   mvn -o -pl :jcodebuddy-compile,:jcodebuddy-watch,:jcodebuddy-ipc install -DskipTests
    mavenLocal()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // These are implementation dependencies, not compileOnly: the plugin must bundle the compiler, its ECJ
    // dependency, the fs-meta client and the live-tool socket into the distributable.
    implementation("hr.hrg.jcodebuddy:jcodebuddy-compile:1.0-SNAPSHOT")
    implementation("hr.hrg.jcodebuddy:jcodebuddy-watch:1.0-SNAPSHOT")
    implementation("hr.hrg.jcodebuddy:jcodebuddy-ipc:1.0-SNAPSHOT")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation(libs.junit)
}

intellijPlatform {
    pluginConfiguration {
        id = "hr.hrg.jetbrains.jcodebuddy.incremental"
        name = "JCodeBuddy Incremental Build"
        version = providers.gradleProperty("version")
        vendor {
            name = "Davor Hrg"
            url = "https://github.com/hrgdavor"
        }
        description = providers.provider {
            "Replaces IntelliJ's Java auto-build with an fs-meta-driven ECJ incremental compiler. " +
                    "Compiler diagnostics are published directly to the editor; no JSON hand-off is used."
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
