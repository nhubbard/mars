import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import org.gradle.language.jvm.tasks.ProcessResources
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES

plugins {
  kotlin("jvm")
  kotlin("plugin.compose")
  jacoco
  id("org.jetbrains.intellij.platform")
  id("org.jetbrains.intellij.platform.grammarkit")
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

val localIdePath = providers.gradleProperty("localIdePath")

dependencies {
  implementation(project(":core")) {
    // The standalone CLI bundles this runtime. IntelliJ supplies it for the plugin.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
  }
  implementation(project(":datapath"))
  testCompileOnly(project(":plugin-jvm-compiler"))
  intellijPlatform {
    if (localIdePath.isPresent) {
      local(localIdePath.get())
    } else {
      intellijIdea("2026.3")
    }
    bundledModule("intellij.platform.buildView")
    bundledModule("intellij.platform.debugger")
    bundledPlugin("com.intellij.modules.compose")
    bundledModule("intellij.platform.jewel.ideLafBridge")
    bundledModule("intellij.platform.jewel.ui")
    bundledModule("intellij.libraries.compose.foundation.desktop")
    bundledModule("intellij.libraries.compose.runtime.desktop")
    pluginModule(project(":plugin-jvm-compiler"))
    testBundledPlugin("com.intellij.java")
    testBundledPlugin("org.editorconfig.editorconfigjetbrains")
    testBundledModule("intellij.platform.debugger.impl.ui")
    testBundledModule("intellij.platform.debugger.impl.shared")
    grammarKit("2023.3.4")
    pluginVerifier("1.411")
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
  pluginVerification {
    ides { current() }
    failureLevel.set(listOf(COMPATIBILITY_PROBLEMS, INTERNAL_API_USAGES, OVERRIDE_ONLY_API_USAGES))
  }
}

kotlin {
  jvmToolchain(25)
  compilerOptions {
    allWarningsAsErrors.set(true)
    // The pinned IntelliJ interfaces already supply JVM defaults.
    jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
  }
}

tasks.test {
  useJUnitPlatform { includeEngines("junit-jupiter") }
  // The 263 platform spells this property "descovery". Its project discovery worker can outlive
  // fixture teardown while macOS enumerates mounts. The test fixtures do not use IDE discovery.
  systemProperty("jetbrains.ide.builtin.descovery.enabled", false)
}

val thirdPartyNotices = layout.projectDirectory.file("licenses/THIRD-PARTY-NOTICES.txt")
val distributionNotices = listOf(
  rootProject.layout.projectDirectory.file("shared/MARSlicense.txt"),
  layout.projectDirectory.file("licenses/Apache-2.0.txt"),
  thirdPartyNotices,
)
tasks.processResources {
  from(rootProject.layout.projectDirectory.dir("shared/help")) {
    include("Acknowledgements.html", "MarsHelpHistory.html")
    into("mars-reference")
  }
  from(distributionNotices) { into("mars-reference") }
}
val pluginArchive = tasks.named<BuildPluginTask>("buildPlugin") {
  from(distributionNotices) { into("licenses") }
}

val verifyPluginNotices = tasks.register("verifyPluginNotices") {
  group = "verification"
  description = "Check the license files and third-party JAR inventory in the installable plugin."
  val archiveFile = pluginArchive.flatMap { it.archiveFile }
  val archiveRoot = pluginArchive.flatMap { it.archiveBaseName }
  val pluginJarName = tasks.named<ComposedJarTask>("composedJar").flatMap { it.archiveFileName }
  val coreJarName = project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFileName }
  val datapathJarName = project(":datapath").tasks.named<Jar>("jar").flatMap { it.archiveFileName }
  inputs.file(archiveFile)
  inputs.files(distributionNotices)
  inputs.property("archiveRoot", archiveRoot)
  inputs.property("pluginJarName", pluginJarName)
  inputs.property("coreJarName", coreJarName)
  inputs.property("datapathJarName", datapathJarName)
  doLast {
    val root = archiveRoot.get()
    ZipFile(archiveFile.get().asFile).use { zip ->
      for (notice in distributionNotices) {
        val path = "$root/licenses/${notice.asFile.name}"
        val entry = checkNotNull(zip.getEntry(path)) { "Plugin archive is missing $path" }
        check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(notice.asFile.readBytes())) {
          "Plugin archive contains a different copy of $path"
        }
      }
      val libraryPrefix = "$root/lib/"
      check(zip.getEntry("${libraryPrefix}modules/mars.jvm.compiler.jar") != null) {
        "The optional JVM compiler module must have its own JAR"
      }
      val bundledJars = zip.entries().asSequence()
        .filter { it.name.startsWith(libraryPrefix) && it.name.endsWith(".jar") }
        .map { it.name.substringAfterLast('/') }
        .toSet() - setOf(pluginJarName.get(), coreJarName.get(), datapathJarName.get(), "mars.jvm.compiler.jar")
      val documentedJars = thirdPartyNotices.asFile.useLines { lines ->
        lines.filter { it.startsWith("JAR ") }.map { it.removePrefix("JAR ") }.toSet()
      }
      check(bundledJars == documentedJars) {
        "Update third-party notices. Undocumented: ${bundledJars - documentedJars}. Absent: ${documentedJars - bundledJars}."
      }
    }
  }
}
tasks.check { dependsOn(verifyPluginNotices) }
tasks.named("verifyPlugin") { dependsOn(verifyPluginNotices) }

