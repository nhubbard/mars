package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.AssembledInstruction
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblyResult
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.EquivalenceOccurrence
import edu.missouristate.mars.api.AssemblySymbolMap.MacroDefinition
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.AssemblySymbolMap.SymbolUse
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.Program
import edu.missouristate.mars.assembler.Assembler
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.SourceLine
import edu.missouristate.mars.assembler.Symbol
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.assembler.Token
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.assembler.Tokenizer.EquivalenceDefinition
import edu.missouristate.mars.assembler.Tokenizer.IncludePath
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.core.SessionAssembler
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.Register
import edu.missouristate.mars.simulator.BackStepper
import edu.missouristate.mars.simulator.ProgramArgumentList
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.Map.entry as mapEntry
import java.util.Map.of as immutableMapOf
import java.util.Objects
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.LongConsumer
import java.util.stream.Stream
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert
import org.assertj.core.api.ThrowingConsumer
import org.assertj.core.api.iterable.ThrowingExtractor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

internal class SessionAssemblyTest {
    @TempDir lateinit var directory: Path

    @Test
    fun macrosWithTheSameNameAndArityHaveEqualHashes() {
        val first = Macro()
        first.name = "emit"
        first.addArg("%value")
        val second = Macro()
        second.name = "emit"
        second.addArg("%other")

        Assertions.assertThat<Macro?>(first).isEqualTo(second).hasSameHashCodeAs(second)
        Assertions.assertThat<Macro?>(setOf(first)).contains(second)
    }

