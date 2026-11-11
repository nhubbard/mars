import java.util.zip.ZipFile

plugins {
  id("java")
  jacoco
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation("com.google.dagger:dagger:2.59.2")
  annotationProcessor("com.google.dagger:dagger-compiler:2.59.2")

  testImplementation(platform("org.junit:junit-bom:6.0.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.assertj:assertj-core:3.26.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

jacoco { toolVersion = "0.8.16" }
tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}

tasks.processResources {
  from(rootProject.layout.projectDirectory.file("shared/MARSlicense.txt"))
}

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.addAll(listOf("-Xlint:rawtypes", "-Xlint:unchecked", "-Werror"))
}

val benchmark = sourceSets.create("benchmark")
configurations[benchmark.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies {
  add(benchmark.implementationConfigurationName, sourceSets.main.get().output)
  add(benchmark.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
  add(benchmark.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
tasks.register<JavaExec>("benchmark") {
  description = "Measure assembly of 10,000 instructions with JMH."
  group = "verification"
  classpath = benchmark.runtimeClasspath
  mainClass.set("org.openjdk.jmh.Main")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
  args("AssemblerBenchmark", "-wi", "1", "-i", "1", "-w", "1s", "-r", "1s", "-f", "1")
}

val verifyCoreIsolation = tasks.register("verifyCoreIsolation") {
  group = "verification"
  description = "Reject desktop, compatibility, and XML dependencies from the core library."
  val sources = fileTree("src/main/java") { include("**/*.java") }
  val archive = tasks.jar.flatMap { it.archiveFile }
  inputs.files(sources)
  inputs.file(archive)
  doLast {
    val forbidden = Regex("(?m)^\\s*import\\s+(javax\\.swing|java\\.awt|edu\\.missouristate\\.mars\\.(Globals|plugin\\.|desktop\\.|util\\.SystemIO))")
    val xmlApi = Regex("""\b(?:javax\.xml|org\.(?:xml\.sax|w3c\.dom|jdom2?|dom4j))\b""")
    val violations = sources.files.filter { file ->
      val text = file.readText()
      forbidden.containsMatchIn(text) || xmlApi.containsMatchIn(text)
    }
    check(violations.isEmpty()) { "Core has UI, compatibility, or XML dependencies: $violations" }
    ZipFile(archive.get().asFile).use { jar ->
      val xmlFiles = jar.entries().asSequence().map { it.name }.filter { it.endsWith(".xml", ignoreCase = true) }.toList()
      check(xmlFiles.isEmpty()) { "Core archive contains XML resources: $xmlFiles" }
    }
  }
}
tasks.check { dependsOn(verifyCoreIsolation, tasks.jacocoTestReport) }
