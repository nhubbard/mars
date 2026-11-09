import java.security.MessageDigest
import org.gradle.language.jvm.tasks.ProcessResources
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask

plugins {
  kotlin("jvm") version "2.4.21"
  jacoco
  id("org.jetbrains.intellij.platform") version "2.20.0"
  id("org.jetbrains.intellij.platform.grammarkit") version "2.20.0"
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
    bundledModule("intellij.platform.buildView")
    bundledModule("intellij.platform.debugger")
    testBundledModule("intellij.platform.debugger.impl.ui")
    testBundledModule("intellij.platform.debugger.impl.shared")
    grammarKit("2023.3.4")
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

tasks.processResources {
  from(rootProject.layout.projectDirectory.dir("shared/datapath")) { into("datapath") }
}

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
      excludes = listOf("edu.missouristate.mars.plugin.language.parser")
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
tasks.named("runIde") {
  dependsOn(tasks.check, ":core:check", ":cli:check", ":desktop:check", rootProject.tasks.named("check"))
}
tasks.matching { it.name.startsWith("prepare") && it.name.contains("Sandbox") }.configureEach {
  mustRunAfter(tasks.named("cleanSandbox"))
}

val parserGenerator = sourceSets.create("parserGenerator") {
  compileClasspath += configurations["intellijPlatformGrammarKit"] + configurations["intellijPlatformClasspath"]
  runtimeClasspath += compileClasspath
}
val generateMipsParser = tasks.named<GenerateParserTask>("generateParser") {
  sourceFile.set(layout.projectDirectory.file("src/main/grammar/Mips.bnf"))
  targetRootOutputDir.set(layout.buildDirectory.dir("generated/sources/mips/kotlin"))
  // The released CLI hard-codes JavaParserGenerator; this entry point calls KotlinParserGenerator.
  mainClass.set("org.intellij.grammar.GenerateMipsParserKt")
  classpath = parserGenerator.runtimeClasspath
  dependsOn(tasks.named(parserGenerator.classesTaskName))
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
kotlin.sourceSets.named("main") {
  kotlin.srcDir(generateMipsParser.map { it.targetRootOutputDir })
}
tasks.named("compileKotlin") { dependsOn(generateMipsParser) }

sourceSets.named("test") {
  resources.srcDir(project(":core").file("src/test/resources"))
  resources.srcDir(rootProject.file("../examples"))
}

// Keep generated code in the raw report. Only the exact reviewed defensive outcomes are exempt.
val verifyGeneratedParserCoverage = tasks.register("verifyGeneratedParserCoverage") {
  dependsOn(tasks.jacocoTestReport)
  val ledger = layout.projectDirectory.file("coverage/generated-parser-branches.tsv")
  val source = layout.buildDirectory.file("generated/sources/mips/kotlin/edu/missouristate/mars/plugin/language/parser/MipsParser.kt")
  val report = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
  inputs.files(ledger, source, report)
  doLast {
    val records = ledger.asFile.readLines()
    val hash = MessageDigest.getInstance("SHA-256").digest(source.get().asFile.readBytes())
      .joinToString("") { "%02x".format(it) }
    check(records.first() == "# sha256\t$hash") { "Generated parser changed: review its defensive branch ledger" }
    val allowances = records.filterNot { it.startsWith("#") }.associate { line ->
      val fields = line.split('\t')
      fields[0].toInt() to (fields[1].toInt() to fields[2])
    }
    val parser = DocumentBuilderFactory.newInstance().apply {
      setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder()
    val xml = parser.parse(report.get().asFile)
    val sources = xml.getElementsByTagName("sourcefile")
    val code = source.get().asFile.readLines()
    var verified = false
    for (i in 0 until sources.length) {
      val element = sources.item(i)
      if (element.attributes.getNamedItem("name").nodeValue != "MipsParser.kt") continue
      verified = true
      val children = element.childNodes
      for (j in 0 until children.length) {
        val child = children.item(j)
        if (child.nodeName != "line") continue
        val attributes = child.attributes
        val number = attributes.getNamedItem("nr").nodeValue.toInt()
        val missed = attributes.getNamedItem("mb").nodeValue.toInt()
        check(attributes.getNamedItem("ci").nodeValue.toInt() > 0) { "Uncovered generated parser line $number" }
        if (missed == 0) continue
        val allowance = allowances[number]
        check(allowance != null && missed <= allowance.first && code[number - 1].trim() == allowance.second) {
          "Uncovered non-exempt generated parser branches at line $number"
        }
      }
    }
    check(verified) { "Generated parser coverage was not found" }
  }
}
tasks.check { dependsOn(verifyGeneratedParserCoverage) }

tasks.named<ProcessResources>("processTestResources") {
  doLast {
    val programs = project(":core").file("src/test/resources/testPrograms")
    destinationDir.resolve("mars-programs.list").writeText(
      fileTree(programs).matching { include("**/*.asm") }.files
        .map { it.relativeTo(programs).invariantSeparatorsPath }.sorted().joinToString("\n")
    )
  }
}
generateMipsParser.configure {
  doLast {
    val generated = targetRootOutputDir.get().asFile
    check(fileTree(generated).matching { include("**/*.java") }.isEmpty) { "Parser generation must produce Kotlin only" }
    check(fileTree(generated).matching { include("**/MipsParser.kt") }.files.size == 1) { "Kotlin parser was not generated" }
  }
}