    @Test
    fun twoProgramsAssembleAndRunWithoutGlobalSimulationObjects() {
        session("Default").use { first ->
            session("Default").use { second ->
                val firstIo = RecordingEnvironment()
                val secondIo = RecordingEnvironment()
                val firstProgram: MIPSprogram =
                    first.assemble(listOf(source("first.s", program(42, false))))
                val firstResult: Int = first.symbolTable.getAddress("result")
                val secondProgram: MIPSprogram =
                    second.assemble(listOf(source("second.s", program(99, true))))
                val secondResult: Int = second.symbolTable.getAddress("result")
                Assertions.assertThat(secondResult).isEqualTo(firstResult + 4)
                Assertions.assertThat(first.memory.getWord(firstResult)).isEqualTo(42)
                firstProgram.setSyscallEnvironment(firstIo)
                secondProgram.setSyscallEnvironment(secondIo)
                Assertions.assertThat(firstProgram.simulate(100)).isTrue()
                Assertions.assertThat(secondProgram.simulate(100)).isTrue()
                Assertions.assertThat<Int?>(firstIo.printed).containsExactly(43)
                Assertions.assertThat<Int?>(secondIo.printed).containsExactly(100)
                Assertions.assertThat(first.memory.getWord(firstResult)).isEqualTo(43)
                Assertions.assertThat(second.memory.getWord(secondResult)).isEqualTo(100)
                Assertions.assertThat<MIPSprogram>(first.program).isSameAs(firstProgram)
                Assertions.assertThat<MIPSprogram>(second.program).isSameAs(secondProgram)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Default", "CompactDataAtZero", "CompactTextAtZero"])
    fun detachedImagesPreserveAssemblyAndLoadIndependentMachines(layout: String) {
        val file =
            source(
                "image.s",
                $$"""
        .macro increment
          addiu $t0, $t0, 1
        .end_macro
        .text
          nop

        """
                    .trimIndent() + program(42, false).replace($$"addiu $t0, $t0, 1", "increment"),
            )
        val neverCancelled = Runnable {}
        val image: AssemblyImage
        val main: Int
        val result: Int
        val original: MIPSprogram
        session(layout).use { builder ->
            builder.settings.setStartAtMainEnabled(true)
            builder.memory.byteOrder = MemoryState.BIG_ENDIAN
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        builder.captureAssemblyImage(neverCancelled)
                    },
                )
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("No assembled program")
            original = builder.assemble(listOf(file))
            main = builder.registerFile.programCounter
            result = builder.symbolTable.getAddress("result")
            Assertions.assertThat(main).isEqualTo(builder.memory.textBaseAddress + 4)
            image = builder.captureAssemblyImage(neverCancelled)
            builder.memory.setWord(result, 99)
            requireNotNull(builder.memory.getStatementNoNotify(main)).binaryStatement = 0
        }
        Files.delete(file)

        session("Default").use { first ->
            session("Default").use { second ->
                first.loadAssemblyImage(image, neverCancelled)
                second.loadAssemblyImage(image, neverCancelled)
                val installed = Objects.requireNonNull<MIPSprogram>(first.program)
                Assertions.assertThat<InstructionSourceMap?>(installed.sourceMap())
                    .isSameAs(original.sourceMap())
                Assertions.assertThat<AssemblySymbolMap?>(installed.symbolMap())
                    .isSameAs(original.symbolMap())
                Assertions.assertThat(first.memory.getConfiguration().configurationIdentifier())
                    .isEqualTo(layout)
                Assertions.assertThat(first.memory.byteOrder).isEqualTo(MemoryState.BIG_ENDIAN)
                Assertions.assertThat(first.registerFile.programCounter).isEqualTo(main)
                Assertions.assertThat(first.symbolTable.getAddress("result")).isEqualTo(result)
                Assertions.assertThat(first.memory.getWord(result)).isEqualTo(42)
                Assertions.assertThat(
                        requireNotNull(first.memory.getStatementNoNotify(main)).binaryStatement,
                    )
                    .isNotZero()
                val macroSite =
                    installed
                        .sourceMap()
                        .origins()
                        .values
                        .stream()
                        .filter { origin: InstructionSourceMap.Origin? -> origin!!.caller != null }
                        .findFirst()
                        .orElseThrow()
                Assertions.assertThat<AssembledInstruction>(
                        installed.instructionsAt(
                            macroSite.location,
                            neverCancelled,
                        ),
                    )
                    .isEqualTo(original.instructionsAt(macroSite.location, neverCancelled))
                val statement =
                    Objects.requireNonNull<ArrayList<ProgramStatement>?>(installed.machineList)!!
                        .stream()
                        .filter { row: ProgramStatement -> row.instructionOrigin === macroSite }
                        .findFirst()
                        .orElseThrow()
                Assertions.assertThat(ErrorMessage(statement, "failure").filename)
                    .isEqualTo(file.toString())

                val output = RecordingEnvironment()
                installed.setSyscallEnvironment(output)
                Assertions.assertThat(installed.simulate(100)).isTrue()
                Assertions.assertThat<Int?>(output.printed).containsExactly(43)
                Assertions.assertThat(first.memory.getWord(result)).isEqualTo(43)
                Assertions.assertThat(second.memory.getWord(result)).isEqualTo(42)
                Assertions.assertThat(second.registerFile.programCounter).isEqualTo(main)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            first.loadAssemblyImage(
                                image,
                                Runnable {
                                    if (first.program == null) {
                                        throw CancellationException()
                                    }
                                },
                            )
                        },
                    )
                    .isInstanceOf(CancellationException::class.java)
                Assertions.assertThat<MIPSprogram>(first.program).isNull()
                first.loadAssemblyImage(image, neverCancelled)
                Assertions.assertThat(first.memory.getWord(result)).isEqualTo(42)
                Assertions.assertThat(first.registerFile.programCounter).isEqualTo(main)
                Assertions.assertThat(first.backStepper.canBackstep()).isFalse()
            }
        }
    }

    @Test
    fun launchEntryUsesOnlyGlobalMainInAProgramTextSegment() {
        val programs =
            listOf(
                mapEntry(".text\nnop\n.globl main\nmain: nop\n", 0x00400004),
                mapEntry(".text\nnop\nmain: nop\n", 0x00400000),
                mapEntry(".data\n.globl main\nmain: .word 1\n.text\nnop\n", 0x00400000),
                mapEntry(".text\nnop\n.ktext 0x80000180\n.globl main\nmain: nop\n", -0x7ffffe80),
                mapEntry(".text\nnop\n", 0x00400000),
            )
        for (scenario in programs) {
            session("Default").use { builder ->
                session("Default").use { launch ->
                    builder.assemble(listOf(source("launch.s", scenario.key!!)))
                    val image: AssemblyImage = builder.captureAssemblyImage(Runnable {})
                    launch.loadAssemblyImage(image, true, Runnable {})
                    Assertions.assertThat(launch.registerFile.programCounter)
                        .isEqualTo(scenario.value)
                    launch.loadAssemblyImage(image, false, Runnable {})
                    Assertions.assertThat(launch.registerFile.programCounter)
                        .isEqualTo(launch.memory.textBaseAddress)
                }
            }
        }
    }

    @Test
    fun argumentSentinelDoesNotOverlapTheLastStringAtTheDefaultStackPointer() {
        session("Default").use { session ->
            val memory: MemoryState = session.memory
            val argument: String = "x".repeat(memory.stackBaseAddress - memory.stackPointer - 1)
            ProgramArgumentList(arrayOf<String>(argument)).storeProgramArguments(session)
            val stack =
                Objects.requireNonNull<Register?>(
                        session.registerFile.getUserRegister(
                            $$"$sp",
                        ),
                    )!!
                    .valueNoNotify
            val argv =
                Objects.requireNonNull<Register?>(
                        session.registerFile.getUserRegister(
                            $$"$a1",
                        ),
                    )!!
                    .valueNoNotify
            val string = memory.getWord(argv)
            Assertions.assertThat(stack).isLessThan(memory.stackPointer)
            Assertions.assertThat(memory.getWord(stack)).isEqualTo(1)
            Assertions.assertThat(memory.getWord(argv + 4)).isZero()
            Assertions.assertThat(memory.getByte(string)).isEqualTo('x'.code)
            Assertions.assertThat(memory.getByte(string + argument.length - 1)).isEqualTo('x'.code)
            Assertions.assertThat(memory.getByte(string + argument.length)).isZero()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CompactDataAtZero", "CompactTextAtZero"])
    fun oversizedArgumentFrameFailsBeforeWritingMemoryOrRegisters(layout: String) {
        session(layout).use { session ->
            val memory: MemoryState = session.memory
            memory.set(memory.stackBaseAddress, 0x5a, 1)
            val argument: String = "x".repeat(memory.stackBaseAddress - memory.stackLimitAddress)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        ProgramArgumentList(
                                arrayOf<String>(
                                    argument,
                                ),
                            )
                            .storeProgramArguments(session)
                    },
                )
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Program arguments exceed session stack memory")
            Assertions.assertThat(memory.getByte(memory.stackBaseAddress)).isEqualTo(0x5a)
            Assertions.assertThat(
                    Objects.requireNonNull<Register?>(
                            session.registerFile.getUserRegister(
                                $$"$sp",
                            ),
                        )!!
                        .valueNoNotify,
                )
                .isEqualTo(memory.stackPointer)
            Assertions.assertThat(
                    Objects.requireNonNull<Register?>(
                            session.registerFile.getUserRegister(
                                $$"$a0",
                            ),
                        )!!
                        .valueNoNotify,
                )
                .isZero()
        }
    }

    @ParameterizedTest
    @MethodSource("corpus")
    fun existingCorpusAssemblesThroughTheOwnedProgramPath(filename: String?) {
        session("Default").use { owner ->
            val program: MIPSprogram =
                owner.assemble(listOf(Path.of(SimulatorSmokeTest.resourcePath(filename))))
            Assertions.assertThat<ProgramStatement>(program.machineList).isNotEmpty()
        }
    }

    @Test
    fun independentAssembliesAndExecutionsCanRunConcurrently() {
        session("Default").use { first ->
            session("CompactDataAtZero").use { second ->
                val firstSource = source("concurrent-first.s", program(42, false))
                val secondSource = source("concurrent-second.s", program(99, true))
                val gate = CountDownLatch(1)
                Executors.newFixedThreadPool(2).use { executor ->
                    val left: Future<MutableList<Int?>?> =
                        executor.submit<MutableList<Int?>?>(
                            Callable {
                                gate.await()
                                assembleAndRun(first, firstSource)
                            },
                        )
                    val right: Future<MutableList<Int?>?> =
                        executor.submit<MutableList<Int?>?>(
                            Callable {
                                gate.await()
                                assembleAndRun(second, secondSource)
                            },
                        )
                    gate.countDown()
                    Assertions.assertThat<Int?>(left.get()).containsExactly(43)
                    Assertions.assertThat<Int?>(right.get()).containsExactly(100)
                }
                Assertions.assertThat(first.symbolTable.getAddress("main")).isEqualTo(0x400000)
                Assertions.assertThat(second.symbolTable.getAddress("main")).isEqualTo(0x3000)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Default", "CompactDataAtZero", "CompactTextAtZero"])
    fun assemblyUsesTheSessionsConfiguredAddressLayout(layout: String) {
        session(layout).use { session ->
            val program: MIPSprogram =
                session.assemble(listOf(source(layout + ".s", program(12, false))))
            Assertions.assertThat(session.symbolTable.getAddress("result"))
                .isEqualTo(session.memory.dataBaseAddress)
            Assertions.assertThat(session.symbolTable.getAddress("main"))
                .isEqualTo(session.memory.textBaseAddress)
            val output = RecordingEnvironment()
            program.setSyscallEnvironment(output)
            Assertions.assertThat(program.simulate(100)).isTrue()
            Assertions.assertThat<Int?>(output.printed).containsExactly(13)
        }
    }

    @Test
    fun separateKernelHandlerDoesNotChangeTheDefaultSegmentOfUserSources() {
        val handler =
            source(
                "handler.s",
                $$"""
                .ktext 0x80000180
                li $a0, 42
                li $v0, 1
                syscall
                li $v0, 10
                syscall

                """
                    .trimIndent(),
            )
        val lead = source("lead.s", "break\n")
        val sibling = source("sibling.s", "nop\n")
        session("Default").use { session ->
            val program: MIPSprogram = session.assemble(listOf(lead, sibling), handler, extended = true, warnings = false)
            Assertions.assertThat<Int?>(
                    program.sourceMap().breakpoints().get(InstructionSourceMap.Location(lead, 1)),
                )
                .containsExactly(session.memory.textBaseAddress)
            Assertions.assertThat<Int?>(
                    program
                        .sourceMap()
                        .breakpoints()
                        .get(InstructionSourceMap.Location(sibling, 1)),
                )
                .containsExactly(session.memory.textBaseAddress + 4)
            Assertions.assertThat<Int?>(
                    program
                        .sourceMap()
                        .breakpoints()
                        .get(InstructionSourceMap.Location(handler, 2)),
                )
                .containsExactly(session.memory.exceptionHandlerAddress)
            val output = RecordingEnvironment()
            program.setSyscallEnvironment(output)
            Assertions.assertThat(program.simulate(20)).isTrue()
            Assertions.assertThat<Int?>(output.printed).containsExactly(42)
        }
    }

    @Test
    fun multiFileForwardReferencesResolveOnlyThroughTheOwningGlobalScope() {
        session("Default").use { session ->
            val lead =
                source(
                    "lead.s",
                    $$"""
                    .data
                    .globl ptr
                    ptr: .word target
                    .text
                    .globl main
                    main:
                      la $t0, ptr
                      lw $t1, 0($t0)
                      lw $a0, 0($t1)
                      li $v0, 1
                      syscall
                      li $v0, 10
                      syscall

                    """
                        .trimIndent(),
                )
            val library =
                source(
                    "library.s",
                    """
                    .data
                    .globl target
                    target: .word 73
                    private_label: .word 99

                    """
                        .trimIndent(),
                )
            val program: MIPSprogram = session.assemble(listOf(lead, library))
            val target: Int = session.symbolTable.getAddress("target")
            Assertions.assertThat(session.memory.getWord(session.symbolTable.getAddress("ptr")))
                .isEqualTo(target)
            Assertions.assertThat(
                    Objects.requireNonNull<SymbolTable?>(program.localSymbolTable)!!
                        .getAddressLocalOrGlobal("target"),
                )
                .isEqualTo(target)
            Assertions.assertThat<Symbol?>(
                    program.localSymbolTable!!.getSymbolGivenAddressLocalOrGlobal(
                        target.toString(),
                    ),
                )
                .isNotNull()
            Assertions.assertThat(session.symbolTable.getAddress("private_label"))
                .isEqualTo(SymbolTable.NOT_FOUND)
            val output = RecordingEnvironment()
            program.setSyscallEnvironment(output)
            Assertions.assertThat(program.simulate(100)).isTrue()
            Assertions.assertThat<Int?>(output.printed).containsExactly(73)
            val badLead = source("bad-lead.s", ".text\nj private_label\n")
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        session.assemble(
                            listOf(
                                badLead,
                                library,
                            ),
                        )
                    },
                )
                .isInstanceOfSatisfying<ProcessingException?>(
                    ProcessingException::class.java,
                    Consumer { error: ProcessingException? ->
                        Assertions.assertThat(
                                Objects.requireNonNull<ErrorList?>(error!!.errors())!!
                                    .generateErrorReport(),
                            )
                            .contains("private_label", "not found")
                    },
                )
        }
    }

    @Test
    fun nestedIncludesMacrosAndEqvUseTheSameAssemblyOwner() {
        Files.createDirectories(directory.resolve("defs"))
        source(
            "defs/inner.s",
            """
            .eqv VALUE 42
            .macro set_value (%reg)
              li %reg, VALUE
            .end_macro

            """
                .trimIndent(),
        )
        source("defs/outer.s", ".include \"inner.s\"\n")
        val main =
            source(
                "includes.s",
                $$"""
                .include "defs/outer.s"
                .text
                .globl main
                main:
                  set_value ($a0)
                  li $v0, 1
                  syscall
                  li $v0, 10
                  syscall

                """
                    .trimIndent(),
            )
        Assertions.assertThat<Int?>(assembleAndRun(session("Default"), main)).containsExactly(42)
    }

    @ParameterizedTest
    @MethodSource("unicodeSourceEncodings")
    fun readsUnicodeMarkedSourceAndIncludesWithoutShiftingLocations(
        marker: String,
        charset: Charset,
    ) {
        val entry = directory.resolve("entrée.asm")
        val include = directory.resolve("pièce.s")
        Files.write(
            entry,
            encodedSource(
                marker,
                charset,
                $$"\r\n.include \"pièce.s\"\r\n.text\r\nentrée: addiu $t0, $zero, LIMIT",
            ),
        )
        Files.write(
            include,
            encodedSource(
                marker,
                charset,
                ".eqv LIMIT 7\r.data\rtexte: .asciiz \"é\uFEFF\"",
            ),
        )

        TestSessions.create().use { machine ->
            val program: MIPSprogram = machine.assemble(listOf(entry))
            val instruction =
                Objects.requireNonNull<ArrayList<ProgramStatement>?>(program.machineList)!!.first()
            Assertions.assertThat(instruction.binaryStatement).isEqualTo(0x24080007)
            Assertions.assertThat<InstructionSourceMap.Location?>(
                    program.sourceMap().origins().get(instruction.address)!!.location,
                )
                .isEqualTo(InstructionSourceMap.Location(entry, 4))
            val data: Int = machine.memory.dataBaseAddress
            Assertions.assertThat(machine.memory.getByte(data)).isEqualTo(0xe9)
            Assertions.assertThat(machine.memory.getByte(data + 1)).isEqualTo(0xff)
            Assertions.assertThat(machine.memory.getByte(data + 2)).isZero()
            Assertions.assertThat(
                    Objects.requireNonNull<SymbolTable?>(program.localSymbolTable)!!
                        .getAddressLocalOrGlobal("entrée"),
                )
                .isEqualTo(instruction.address)

            val empty = directory.resolve("empty.s")
            Files.write(empty, HexFormat.of().parseHex(marker))
            val source = MIPSprogram(machine)
            source.readSource(empty.toString())
            Assertions.assertThat<String?>(source.sourceList).isEmpty()
        }
    }

    @Test
    fun capturedSourcesAssembleNestedIncludesAndOrderedEntriesWithoutReadingDisk() {
        val main = directory.resolve("main.s")
        val library = directory.resolve("library.s")
        val handler = directory.resolve("handler.s")
        val outer = directory.resolve("outer.s")
        val inner = source("inner.s", ".eqv VALUE 99\n")
        val entries = ArrayList<Path>(listOf(main, library))
        val texts = HashMap<Path, String>()
        texts.put(
            main,
            $$"""
            .include "./outer.s"
            .text
            main:
              emit ($a0)
              li $v0, 1
              syscall
              jal finish
              nop

            """
                .trimIndent(),
        )
        texts.put(library, $$".text\n.globl finish\nfinish: li $v0, 10\nsyscall\n")
        texts.put(handler, $$".text\naddiu $s0, $zero, 17\n")
        texts.put(outer, ".include \"" + inner + "\"\n")
        texts.put(
            directory.resolve("unused/../inner.s"),
            ".eqv VALUE 42\r\n.macro emit (%reg)\r  li %reg, VALUE\n.end_macro",
        )
        val snapshot = AssemblySourceSnapshot(entries, texts)
        entries.clear()
        texts.clear()
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable { (snapshot.entries() as MutableList).clear() },
            )
            .isInstanceOf(UnsupportedOperationException::class.java)
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable { (snapshot.texts() as MutableMap).clear() },
            )
            .isInstanceOf(UnsupportedOperationException::class.java)

        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, handler, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors()).isEmpty()
            Assertions.assertThat(result.succeeded()).isTrue()
            val program = Objects.requireNonNull<MIPSprogram>(session.program)
            Assertions.assertThat<Program?>(result.program()).isSameAs(program)
            Assertions.assertThat(program.filename).isEqualTo(main.toString())
            Assertions.assertThat(program.sourceMap().breakpoints())
                .containsKeys(
                    InstructionSourceMap.Location(handler, 2),
                    InstructionSourceMap.Location(library, 3),
                )
            Assertions.assertThat<SourceLine>(program.sourceLineList)
                .anySatisfy(
                    ThrowingConsumer { line: SourceLine ->
                        Assertions.assertThat(line.filename).isEqualTo(inner.toString())
                    },
                )
            val output = RecordingEnvironment()
            program.setSyscallEnvironment(output)
            Assertions.assertThat(program.simulate(100)).isTrue()
            Assertions.assertThat<Int?>(output.printed).containsExactly(42)
            Assertions.assertThat(session.registerFile.getValue(16)).isEqualTo(17)
        }
        Assertions.assertThat(Files.readString(inner)).isEqualTo(".eqv VALUE 99\n")
        Assertions.assertThat(main).doesNotExist()
        Assertions.assertThat(handler).doesNotExist()
    }

    @Test
    fun sourceMapBindsNestedIncludesMacroInvocationsAndPseudoInstructions() {
        val main = directory.resolve("main.s")
        val outer = directory.resolve("outer.s")
        val definitions = directory.resolve("definitions.s")
        val calls = directory.resolve("calls.s")
        val plain = directory.resolve("plain.s")
        val snapshot =
            AssemblySourceSnapshot(
                listOf(main),
                immutableMapOf(
                    main,
                    """
                    .include "outer.s"
                    .text
                    .include "calls.s"
                    outer
                    .include "plain.s"
                    nop

                    """
                        .trimIndent(),
                    outer,
                    ".include \"definitions.s\"\n",
                    definitions,
                    $$"""
                    .macro inner (%reg)
                      li %reg, 0x12345678
                      nop
                    .end_macro
                    .macro outer
                      inner ($t0)
                      nop
                    .end_macro

                    """
                        .trimIndent(),
                    calls,
                    "# A call in an included file.\nouter\n",
                    plain,
                    $$"addiu $t0, $zero, 1\n",
                ),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors()).isEmpty()
            val assembled = Objects.requireNonNull<Program>(result.program())
            val map = assembled.sourceMap()
            val pc: Int = session.memory.textBaseAddress
            val includedCall =
                InstructionSourceMap.Origin(InstructionSourceMap.Location(calls, 2), null)
            val innerCall =
                InstructionSourceMap.Origin(
                    InstructionSourceMap.Location(definitions, 6),
                    includedCall,
                )
            Assertions.assertThat<Int?, InstructionSourceMap.Origin?>(map.origins()).hasSize(10)
            Assertions.assertThat<InstructionSourceMap.Origin?>(map.origins().get(pc))
                .isEqualTo(
                    InstructionSourceMap.Origin(
                        InstructionSourceMap.Location(definitions, 2),
                        innerCall,
                    ),
                )
            Assertions.assertThat<InstructionSourceMap.Origin?>(map.origins().get(pc + 4))
                .isSameAs(map.origins().get(pc))
            Assertions.assertThat<InstructionSourceMap.Origin?>(map.origins().get(pc + 8))
                .isEqualTo(
                    InstructionSourceMap.Origin(
                        InstructionSourceMap.Location(definitions, 3),
                        innerCall,
                    ),
                )
            Assertions.assertThat<InstructionSourceMap.Origin?>(map.origins().get(pc + 12))
                .isEqualTo(
                    InstructionSourceMap.Origin(
                        InstructionSourceMap.Location(definitions, 7),
                        includedCall,
                    ),
                )
            val expansion =
                Objects.requireNonNull<InstructionSourceMap.Origin>(map.origins().get(pc + 16))
            val caller = Objects.requireNonNull<InstructionSourceMap.Origin>(expansion.caller)
            Assertions.assertThat<InstructionSourceMap.Origin?>(caller.caller)
                .isEqualTo(
                    InstructionSourceMap.Origin(InstructionSourceMap.Location(main, 4), null),
                )
            Assertions.assertThat<InstructionSourceMap.Origin?>(map.origins().get(pc + 32))
                .isEqualTo(
                    InstructionSourceMap.Origin(InstructionSourceMap.Location(plain, 1), null),
                )
            Assertions.assertThat<InstructionSourceMap.Origin?>(map.origins().get(pc + 36))
                .isEqualTo(
                    InstructionSourceMap.Origin(InstructionSourceMap.Location(main, 6), null),
                )
            Assertions.assertThat(map.breakpoints())
                .containsExactlyInAnyOrderEntriesOf(
                    immutableMapOf(
                        InstructionSourceMap.Location(definitions, 2),
                        listOf(pc, pc + 16),
                        InstructionSourceMap.Location(definitions, 3),
                        listOf(pc + 8, pc + 24),
                        InstructionSourceMap.Location(definitions, 6),
                        listOf(pc, pc + 16),
                        InstructionSourceMap.Location(definitions, 7),
                        listOf(pc + 12, pc + 28),
                        InstructionSourceMap.Location(calls, 2),
                        listOf(pc),
                        InstructionSourceMap.Location(main, 4),
                        listOf(pc + 16),
                        InstructionSourceMap.Location(plain, 1),
                        listOf(pc + 32),
                        InstructionSourceMap.Location(main, 6),
                        listOf(pc + 36),
                    ),
                )
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { (map.origins() as MutableMap).clear() },
                )
                .isInstanceOf(UnsupportedOperationException::class.java)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        (map.breakpoints().get(InstructionSourceMap.Location(calls, 2))
                                as MutableList)
                            .clear()
                    },
                )
                .isInstanceOf(UnsupportedOperationException::class.java)
            val expanded: List<AssembledInstruction> =
                assembled.instructionsAt(InstructionSourceMap.Location(calls, 2), Runnable {})
            Assertions.assertThat<AssembledInstruction>(expanded)
                .extracting<Int?, RuntimeException>(AssembledInstruction::address)
                .containsExactly(pc, pc + 4, pc + 8, pc + 12)
            Assertions.assertThat(expanded.first().assembly).startsWith("lui ")
            Assertions.assertThat(expanded.first().encoding).isEqualTo(0x3c011234)
            Assertions.assertThat<InstructionSourceMap.Origin?>(expanded.first().origin)
                .isSameAs(map.origins().get(pc))
            Assertions.assertThat<Int?>(map.pseudoAddresses())
                .containsExactlyInAnyOrder(pc, pc + 4, pc + 16, pc + 20)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        (map.pseudoAddresses() as MutableSet).clear()
                    },
                )
                .isInstanceOf(UnsupportedOperationException::class.java)
            Assertions.assertThat<AssembledInstruction>(assembled.pseudoInstructions(Runnable {}))
                .containsExactly(
                    expanded.get(0),
                    expanded.get(1),
                    assembled
                        .instructionsAt(InstructionSourceMap.Location(main, 4), Runnable {})
                        .get(0),
                    assembled
                        .instructionsAt(InstructionSourceMap.Location(main, 4), Runnable {})
                        .get(1),
                )
            Assertions.assertThat<AssembledInstruction>(
                    assembled.instructionsAt(
                        InstructionSourceMap.Location(
                            definitions,
                            2,
                        ),
                        Runnable {},
                    ),
                )
                .hasSize(4)
            Assertions.assertThat<AssembledInstruction>(
                    assembled.instructionsAt(
                        InstructionSourceMap.Location(
                            main,
                            1,
                        ),
                        Runnable {},
                    ),
                )
                .isEmpty()
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        assembled.instructionsAt(
                            InstructionSourceMap.Location(calls, 2),
                            Runnable {
                                throw CancellationException("stop inspection")
                            },
                        )
                    },
                )
                .isInstanceOf(CancellationException::class.java)
        }
    }

    @Test
    fun symbolMapPreservesExpandedDefinitionsAcrossIncludesAndExports() {
        val main = directory.resolve("main.s")
        val other = directory.resolve("other.s")
        val macros = directory.resolve("macros.s")
        val sources =
            AssemblySourceSnapshot(
                listOf(main, other),
                immutableMapOf(
                    main,
                    """
                    .include "macros.s"
                    .data
                    .byte 1
                    word: .word 2
                    .extern storage 4
                    .text
                    outer (exported_M0)
                    outer (privateLabel)
                    .globl exported_M0
                    reserve(shared)

                    """
                        .trimIndent(),
                    macros,
                    """
                    .macro inner (%entry)
                      %entry:
                      privateLoop: nop
                    .end_macro
                    .macro outer (%name)
                      inner (%name)
                    .end_macro
                    .eqv RESERVE .extern
                    .macro reserve (%name)
                      RESERVE %name 4
                    .end_macro

                    """
                        .trimIndent(),
                    other,
                    ".text\nprivateLabel: j exported_M0\n.extern storage 64\n.extern nextCell 8\n",
                ),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(sources, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors()).isEmpty()
            val map = Objects.requireNonNull<Program?>(result.program())!!.symbolMap()
            val text: Int = session.memory.textBaseAddress
            val firstCall =
                InstructionSourceMap.Origin(InstructionSourceMap.Location(main, 7), null)
            val secondCall =
                InstructionSourceMap.Origin(InstructionSourceMap.Location(main, 8), null)
            val exported =
                InstructionSourceMap.Origin(
                    InstructionSourceMap.Location(macros, 2),
                    InstructionSourceMap.Origin(
                        InstructionSourceMap.Location(macros, 6),
                        firstCall,
                    ),
                )
            val local =
                InstructionSourceMap.Origin(
                    InstructionSourceMap.Location(macros, 2),
                    InstructionSourceMap.Origin(
                        InstructionSourceMap.Location(macros, 6),
                        secondCall,
                    ),
                )
            val storage = InstructionSourceMap.Origin(InstructionSourceMap.Location(main, 5), null)
            val reserved =
                InstructionSourceMap.Origin(
                    InstructionSourceMap.Location(macros, 10),
                    InstructionSourceMap.Origin(InstructionSourceMap.Location(main, 10), null),
                )
            Assertions.assertThat<String?, Definition?>(map.globals())
                .containsExactlyInAnyOrderEntriesOf(
                    immutableMapOf(
                        "exported_M0",
                        Definition(text, AssemblySymbolMap.Kind.TEXT, exported, false, 3),
                        "storage",
                        Definition(
                            session.memory.externBaseAddress,
                            AssemblySymbolMap.Kind.EXTERNAL,
                            storage,
                            false,
                            9,
                        ),
                        "shared",
                        Definition(
                            session.memory.externBaseAddress + 4,
                            AssemblySymbolMap.Kind.EXTERNAL,
                            reserved,
                            false,
                            11,
                        ),
                        "nextCell",
                        Definition(
                            session.memory.externBaseAddress + 8,
                            AssemblySymbolMap.Kind.EXTERNAL,
                            InstructionSourceMap.Origin(
                                InstructionSourceMap.Location(other, 4),
                                null,
                            ),
                            false,
                            9,
                        ),
                    ),
                )
            Assertions.assertThat<SymbolUse?>(
                    map.symbolUses().get(SourceToken(InstructionSourceMap.Location(other, 3), 9)),
                )
                .containsExactly(
                    SymbolUse(
                        SourceToken(InstructionSourceMap.Location(main, 5), 9),
                        "storage",
                        session.memory.externBaseAddress,
                    ),
                )
            Assertions.assertThat<String?, Definition?>(map.locals().get(main))
                .containsExactlyInAnyOrderEntriesOf(
                    immutableMapOf(
                        "word",
                        Definition(
                            session.memory.dataBaseAddress + 4,
                            AssemblySymbolMap.Kind.DATA,
                            InstructionSourceMap.Origin(
                                InstructionSourceMap.Location(main, 4),
                                null,
                            ),
                            false,
                            1,
                        ),
                        "privateLabel",
                        Definition(text + 4, AssemblySymbolMap.Kind.TEXT, local, false, 3),
                        "privateLoop_M1",
                        Definition(
                            text,
                            AssemblySymbolMap.Kind.TEXT,
                            InstructionSourceMap.Origin(
                                InstructionSourceMap.Location(macros, 3),
                                exported.caller,
                            ),
                            true,
                            3,
                        ),
                        "privateLoop_M3",
                        Definition(
                            text + 4,
                            AssemblySymbolMap.Kind.TEXT,
                            InstructionSourceMap.Origin(
                                InstructionSourceMap.Location(macros, 3),
                                local.caller,
                            ),
                            true,
                            3,
                        ),
                    ),
                )
            Assertions.assertThat<String?, Definition?>(map.locals().get(other))
                .containsExactlyEntriesOf(
                    immutableMapOf(
                        "privateLabel",
                        Definition(
                            text + 8,
                            AssemblySymbolMap.Kind.TEXT,
                            InstructionSourceMap.Origin(
                                InstructionSourceMap.Location(other, 2),
                                null,
                            ),
                            false,
                            1,
                        ),
                    ),
                )
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        (map.locals().getValue(main) as MutableMap).clear()
                    },
                )
                .isInstanceOf(UnsupportedOperationException::class.java)
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { (map.globals() as MutableMap).clear() },
                )
                .isInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    @Test
    fun assemblyImageComparisonSeparatesMachineChangesFromSourceNames() {
        val file = directory.resolve("compare.asm")
        val source = $$".data\noriginal: .word 42\n.text\naddiu $a0, $zero, 7\n"
        session("Default").use { session ->
            val assembler = SessionAssembler(session)
            val first = AssemblySourceSnapshot(listOf(file), immutableMapOf(file, source))
            Assertions.assertThat<AssemblyError>(
                    assembler.assembleSnapshot(first, null, extendedAssemblerEnabled = true, warningsAreErrors = false).errors(),
                )
                .isEmpty()
            val image: AssemblyImage = session.captureAssemblyImage(Runnable {})
            for (changed in
                listOf(
                    source.replace("original", "renamed"),
                    source.replace("42", "43"),
                    source.replace(", 7", ", 8"),
                    source + "nop\n",
                )) {
                val snapshot = AssemblySourceSnapshot(listOf(file), immutableMapOf(file, changed))
                Assertions.assertThat<AssemblyError>(
                        assembler.assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false).errors(),
                    )
                    .isEmpty()
                val next: AssemblyImage = session.captureAssemblyImage(Runnable {})
                Assertions.assertThat(image.hasSameMachineImage(next, Runnable {}))
                    .isEqualTo(changed.contains("renamed"))
            }
        }
    }

    @Test
    fun symbolUsesKeepWrittenColumnsThroughMacroForwardingAndDataBackpatches() {
        val main = directory.resolve("main.s")
        val other = directory.resolve("other.s")
        val macros = directory.resolve("macros.txt")
        val sources =
            AssemblySourceSnapshot(
                listOf(main, other),
                immutableMapOf(
                    main,
                    """
                    .include "macros.txt"
                    .eqv TARGET BASE
                    .data
                    first: .word target
                    .text
                    branch (target)
                    j TARGET
                    local: nop
                    .data
                    .word local
                    .word future
                    future: .word 0
                    .word TARGET, TARGET
                    .text
                    branch (TARGET)

                    """
                        .trimIndent(),
                    other,
                    ".text\ntarget: nop\n.globl target\n",
                    macros,
                    """
                    .eqv BASE target
                    .macro inner (%label)
                      j %label
                    .end_macro
                    .macro branch (%label)
                      inner(%label)
                    .end_macro

                    """
                        .trimIndent(),
                ),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(sources, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors()).isEmpty()
            Assertions.assertThat(
                    Objects.requireNonNull<Program?>(result.program())!!.symbolMap().macros(),
                )
                .containsExactlyInAnyOrderEntriesOf(
                    immutableMapOf(
                        main,
                        listOf(
                            MacroDefinition(
                                SourceToken(InstructionSourceMap.Location(macros, 2), 8),
                                "inner",
                                listOf("%label"),
                            ),
                            MacroDefinition(
                                SourceToken(InstructionSourceMap.Location(macros, 5), 8),
                                "branch",
                                listOf("%label"),
                            ),
                        ),
                        other,
                        emptyList<MacroDefinition>(),
                    ),
                )
            val symbols = Objects.requireNonNull<Program?>(result.program())!!.symbolMap()
            val target =
                SymbolUse(
                    SourceToken(InstructionSourceMap.Location(other, 2), 1),
                    "target",
                    symbols.globals().get("target")!!.address,
                )
            val alias = SourceToken(InstructionSourceMap.Location(main, 2), 6)
            val base = SourceToken(InstructionSourceMap.Location(macros, 1), 6)
            val invocation = SourceToken(InstructionSourceMap.Location(main, 7), 3)
            Assertions.assertThat<EquivalenceOccurrence?>(
                    Objects.requireNonNull<Program?>(result.program())!!.symbolMap()
                        .equivalences()
                        .get(main),
                )
                .contains(
                    EquivalenceOccurrence(alias, alias, alias, "TARGET", true),
                    EquivalenceOccurrence(base, base, base, "BASE", true),
                    EquivalenceOccurrence(invocation, invocation, alias, "TARGET", false),
                    EquivalenceOccurrence(
                        invocation,
                        SourceToken(InstructionSourceMap.Location(main, 2), 13),
                        base,
                        "BASE",
                        false,
                    ),
                )
            val aliasedTarget =
                SymbolUse(
                    target.definition,
                    target.name,
                    target.address,
                    SourceToken(InstructionSourceMap.Location(macros, 1), 11),
                )
            val local =
                SymbolUse(
                    SourceToken(InstructionSourceMap.Location(main, 8), 1),
                    "local",
                    symbols.locals().get(main)!!.get("local")!!.address,
                )
            val future =
                SymbolUse(
                    SourceToken(InstructionSourceMap.Location(main, 12), 1),
                    "future",
                    symbols.locals().get(main)!!.get("future")!!.address,
                )
            val uses: Map<SourceToken, Set<SymbolUse>> = symbols.symbolUses()
            Assertions.assertThat(uses)
                .containsExactlyInAnyOrderEntriesOf(
                    immutableMapOf(
                        SourceToken(InstructionSourceMap.Location(main, 4), 14),
                        setOf(target),
                        SourceToken(InstructionSourceMap.Location(main, 6), 9),
                        setOf(target),
                        SourceToken(InstructionSourceMap.Location(main, 7), 3),
                        setOf(aliasedTarget),
                        SourceToken(InstructionSourceMap.Location(main, 10), 7),
                        setOf(local),
                        SourceToken(InstructionSourceMap.Location(main, 11), 7),
                        setOf(future),
                        SourceToken(InstructionSourceMap.Location(main, 13), 7),
                        setOf(aliasedTarget),
                        SourceToken(InstructionSourceMap.Location(main, 13), 15),
                        setOf(aliasedTarget),
                        SourceToken(InstructionSourceMap.Location(main, 15), 9),
                        setOf(aliasedTarget),
                        SourceToken(InstructionSourceMap.Location(other, 3), 8),
                        setOf(target),
                    ),
                )
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable {
                        (uses.getValue(
                                SourceToken(
                                    InstructionSourceMap.Location(
                                        main,
                                        4,
                                    ),
                                    14,
                                ),
                            ) as MutableSet)
                            .clear()
                    },
                )
                .isInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    @Test
    fun symbolUseKeepsFinalAddressAfterDataAlignment() {
        val file =
            source("aligned-binding.s", ".data\n.byte 1\naligned: .word aligned\n.text\nnop\n")
        session("Default").use { session ->
            val result =
                SessionAssembler(session)
                    .assembleSnapshot(
                        AssemblySourceSnapshot(
                            listOf(file),
                            immutableMapOf(file, Files.readString(file)),
                        ),
                        null,
                        true,
                        false,
                    )
            Assertions.assertThat<AssemblyError>(result.errors()).isEmpty()
            val uses: Map<SourceToken, Set<SymbolUse>> =
                Objects.requireNonNull<Program?>(result.program())!!.symbolMap().symbolUses()
            Assertions.assertThat<SymbolUse?>(
                    uses.get(SourceToken(InstructionSourceMap.Location(file, 3), 16)),
                )
                .containsExactly(
                    SymbolUse(
                        SourceToken(InstructionSourceMap.Location(file, 3), 1),
                        "aligned",
                        session.memory.dataBaseAddress + 4,
                    ),
                )
        }
    }

    @Test
    fun macroInvocationBreakpointUsesFirstEmittedAddressEvenWhenTextAddressesAreReordered() {
        val file =
            source(
                "reordered.s",
                """
                .macro emit
                  .text 0x00400020
                  nop
                  .text 0x00400000
                  nop
                .end_macro
                emit

                """
                    .trimIndent(),
            )
        session("Default").use { session ->
            val program: MIPSprogram = session.assemble(listOf(file))
            val map = program.sourceMap()
            Assertions.assertThat<ProgramStatement>(program.machineList)
                .extracting<Int?, RuntimeException>(ProgramStatement::address)
                .containsExactly(0x00400000, 0x00400020)
            Assertions.assertThat<Int?>(
                    map.breakpoints().get(InstructionSourceMap.Location(file, 7)),
                )
                .containsExactly(0x00400020)
            Assertions.assertThat<InstructionSourceMap.Origin?>(map.origins().get(0x00400000))
                .isEqualTo(
                    InstructionSourceMap.Origin(
                        InstructionSourceMap.Location(file, 5),
                        InstructionSourceMap.Origin(InstructionSourceMap.Location(file, 7), null),
                    ),
                )
        }
    }

    @Test
    fun aRuntimeTextWriteHasNoSourceOriginInLiveMemory() {
        val file = source("modified.s", $$".text\naddiu $t0, $zero, 1\n")
        session("Default").use { session ->
            val program: MIPSprogram = session.assemble(listOf(file))
            val pc: Int = session.memory.textBaseAddress
            val original: InstructionSourceMap.Origin = program.sourceMap().origins().get(pc)!!
            Assertions.assertThat<InstructionSourceMap.Origin?>(original)
                .isEqualTo(
                    InstructionSourceMap.Origin(InstructionSourceMap.Location(file, 2), null),
                )
            Assertions.assertThat<InstructionSourceMap.Origin?>(
                    requireNotNull(
                            session.memory.getStatementNoNotify(
                                pc,
                            ),
                        )
                        .instructionOrigin,
                )
                .isSameAs(original)
            session.settings.setSelfModifyingCodeEnabled(true)
            session.memory.setWord(pc, 0)
            Assertions.assertThat<InstructionSourceMap.Origin?>(
                    requireNotNull(
                            session.memory.getStatementNoNotify(
                                pc,
                            ),
                        )
                        .instructionOrigin,
                )
                .isNull()
            Assertions.assertThat<InstructionSourceMap.Origin?>(
                    program.sourceMap().origins().get(pc),
                )
                .isSameAs(original)
            Assertions.assertThat(
                    program.instructionsAt(original.location, Runnable {}).first().encoding,
                )
                .isEqualTo(0x24080001)
        }
    }

    @Test
    fun capturedAssemblyReportsMissingSourcesAndNestedDiagnosticsAtTheirOriginalPaths() {
        val main = source("disk-main.s", "nop\n")
        val outer = directory.resolve("outer.s")
        val inner = source("disk-inner.s", "nop\n")
        val entries: List<Path> = listOf(main)
        val mainText = "# comment\n.include \"outer.s\"\n"
        val outerText = "# comment\n\n.include \"disk-inner.s\"\n"
        session("Default").use { session ->
            val assembler = SessionAssembler(session)
            val absentMain =
                assembler.assembleSnapshot(
                    AssemblySourceSnapshot(entries, immutableMapOf()),
                    null,
                    true,
                    false,
                )
            Assertions.assertThat(absentMain.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(absentMain.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(main)
                        Assertions.assertThat(error.message).contains("Source was not captured")
                    },
                )
            val absentInclude =
                assembler.assembleSnapshot(
                    AssemblySourceSnapshot(
                        entries,
                        immutableMapOf(main, mainText, outer, outerText),
                    ),
                    null,
                    true,
                    false,
                )
            Assertions.assertThat(absentInclude.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(absentInclude.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(outer)
                        Assertions.assertThat(error.line).isEqualTo(3)
                        Assertions.assertThat(error.message)
                            .contains("Error reading include file", inner.toString())
                    },
                )
            val invalidInclude =
                assembler.assembleSnapshot(
                    AssemblySourceSnapshot(
                        entries,
                        immutableMapOf(
                            main,
                            mainText,
                            outer,
                            outerText,
                            inner,
                            "# comment\r\n  j undefined\r\n",
                        ),
                    ),
                    null,
                    true,
                    false,
                )
            Assertions.assertThat(invalidInclude.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(invalidInclude.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(inner)
                        Assertions.assertThat(error.line).isEqualTo(2)
                        Assertions.assertThat(error.message).contains("undefined", "not found")
                    },
                )
            Assertions.assertThat(assembler.assemble(entries).succeeded()).isTrue()
        }
    }

    @Test
    fun malformedTokensOnReplacedIncludeLinesAreStillReportedOnce() {
        val main = directory.resolve("main.s")
        val part = directory.resolve("part.s")
        val directive = "bad%name .InClUdE \"part.s\" bad%name"
        val snapshot =
            AssemblySourceSnapshot(
                listOf(main),
                immutableMapOf(
                    main,
                    "# comment\n" + directive + "\n",
                    part,
                    $$"addiu $t0, $zero, 7\n",
                ),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat(result.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(result.errors())
                .hasSize(2)
                .allSatisfy(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(main)
                        Assertions.assertThat(error.line).isEqualTo(2)
                        Assertions.assertThat(error.message)
                            .endsWith("Invalid language element: bad%name")
                    },
                )
            Assertions.assertThat<AssemblyError>(result.errors())
                .extracting<Int?, RuntimeException>(AssemblyError::column)
                .containsExactly(1, directive.lastIndexOf("bad%name") + 1)
        }
    }

    @Test
    fun unfinishedIncludePathsReportSourceDiagnosticsWithoutLoadingTruncatedFiles() {
        val main = directory.resolve("main.s")
        val part = directory.resolve("part.s")
        val bareQuote = ".include \""
        val partialPath = "prefix: .InClUdE ,, \"part.sx"
        val completePath = "prefix: .InClUdE ,, \"part.s\""
        val tokenizer = Tokenizer()
        Assertions.assertThat(tokenizer.findIncludePath(bareQuote)).isNull()
        Assertions.assertThat(tokenizer.findIncludePath(partialPath)).isNull()
        Assertions.assertThat(tokenizer.findIncludePath(completePath)).isEqualTo("part.s")
        Assertions.assertThat<IncludePath>(tokenizer.findInclude(completePath))
            .isEqualTo(IncludePath("part.s", completePath.indexOf('"'), completePath.indexOf('.')))
        Assertions.assertThat<IncludePath>(tokenizer.inspectInclude(partialPath))
            .isEqualTo(IncludePath(null, partialPath.indexOf('"'), partialPath.indexOf('.')))

        session("Default").use { session ->
            val assembler = SessionAssembler(session)
            val unfinished =
                assembler.assembleSnapshot(
                    AssemblySourceSnapshot(
                        listOf(main),
                        immutableMapOf(
                            main,
                            bareQuote + "\r\n" + partialPath + "\r\nnop\r\n",
                            part,
                            "j missing\n",
                        ),
                    ),
                    null,
                    true,
                    false,
                )
            Assertions.assertThat(unfinished.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(unfinished.errors())
                .satisfiesExactly(
                    ThrowingConsumer { first: AssemblyError ->
                        Assertions.assertThat(first.file).isEqualTo(main)
                        Assertions.assertThat(first.line).isEqualTo(1)
                        Assertions.assertThat(first.column).isEqualTo(bareQuote.indexOf('"') + 1)
                        Assertions.assertThat(first.message).contains("Unterminated .include path")
                    },
                    ThrowingConsumer { second: AssemblyError ->
                        Assertions.assertThat(second.file).isEqualTo(main)
                        Assertions.assertThat(second.line).isEqualTo(2)
                        Assertions.assertThat(second.column).isEqualTo(partialPath.indexOf('"') + 1)
                        Assertions.assertThat(second.message).contains("Unterminated .include path")
                    },
                )
            val completed =
                assembler.assembleSnapshot(
                    AssemblySourceSnapshot(
                        listOf(main),
                        immutableMapOf(main, completePath + "\nj entry\n", part, "entry: nop\n"),
                    ),
                    null,
                    true,
                    false,
                )
            Assertions.assertThat(completed.succeeded()).isTrue()
        }
    }

    @Test
    fun unfinishedDataStringsReportAtTheIncludedSourceAndPreserveClosedEscapes() {
        val main = directory.resolve("main.s")
        val part = directory.resolve("strings.s")
        val entry = ".include \"strings.s\"\n.text\nnop\n"
        val unfinished = ".data\r\n.ascii \"\r\n.asciiz \"partial\r\n  \"trailing\\\r\n"
        session("Default").use { session ->
            val assembler = SessionAssembler(session)
            val result =
                assembler.assembleSnapshot(
                    AssemblySourceSnapshot(
                        listOf(main),
                        immutableMapOf(main, entry, part, unfinished),
                    ),
                    null,
                    true,
                    false,
                )
            Assertions.assertThat(result.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(result.errors())
                .hasSize(3)
                .allSatisfy(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(part)
                        Assertions.assertThat(error.message)
                            .isEqualTo("Unterminated string literal")
                    },
                )
            Assertions.assertThat<AssemblyError>(result.errors())
                .extracting<List<Int>, RuntimeException>(
                    ThrowingExtractor<AssemblyError, List<Int>, RuntimeException> { error ->
                        listOf(error.line, error.column)
                    },
                )
                .containsExactly(
                    mutableListOf<Int>(2, 8),
                    mutableListOf<Int>(3, 9),
                    mutableListOf<Int>(4, 3),
                )

            val completed = ".data\n.ascii \"\", \"OK\"\n.asciiz \"tail\\\"\n"
            val valid =
                assembler.assembleSnapshot(
                    AssemblySourceSnapshot(
                        listOf(main),
                        immutableMapOf(main, entry, part, completed),
                    ),
                    null,
                    true,
                    false,
                )
            Assertions.assertThat(valid.succeeded()).isTrue()
            val base: Int = session.memory.dataBaseAddress
            val expected = "OKtail\"\u0000"
            for (i in 0..<expected.length) {
                Assertions.assertThat(session.memory.getByte(base + i))
                    .isEqualTo(expected.get(i).code)
            }
        }
    }

    @Test
    fun utf8DirectivesStoreEncodedBytesAndAdvanceLabelsByByteLength() {
        val entry = directory.resolve("strings.s")
        Files.writeString(
            entry,
            """
            .data
            start: .utf8 "é", "🐟"
              "\n"
            tail: .UTF8Z "鯉", ""
            old: .asciiz "é"
            stop: .byte 1
            .text
            nop

            """
                .trimIndent(),
        )

        TestSessions.create().use { machine ->
            val program: MIPSprogram = machine.assemble(listOf(entry))
            val symbols = Objects.requireNonNull<SymbolTable>(program.localSymbolTable)
            val base: Int = machine.memory.dataBaseAddress
            Assertions.assertThat(symbols.getAddress("start")).isEqualTo(base)
            Assertions.assertThat(symbols.getAddress("tail")).isEqualTo(base + 7)
            Assertions.assertThat(symbols.getAddress("old")).isEqualTo(base + 12)
            Assertions.assertThat(symbols.getAddress("stop")).isEqualTo(base + 14)

            val expected =
                intArrayOf(
                    0xc3,
                    0xa9,
                    0xf0,
                    0x9f,
                    0x90,
                    0x9f,
                    10,
                    0xe9,
                    0xaf,
                    0x89,
                    0,
                    0,
                    0xe9,
                    0,
                    1,
                )
            for (i in expected.indices) {
                Assertions.assertThat(machine.memory.getByte(base + i)).isEqualTo(expected[i])
            }
        }
    }

    @Test
    fun utf8RejectsAnUnpairedSurrogateInCapturedSource() {
        val entry = directory.resolve("strings.s")
        val source = ".data\n.utf8z \"" + Character.MIN_HIGH_SURROGATE + "\"\n"
        TestSessions.create().use { machine ->
            val result =
                SessionAssembler(machine)
                    .assembleSnapshot(
                        AssemblySourceSnapshot(listOf(entry), immutableMapOf(entry, source)),
                        null,
                        true,
                        false,
                    )
            Assertions.assertThat(result.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(result.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(entry)
                        Assertions.assertThat(error.line).isEqualTo(2)
                        Assertions.assertThat(error.column).isEqualTo(8)
                        Assertions.assertThat(error.message)
                            .isEqualTo("Invalid Unicode in UTF-8 string literal")
                    },
                )
        }
    }

    @Test
    fun capturedIncludeRequestsKeepDistinctFilesystemTargets() {
        val main = directory.resolve("main.s")
        val local = directory.resolve("value.s")
        val remote = directory.resolve("remote/value.s")
        val throughLink = directory.resolve("shortcut/../value.s")
        val entries: List<Path> = listOf(main)
        val source: String =
            $$"""
            .include "shortcut/../value.s"
            .include "value.s"
            .text
            addiu $t0, $zero, REMOTE
            addiu $t1, $zero, LOCAL

            """
                .trimIndent()
        val texts: MutableMap<Path, String> =
            immutableMapOf(main, source, local, ".eqv LOCAL 2\n", remote, ".eqv REMOTE 7\n")
        val resolved = HashMap<Path, Path>(immutableMapOf(local, local, throughLink, remote))
        val snapshot = AssemblySourceSnapshot(entries, texts, resolved)
        resolved.clear()

        session("Default").use { session ->
            val assembler = SessionAssembler(session)
            val result = assembler.assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat(result.succeeded()).isTrue()
            Assertions.assertThat<ProgramStatement>(
                    Objects.requireNonNull<MIPSprogram>(session.program)!!.machineList,
                )
                .extracting<Int?, RuntimeException>(ProgramStatement::binaryStatement)
                .containsExactly(0x24080007, 0x24090002)
            Assertions.assertThat<SourceLine>(checkNotNull(session.program).sourceLineList)
                .anySatisfy(
                    ThrowingConsumer { line: SourceLine ->
                        Assertions.assertThat(line.filename).isEqualTo(remote.toString())
                    },
                )

            val missing = AssemblySourceSnapshot(entries, texts, immutableMapOf(local, local))
            val failure = assembler.assembleSnapshot(missing, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat(failure.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(failure.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(main)
                        Assertions.assertThat(error.line).isEqualTo(1)
                        Assertions.assertThat(error.message)
                            .contains("Error reading include file", throughLink.toString())
                    },
                )
        }
    }

    @Test
    fun diskIncludesResolveParentsAfterFollowingDirectories() {
        val actual = Files.createDirectory(directory.resolve("actual"))
        val nested = Files.createDirectory(actual.resolve("nested"))
        Files.createSymbolicLink(directory.resolve("shortcut"), nested)
        Files.writeString(actual.resolve("value.s"), ".eqv VALUE 7\n")
        val regular = source("value.s", ".eqv VALUE 9\n")
        val entry =
            source("main.s", $$".include \"shortcut/../value.s\"\naddiu $t0, $zero, VALUE\n")

        session("Default").use { session ->
            val assembler = SessionAssembler(session)
            Assertions.assertThat(assembler.assemble(listOf(entry)).succeeded()).isTrue()
            Assertions.assertThat<ProgramStatement>(
                    Objects.requireNonNull<MIPSprogram>(session.program)!!.machineList,
                )
                .extracting<Int?, RuntimeException>(ProgramStatement::binaryStatement)
                .containsExactly(0x24080007)

            Files.writeString(entry, ".include \"value.s/../value.s\"\n")
            Assertions.assertThat<AssemblyError>(assembler.assemble(listOf(entry)).errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(entry)
                        Assertions.assertThat(error.message)
                            .contains(regular.resolve("../value.s").toString())
                    },
                )
        }
    }

    @Test
    fun diskIncludesReportTheDirectiveThatClosesACycle() {
        val main = source("main.s", "# leading comment\n.include \"./main.s\"\n")
        session("Default").use { session ->
            val assembler = SessionAssembler(session)
            val direct = assembler.assemble(listOf(main))
            Assertions.assertThat<AssemblyError>(direct.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(main)
                        Assertions.assertThat(error.line).isEqualTo(2)
                        Assertions.assertThat(error.message).contains("Recursive include of file")
                    },
                )

            val parts = Files.createDirectory(directory.resolve("parts"))
            Files.createSymbolicLink(parts.resolve("through-root"), directory)
            Files.writeString(main, ".include \"parts/outer.s\"\n")
            val outer =
                Files.writeString(
                    parts.resolve("outer.s"),
                    "# outer\n.include \"through-root/main.s\"\n",
                )
            val indirect = assembler.assemble(listOf(main))
            Assertions.assertThat<AssemblyError>(indirect.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(outer)
                        Assertions.assertThat(error.line).isEqualTo(2)
                        Assertions.assertThat(error.message)
                            .contains("Recursive include of file", "through-root")
                    },
                )
        }
    }

    @Test
    fun symlinkedSourceFilesKeepTheirOwnRelativeIncludeDirectories() {
        val template = source("template.s", ".include \"value.s\"\n")
        val main = source("main.s", ".include \"left/template.s\"\n.include \"right/template.s\"\n")
        val left = Files.createDirectory(directory.resolve("left"))
        val right = Files.createDirectory(directory.resolve("right"))
        Files.createSymbolicLink(left.resolve("template.s"), template)
        Files.createSymbolicLink(right.resolve("template.s"), template)
        Files.writeString(left.resolve("value.s"), $$"addiu $t0, $zero, 7\n")
        Files.writeString(right.resolve("value.s"), $$"addiu $t1, $zero, 2\n")
        session("Default").use { session ->
            val result = SessionAssembler(session).assemble(listOf(main))
            Assertions.assertThat(result.succeeded()).isTrue()
            Assertions.assertThat<ProgramStatement>(
                    Objects.requireNonNull<MIPSprogram>(session.program)!!.machineList,
                )
                .extracting<Int?, RuntimeException>(ProgramStatement::binaryStatement)
                .containsExactly(0x24080007, 0x24090002)
        }
    }

    @Test
    fun deeplyNestedCapturedIncludesKeepSourceOrderWithoutUsingTheCallStack() {
        val texts = HashMap<Path, String>()
        val main = directory.resolve("main.s")
        texts.put(main, $$"addiu $t0, $zero, 1\n.include \"part0.s\"\naddiu $t2, $zero, 3\n")
        val depth = 5000
        for (index in 0..<depth) {
            texts.put(
                directory.resolve("part" + index + ".s"),
                ".include \"part" + (index + 1) + ".s\"\n",
            )
        }
        val leaf = directory.resolve("part" + depth + ".s")
        texts.put(leaf, $$"addiu $t1, $zero, 2\n")
        val snapshot = AssemblySourceSnapshot(listOf(main), texts)
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat(result.succeeded()).isTrue()
            Assertions.assertThat<ProgramStatement>(
                    Objects.requireNonNull<MIPSprogram>(session.program)!!.machineList,
                )
                .extracting<Int?, RuntimeException>(ProgramStatement::binaryStatement)
                .containsExactly(0x24080001, 0x24090002, 0x240a0003)
            Assertions.assertThat<SourceLine>(checkNotNull(session.program).sourceLineList)
                .extracting<String?, RuntimeException>(SourceLine::filename)
                .containsExactly(main.toString(), leaf.toString(), main.toString())
        }
    }

    @Test
    fun capturedIncludeIdentitiesDetectCyclesWithoutResolvingPathsOnDisk() {
        val main = directory.resolve("main.s")
        val outer = directory.resolve("parts/outer.s")
        val alias = directory.resolve("parts/shortcut/main.s")
        val snapshot =
            AssemblySourceSnapshot(
                listOf(main),
                immutableMapOf(
                    main,
                    ".include \"parts/outer.s\"\n",
                    outer,
                    "# nested\n.include \"shortcut/main.s\"\n",
                    alias,
                    ".include \"parts/outer.s\"\n",
                ),
                immutableMapOf(outer, outer, alias, alias),
                immutableMapOf(alias, main),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(outer)
                        Assertions.assertThat(error.line).isEqualTo(2)
                        Assertions.assertThat(error.message)
                            .contains("Recursive include of file", alias.toString())
                    },
                )
        }
    }

    @Test
    fun capturedSourceAliasesCannotBypassIncludeRecursionChecks() {
        val main = directory.resolve("main.s")
        val snapshot =
            AssemblySourceSnapshot(
                listOf(main),
                immutableMapOf(main, ".include \"sub/.././main.s\"\n"),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat(result.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(result.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(main)
                        Assertions.assertThat(error.line).isEqualTo(1)
                        Assertions.assertThat(error.message)
                            .contains("Recursive include of file", main.toString())
                    },
                )
        }
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable {
                    AssemblySourceSnapshot(
                        listOf(main),
                        immutableMapOf(main, "", directory.resolve("./main.s"), "nop"),
                    )
                },
            )
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Multiple source texts")
        Assertions.assertThatThrownBy(
                ThrowableAssert.ThrowingCallable {
                    AssemblySourceSnapshot(
                        mutableListOf<Path>(),
                        immutableMapOf(),
                    )
                },
            )
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("At least one source file")
    }

    @Test
    fun mappedEquivalenceTokensKeepOriginalPositionsAcrossNestedReplacements() {
        val source = $$"LABEL OP $t0, $zero, COUNT"
        val replacements: MutableMap<String, String> =
            immutableMapOf(
                "LABEL",
                "NAME :",
                "NAME",
                "entry",
                "OP",
                "NEXT",
                "NEXT",
                "addiu",
                "COUNT",
                "7",
            )
        val errors = ErrorList()
        val mapped =
            Tokenizer.tokenizeMappedLine(
                source,
                errors,
                Function { o: String? -> replacements.get(o!!) },
                Runnable {},
            )

        Assertions.assertThat(errors.errorsOccurred()).isFalse()
        Assertions.assertThat<EquivalenceDefinition?>(mapped.expanded().definition).isNull()
        Assertions.assertThat<Int?>(mapped.substitutionOffsets())
            .containsExactlyInAnyOrder(0, source.indexOf("OP"), source.indexOf("COUNT"))
        val tokens = (0 until mapped.tokens().size()).map(mapped.tokens()::get)
        Assertions.assertThat<Token>(tokens)
            .extracting<String, RuntimeException>(Token::value)
            .containsExactly("entry", ":", "addiu", $$"$t0", $$"$zero", "7")
        Assertions.assertThat<Token>(tokens)
            .extracting<Int?, RuntimeException>(
                ThrowingExtractor { token: Token? -> mapped.originalOffset(token!!.startPos - 1) },
            )
            .containsExactly(
                0,
                0,
                source.indexOf("OP"),
                source.indexOf($$"$t0"),
                source.indexOf($$"$zero"),
                source.indexOf("COUNT"),
            )
        Assertions.assertThat<Token>(tokens)
            .extracting<Int, RuntimeException>(Token::originalStartPos)
            .containsExactly(
                1,
                1,
                source.indexOf("OP") + 1,
                source.indexOf($$"$t0") + 1,
                source.indexOf($$"$zero") + 1,
                source.indexOf("COUNT") + 1,
            )
    }

    @Test
    fun mappedExpansionRetainsNewEquivalenceDefinitions() {
        val errors = ErrorList()
        val replacements: MutableMap<String, String> = immutableMapOf("DEFINE", ".eqv", "BASE", "7")
        val mapped =
            Tokenizer.tokenizeMappedLine(
                "DEFINE PAIR BASE,   9",
                errors,
                Function { o: String? -> replacements.get(o!!) },
                Runnable {},
            )

        Assertions.assertThat(errors.errorsOccurred()).isFalse()
        Assertions.assertThat<EquivalenceDefinition?>(mapped.expanded().definition)
            .isEqualTo(EquivalenceDefinition("PAIR", "BASE,   9"))
        Assertions.assertThat<Int?>(mapped.substitutionOffsets()).containsExactly(0)
        Assertions.assertThat(mapped.originalOffset(mapped.tokens().get(0).startPos - 1)).isZero()
    }

    @Test
    fun mappedExpansionDistinguishesLexicalErrorsFromInterruptedSubstitution() {
        val replacements: MutableMap<String, String> =
            immutableMapOf("FORM", "%value", "FIRST", "SECOND", "SECOND", "FIRST")
        val lexicalErrors = ErrorList()
        val lexical =
            Tokenizer.tokenizeMappedLine(
                ".macro emit (FORM, @)",
                lexicalErrors,
                Function { o: String? -> replacements.get(o!!) },
                Runnable {},
            )
        Assertions.assertThat(lexicalErrors.errorsOccurred()).isTrue()
        Assertions.assertThat(lexical.tokens().hasCompleteEquivalenceExpansion()).isTrue()
        Assertions.assertThat(lexical.tokens().processedLine).isEqualTo(".macro emit (%value, @)")

        val cycleErrors = ErrorList()
        val cycle =
            Tokenizer.tokenizeMappedLine(
                ".macro emit (FORM, FIRST)",
                cycleErrors,
                Function { o: String? -> replacements.get(o!!) },
                Runnable {},
            )
        Assertions.assertThat(cycleErrors.errorsOccurred()).isTrue()
        Assertions.assertThat(cycle.tokens().hasCompleteEquivalenceExpansion()).isFalse()
    }

    @ParameterizedTest
    @MethodSource("equivalenceDiagnosticCases")
    fun equivalenceSemanticErrorsUseWrittenColumnsInAnInclude(
        definitions: String,
        use: String,
        target: String,
        message: String?,
    ) {
        val main = directory.resolve("main.s")
        val include = directory.resolve("body.s")
        val snapshot =
            AssemblySourceSnapshot(
                listOf(main),
                immutableMapOf(main, ".include \"body.s\"\n", include, definitions + use + "\n"),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(include)
                        Assertions.assertThat(error.line)
                            .isEqualTo(definitions.reader().readLines().size + 1)
                        Assertions.assertThat(error.message).contains(message)
                        Assertions.assertThat(error.column).isEqualTo(use.indexOf(target) + 1)
                    },
                )
        }
    }

    @ParameterizedTest
    @MethodSource("macroDiagnosticCases")
    fun nestedMacroErrorsPointToTheWrittenArgumentOrBody(
        body: String,
        call: String,
        inCaller: Boolean,
        target: String,
        message: String?,
    ) {
        val main = directory.resolve("main.s")
        val include = directory.resolve("macros.s")
        val snapshot =
            AssemblySourceSnapshot(
                listOf(main),
                immutableMapOf(
                    main,
                    (".include \"macros.s\"\n.eqv LATER missing\n.eqv CYCLE NEXT\n.eqv NEXT CYCLE\n" +
                        ".eqv MAKE .eqv\n.eqv EXISTING 1\n" +
                        call +
                        "\n"),
                    include,
                    ($$".eqv BAD $s29\n.eqv ADDRESS -4(\n.macro inner (%argument)\n" +
                        body +
                        "\n.end_macro\n.macro outer (%forwarded)\n" +
                        "inner (%forwarded)\n.end_macro\n"),
                ),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.message).contains(message)
                        Assertions.assertThat(error.file).isEqualTo(if (inCaller) main else include)
                        Assertions.assertThat(error.line).isEqualTo(if (inCaller) 7 else 4)
                        Assertions.assertThat(error.column)
                            .isEqualTo((if (inCaller) call else body).indexOf(target) + 1)
                    },
                )
        }
    }

    @Test
    fun equivalenceLexicalErrorsAreReportedOnceAtTheirWrittenTokensOrAliases() {
        val source = ".byte FIRST, SECOND, OUTER, 'ab', 'ab'"
        val replacements: MutableMap<String, String> =
            immutableMapOf(
                "FIRST",
                "100",
                "SECOND",
                "2000",
                "OUTER",
                "INNER",
                "INNER",
                "'ab', 'cd'",
            )
        val errors = ErrorList()
        val mapped =
            Tokenizer.tokenizeMappedLine(
                source,
                errors,
                Function { o: String? -> replacements.get(o!!) },
                Runnable {},
            )

        Assertions.assertThat(mapped.tokens().processedLine)
            .isEqualTo(".byte 100, 2000, 'ab', 'cd', 'ab', 'ab'")
        val diagnostics: ArrayList<ErrorMessage> = errors.errorMessages
        Assertions.assertThat<ErrorMessage>(diagnostics)
            .extracting<Int?, RuntimeException>(ErrorMessage::position)
            .containsExactly(
                source.indexOf("'ab'") + 1,
                source.lastIndexOf("'ab'") + 1,
                source.indexOf("OUTER") + 1,
                source.indexOf("OUTER") + 1,
            )
        Assertions.assertThat<ErrorMessage>(diagnostics)
            .extracting<String?, RuntimeException>(ErrorMessage::message)
            .containsExactly(
                source + "\nInvalid language element: 'ab'",
                source + "\nInvalid language element: 'ab'",
                source + "\nInvalid language element: 'ab'",
                source + "\nInvalid language element: 'cd'",
            )
    }

    @Test
    fun equivalencesPreserveWrittenLexicalErrorsWhenTheReplacementAbsorbsTheToken() {
        val source = ".ascii QUOTE bad%name\""
        val errors = ErrorList()
        val tokens =
            Tokenizer.tokenizeWithEquivalences(
                source,
                errors,
                Function { o: String? -> immutableMapOf("QUOTE", "\"prefix ").get(o) },
                Runnable {},
            )
        Assertions.assertThat(tokens.processedLine).isEqualTo(".ascii \"prefix  bad%name\"")
        Assertions.assertThat<ErrorMessage>(errors.errorMessages)
            .singleElement()
            .satisfies(
                ThrowingConsumer { error: ErrorMessage ->
                    Assertions.assertThat(error.position).isEqualTo(source.indexOf("bad%name") + 1)
                    Assertions.assertThat(error.message)
                        .endsWith("Invalid language element: bad%name")
                },
            )
    }

    @Test
    fun aMalformedIntermediateExpansionDoesNotLeaveAStaleDiagnostic() {
        val errors = ErrorList()
        val replacements: MutableMap<String, String> =
            immutableMapOf("OUTER", "QUOTE bad%name", "QUOTE", "\"prefix ")
        val tokens =
            Tokenizer.tokenizeWithEquivalences(
                ".ascii OUTER\"",
                errors,
                Function { o: String? -> replacements.get(o!!) },
                Runnable {},
            )
        Assertions.assertThat(tokens.processedLine).isEqualTo(".ascii \"prefix  bad%name\"")
        Assertions.assertThat<ErrorMessage>(errors.errorMessages).isEmpty()
    }

    @Test
    fun circularEquivalenceExpansionReportsTheOriginalUseInAnInclude() {
        val main = directory.resolve("main.s")
        val include = directory.resolve("cyclic.s")
        val use = $$"\tOPERATION $t0, FIRST"
        val snapshot =
            AssemblySourceSnapshot(
                listOf(main),
                immutableMapOf(
                    main,
                    ".include \"cyclic.s\"\nnop\n",
                    include,
                    """
                    .eqv OPERATION li
                    .eqv FIRST SECOND
                    .eqv SECOND FIRST + 1

                    """
                        .trimIndent() + use + "\n",
                ),
            )
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat(result.succeeded()).isFalse()
            Assertions.assertThat<AssemblyError>(result.errors())
                .singleElement()
                .satisfies(
                    ThrowingConsumer { error: AssemblyError ->
                        Assertions.assertThat(error.file).isEqualTo(include)
                        Assertions.assertThat(error.line).isEqualTo(4)
                        Assertions.assertThat(error.column).isEqualTo(use.indexOf("FIRST") + 1)
                        Assertions.assertThat(error.message)
                            .contains("Circular .eqv expansion", "FIRST -> SECOND -> FIRST")
                    },
                )
        }
    }

    @Test
    fun longEquivalenceChainsAndRepeatedAliasesAssembleWithoutRecursion() {
        val main = directory.resolve("main.s")
        val text = StringBuilder()
        for (i in 0..6000 - 1) {
            text.append(".eqv VALUE").append(i).append(" VALUE").append(i + 1).append('\n')
        }
        text.append(
            """
            .eqv VALUE6000 7
            .eqv VALUE VALUE6000
            .eqv PAIR VALUE, VALUE
            .eqv UNUSED_A UNUSED_B
            .eqv UNUSED_B UNUSED_A
            .data
            .word VALUE0, PAIR, VALUE
            .text
            nop

            """
                .trimIndent(),
        )
        val snapshot = AssemblySourceSnapshot(listOf(main), immutableMapOf(main, text.toString()))
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors()).isEmpty()
            Assertions.assertThat(result.succeeded()).isTrue()
            for (i in 0..3) {
                Assertions.assertThat(
                        session.memory.getWordNoNotify(session.memory.dataBaseAddress + i * 4),
                    )
                    .isEqualTo(7)
            }
        }
    }

    @Test
    fun equivalencesPreserveCharacterLiteralSpellingBeforeRetokenizing() {
        val main = directory.resolve("main.s")
        val source: String =
            """
            .eqv LETTER 'A', # trailing separator and comment
            .eqv LETTER 'A'
            .eqv NEWLINE '\n'
            .eqv OCTAL '\101'
            .eqv APOSTROPHE '\''
            .eqv BACKSLASH '\\'
            .eqv WIDE '鯉'
            .eqv PAIR 99, '\0'
            .data
            .word LETTER, NEWLINE, OCTAL, APOSTROPHE, BACKSLASH, WIDE, PAIR
            .text
            nop

            """
                .trimIndent()
        val snapshot = AssemblySourceSnapshot(listOf(main), immutableMapOf(main, source))
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors()).isEmpty()
            Assertions.assertThat(result.succeeded()).isTrue()
            val expected = intArrayOf(65, 10, 65, 39, 92, '鯉'.code, 99, 0)
            for (i in expected.indices) {
                Assertions.assertThat(
                        session.memory.getWordNoNotify(session.memory.dataBaseAddress + i * 4),
                    )
                    .isEqualTo(expected[i])
            }
            val program = Objects.requireNonNull<MIPSprogram>(session.program)
            val sourceLines = Objects.requireNonNull<ArrayList<SourceLine>>(program.sourceLineList)
            Assertions.assertThat(sourceLines.get(9).source)
                .isEqualTo(".word 'A', '\\n', '\\101', '\\'', '\\\\', '鯉', 99, '\\0'")
        }
    }

    @Test
    fun macroSubstitutionRewritesOnlyTheParsedTokenSpans() {
        val main = directory.resolve("main.s")
        val source: String =
            """
            .eqv DATAARG %ab
            .macro words (%a, %ab)
              .word DATAARG, %a
              .word %ab, %a, %a
            .end_macro
            .macro emit (%text)
              .asciiz "%text", %text
            .end_macro
            .macro embed (%text, %address)
              .asciiz %text
              .word %address
            .end_macro
            .macro outer
              tag: .word 1
              embed("tag", tag)
            .end_macro
            .data
            words(7, 11)
            emit("A")
            outer()
            .text
            nop

            """
                .trimIndent()
        val snapshot = AssemblySourceSnapshot(listOf(main), immutableMapOf(main, source))
        session("Default").use { session ->
            val result = SessionAssembler(session).assembleSnapshot(snapshot, null, extendedAssemblerEnabled = true, warningsAreErrors = false)
            Assertions.assertThat<AssemblyError>(result.errors()).isEmpty()
            Assertions.assertThat(result.succeeded()).isTrue()
            val base: Int = session.memory.dataBaseAddress
            val words = intArrayOf(11, 7, 11, 7, 7)
            for (i in words.indices) {
                Assertions.assertThat(session.memory.getWordNoNotify(base + i * 4))
                    .isEqualTo(words[i])
            }
            val emitted = "%text\u0000A\u0000"
            for (i in 0..<emitted.length) {
                Assertions.assertThat(session.memory.getByte(base + 20 + i))
                    .isEqualTo(emitted.get(i).code)
            }
            Assertions.assertThat(session.memory.getWordNoNotify(base + 28)).isEqualTo(1)
            Assertions.assertThat(session.memory.getByte(base + 32)).isEqualTo('t'.code)
            Assertions.assertThat(session.memory.getByte(base + 33)).isEqualTo('a'.code)
            Assertions.assertThat(session.memory.getByte(base + 34)).isEqualTo('g'.code)
            Assertions.assertThat(session.memory.getByte(base + 35)).isZero()
            Assertions.assertThat(session.memory.getWordNoNotify(base + 36)).isEqualTo(base + 28)
        }
    }

    @Test
    fun cancellationAtAnySnapshotCheckpointPropagatesAndReleasesTheSession() {
        val main = directory.resolve("main.s")
        val definitions = directory.resolve("defs.s")
        val snapshot =
            AssemblySourceSnapshot(
                listOf(main),
                immutableMapOf(
                    definitions,
                    """
                    .eqv VALUE 7
                    .macro emit (%register)
                      li %register, VALUE
                    .end_macro

                    """
                        .trimIndent(),
                    main,
                    $$"""
                    .include "defs.s"
                    .data
                    .word VALUE:3
                    .word tail
                    tail: .asciiz "%s"
                    .text
                    .globl main
                    main:
                      emit ($a0)
                      li $t0, 0x12345678
                      nop

                    """
                        .trimIndent()
                        .format("a".repeat(600)),
                ),
            )
        val checkpoints = AtomicInteger()
        session("Default").use { baseline ->
            Assertions.assertThat(
                    SessionAssembler(baseline)
                        .assembleSnapshot(
                            snapshot,
                            null,
                            true,
                            false,
                            Runnable { checkpoints.incrementAndGet() },
                        )
                        .succeeded(),
                )
                .isTrue()
        }
        session("Default").use { session ->
            Executors.newSingleThreadExecutor().use { executor ->
                val assembler = SessionAssembler(session)
                var cancelledDuringDataWrites = false
                var cancelledDuringTextWrites = false
                for (checkpoint in 1..checkpoints.get()) {
                    val remaining = AtomicInteger(checkpoint)
                    val cancellation = CancellationException("stop assembly")
                    Assertions.assertThatThrownBy(
                            ThrowableAssert.ThrowingCallable {
                                assembler.assembleSnapshot(
                                    snapshot,
                                    null,
                                    true,
                                    false,
                                    Runnable {
                                        if (remaining.decrementAndGet() == 0) {
                                            throw cancellation
                                        }
                                    },
                                )
                            },
                        )
                        .isSameAs(cancellation)
                    Assertions.assertThat<MIPSprogram>(session.program).isNull()
                    val data: Int = session.memory.dataBaseAddress
                    val text: Int = session.memory.textBaseAddress
                    cancelledDuringDataWrites =
                        cancelledDuringDataWrites or
                            (session.memory.getWordNoNotify(data) == 7 &&
                                session.memory.getWordNoNotify(data + 8) == 0)
                    cancelledDuringTextWrites =
                        cancelledDuringTextWrites or
                            (session.memory.getStatementNoNotify(text) != null &&
                                session.memory.getStatementNoNotify(text + 4) == null)
                }
                Assertions.assertThat(cancelledDuringDataWrites).isTrue()
                Assertions.assertThat(cancelledDuringTextWrites).isTrue()
                val rebuilt: AssemblyResult =
                    executor
                        .submit<AssemblyResult>(
                            Callable {
                                assembler.assembleSnapshot(
                                    snapshot,
                                    null,
                                    true,
                                    false,
                                )
                            },
                        )
                        .get(10, TimeUnit.SECONDS)
                Assertions.assertThat(rebuilt.succeeded()).isTrue()
                Assertions.assertThat<Program?>(rebuilt.program()).isSameAs(session.program)
            }
        }
    }

    @Test
    fun pseudoExpansionUsesEachSessionsDelayedBranchSetting() {
        val source =
            source(
                "pseudo.s",
                $$"""
                .text
                .globl main
                main:
                  li $t0, 2
                  li $t1, 3
                  mulo $a0, $t0, $t1
                done:
                  li $v0, 1
                  syscall
                  li $v0, 10
                  syscall

                """
                    .trimIndent(),
            )
        session("Default").use { first ->
            session("Default").use { second ->
                second.settings.setDelayedBranchingEnabled(true)
                val plain: MIPSprogram = first.assemble(listOf(source))
                val delayed: MIPSprogram = second.assemble(listOf(source))
                Assertions.assertThat<ProgramStatement>(delayed.machineList)
                    .hasSize(
                        Objects.requireNonNull<ArrayList<ProgramStatement>?>(plain.machineList)!!
                            .size + 1,
                    )
                Assertions.assertThat(
                        Objects.requireNonNull<SymbolTable?>(delayed.localSymbolTable)!!.getAddress(
                            "done",
                        ),
                    )
                    .isEqualTo(
                        Objects.requireNonNull<SymbolTable?>(plain.localSymbolTable)!!.getAddress(
                            "done",
                        ) + 4,
                    )
                val plainIo = RecordingEnvironment()
                val delayedIo = RecordingEnvironment()
                plain.setSyscallEnvironment(plainIo)
                delayed.setSyscallEnvironment(delayedIo)
                Assertions.assertThat(plain.simulate(100)).isTrue()
                Assertions.assertThat(delayed.simulate(100)).isTrue()
                Assertions.assertThat<Int?>(plainIo.printed).containsExactly(6)
                Assertions.assertThat<Int?>(delayedIo.printed).containsExactly(6)
            }
        }
    }

    @Test
    fun errorBudgetsAndFailedAssemblyDoNotChangeAnotherSession() {
        session("Default").use { first ->
            session("Default").use { second ->
                val valid: MIPSprogram =
                    first.assemble(listOf(source("valid.s", program(42, false))))
                val address: Int = first.symbolTable.getAddress("result")
                val invalid =
                    source("errors.s", ".text\nunknown_op\nunknown_op\nunknown_op\nunknown_op\n")
                second.settings.setAssemblerErrorLimit(1)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { second.assemble(listOf(invalid)) },
                    )
                    .isInstanceOfSatisfying<ProcessingException?>(
                        ProcessingException::class.java,
                        Consumer { error: ProcessingException? ->
                            Assertions.assertThat(
                                    Objects.requireNonNull<ErrorList?>(error!!.errors())!!
                                        .errorLimit,
                                )
                                .isEqualTo(1)
                            Assertions.assertThat<ErrorMessage>(
                                    Objects.requireNonNull<ErrorList?>(error.errors())!!
                                        .errorMessages,
                                )
                                .hasSize(2)
                        },
                    )
                Assertions.assertThat<MIPSprogram>(second.program).isNull()
                Assertions.assertThat<MIPSprogram>(first.program).isSameAs(valid)
                Assertions.assertThat(first.memory.getWord(address)).isEqualTo(42)
                Assertions.assertThat(first.symbolTable.getAddress("result")).isEqualTo(address)
                second.settings.setAssemblerErrorLimit(20)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { second.assemble(listOf(invalid)) },
                    )
                    .isInstanceOfSatisfying<ProcessingException?>(
                        ProcessingException::class.java,
                        Consumer { error: ProcessingException? ->
                            Assertions.assertThat<ErrorMessage>(
                                    Objects.requireNonNull<ErrorList?>(
                                            error!!.errors(),
                                        )!!
                                        .errorMessages,
                                )
                                .hasSize(4)
                        },
                    )
                val duplicate = source("duplicate.s", ".data\nitem: .word 1\nitem: .word 2\n")
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { second.assemble(listOf(duplicate)) },
                    )
                    .isInstanceOfSatisfying<ProcessingException?>(
                        ProcessingException::class.java,
                        Consumer { error: ProcessingException? ->
                            Assertions.assertThat(
                                    Objects.requireNonNull<ErrorList?>(error!!.errors())!!
                                        .generateErrorReport(),
                                )
                                .contains("already defined", "duplicate.s")
                            Assertions.assertThat(
                                    Objects.requireNonNull<ErrorList?>(error.errors())!!
                                        .errorMessages
                                        .first()
                                        .line,
                                )
                                .isEqualTo(3)
                        },
                    )
            }
        }
    }

    @Test
    fun tokenizerErrorsUseTheSessionBudgetAndPreserveThePreviouslyAssembledProgram() {
        session("Default").use { session ->
            val valid: MIPSprogram =
                session.assemble(listOf(source("valid-before-token-error.s", program(42, false))))
            session.settings.setAssemblerErrorLimit(2)
            val invalid = source("token-errors.s", ".data\n.byte 'ab'\n.byte 'cd'\n.byte 'ef'\n")
            Assertions.assertThatThrownBy(
                    ThrowableAssert.ThrowingCallable { session.assemble(listOf(invalid)) },
                )
                .isInstanceOfSatisfying<ProcessingException?>(
                    ProcessingException::class.java,
                    Consumer { error: ProcessingException? ->
                        Assertions.assertThat(
                                Objects.requireNonNull<ErrorList?>(error!!.errors())!!.errorLimit,
                            )
                            .isEqualTo(2)
                        Assertions.assertThat<ErrorMessage>(
                                Objects.requireNonNull<ErrorList?>(error.errors())!!.errorMessages,
                            )
                            .satisfiesExactly(
                                ThrowingConsumer { first: ErrorMessage ->
                                    Assertions.assertThat(first.message)
                                        .endsWith("Invalid language element: 'ab'")
                                },
                                ThrowingConsumer { second: ErrorMessage ->
                                    Assertions.assertThat(second.message)
                                        .endsWith("Invalid language element: 'cd'")
                                },
                                ThrowingConsumer { limit: ErrorMessage ->
                                    Assertions.assertThat(limit.message)
                                        .isEqualTo("Error Limit of 2 exceeded.")
                                },
                            )
                    },
                )
            Assertions.assertThat<MIPSprogram>(session.program).isSameAs(valid)
            Assertions.assertThat(session.memory.getWord(session.symbolTable.getAddress("result")))
                .isEqualTo(42)
        }
    }

    @Test
    fun numericRegisterOnlyModeIsOwnedByTheAssemblySession() {
        session("Default").use { regular ->
            session("Default").use { strict ->
                strict.settings.setBareMachineEnabled(true)
                val named = source("named-registers.s", $$".text\nadd $t0, $t1, $t2\n")
                Assertions.assertThat<ProgramStatement>(regular.assemble(listOf(named)).machineList)
                    .hasSize(1)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { strict.assemble(listOf(named)) },
                    )
                    .isInstanceOfSatisfying<ProcessingException?>(
                        ProcessingException::class.java,
                        Consumer { error: ProcessingException? ->
                            Assertions.assertThat(
                                    Objects.requireNonNull<ErrorList?>(
                                            error!!.errors(),
                                        )!!
                                        .generateErrorReport(),
                                )
                                .contains("register number")
                        },
                    )
                val numbered = source("numbered-registers.s", ".text\nadd $8, $9, $10\n")
                Assertions.assertThat<ProgramStatement>(
                        strict.assemble(listOf(numbered)).machineList,
                    )
                    .hasSize(1)
            }
        }
    }

    @Test
    fun staleAndMixedProgramsCannotReplaceOrRunAgainstTheCurrentSession() {
        session("Default").use { session ->
            TestSessions.create().use { other ->
                val old: MIPSprogram = session.assemble(listOf(source("old.s", program(1, false))))
                val current: MIPSprogram =
                    session.assemble(listOf(source("new.s", program(2, false))))
                val address: Int = session.symbolTable.getAddress("result")
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { old.simulate(100) },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThat<BackStepper?>(old.backStepper).isNull()
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            Assembler(session).assemble(MIPSprogram(other), true)
                        },
                    )
                    .isInstanceOf(IllegalArgumentException::class.java)
                Assertions.assertThat<MIPSprogram>(session.program).isSameAs(current)
                Assertions.assertThat(session.memory.getWord(address)).isEqualTo(2)
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable {
                            session.assemble(
                                listOf(
                                    directory.resolve("missing.s"),
                                ),
                            )
                        },
                    )
                    .isInstanceOf(ProcessingException::class.java)
                Assertions.assertThat<MIPSprogram>(session.program).isSameAs(current)
                val standaloneSource = MIPSprogram(session)
                standaloneSource.readSource(
                    source("direct-assembler.s", program(3, false)).toString(),
                )
                standaloneSource.tokenize()
                Assertions.assertThat<ProgramStatement>(
                        Assembler(session).assemble(standaloneSource, true),
                    )
                    .isNotEmpty()
                Assertions.assertThat<MIPSprogram>(session.program).isNull()
                Assertions.assertThatThrownBy(
                        ThrowableAssert.ThrowingCallable { current.simulate(100) },
                    )
                    .isInstanceOf(IllegalStateException::class.java)
            }
        }
    }

    @Test
    fun ownedExecutionSupportsStepLimitsBreakpointsAndUndo() {
        session("Default").use { session ->
            val program: MIPSprogram =
                session.assemble(
                    listOf(
                        source(
                            "loop.s",
                            $$"""
                            .text
                            .globl main
                            main:
                              addiu $t0, $t0, 1
                              b main
                              nop

                            """
                                .trimIndent(),
                        ),
                    ),
                )
            val base: Int = session.memory.textBaseAddress
            val completed = ArrayList<Long?>()
            Assertions.assertThat(program.simulate(3, LongConsumer { e: Long -> completed.add(e) }))
                .isFalse()
            Assertions.assertThat<Long?>(completed).containsExactly(1L, 2L, 3L)
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(2)
            session.backStepper.backStep()
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(1)
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base)
            Assertions.assertThat(program.simulateStepAtPC()).isFalse()
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(2)
            Assertions.assertThat(program.simulateFromPC(intArrayOf(base), 100)).isFalse()
            Assertions.assertThat(session.registerFile.getPC()).isEqualTo(base)
        }
    }

    @Test
    fun ownedExecutionDispatchesToItsOwnExceptionHandler() {
        session("Default").use { session ->
            val program: MIPSprogram =
                session.assemble(
                    listOf(
                        source(
                            "handler.s",
                            $$"""
                            .text
                            .globl main
                            main:
                              li $t0, 0x7fffffff
                              addi $t0, $t0, 1
                              li $v0, 10
                              syscall
                            .ktext 0x80000180
                              mfc0 $k0, $14
                              addiu $k0, $k0, 4
                              mtc0 $k0, $14
                              eret

                            """
                                .trimIndent(),
                        ),
                    ),
                )
            Assertions.assertThat(program.simulate(100)).isTrue()
            Assertions.assertThat(session.registerFile.getValue(8)).isEqualTo(Int.MAX_VALUE)
            Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.STATUS) and 2)
                .isZero()
            Assertions.assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE))
                .isEqualTo(12 shl 2)
        }
    }

    @Test
    fun startAtMainAndEntryPointSettingsRemainSessionSpecific() {
        session("Default").use { session ->
            session.settings.setStartAtMainEnabled(true)
            val program: MIPSprogram =
                session.assemble(
                    listOf(
                        source(
                            "entry.s",
                            $$"""
                            .text
                              nop
                            .globl main
                            main:
                              li $v0, 17
                              li $a0, 7
                              syscall

                            """
                                .trimIndent(),
                        ),
                    ),
                )
            Assertions.assertThat(session.registerFile.getPC())
                .isEqualTo(session.memory.textBaseAddress + 4)
            Assertions.assertThat(program.simulate(100)).isTrue()
            Assertions.assertThat(program.exitCode).isEqualTo(7)
        }
    }

    private fun source(filename: String, text: String): Path {
        val path = directory.resolve(filename)
        Files.writeString(path, text)
        return path
    }

    private class RecordingEnvironment : TestEnvironment() {
        val printed: MutableList<Int?> = ArrayList<Int?>()

        override fun printInt(value: Int) {
            printed.add(value)
        }
    }

    companion object {
        @JvmStatic
        fun corpus(): Stream<String?> {
            return Stream.concat<String?>(
                AssemblerSmokeTest.allPrograms(),
                Stream.of<String?>("16_floating_point.asm"),
            )
        }

        @JvmStatic
        fun unicodeSourceEncodings(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("efbbbf", StandardCharsets.UTF_8),
                Arguments.of("feff", StandardCharsets.UTF_16BE),
                Arguments.of("fffe", StandardCharsets.UTF_16LE),
                Arguments.of("0000feff", Charset.forName("UTF-32BE")),
                Arguments.of("fffe0000", Charset.forName("UTF-32LE")),
            )
        }

        private fun encodedSource(marker: String, charset: Charset, text: String): ByteArray {
            val bytes = ByteArrayOutputStream()
            bytes.writeBytes(HexFormat.of().parseHex(marker))
            bytes.writeBytes(text.toByteArray(charset))
            return bytes.toByteArray()
        }

        @JvmStatic
        fun equivalenceDiagnosticCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(
                    $$".eqv DEST $f0\n.eqv ADDRESS OFFSET\n.eqv OFFSET -4(\n",
                    $$"\tentry: lwc1 DEST, ADDRESS $s29)",
                    $$"$s29",
                    "operand is of incorrect type",
                ),
                Arguments.of(
                    $$".eqv BOTH -4($s29\n",
                    $$"lwc1 $f0, BOTH)",
                    "BOTH",
                    "operand is of incorrect type",
                ),
                Arguments.of(
                    $$".eqv REGS $t0, $t1\n.eqv TARGET nop\n",
                    "beq REGS, TARGET",
                    "TARGET",
                    "Symbol \"nop\" not found",
                ),
                Arguments.of(
                    ".eqv DATA .word\n.eqv VALUE missing\n.data\n",
                    "DATA VALUE",
                    "VALUE",
                    "Symbol \"missing\" not found",
                ),
                Arguments.of(
                    ".eqv DEFINE .eqv\n.eqv VALUE 1\n",
                    "DEFINE VALUE 2",
                    "VALUE",
                    "is already defined",
                ),
            )
        }

        @JvmStatic
        fun macroDiagnosticCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(
                    $$"lwc1 $f0, -4(%argument)",
                    "outer (BAD)",
                    true,
                    "BAD",
                    "operand is of incorrect type",
                ),
                Arguments.of(
                    "lwc1 %argument, ADDRESS invalid)",
                    $$"outer ($f0)",
                    false,
                    "invalid",
                    "operand is of incorrect type",
                ),
                Arguments.of(
                    $$"beq $zero, $zero, %argument",
                    "outer (nop)",
                    true,
                    "nop",
                    "Symbol \"nop\" not found",
                ),
                Arguments.of(
                    $$"beq $zero, $zero, LATER",
                    "outer (0)",
                    false,
                    "LATER",
                    "Symbol \"missing\" not found",
                ),
                Arguments.of(
                    $$"li $t0, CYCLE",
                    "outer (0)",
                    false,
                    "CYCLE",
                    "Circular .eqv expansion CYCLE -> NEXT -> CYCLE",
                ),
                Arguments.of(
                    "MAKE EXISTING 2",
                    "outer (0)",
                    false,
                    "EXISTING",
                    "is already defined",
                ),
            )
        }

        private fun assembleAndRun(session: MarsContext, source: Path): MutableList<Int?> {
            val program = session.assemble(listOf(source))
            val output = RecordingEnvironment()
            program.setSyscallEnvironment(output)
            Assertions.assertThat(program.simulate(200)).isTrue()
            return output.printed
        }

        private fun program(value: Int, padding: Boolean): String {
            return (".data\n" +
                (if (padding) ".word 11\n" else "") +
                ".globl result\nresult: .word " +
                value +
                "\n" +
                $$"""
                .text
                .globl main
                main:
                  la $t1, result
                  lw $t0, 0($t1)
                  addiu $t0, $t0, 1
                  sw $t0, 0($t1)
                  move $a0, $t0
                  li $v0, 1
                  syscall
                  li $v0, 10
                  syscall

                """
                    .trimIndent())
        }

        private fun session(layout: String): MarsContext {
            val settings = Settings()
            settings.simulationSettings
                .setMemoryConfiguration(layout)
                .setDelayedBranchingEnabled(false)
                .setStartAtMainEnabled(false)
                .setBareMachineEnabled(false)
                .setExtendedAssemblerEnabled(true)
                .setWarningsAreErrors(false)
            return DaggerMarsComponent.builder()
                .coreModule(CoreModule(settings, target = MarsTarget.MARS32))
                .guiProxy(NO_GUI)
                .build()
                .context()
        }

        private val NO_GUI: MarsGuiProxy =
            object : MarsGuiProxy {
                override val isGuiMode: Boolean
                    get() = false

                override fun postMessage(message: String) {}

                override fun postRunMessage(message: String) {}

                override fun readInput(maxLength: Int): String {
                    return ""
                }

                override fun readInputWithPrompt(prompt: String): String {
                    return ""
                }

                override fun clearRunConsole() {}
            }
    }
}
