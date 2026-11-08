import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  kotlin("jvm") version "2.4.21"
  jacoco
  id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

val localIdePath = providers.gradleProperty("localIdePath")

dependencies {
  implementation(project(":core"))
  intellijPlatform {
    if (localIdePath.isPresent) {
      local(localIdePath.get())
    } else {
      intellijIdea("2026.3")
    }
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.JUnit5)
    testFramework(TestFrameworkType.JUnit5CodeInsight)
  }
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
  // This bootstrap has no GUI Designer forms, runtime @NotNull checks, or Settings pages.
  // Enable the corresponding generators when those features are introduced.
  instrumentCode.set(false)
  buildSearchableOptions.set(false)
  pluginConfiguration {
    id.set("edu.missouristate.mars")
    name.set("MARS MIPS")
    version.set(project.version.toString())
    ideaVersion {
      sinceBuild.set("263")
      untilBuild.set("263.*")
    }
  }
}

kotlin {
  jvmToolchain(25)
  compilerOptions { allWarningsAsErrors.set(true) }
}

tasks.test { useJUnitPlatform { includeEngines("junit-jupiter") } }

jacoco { toolVersion = "0.8.16" }

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}

// The IDE test classloader can load plugin classes without a source location.
tasks.test {
  extensions.configure<JacocoTaskExtension> {
    isIncludeNoLocationClasses = true
    includes = listOf("edu.missouristate.mars.plugin.*")
  }
}

tasks.jacocoTestCoverageVerification {
  dependsOn(tasks.test)
  violationRules {
    rule {
      element = "PACKAGE"
      includes = listOf("edu.missouristate.mars.plugin*")
      limit {
        counter = "LINE"
        minimum = "1.0".toBigDecimal()
      }
      limit {
        counter = "BRANCH"
        minimum = "1.0".toBigDecimal()
      }
    }
  }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

// A launch always verifies the plugin; callers clean the build and sandbox first.
tasks.named("runIde") { dependsOn(tasks.check) }
tasks.matching { it.name.startsWith("prepare") && it.name.contains("Sandbox") }.configureEach {
  mustRunAfter(tasks.named("cleanSandbox"))
}
