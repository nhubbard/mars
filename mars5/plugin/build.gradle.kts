plugins {
  kotlin("jvm") version "2.4.21"
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
  }
  testImplementation(platform("org.junit:junit-bom:6.0.0"))
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

tasks.test { useJUnitPlatform() }
