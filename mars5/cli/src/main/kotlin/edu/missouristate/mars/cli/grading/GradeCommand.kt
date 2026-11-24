package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceReader
import edu.missouristate.mars.api.AssemblySourceSnapshot
import java.io.IOException
import java.io.PrintStream
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.filePathPattern
import kotlin.script.experimental.api.isStandalone
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

// The IntelliJ plugin registers the definition through MarsAssignmentDefinitions.
@Suppress("KotlinScriptTemplateNotRegistered")
@KotlinScript(
    displayName = "MARS assignment",
    fileExtension = "kts",
    compilationConfiguration = AssignmentCompilation::class,
)
abstract class AssignmentScript(assignment: Assignment) {
  private val submission = assignment

  fun case(
      name: String,
      instructionLimit: Long = 100_000,
      input: String = "",
      utf8Text: Boolean = false,
      byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
      verify: AssemblyCase.() -> Unit,
  ) = submission.case(name, instructionLimit, input, utf8Text, byteOrder, verify)
}

object AssignmentCompilation :
    ScriptCompilationConfiguration(
        {
          // Leave Gradle and Kotlin main scripts with their dedicated definitions.
          filePathPattern("""(?:.*[/\\])?(?![^/\\]*\.(?:gradle|main)\.kts$)[^/\\]+\.kts""")
          isStandalone(true)
        },
    ) {
  private fun readResolve(): Any = AssignmentCompilation
}

object GradeCommand {
  @JvmStatic
  fun execute(arguments: Array<String>, output: PrintStream, error: PrintStream): Int {
    if (arguments.size < 2) {
      error.println(
          "Usage: mars grade assignment.kts source.s [more sources.s] [--include header.inc]...",
      )
      return 2
    }
    try {
      val script = Path.of(arguments.first())
      require(script.fileName.toString().endsWith(".kts")) {
        "The assignment must be a Kotlin .kts file"
      }
      val assignment = Assignment(captureSubmission(arguments.drop(1)))
      val result =
          BasicJvmScriptingHost()
              .eval(
                  script.toFile().toScriptSource(),
                  createJvmCompilationConfigurationFromTemplate<AssignmentScript> {
                    // IntelliJ supplies the definition classpath when it loads the template.
                    jvm {
                      dependenciesFromClassContext(AssignmentScript::class, wholeClasspath = true)
                    }
                  },
                  ScriptEvaluationConfiguration { constructorArgs(assignment) },
              )
      val evaluated = result.valueOrNull()
      if (evaluated == null) {
        for (report in result.reports) {
          if (report.severity >= ScriptDiagnostic.Severity.ERROR) error.println(report.render())
        }
        return 2
      }
      val returned = evaluated.returnValue
      if (returned is ResultValue.Error) {
        error.println("Assignment error: ${returned.error.message ?: returned.error}")
        return 2
      }
      require(assignment.results.isNotEmpty()) { "The assignment must declare at least one case" }
      for ((name, failure) in assignment.results) {
        if (failure == null) output.println("PASS $name")
        else output.println("FAIL $name: $failure")
      }
      return if (assignment.results.all { it.failure == null }) 0 else 1
    } catch (failure: IllegalArgumentException) {
      error.println(failure.message)
      return 2
    } catch (failure: IOException) {
      error.println(failure.message)
      return 2
    }
  }

  private fun captureSubmission(arguments: List<String>): AssemblySourceSnapshot {
    val entries = mutableListOf<Path>()
    val texts = linkedMapOf<Path, String>()
    val identities = linkedMapOf<Path, Path>()
    var index = 0
    var remaining = 1024 * 1024
    while (index < arguments.size) {
      val include = arguments[index] == "--include"
      if (include) index++
      require(index < arguments.size && !arguments[index].startsWith("--")) {
        "Expected an assembly source path"
      }
      val file = Path.of(arguments[index++]).toAbsolutePath().normalize()
      require(Files.isRegularFile(file)) { "Assembly source is not a regular file: $file" }
      if (!include) entries.add(file)
      if (file in texts) continue
      val size = Files.size(file)
      require(size <= remaining) { "Submission exceeds the 1 MiB source limit" }
      val text =
          AssemblySourceReader.open(file).use { reader ->
            val chars = CharArray(remaining + 1)
            var length = 0
            while (length < chars.size) {
              val count = reader.read(chars, length, chars.size - length)
              if (count == -1) break
              length += count
            }
            require(length <= remaining) { "Submission exceeds the 1 MiB source limit" }
            String(chars, 0, length)
          }
      remaining -= maxOf(text.length, size.toInt())
      texts[file] = text
      identities[file] = file.toRealPath()
    }
    require(entries.isNotEmpty()) { "At least one assembly entry is required" }
    require(entries.distinct().size == entries.size) { "Assembly entries must be unique" }
    return AssemblySourceSnapshot(entries, texts, includeIdentities = identities)
  }
}
