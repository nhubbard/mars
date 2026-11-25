package edu.missouristate.mars.cli

import edu.missouristate.mars.ApplicationInfo
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.cli.grading.GradeCommand
import edu.missouristate.mars.core.MarsContext.AssemblyPhase
import edu.missouristate.mars.core.configureSimulation
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.mips.hardware.MemoryAddressRange
import edu.missouristate.mars.simulator.ProgramArgumentList
import edu.missouristate.mars.util.FilenameFinder
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Standalone command-line assembler and simulator. */
object MarsCli {
  @JvmStatic
  fun main(arguments: Array<String>) {
    exitProcess(execute(arguments, System.`in`, System.out, System.err))
  }

  /** Executes one independent invocation without exiting the host process. */
  @JvmStatic
  fun execute(
      arguments: Array<String>,
      input: InputStream,
      output: PrintStream,
      error: PrintStream,
  ): Int {
    if (arguments.firstOrNull() == "grade") {
      return GradeCommand.execute(arguments.copyOfRange(1, arguments.size), output, error)
    }
    var assemblyErrorExitCode = 1
    var simulationErrorExitCode = 1
    var simulating = false
    try {
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()
          .use { session ->
            val settings = session.settings
            settings.configureSimulation {
              isDelayedBranchingEnabled = false
              isSelfModifyingCodeEnabled = false
              isStartAtMainEnabled = false
              isExtendedAssemblerEnabled = true
              isWarningsAreErrors = false
              isBareMachineEnabled = false
            }
            val files = ArrayList<Path>()
            val programArguments = ArrayList<String>()
            val dumps = ArrayList<CliMemoryDump>()
            val report = CliMachineReport()
            var reportOutput = output
            var layout = "Default"
            var maximumSteps = 0
            var assembleOnly = false
            var project = false
            var tracePhases = false
            var index = 0
            fun nextValue(description: String): String {
              index++
              return arguments.getOrNull(index)
                  ?: throw IllegalArgumentException("Missing $description")
            }
            while (index < arguments.size) {
              val arg = arguments[index]
              when (arg.lowercase()) {
                "--help",
                "-h",
                "h" -> {
                  output.print(usage())
                  return 0
                }
                "--",
                "pa" -> {
                  programArguments.addAll(arguments.asList().subList(index + 1, arguments.size))
                  break
                }
                "--assemble-only",
                "a" -> assembleOnly = true
                "--trace-phases",
                "d" -> tracePhases = true
                "ad",
                "da" -> {
                  assembleOnly = true
                  tracePhases = true
                }
                "--project",
                "p" -> project = true
                "--self-modifying-code",
                "smc" -> settings.isSelfModifyingCodeEnabled = true
                "--start-at-main",
                "sm" -> settings.isStartAtMainEnabled = true
                "--no-pseudo",
                "np",
                "ne" -> settings.isExtendedAssemblerEnabled = false
                "--utf8-text" -> settings.isUtf8TextEnabled = true
                "--warnings-as-errors",
                "we" -> settings.isWarningsAreErrors = true
                "--messages-to-stderr",
                "me" -> reportOutput = error
                "--brief",
                "b" -> report.brief = true
                "--instruction-count",
                "ic" -> report.countInstructions = true
                "hex" -> report.format = CliMachineReport.Format.HEX
                "dec" -> report.format = CliMachineReport.Format.DECIMAL
                "ascii" -> report.format = CliMachineReport.Format.ASCII
                "--register" -> report.requireRegister(nextValue("register name"), session)
                "--memory-range" -> report.addRange(nextValue("memory range"))
                "nc" -> Unit
                "--dump",
                "dump" ->
                    dumps.add(
                        CliMemoryDump.parse(
                            nextValue("dump segment or address range"),
                            nextValue("dump format"),
                            nextValue("dump filename"),
                        ),
                    )
                "--memory",
                "mc" -> layout = nextValue("memory layout")
                "--assembly-error-exit-code" ->
                    assemblyErrorExitCode = Integer.decode(nextValue("assembly error exit code"))
                "--simulation-error-exit-code" ->
                    simulationErrorExitCode =
                        Integer.decode(nextValue("simulation error exit code"))
                "--max-steps" -> {
                  maximumSteps = Integer.parseInt(nextValue("step limit"))
                  require(maximumSteps >= 1) { "Step limit must be positive" }
                }
                else -> {
                  val number = decode(arg)
                  val assemblyStatus = decodeSuffix(arg, "ae")
                  val simulationStatus = decodeSuffix(arg, "se")
                  when {
                    assemblyStatus != null -> assemblyErrorExitCode = assemblyStatus
                    simulationStatus != null -> simulationErrorExitCode = simulationStatus
                    arg.startsWith('$') -> report.requireRegister(arg, session)
                    else -> {
                      val path = if (arg.startsWith('-')) null else Path.of(arg)
                      when {
                        path != null && Files.isRegularFile(path) -> files.add(path)
                        number != null -> maximumSteps = number
                        MemoryAddressRange.hasAddressPrefix(arg) -> report.addRange(arg)
                        path == null -> throw IllegalArgumentException("Unknown option: $arg")
                        !report.tryAddRegister(arg, session) -> files.add(path)
                      }
                    }
                  }
                }
              }
              index++
            }
            if (files.isEmpty()) {
              error.print(usage())
              return 2
            }
            session.setMemoryConfiguration(layout)
            report.validateRanges(session.memory)
            val sources = if (project) projectSources(files) else files
            val trace = if (tracePhases) reportOutput else null
            val program =
                if (trace == null) session.assemble(sources)
                else
                    session.assemble(sources) { phase ->
                      trace.println(
                          when (phase) {
                            AssemblyPhase.TOKENIZING -> "--------  TOKENIZING BEGINS  -----------"
                            AssemblyPhase.ASSEMBLING -> "--------  ASSEMBLY BEGINS  -----------"
                          },
                      )
                    }
            program.assemblyDiagnostics?.let { error.print(it.generateWarningReport()) }
            try {
              var status = 0
              if (!assembleOnly) {
                ProgramArgumentList(programArguments).storeProgramArguments(session)
                SessionSyscallEnvironment(session.memory, input, output, error).use { io ->
                  program.setSyscallEnvironment(io)
                  simulating = true
                  trace?.println("--------  SIMULATION BEGINS  -----------")
                  val finished =
                      if (report.countInstructions)
                          program.simulate(maximumSteps, report::recordInstructionCount)
                      else program.simulate(maximumSteps)
                  status = if (finished) program.exitCode else 3
                  if (!finished) {
                    if (Thread.currentThread().isInterrupted) {
                      error.println("Execution cancelled.")
                      return 130
                    }
                    error.println("Execution reached the step limit.")
                  }
                }
              }
              trace?.println("\n--------  ALL PROCESSING COMPLETE  -----------")
              return status
            } finally {
              if (simulating) report.write(session, reportOutput)
              for (dump in dumps) dump.write(session, error)
            }
          }
    } catch (failure: ProcessingException) {
      val diagnostics = failure.errors()
      if (diagnostics != null) error.print(diagnostics.generateErrorAndWarningReport())
      else error.println(failure.message ?: "MARS processing failed.")
      return if (simulating) simulationErrorExitCode else assemblyErrorExitCode
    } catch (failure: IllegalArgumentException) {
      error.println(failure.message)
      return 2
    } catch (failure: IllegalStateException) {
      error.println(failure.message)
      return 2
    }
  }

