import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
  kotlin("jvm")
  id("org.jetbrains.intellij.platform.module")
}

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

val localIdePath = providers.gradleProperty("localIdePath")

dependencies {
  compileOnly(project(":core"))
  compileOnly(project(path = ":cli", configuration = "assignmentDefinition"))
  intellijPlatform {
    if (localIdePath.isPresent) {
      local(localIdePath.get())
    } else {
      intellijIdea("2026.3")
    }
    bundledModule("kotlin.scripting")
    bundledModule("intellij.libraries.kotlinc.kotlin.scripting.common")
  }
}

intellijPlatform { instrumentCode.set(false) }

kotlin {
  jvmToolchain(25)
  compilerOptions {
    allWarningsAsErrors.set(true)
    jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
  }
}

tasks.named<ComposedJarTask>("composedJar") { archiveBaseName.set("mars.kotlin.scripting") }
