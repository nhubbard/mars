plugins {
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("edu.missouristate:core:1.0-SNAPSHOT")
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

application {
  mainClass = "edu.missouristate.mars.smoke.InstructionLab"
}

val catalogOutput = layout.buildDirectory.dir("instruction-catalog")
val exerciseInstructions = tasks.register<JavaExec>("exerciseInstructions") {
  group = "verification"
  description = "Assemble and execute an isolated MIPS program for every catalog entry."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass = application.mainClass
  args("catalog", catalogOutput.get().asFile.absolutePath)
  inputs.files(sourceSets.main.get().output)
  outputs.dir(catalogOutput)
}

tasks.test {
  useJUnitPlatform()
  inputs.dir("src/main/mips")
}

tasks.named("check") {
  dependsOn(exerciseInstructions)
}

tasks.register<JavaExec>("workbench") {
  group = "application"
  description = "Run the interactive MIPS binary workbench."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass = application.mainClass
  args("run", layout.projectDirectory.file("src/main/mips/workbench.asm").asFile.absolutePath)
  standardInput = System.`in`
}