  private fun projectSources(files: List<Path>): List<Path> {
    val lead = files.first().toAbsolutePath().normalize()
    val directory = requireNotNull(lead.parent) { "Project mode needs a lead source file" }
    val sources = linkedSetOf(lead)
    for (file in
        FilenameFinder.getFilenameList(directory.toString(), ApplicationInfo.fileExtensions)) {
      sources.add(Path.of(file).normalize())
    }
    for (file in files) sources.add(file.toAbsolutePath().normalize())
    return sources.toList()
  }

  private fun decodeSuffix(text: String, prefix: String): Int? =
      if (text.startsWith(prefix, ignoreCase = true)) decode(text.substring(prefix.length))
      else null

  private fun decode(text: String): Int? =
      try {
        Integer.decode(text)
      } catch (_: NumberFormatException) {
        null
      }

  private fun usage(): String =
      $$"""
      Usage: mars [options] source.s [more sources.s] [-- program arguments]
             mars grade assignment.kts source.s [more sources.s] [--include header.inc]...
        --assemble-only       Assemble without running (a)
        --trace-phases        Show tokenization, assembly, and simulation phases (d, ad, da)
        --project             Assemble sources beside the first file and any additional files (p)
        --self-modifying-code  Allow writes to text and execution from data (smc)
        --start-at-main        Start at the exported main label (sm)
        --no-pseudo            Accept basic instructions only (np)
        --utf8-text            Use UTF-8 text for .ascii, .asciiz, and console string syscalls
        --warnings-as-errors   Fail assembly on warnings (we)
        --memory LAYOUT        Default, CompactDataAtZero, or CompactTextAtZero (mc)
        --dump REGION FORMAT FILE   Export .text, .data, or an inclusive word range (dump)
                               Formats: %s
        --register NAME       Report a register after execution ($NAME or bare mnemonic)
        --memory-range M-N    Report an inclusive word range after execution (M-N)
        hex | dec | ascii     Select the register and memory display format (default: hex)
        --brief               Omit register names and memory row addresses (b)
        --instruction-count   Report the number of completed instructions (ic)
        --messages-to-stderr  Send reports and phase traces to stderr (me)
        nc                    Keep banner output suppressed (the CLI default)
        --max-steps COUNT      Bound execution and return code 3 at the limit
        COUNT                  Positional step limit (zero or negative means unlimited)
        --assembly-error-exit-code CODE     Override the assembly error status (aeCODE)
        --simulation-error-exit-code CODE   Override the simulation error status (seCODE)
        --help                 Show this help
      Exit codes: simulated exit code, 1 for assembly/runtime errors, 2 for invalid arguments,
                  3 for a step limit, or 130 for interrupted execution.
      """
          .trimIndent()
          .format(DumpFormatLoader.FORMATS.joinToString(", ") { it.commandDescriptor.toString() }) +
          "\n"
}
