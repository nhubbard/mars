plugins {
  id("java")
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  // Phase 1: all source still lives in the root module; mars-cli is a thin launch wrapper.
  // Phase 2: this dependency will be replaced by implementation(project(":mars-core")).
  implementation(project(":"))
  testImplementation(platform("org.junit:junit-bom:6.0.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

tasks.jar {
  manifest {
    attributes("Main-Class" to "edu.missouristate.mars.Mars")
  }
  // Fat jar: include all runtime deps so the jar is self-contained.
  from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
