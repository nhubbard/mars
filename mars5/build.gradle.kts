plugins {
  id("java")
  id("idea")
  id("com.diffplug.spotless") version "8.11.0"
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.jspecify:jspecify:1.0.0")
  testImplementation(platform("org.junit:junit-bom:6.0.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.assertj:assertj-core:3.27.7")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

spotless {
  java {
    googleJavaFormat()
  }
}
subprojects {
  plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
      toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
  }
}

// Deprecated public facades remain available to compatibility clients. Production code must
// receive the canonical state through the legacy composition boundary instead.
val verifyLegacyFacadeBoundary = tasks.register("verifyLegacyFacadeBoundary") {
  val applicationSources = files(
    fileTree("legacy/src/main/java") { include("**/*.java") },
    fileTree("plugin/src/main/java") { include("**/*.java") }
  )
  inputs.files(applicationSources)
  doLast {
    val directAccess = Regex("""\b(Globals|RegisterFile|Coprocessor0|Coprocessor1)\s*\.""")
    val violations = applicationSources.files.filter { source ->
      source.name != "Globals.java" &&
          source.relativeTo(projectDir).invariantSeparatorsPath !=
              "legacy/src/main/java/edu/missouristate/mars/legacy/di/LegacyBindings.java" &&
          directAccess.containsMatchIn(source.readText())
    }
    check(violations.isEmpty()) {
      "Deprecated static facade access outside the Dagger boundary: " +
          violations.joinToString { it.relativeTo(projectDir).path }
    }
  }
}
tasks.check { dependsOn(verifyLegacyFacadeBoundary) }
