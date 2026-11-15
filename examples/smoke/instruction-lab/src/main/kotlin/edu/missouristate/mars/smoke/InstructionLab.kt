package edu.missouristate.mars.smoke

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.MIPSprogram.ExecutionStep
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.Settings
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.simulator.Exceptions.BREAKPOINT_EXCEPTION
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

object InstructionLab {
    private const val STEP_LIMIT = 96
    private val MEMORY: Set<String> =
        setOf(
            "lb",
            "lbu",
            "lh",
            "lhu",
            "lw",
            "ll",
            "lwl",
            "lwr",
            "sb",
            "sh",
            "sw",
            "sc",
            "swl",
            "swr",
            "lwc1",
            "ldc1",
            "swc1",
            "sdc1",
            "l.s",
            "l.d",
            "s.s",
            "s.d",
            "ulw",
            "ulh",
            "ulhu",
            "ld",
            "usw",
            "ush",
            "sd",
        )
    private val DOUBLE_MEMORY: Set<String> = setOf("ldc1", "sdc1", "l.d", "s.d")
    private val TAKEN_TRAPS: Set<String> = setOf("tne", "tnei", "tge", "tgeu", "tgei", "tltiu")
    private val HEADLESS: MarsGuiProxy =
        object : MarsGuiProxy {
            override val isGuiMode = false

            override fun postMessage(message: String) {
                System.err.print(message)
            }

            override fun postRunMessage(message: String) {
                print(message)
            }

            override fun readInput(maxLength: Int): String {
                throw UnsupportedOperationException("Use the console input stream")
            }

            override fun readInputWithPrompt(prompt: String): String {
                throw UnsupportedOperationException("Use the console input stream")
            }

