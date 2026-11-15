package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.mips.instructions.syscalls.RandomStreams
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandDouble
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandFloat
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandInt
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandIntRange
import edu.missouristate.mars.mips.instructions.syscalls.SyscallRandSeed
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects
import java.util.Random
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class SessionSyscallTest {
    @TempDir lateinit var directory: Path

    @Test
    fun allRandomSyscallsUseSessionStreamsAcrossInstructionContexts() {
        session().use { first ->
            session().use { second ->
                var a = InstructionContext(first, first.syscallEnvironment)
                val b = InstructionContext(second, second.syscallEnvironment)
                seed(a, 123)
                seed(b, 987)
                val expectedA = Random(123)
                val expectedB = Random(987)
                val legacy = RandomStreams()
                legacy.setSeed(7, 42)
                Assertions.assertThat<RandomStreams?>(a.randomStreams())
                    .isNotSameAs(b.randomStreams())
                    .isNotSameAs(legacy)
                for (i in 0..2) {
                    assertRandomValues(a, expectedA)
                    seed(b, 987 + i)
                    expectedB.setSeed((987 + i).toLong())
                    assertRandomValues(b, expectedB)
                    a = InstructionContext(first, first.syscallEnvironment)
                }
                Assertions.assertThat(legacy.get(7).nextInt()).isEqualTo(Random(42).nextInt())
                // Different stream identifiers in one session also retain independent positions.
                Assertions.assertThat<Random?>(first.randomStreams.get(8))
                    .isNotSameAs(first.randomStreams.get(7))
            }
        }
    }

    @Test
    fun fileDescriptorsArePrivateAndResettingOneSessionKeepsTheOtherOpen() {
        val input = directory.resolve("input")
        Files.writeString(input, "abcdef")
        session().use { first ->
            session().use { second ->
                val a: SessionSyscallEnvironment = first.syscallEnvironment
                val b: SessionSyscallEnvironment = second.syscallEnvironment
                Assertions.assertThat(a.openFile(input.toString(), 0)).isEqualTo(3)
                Assertions.assertThat(b.openFile(input.toString(), 0)).isEqualTo(3)
                val bytes = ByteArray(3)
                Assertions.assertThat(a.readFromFile(3, bytes, 3)).isEqualTo(3)
                Assertions.assertThat(String(bytes)).isEqualTo("abc")
                Assertions.assertThat(b.readFromFile(3, bytes, 3)).isEqualTo(3)
                Assertions.assertThat(String(bytes)).isEqualTo("abc")
                val source = directory.resolve("exit.s")
                Files.writeString(source, ".text\nli \$v0, 10\nsyscall\n")
                first.assemble(listOf(source))
                Assertions.assertThat(a.readFromFile(3, bytes, 3)).isEqualTo(-1)
                Assertions.assertThat(b.readFromFile(3, bytes, 3)).isEqualTo(3)
                Assertions.assertThat(String(bytes)).isEqualTo("def")
                Assertions.assertThat(b.readFromFile(3, bytes, 3)).isZero()
                second.close()
                Assertions.assertThat(b.readFromFile(3, bytes, 3)).isEqualTo(-1)
                Assertions.assertThat(
                        Objects.requireNonNull<MIPSprogram>(first.program)!!.simulate(20),
                    )
                    .isTrue()
            }
        }
    }

    @Test
    fun fileModesCapacityAndDescriptorReuseFollowMarsConventions() {
        session().use { session ->
            val io: SessionSyscallEnvironment = session.syscallEnvironment
            val path = directory.resolve("output").toString()
            val bytes = byteArrayOf(65, 0, 66)
            Assertions.assertThat(io.openFile(path, 2)).isEqualTo(-1)
            Assertions.assertThat(io.openFile(directory.resolve("missing").toString(), 0))
                .isEqualTo(-1)
            Assertions.assertThat(io.openFile(path, 1)).isEqualTo(3)
            Assertions.assertThat(io.openFile(path, 1)).isEqualTo(-1)
            Assertions.assertThat(io.readFromFile(3, bytes, 3)).isEqualTo(-1)
            Assertions.assertThat(io.writeToFile(3, bytes, 3)).isEqualTo(3)
            io.closeFile(3)
            Assertions.assertThat(io.openFile(path, 9)).isEqualTo(3)
            Assertions.assertThat(io.writeToFile(3, bytes, 2)).isEqualTo(2)
            io.closeFile(3)
            Assertions.assertThat(Files.readAllBytes(Path.of(path)))
                .containsExactly(65, 0, 66, 65, 0)
            Assertions.assertThat(io.openFile(path, 0)).isEqualTo(3)
            Assertions.assertThat(io.writeToFile(3, bytes, 1)).isEqualTo(-1)
            Assertions.assertThat(io.readFromFile(-1, bytes, 1)).isEqualTo(-1)
            Assertions.assertThat(io.readFromFile(3, bytes, 4)).isEqualTo(-1)
            Assertions.assertThat(io.writeToFile(99, bytes, 1)).isEqualTo(-1)
            Assertions.assertThat(io.writeToFile(1, bytes, -1)).isEqualTo(-1)
            for (fd in 4..31) {
                Assertions.assertThat(io.openFile(directory.resolve("file" + fd).toString(), 1))
                    .isEqualTo(fd)
            }
            Assertions.assertThat(io.openFile(directory.resolve("overflow").toString(), 1))
                .isEqualTo(-1)
            io.closeFile(15)
            Assertions.assertThat(io.openFile(directory.resolve("reused").toString(), 1))
                .isEqualTo(15)
        }
    }

    @Test
    fun relativeFilePathsUseTheEnvironmentWorkingDirectory() {
        val firstDirectory = Files.createDirectory(directory.resolve("first"))
        val secondDirectory = Files.createDirectory(directory.resolve("second"))
        Files.writeString(firstDirectory.resolve("input"), "A")
        Files.writeString(secondDirectory.resolve("input"), "B")
        session().use { first ->
            session().use { second ->
                SessionSyscallEnvironment(
                        first.memory,
                        InputStream.nullInputStream(),
                        System.out,
                        System.err,
                        firstDirectory,
                    )
                    .use { firstIo ->
                        SessionSyscallEnvironment(
                                second.memory,
                                InputStream.nullInputStream(),
                                System.out,
                                System.err,
                                secondDirectory,
                            )
                            .use { secondIo ->
                                val bytes = ByteArray(1)
                                Assertions.assertThat(firstIo.openFile("input", 0)).isEqualTo(3)
                                Assertions.assertThat(secondIo.openFile("input", 0)).isEqualTo(3)
                                Assertions.assertThat(firstIo.readFromFile(3, bytes, 1))
                                    .isEqualTo(1)
                                Assertions.assertThat(bytes).containsExactly(65)
                                Assertions.assertThat(secondIo.readFromFile(3, bytes, 1))
                                    .isEqualTo(1)
                                Assertions.assertThat(bytes).containsExactly(66)
                                Assertions.assertThat(
                                        firstIo.openFile(
                                            secondDirectory.resolve("input").toString(),
                                            0,
                                        ),
                                    )
                                    .isEqualTo(4)
                                Assertions.assertThat(firstIo.readFromFile(4, bytes, 1))
                                    .isEqualTo(1)
                                Assertions.assertThat(bytes).containsExactly(66)
                                Assertions.assertThat(firstIo.openFile("bad\u0000name", 0))
                                    .isEqualTo(-1)
                            }
                    }
            }
        }
    }

    @Test
    fun utf8StringInputWritesByteSizedBuffersAndReversesThoseBytes() {
        val source = directory.resolve("utf8.s")
        Files.writeString(
            source,
            """
            .data
            buffer: .byte 88, 88, 88, 88, 88, 88
            .text
            syscall

            """
                .trimIndent(),
        )
        session().use { session ->
            session.settings.isUtf8TextEnabled = true
            val program: MIPSprogram = session.assemble(listOf(source))
            val buffer = program.symbolMap().locals().get(source)!!.get("buffer")!!.address
            val input = ByteArrayInputStream("ignored\n鯉\n".toByteArray(StandardCharsets.UTF_8))
            SessionSyscallEnvironment(session.memory, input, System.out, System.err).use { io ->
                program.setSyscallEnvironment(io)
                session.registerFile.updateRegister(2, 8)
                session.registerFile.updateRegister(4, buffer)
                session.registerFile.updateRegister(5, Int.MIN_VALUE)
                program.simulateStepAtPC()
                Assertions.assertThat(session.memory.getByte(buffer)).isEqualTo(88)
                session.backStepper.backStep()
                session.registerFile.updateRegister(5, 6)
                program.simulateStepAtPC()
                Assertions.assertThat(session.memory.getByte(buffer)).isEqualTo(0xe9)
                Assertions.assertThat(session.memory.getByte(buffer + 1)).isEqualTo(0xaf)
                Assertions.assertThat(session.memory.getByte(buffer + 2)).isEqualTo(0x89)
                Assertions.assertThat(session.memory.getByte(buffer + 3)).isEqualTo('\n'.code)
                Assertions.assertThat(session.memory.getByte(buffer + 4)).isZero()
                Assertions.assertThat(session.memory.getByte(buffer + 5)).isEqualTo(88)
                session.backStepper.backStep()
                for (offset in 0..5) {
                    Assertions.assertThat(session.memory.getByte(buffer + offset)).isEqualTo(88)
                }
            }
        }
    }

    @Test
    fun textInputPreservesUtf16ByteOrderAcrossRawReadsAndPartialEof() {
        session().use { session ->
            val bytes = ByteArrayOutputStream()
            bytes.write(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
            bytes.write("42\r\n".toByteArray(StandardCharsets.UTF_16LE))
            bytes.write(byteArrayOf(1, 2, 3))
            bytes.write("é💡\n\nend\r".toByteArray(StandardCharsets.UTF_16LE))
            val input = ByteArrayInputStream(bytes.toByteArray())
            SessionSyscallEnvironment(
                    session.memory,
                    input,
                    System.out,
                    System.err,
                    directory,
                    StandardCharsets.UTF_16,
                )
                .use { io ->
                    Assertions.assertThat(io.readInt()).isEqualTo(42)
                    val raw = ByteArray(3)
                    Assertions.assertThat(io.readFromFile(0, raw, raw.size)).isEqualTo(3)
                    Assertions.assertThat(raw).containsExactly(1, 2, 3)
                    Assertions.assertThat(io.readString(20)).isEqualTo("é💡")
                    Assertions.assertThat(io.readString(20)).isEmpty()
                    Assertions.assertThat(io.readString(20)).isEqualTo("end")
                    Assertions.assertThat(io.readString(20)).isNull()
                }
        }
    }

    @Test
    fun malformedConsoleInputIsReplacedWithoutConsumingTheFollowingRawByte() {
        session().use { session ->
            SessionSyscallEnvironment(
                    session.memory,
                    ByteArrayInputStream(
                        byteArrayOf(
                            0xc3.toByte(),
                            '\n'.code.toByte(),
                            'X'.code.toByte(),
                            0xc3.toByte(),
                        ),
                    ),
                    System.out,
                    System.err,
                    directory,
                    StandardCharsets.UTF_8,
                )
                .use { io ->
                    Assertions.assertThat(io.readString(20)).isEqualTo("�")
                    val raw = ByteArray(1)
                    Assertions.assertThat(io.readFromFile(0, raw, raw.size)).isEqualTo(1)
                    Assertions.assertThat(raw).containsExactly('X'.code)
                    Assertions.assertThat(io.readString(20)).isEqualTo("�")
                    Assertions.assertThat(io.readString(20)).isNull()
                }
        }
    }

    @Test
    fun consoleInputDialogsAndRawDescriptorsShareOnlyTheirSuppliedStreams() {
        session().use { session ->
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()
            val input =
                ByteArrayInputStream(
                    "42\nraw\n3.5\n2.25\nZ\nabcdef\nanswer\n".toByteArray(StandardCharsets.UTF_8),
                )
            SessionSyscallEnvironment(
                    session.memory,
                    input,
                    PrintStream(output),
                    PrintStream(error),
                )
                .use { io ->
                    Assertions.assertThat(io.readInt()).isEqualTo(42)
                    val bytes = ByteArray(4)
                    Assertions.assertThat(io.readFromFile(0, bytes, 4)).isEqualTo(4)
                    Assertions.assertThat(String(bytes)).isEqualTo("raw\n")
                    Assertions.assertThat(io.readFloat()).isEqualTo(3.5f)
                    Assertions.assertThat(io.readDouble()).isEqualTo(2.25)
                    Assertions.assertThat(io.readChar()).isEqualTo('Z')
                    Assertions.assertThat(io.readString(3)).isEqualTo("abc")
                    Assertions.assertThat(io.showInputDialog("prompt")).isEqualTo("answer")
                    Assertions.assertThat(io.readString(20)).isNull()
                    Assertions.assertThatThrownBy(ThrowableAssert.ThrowingCallable { io.readInt() })
                        .isInstanceOf(NumberFormatException::class.java)
                    Assertions.assertThatThrownBy(
                            ThrowableAssert.ThrowingCallable { io.readChar() },
                        )
                        .isInstanceOf(IndexOutOfBoundsException::class.java)
                    io.printString("value=")
                    io.printInt(42)
                    io.printChar(' ')
                    io.printFloat(1.5f)
                    io.printDouble(2.5)
                    io.writeToFile(1, byteArrayOf(33), 1)
                    io.writeToFile(2, byteArrayOf(63), 1)
                    io.showMessageDialog("message", "title", 1)
                    io.closeFile(0)
                    io.closeFile(1)
                    io.closeFile(2)
                    io.close()
                    io.printString("open")
                    Assertions.assertThat(output.toString()).isEqualTo("value=42 1.52.5!open")
                    Assertions.assertThat(error.toString()).contains("prompt: ?[title] message")
                    val heap: Int = session.memory.heapAddress
                    Assertions.assertThat(io.sbrk(4)).isEqualTo(heap)
                    Assertions.assertThat(session.memory.heapAddress).isEqualTo(heap + 4)
                }
        }
    }

    @Test
    fun assembledFileSyscallsWorkWithDefaultEnvironmentAndNoGlobals() {
        session().use { session ->
            val output = directory.resolve("mips-output")
            val source = directory.resolve("files.s")
            Files.writeString(
                source,
                """
                .data
                path: .asciiz "%s"
                bytes: .byte 65, 0, 66
                buffer: .space 3
                .text
                la ${'$'}a0, path
                li ${'$'}a1, 1
                li ${'$'}v0, 13
                syscall
                move ${'$'}s0, ${'$'}v0
                move ${'$'}a0, ${'$'}s0
                la ${'$'}a1, bytes
                li ${'$'}a2, 3
                li ${'$'}v0, 15
                syscall
                move ${'$'}s1, ${'$'}v0
                li ${'$'}v0, 16
                syscall
                la ${'$'}a0, path
                li ${'$'}a1, 0
                li ${'$'}v0, 13
                syscall
                move ${'$'}a0, ${'$'}v0
                la ${'$'}a1, buffer
                li ${'$'}a2, 3
                li ${'$'}v0, 14
                syscall
                move ${'$'}s2, ${'$'}v0
                lbu ${'$'}s3, 2(${'$'}a1)
                li ${'$'}v0, 16
                syscall
                li ${'$'}v0, 10
                syscall

                """
                    .trimIndent()
                    .format(output.toString().replace("\\", "\\\\").replace("\"", "\\\"")),
            )
            Assertions.assertThat(session.assemble(listOf(source)).simulate(200)).isTrue()
            Assertions.assertThat(Files.readAllBytes(output)).containsExactly(65, 0, 66)
            Assertions.assertThat(session.registerFile.getValue(16)).isEqualTo(3)
            Assertions.assertThat(session.registerFile.getValue(17)).isEqualTo(3)
            Assertions.assertThat(session.registerFile.getValue(18)).isEqualTo(3)
            Assertions.assertThat(session.registerFile.getValue(19)).isEqualTo(66)
        }
    }

    @Test
    fun assembledConsoleSyscallsUseSuppliedStreamsAndHandleEndOfInput() {
        session().use { session ->
            val output = ByteArrayOutputStream()
            val source = directory.resolve("console.s")
            Files.writeString(
                source,
                """
                .data
                buffer: .space 8
                .text
                li ${'$'}v0, 5
                syscall
                move ${'$'}a0, ${'$'}v0
                li ${'$'}v0, 1
                syscall
                la ${'$'}a0, buffer
                li ${'$'}a1, 8
                li ${'$'}v0, 8
                syscall
                li ${'$'}v0, 4
                syscall
                li ${'$'}v0, 8
                syscall
                lbu ${'$'}s0, 0(${'$'}a0)
                lbu ${'$'}s1, 1(${'$'}a0)
                li ${'$'}v0, 10
                syscall

                """
                    .trimIndent(),
            )
            val program: MIPSprogram = session.assemble(listOf(source))
            SessionSyscallEnvironment(
                    session.memory,
                    ByteArrayInputStream("42\nhello\n".toByteArray(StandardCharsets.UTF_8)),
                    PrintStream(output),
                    PrintStream(ByteArrayOutputStream()),
                )
                .use { io ->
                    program.setSyscallEnvironment(io)
                    Assertions.assertThat(program.simulate(100)).isTrue()
                    Assertions.assertThat(output.toString()).isEqualTo("42hello\n")
                    Assertions.assertThat(session.registerFile.getValue(16)).isEqualTo('\n'.code)
                    Assertions.assertThat(session.registerFile.getValue(17)).isZero()
                    // Session resets release default resources, but supplied environments remain
                    // caller-owned.
                    val path = directory.resolve("custom-output").toString()
                    Assertions.assertThat(io.openFile(path, 1)).isEqualTo(3)
                    session.setMemoryConfiguration("Default")
                    Assertions.assertThat(io.writeToFile(3, byteArrayOf(65), 1)).isEqualTo(1)
                }
        }
    }

    companion object {
        private fun seed(context: InstructionContext, seed: Int) {
            context.registerFile.updateRegister(4, 7)
            context.registerFile.updateRegister(5, seed)
            SyscallRandSeed().simulate(null, context)
        }

        private fun assertRandomValues(context: InstructionContext, expected: Random) {
            context.registerFile.updateRegister(4, 7)
            SyscallRandInt().simulate(null, context)
            Assertions.assertThat(context.registerFile.getValue(4)).isEqualTo(expected.nextInt())
            context.registerFile.updateRegister(4, 7)
            context.registerFile.updateRegister(5, 1000)
            SyscallRandIntRange().simulate(null, context)
            Assertions.assertThat(context.registerFile.getValue(4))
                .isEqualTo(expected.nextInt(1000))
            context.registerFile.updateRegister(4, 7)
            SyscallRandFloat().simulate(null, context)
            Assertions.assertThat(context.coprocessor1.getFloatFromRegister(0))
                .isEqualTo(expected.nextFloat())
            SyscallRandDouble().simulate(null, context)
            Assertions.assertThat(context.coprocessor1.getDoubleFromRegisterPair(0))
                .isEqualTo(expected.nextDouble())
        }

        fun session(): MarsContext {
            val settings = Settings()
            settings.simulationSettings
                .setMemoryConfiguration("Default")
                .setDelayedBranchingEnabled(false)
                .setStartAtMainEnabled(false)
                .setBareMachineEnabled(false)
                .setExtendedAssemblerEnabled(true)
                .setWarningsAreErrors(false)
            return DaggerMarsComponent.builder()
                .coreModule(CoreModule(settings, target = MarsTarget.MARS32))
                .guiProxy(
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
                    },
                )
                .build()
                .context()
        }
    }
}