jacoco { toolVersion = "0.8.16" }

val compilerClasses = project(":plugin-jvm-compiler").layout.buildDirectory.dir("classes/kotlin/main")

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  additionalClassDirs.from(compilerClasses)
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
  additionalClassDirs.from(compilerClasses)
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
tasks.named<RunIdeTask>("runIde") {
  dependsOn(tasks.check, ":core:check", ":cli:check", ":datapath:check", rootProject.tasks.named("check"))
  val suppliedArchive = providers.gradleProperty("packagedSmokeArchive").map { rootProject.file(it) }
  if (providers.gradleProperty("packagedSmoke").isPresent || suppliedArchive.isPresent) {
    val scenario = layout.projectDirectory.dir("src/qualification")
    val fixture = layout.buildDirectory.dir("packaged-smoke")
    val candidate = suppliedArchive.orElse(pluginArchive.flatMap { it.archiveFile }.map { it.asFile })
    val stagedArchive = layout.buildDirectory.dir("packaged-smoke-input")
      .zip(candidate) { directory, archive -> directory.file(archive.name).asFile }
    if (!suppliedArchive.isPresent) dependsOn(pluginArchive)
    args(fixture.get().asFile.absolutePath)
    systemProperty("testscript.filename", scenario.file("packaged-smoke.ijperf").asFile.absolutePath)
    systemProperty("performance.plugin.playback.runner.useDirectActionCall", "true")
    // Match IntelliJ IDE Starter's test policies in an unattended, disposable IDE.
    systemProperty("jb.consents.confirmation.enabled", false)
    systemProperty("jb.privacy.policy.text", "<!--999.999-->")
    systemProperty("jb.privacy.policy.ai.assistant.text", "<!--999.999-->")
    timeout.set(Duration.ofMinutes(5))

    doFirst {
      val log = sandboxLogDirectory.get().asFile
      project.delete(log.resolve("packaged-smoke-complete.txt"), log.resolve("packaged-smoke-SHA256SUMS"))
      val archive = candidate.get()
      check(archive.isFile) {
        "Packaged smoke ZIP not found at $archive. Keep supplied archives outside build directories cleaned by Gradle."
      }
      // Install and hash the same local copy, even if an external archive is replaced during the replay.
      val staged = archive.copyTo(stagedArchive.get(), overwrite = true)
      project.sync {
        from(scenario.dir("project"))
        into(fixture)
      }
      // Exercise CRLF on every host, independent of the checkout's line ending settings.
      val legacySource = fixture.get().file("legacy.asm").asFile
      legacySource.writeText(legacySource.readText().replace("\r\n", "\n").replace("\n", "\r\n"))
      val plugins = sandboxPluginsDirectory.get().asFile
      project.delete(plugins.resolve(pluginArchive.get().archiveBaseName.get()))
      project.copy {
        from(project.zipTree(staged))
        into(plugins)
      }
      val options = sandboxConfigDirectory.dir("options").get().asFile
      options.mkdirs()
      val trustedPath = fixture.get().asFile.absolutePath
        .replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
      options.resolve("trusted-paths.xml").writeText("""
        <application><component name="Trusted.Paths">
          <option name="TRUSTED_PROJECT_PATHS"><map><entry key="$trustedPath" value="true" /></map></option>
        </component></application>
      """.trimIndent())
    }
    doLast {
      val log = sandboxLogDirectory.get().asFile
      check(log.resolve("packaged-smoke-complete.txt").isFile) {
        "IntelliJ did not finish the packaged smoke scenario. See $log."
      }
      val expected = "MARS packaged smoke\n".encodeToByteArray()
      for (name in listOf("run.ok", "debug.ok")) {
        val result = fixture.get().file(name).asFile
        check(result.isFile && result.readBytes().contentEquals(expected)) {
          "The MARS program did not write the expected result to $result."
        }
      }
      val archive = stagedArchive.get()
      val checksum = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
        .joinToString("") { "%02x".format(it) }
      log.resolve("packaged-smoke-SHA256SUMS").writeText("$checksum  ${archive.name}\n")
      logger.lifecycle("Packaged MARS smoke passed. IDE evidence is in $log.")
    }
  }
}
tasks.matching { it.name.startsWith("prepare") && it.name.contains("Sandbox") }.configureEach {
  mustRunAfter(tasks.named("cleanSandbox"))
}

