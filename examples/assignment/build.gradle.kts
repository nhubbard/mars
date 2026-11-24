plugins {
  kotlin("jvm") version "2.4.21"
}

repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(25)
}

val marsCli =
    providers.gradleProperty("marsCliJar").orElse("../../mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar")
val cliJar = file(marsCli.get())
val assignment = file(providers.gradleProperty("assignment").getOrElse("assignment1.kts"))
val submission = file(providers.gradleProperty("submission").getOrElse("submission.s"))

dependencies {
  implementation(files(cliJar))
}

val verifyMarsCli =
    tasks.register("verifyMarsCli") {
      doLast {
        check(cliJar.isFile) {
          "MARS CLI JAR is missing at $cliJar. Build it from the MARS repository root " +
              "with ./mars5/gradlew -p mars5 :cli:jar or set marsCliJar in gradle.properties."
        }
      }
    }

tasks.register<JavaExec>("gradeAssignment") {
  group = "verification"
  description = "Grades an assembly submission with a MARS Kotlin assignment."
  dependsOn(verifyMarsCli)
  javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
  classpath = files(cliJar)
  mainClass = "edu.missouristate.mars.cli.MarsCli"
  args("grade", assignment.absolutePath, submission.absolutePath)
}

tasks.check { dependsOn("gradeAssignment") }
