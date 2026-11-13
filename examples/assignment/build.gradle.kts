plugins {
    kotlin("jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

val marsCli = providers.gradleProperty("marsCliJar")
    .orElse("../../mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar")
val assignment = layout.projectDirectory.file("assignment1.kts")
val submission = layout.projectDirectory.file("submission.s")

dependencies {
    implementation(files(marsCli))
}

tasks.register<JavaExec>("gradeAssignment") {
    group = "verification"
    description = "Grades the sample assembly submission."
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    classpath = files(marsCli)
    mainClass = "edu.missouristate.mars.cli.MarsCli"
    args("grade", assignment.asFile.absolutePath, submission.asFile.absolutePath)
}

tasks.check { dependsOn("gradeAssignment") }