            override fun clearRunConsole() {}
        }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) {
            "Usage: InstructionLab catalog <output-directory> | run <file.asm>"
        }
        val path = Path.of(args[1]).toAbsolutePath().normalize()
        when (args[0]) {
            "catalog" -> exerciseCatalog(path)
            "run" -> runWorkbench(path, System.`in`, System.out)
            else -> throw IllegalArgumentException("Unknown command: " + args[0])
        }
    }

    private fun exerciseCatalog(output: Path) {
        val sources = Files.createDirectories(output.resolve("sources"))
        Files.list(sources).use { generated ->
            for (path in
                generated
                    .filter { path: Path ->
                        path.fileName.toString().matches("\\d{3,}-[a-z0-9.]+\\.asm".toRegex())
                    }
                    .toList()) {
                Files.delete(path)
            }
        }
        val catalog = InstructionDefaults.forTarget(MarsTarget.MARS32).instructionList
        check(!catalog.isEmpty()) { "MARS returned an empty instruction catalog" }
        val lines = ArrayList<String>()
        lines.add(
            "file\tkind\tcatalog form\tassembled form\tmemory layout\tresult\treached probe words\temitted probe words",
        )
        val errors = ArrayList<String>()
        var number = 0
        var basics = 0
        for (instruction in catalog) {
            val basic = instruction is BasicInstruction
            if (basic) {
                basics++
            }
            val probe = Probe.forInstruction(instruction)
            val name: String = "%03d-%s.asm".format(++number, instruction.name)
            val source = sources.resolve(name)
            Files.writeString(source, probe.source(instruction.exampleFormat))
            try {
                val result = execute(source, instruction, probe)
                lines.add(
                    listOf(
                            name,
                            if (basic) "basic" else "pseudo",
                            instruction.exampleFormat,
                            probe.assembly,
                            probe.layout,
                            result.outcome,
                            result.executed,
                            result.emitted,
                        )
                        .joinToString("\t"),
                )
            } catch (failure: Exception) {
                errors.add(name + " (" + probe.assembly + "): " + failure.message)
            }
        }
        Files.write(output.resolve("coverage.tsv"), lines)
        Files.write(output.resolve("failures.txt"), errors)
        check(errors.isEmpty()) {
            errors.size.toString() +
                " of " +
                number +
                " probes failed. See " +
                output.resolve("failures.txt")
        }
        System.out.printf(
            "Executed %d basic and %d pseudo-instruction catalog forms.%n",
            basics,
            number - basics,
        )
        println("Sources and coverage report: " + output)
    }

    private fun execute(path: Path, instruction: Instruction, probe: Probe): Result {
        session(probe.layout).use { session ->
            val program: MIPSprogram
            try {
                program = session.assemble(listOf(path))
            } catch (failure: ProcessingException) {
                throw IllegalStateException(
                    failure.errors()!!.generateErrorAndWarningReport(),
                    failure,
                )
            }
            val symbols = program.localSymbolTable
            val start = symbols!!.getAddress("probe")
            val end = symbols.getAddress("probe_end")
            val words = checkNotNull(program.machineList).filter { it.address in start until end }
            val matches =
                if (instruction is BasicInstruction)
                    words.size == 1 && words.first().instruction?.javaClass == instruction.javaClass
                else
                    words.isNotEmpty() &&
                        words.all { it.address in program.sourceMap().pseudoAddresses() }
            check(matches) { "The emitted probe did not select the catalog instruction" }
            val seen = ArrayList<String>()
            for (step in 0..<STEP_LIMIT) {
                val pc: Int = session.registerFile.getPC()
                val event: ExecutionStep
                try {
                    event = program.executeSessionInstruction()
                } catch (failure: ProcessingException) {
                    val cause: Int =
                        (session.coprocessor0.getValue(Coprocessor0State.CAUSE) ushr 2) and 31
                    if (
                        pc < start ||
                            pc >= end ||
                            probe.expectedException == 0 ||
                            cause != probe.expectedException
                    ) {
                        throw IllegalStateException(
                            failure.errors()!!.generateErrorAndWarningReport(),
                            failure,
                        )
                    }
                    val faultingWord = words.first { it.address == pc }.binaryStatement
                    seen.add(hex(faultingWord))
                    return result("exception " + cause, seen, words)
                }
                if (event.executed && pc >= start && pc < end) {
                    seen.add(hex(event.instruction))
                }
                if (event.stopReason != null) {
                    check(
                        !(event.stopReason != StopEvent.Reason.NORMAL_EXIT ||
                            program.exitCode != 0 ||
                            seen.isEmpty() ||
                            probe.expectedException != 0),
                    ) {
                        "Unexpected stop " +
                            event.stopReason +
                            " with " +
                            seen.size +
                            " probe steps"
                    }
                    return result("exit 0", seen, words)
                }
            }
            throw IllegalStateException("Probe exceeded " + STEP_LIMIT + " instructions")
        }
    }

    private fun result(
        outcome: String,
        seen: List<String>,
        words: List<ProgramStatement>,
    ): Result {
        return Result(
            outcome,
            seen.joinToString(" "),
            words.joinToString(" ") { hex(it.binaryStatement) },
        )
    }

    private fun hex(word: Int): String {
        return String.format(Locale.ROOT, "%08x", word)
    }

    fun runWorkbench(
        path: Path,
        input: InputStream,
        output: PrintStream,
    ) {
        session("Default", MarsTarget.MARS64).use { session ->
            try {
                SessionSyscallEnvironment(session.memory, input, output, output).use { console ->
                    val program: MIPSprogram = session.assemble(listOf(path))
                    program.setSyscallEnvironment(console)
                    check(program.simulate(1_000_000) && program.exitCode == 0) {
                        "Workbench did not exit successfully"
                    }
                }
            } catch (failure: ProcessingException) {
                throw IllegalStateException(
                    failure.errors()!!.generateErrorAndWarningReport(),
                    failure,
                )
            }
        }
    }

    private fun session(layout: String, target: MarsTarget = MarsTarget.MARS32): MarsContext {
        val settings = Settings()
        settings.simulationSettings
            .setMemoryConfiguration(layout)
            .setDelayedBranchingEnabled(false)
            .setBareMachineEnabled(false)
            .setStartAtMainEnabled(false)
            .setExtendedAssemblerEnabled(true)
            .setSelfModifyingCodeEnabled(false)
            .setWarningsAreErrors(false)
        return DaggerMarsComponent.builder()
            .coreModule(CoreModule(settings, 32, target))
            .guiProxy(HEADLESS)
            .build()
            .context()
    }

    private data class Result(val outcome: String, val executed: String, val emitted: String)

    private data class Probe(
        val assembly: String,
        val layout: String,
        val seed: String,
        val branch: Boolean,
        val expectedException: Int,
    ) {
        fun source(original: String): String {
            return """
            # Catalog form: %s
            # Memory configuration: %s. Delayed branching: off.
            .data
            .align 3
            buffer: .space 128
            %s
            single_value: .float 2.5
            .align 3
            double_value: .double 4.0
            second_double: .double 2.0
            third_double: .double 3.0
            .text
            .globl main
            main:
                li ${'$'}t1,12
                li ${'$'}t2,3
                li ${'$'}t3,2
                li ${'$'}v0,10
                lwc1 ${'$'}f1,single_value
                lwc1 ${'$'}f3,single_value
                ldc1 ${'$'}f2,double_value
                ldc1 ${'$'}f4,second_double
                ldc1 ${'$'}f6,third_double
            %s
            probe:
                %s
            probe_end:
                nop
            %s
            target:
                li ${'$'}v0,10
                syscall

            """
                .trimIndent()
                .format(
                    original,
                    layout,
                    if (branch) "" else "label: .word 0x01020304, 0x05060708",
                    seed,
                    assembly,
                    if (branch) "label:" else "",
                )
        }

        companion object {
            fun forInstruction(instruction: Instruction): Probe {
                val name = instruction.name
                var assembly: String =
                    instruction.exampleFormat.replace("label+100000", "label + 100000")
                val memory = MEMORY.contains(name)
                val branch = name.startsWith("b") && name != "break"
                val layout =
                    if (memory && assembly.endsWith(",100")) "CompactDataAtZero" else "Default"
                if (memory && assembly.endsWith(",100000")) {
                    assembly = assembly.replace(",100000", ",0x10010000")
                }
                if (DOUBLE_MEMORY.contains(name) && assembly.endsWith(",-100")) {
                    assembly = assembly.replace(",-100", ",-104")
                }
                var seed = ""
                if (memory && assembly.endsWith("(\$t2)")) {
                    seed =
                        if (assembly.contains("label")) "move \$t2,\$zero"
                        else if (assembly.endsWith("-100(\$t2)"))
                            "la \$t2,buffer\naddiu \$t2,\$t2,100"
                        else "la \$t2,buffer"
                }
                if (name == "jr" || name == "jalr") {
                    seed = "la \$t1,target\nla \$t2,target"
                }
                if (name == "jic" || name == "jialc") {
                    seed = "la \$t2,target\naddiu \$t2,\$t2,100"
                }
                if (name == "eret") {
                    seed = "la \$t0,target\nmtc0 \$t0,$14"
                }
                if (name.startsWith("movz")) {
                    seed = "move \$t3,\$zero"
                }
                if (name.startsWith("movt")) {
                    seed = "c.eq.s \$f1,\$f1\nc.eq.s 1,\$f1,\$f1"
                }
                val overflowProbe = name == "mulou" && assembly.endsWith(",-100")
                val exception =
                    if (name == "break" || overflowProbe) BREAKPOINT_EXCEPTION
                    else if (TAKEN_TRAPS.contains(name)) TRAP_EXCEPTION else 0
                return Probe(assembly, layout, seed, branch, exception)
            }
        }
    }
}
