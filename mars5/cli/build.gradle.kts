plugins {
  id("java")
  kotlin("jvm")
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":core"))
  implementation(kotlin("stdlib", kotlin.coreLibrariesVersion))
  implementation(kotlin("scripting-common", kotlin.coreLibrariesVersion))
  implementation(kotlin("scripting-jvm", kotlin.coreLibrariesVersion))
  implementation(kotlin("scripting-jvm-host", kotlin.coreLibrariesVersion))
  compileOnly("org.jetbrains:annotations:26.1.0")
  testCompileOnly("org.jetbrains:annotations:26.1.0")
  testImplementation(platform("org.junit:junit-bom:6.0.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  jvmToolchain(25)
  compilerOptions { allWarningsAsErrors.set(true) }
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
  // Dependency module descriptors do not describe the combined classpath archive.
  exclude("module-info.class", "META-INF/versions/**/module-info.class")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// IntelliJ supplies the Kotlin scripting host. Share the DSL and its discovery marker
// with the plugin without shipping the CLI entry point or a second Kotlin compiler.
val assignmentDefinitionJar = tasks.register<Jar>("assignmentDefinitionJar") {
  archiveBaseName.set("mars-assignment-definition")
  from(sourceSets.main.map { it.output })
  include(
    "edu/missouristate/mars/cli/grading/**",
    "edu/missouristate/mars/cli/HeadlessGuiProxy.class",
    "META-INF/kotlin/script/templates/**",
  )
  exclude("edu/missouristate/mars/cli/grading/GradeCommand*.class")
}

configurations.consumable("assignmentDefinition") {
  outgoing.artifact(assignmentDefinitionJar)
}
