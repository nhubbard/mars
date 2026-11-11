import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask

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
  compileOnly(project(":plugin"))
  intellijPlatform {
    if (localIdePath.isPresent) {
      local(localIdePath.get())
    } else {
      intellijIdea("2026.3")
    }
    bundledModule("intellij.platform.buildView")
    bundledPlugin("com.intellij.java")
  }
}

intellijPlatform { instrumentCode.set(false) }

kotlin {
  jvmToolchain(25)
  compilerOptions { allWarningsAsErrors.set(true) }
}

// Match the optional content module descriptor name in the packaged lib/modules directory.
tasks.named<ComposedJarTask>("composedJar") { archiveBaseName.set("mars.jvm.compiler") }