val parserGenerator = sourceSets.create("parserGenerator") {
  compileClasspath += configurations["intellijPlatformGrammarKit"] + configurations["intellijPlatformClasspath"]
  runtimeClasspath += compileClasspath
}
// The Compose compiler checks its runtime even when compiling the standalone grammar generator.
configurations[parserGenerator.compileOnlyConfigurationName].extendsFrom(configurations["intellijPlatformDependencies"])
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
}

// Keep generated code in the raw report. Only the exact reviewed defensive outcomes are exempt.
val verifyGeneratedParserCoverage = tasks.register("verifyGeneratedParserCoverage") {
  description = "Verify coverage of the generated MIPS parser against its reviewed branch ledger."
  dependsOn(tasks.jacocoTestReport)
  val ledger = layout.projectDirectory.file("coverage/generated-parser-branches.tsv")
  val source = layout.buildDirectory.file("generated/sources/mips/kotlin/edu/missouristate/mars/plugin/language/parser/MipsParser.kt")
  val report = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
  inputs.files(ledger, source, report)
  doLast {
    val records = ledger.asFile.readLines()
    // Grammar-Kit writes native line endings. The branch ledger is shared by all hosts.
    val sourceText = source.get().asFile.readText().replace("\r\n", "\n")
    val hash = MessageDigest.getInstance("SHA-256").digest(sourceText.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    check(records.first() == "# sha256\t$hash") { "Generated parser changed: review its defensive branch ledger" }
    val allowances = records.filterNot { it.startsWith("#") }.associate { line ->
      val fields = line.split('\t')
      fields[0].toInt() to (fields[1].toInt() to fields[2])
    }
    val parser = DocumentBuilderFactory.newDefaultInstance().apply {
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      // JAXP identifies this parser feature by URI. The parser does not fetch it.
      //noinspection HttpUrlsUsage
      setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      isXIncludeAware = false
      isExpandEntityReferences = false
    }.newDocumentBuilder()
    val xml = parser.parse(report.get().asFile)
    val sources = xml.getElementsByTagName("sourcefile")
    val code = sourceText.lines()
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
  from(rootProject.file("../examples")) {
    include("**/*.asm", "**/*.s", "**/*.mars", "**/*.inc")
    exclude("**/build/**", "**/.gradle/**")
  }
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
