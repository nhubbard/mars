plugins {
  id("java")
  id("com.diffplug.spotless") version "8.11.0"
  id("org.jetbrains.intellij.platform") version "2.20.0" apply false
  kotlin("jvm") version "2.4.21" apply false
  kotlin("plugin.compose") version "2.4.21" apply false
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
  kotlin {
    ktfmt()
  }
}

subprojects {
  // The IntelliJ Platform Gradle plugin applies the IDEA model for IDE import.
  // Keep Gradle from writing or deleting the old file-based project metadata.
  val legacyIdeaTasks = setOf(
    "idea", "ideaModule", "ideaProject", "ideaWorkspace", "openIdea",
    "cleanIdea", "cleanIdeaModule", "cleanIdeaProject", "cleanIdeaWorkspace",
  )
  tasks.configureEach {
    if (name in legacyIdeaTasks) enabled = false
  }
  plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
      toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
    tasks.withType<JavaCompile>().configureEach {
      options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal", "-Werror"))
    }
  }
}

// Retired global facades must not return to production sources.
val verifyNoLegacyRuntime = tasks.register("verifyNoLegacyRuntime") {
  val sources = files(
    fileTree("core/src") { include("**/*.java", "**/*.kt") },
    fileTree("cli/src") { include("**/*.java", "**/*.kt") },
    fileTree("plugin/src") { include("**/*.java", "**/*.kt") },
    fileTree("plugin-jvm-compiler/src") { include("**/*.kt") }
  )
  inputs.files(sources)
  doLast {
    check(!file("legacy/src").exists()) { "The retired legacy runtime source tree has returned" }
    val access = Regex("""\b(Globals|RegisterFile|Coprocessor0|Coprocessor1|LegacyProgram|SystemIO)\s*\.""")
    val violations = sources.files.filter { access.containsMatchIn(it.readText()) }
    check(violations.isEmpty()) {
      "Retired runtime API references: " + violations.joinToString { it.relativeTo(projectDir).path }
    }
  }
}

tasks.check { dependsOn(verifyNoLegacyRuntime) }
