plugins {
  id("java")
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":core"))
  compileOnly("org.jetbrains:annotations:26.1.0")
  testCompileOnly("org.jetbrains:annotations:26.1.0")
  testImplementation(platform("org.junit:junit-bom:6.0.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
  val cliArchive = tasks.jar.flatMap { it.archiveFile }
  inputs.file(cliArchive).withPathSensitivity(PathSensitivity.NONE)
  systemProperty("mars.cli.jar", cliArchive.get().asFile.absolutePath)
}

tasks.jar {
  dependsOn(configurations.runtimeClasspath)
  manifest {
    attributes("Main-Class" to "edu.missouristate.mars.cli.MarsCli")
  }
  // Fat jar: include all runtime deps so the jar is self-contained.
  from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
