package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class CompactControlFlowTest {
    @ParameterizedTest
    @CsvSource("bgtc,bltc,5dec0001,2,1", "blec,bgec,59ec0001,1,2",
        "bgtuc,bltuc,1dec0001,-1,1", "bleuc,bgeuc,19ec0001,1,-1")
    fun `reversed compact branch aliases emit one native instruction without a delay slot`(
        alias: String, native: String, encoding: String, left: Long, right: Long, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("alias.s"), "$alias $12,$15,target\nnop\ntarget: nop\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            for (taken in listOf(true, false)) {
                val program = machine.assemble(listOf(source))
                val statements = program.machineList!!
                assertEquals(3, statements.size)
                assertEquals(native, statements.first().instruction?.name)
                assertEquals(encoding.toUInt(16).toInt(), statements.first().binaryStatement)
                machine.editIntegerRegister(12, if (taken) left else right)
                machine.editIntegerRegister(15, if (taken) right else left)
                program.simulate(1)
                assertEquals(statements[if (taken) 2 else 1].address, machine.registerFile.programCounter)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["j target", "bnezalc $9,target", $$"beq $zero,$9,target", "jalr $31,$9", "eret"])
    fun `only a fallthrough compact branch creates a forbidden slot and backstep restores it`(
        transfer: String, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("slot.s"), $$"""
            beqzc $9,target
            $$transfer
            target: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
            val pc = machine.memory.textBaseAddress
            machine.editIntegerRegister(9, 1)
            program.simulate(1)
            assertEquals(pc + 4, machine.registerFile.programCounter)
            repeat(2) {
                val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
                assertTrue(failure.message!!.contains("control transfer in a forbidden slot"))
                machine.backStepper.backStep()
                assertEquals(pc + 4, machine.registerFile.programCounter)
            }
            machine.backStepper.backStep()
            assertEquals(pc, machine.registerFile.programCounter)
            machine.editIntegerRegister(9, 0)
            program.simulate(1)
            assertEquals(pc + 8, machine.registerFile.programCounter)
            program.simulate(1)
            assertEquals(pc + 12, machine.registerFile.programCounter)
        }
    }

    @Test
    fun `ordinary instructions consume a forbidden slot`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("ordinary-slot.s"), $$"""
            bnezc $9,target
            nop
            j target
            target: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
            assertTrue(program.simulate(6))
        }
    }

    @ParameterizedTest
    @CsvSource(
        "blezalc,18090001,-1,1", "bgezalc,19290001,1,-1",
        "bgtzalc,1c090001,1,0", "bltzalc,1d290001,-1,0",
        "bnezalc,60090001,4294967296,0", "beqzalc,20090001,0,4294967296",
    )
    fun `wide compact conditional calls write the link on either path`(
        mnemonic: String, encoding: String, taken: Long, untaken: Long, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("link.s"), "$mnemonic $9,target\nnop\ntarget: nop\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            for ((operand, branches) in listOf(taken to true, untaken to false)) {
                val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
                val statement = program.machineList!!.first()
                val word = encoding.toUInt(16).toInt()
                assertEquals(word, statement.binaryStatement)
                val decoded = ProgramStatement(word, statement.address, machine.instructionSet)
                assertEquals(mnemonic, decoded.instruction?.name)
                assertEquals(listOf(9, 1), decoded.operands.take(2))
                machine.editIntegerRegister(9, operand)
                machine.editIntegerRegister(31, 0x1_0000_0000)
                program.simulate(1)
                assertEquals(statement.address + if (branches) 8 else 4, machine.registerFile.programCounter)
                assertEquals(statement.address.toLong() + 4, machine.registerFile.getLongValueNoNotify(31))
                machine.backStepper.backStep()
                assertEquals(0x1_0000_0000L, machine.registerFile.getLongValueNoNotify(31))
                assertEquals(statement.address, machine.registerFile.programCounter)
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "beqc,212a0001,4294967296,4294967296,true", "beqc,212a0001,4294967296,0,false",
        "bovc,21490001,2147483647,1,true", "bovc,21490001,4294967296,0,true",
        "bovc,21490001,1,2,false",
    )
    fun `MARS64 decodes the reclaimed ADDI opcode as compact branches`(
        mnemonic: String, encoding: String, left: Long, right: Long, taken: Boolean, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("pop10.s"), "$mnemonic $9,$10,target\nnop\ntarget: nop\n")
        val word = encoding.toUInt(16).toInt()
        TestSessions.create().use { machine ->
            assertEquals("addi", machine.instructionSet.findByBinaryCode(word)?.name)
            machine.selectTarget(MarsTarget.MARS64)
            assertEquals(mnemonic, machine.instructionSet.findByBinaryCode(word)?.name)
            val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
            val branch = program.machineList!!.first()
            assertEquals(word, branch.binaryStatement)
            machine.editIntegerRegister(9, left)
            machine.editIntegerRegister(10, right)
            program.simulate(1)
            assertEquals(branch.address + if (taken) 8 else 4, machine.registerFile.programCounter)
        }
    }

    @Test
    fun `wide inherited pseudos no longer emit the reclaimed ADDI opcode`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("pseudo.s"), $$"""
            add $9,$10,-100
            la $11,($12)
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            assertEquals(listOf("addiu", "add", "daddiu"), program.machineList!!.map { it.instruction?.name })
            machine.editIntegerRegister(10, 110)
            machine.editIntegerRegister(12, 0x1_0000_0000)
            program.simulate(3)
            assertEquals(10L, machine.registerFile.getLongValueNoNotify(9))
            assertEquals(0x1_0000_0000L, machine.registerFile.getLongValueNoNotify(11))
        }
    }

    @ParameterizedTest
    @CsvSource("1,2,true", "2147483647,1,false", "-2147483648,-1,false", "4294967296,0,false")
    fun `bnvc checks both inputs and the sum for signed word overflow`(
        left: Long, right: Long, taken: Boolean, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("overflow.s"), $$"""
            bnvc $9,$10,target
            nop
            target: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
            val statement = program.machineList!!.first()
            assertEquals(0x61490001, statement.binaryStatement)
            assertEquals("bnvc", machine.instructionSet.findByBinaryCode(0x61490001)?.name)
            assertEquals("bnec", machine.instructionSet.findByBinaryCode(0x612a0001)?.name)
            machine.editIntegerRegister(9, left)
            machine.editIntegerRegister(10, right)
            program.simulate(1)
            assertEquals(statement.address + if (taken) 8 else 4, machine.registerFile.programCounter)
        }
    }

    @Test
    fun `compact conditional calls reject the link register as input`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("invalid-link.s"), $$"bnezalc $ra,target\ntarget: nop\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val failure = assertThrows(ProcessingException::class.java) {
                machine.assemble(listOf(source), null, extended = false, warnings = false)
            }
            assertTrue(failure.errors()!!.generateErrorReport().contains("source register must not be the link register"))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `compare branches share encodings without changing the legacy branches`(delayed: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("compare.s"), $$"""
            bgezc $t0,back
            addiu $t2,$t2,1
            back: bltzc $t0,next
            addiu $t3,$t3,1
            next: bnec $t1,$t0,done
            addiu $t4,$t4,1
            done: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.settings.setDelayedBranchingEnabled(delayed)
            val program = machine.assemble(listOf(source))
            machine.editIntegerRegister(8, -1)
            assertTrue(program.simulate(8))
            assertEquals(listOf(1, 0, 0), (10..12).map(machine.registerFile::getValueNoNotify))
            assertEquals(0x61090001, program.machineList!![4].binaryStatement)
            assertEquals("blez", machine.instructionSet.findByBinaryCode(0x19000001)?.name)
            assertEquals("bgtz", machine.instructionSet.findByBinaryCode(0x1d000001)?.name)
            assertEquals("addi", machine.instructionSet.findByBinaryCode(0x212a0001)?.name)
            val backward = ProgramStatement(0x5908fffe, machine.memory.textBaseAddress, machine.instructionSet)
            assertEquals("bgezc", backward.instruction?.name)
            assertEquals(8, backward.operands[0])
            assertEquals(-2, backward.operands[1])
        }
    }

    @Test
    fun `compact register comparisons reject ambiguous register fields`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("ambiguous.s"), $$"""
            bgec $t1,$t1,done
            bltuc $zero,$t2,done
            bgtzc $zero,done
            done: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            val failure = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            assertEquals(3, failure.errors()!!.generateErrorReport().lineSequence().count { "distinct nonzero registers" in it })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `compact zero branches use full operands and no delay slots`(delayed: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("zero.s"), $$"""
            beqzc $t0,skip
            addiu $t2,$t2,1
            skip: bnezc $t0,done
            addiu $t3,$t3,1
            done: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.settings.setDelayedBranchingEnabled(delayed)
            val program = machine.assemble(listOf(source))
            assertEquals(0xd9000001.toInt(), program.machineList!![0].binaryStatement)
            assertEquals(0xf9000001.toInt(), program.machineList!![2].binaryStatement)
            machine.editIntegerRegister(8, 0x1_0000_0000L)
            assertTrue(program.simulate(6))
            assertEquals(1, machine.registerFile.getValue(10))
            assertEquals(0, machine.registerFile.getValue(11))
            repeat(2) { machine.backStepper.backStep() }
            assertEquals(machine.memory.textBaseAddress + 8, machine.registerFile.getPC())
            val decoded = ProgramStatement(0xf91ffffe.toInt(), machine.memory.textBaseAddress, machine.instructionSet)
            assertEquals(-2, decoded.operands[1])
            assertEquals("jic", machine.instructionSet.findByBinaryCode(0xd8080004.toInt())?.name)
            assertEquals("jialc", machine.instructionSet.findByBinaryCode(0xf8080004.toInt())?.name)
        }
    }

    @Test
    fun `compact zero branches reject the register field reserved for indirect jumps`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("reserved.s"), $$"beqzc $zero,target\ntarget: nop\n")
        TestSessions.create().use { machine ->
            val failure = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            assertTrue(failure.errors()!!.generateErrorReport().contains("requires a nonzero register number"))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `compact branches skip slots and preserve their call links`(delayed: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("compact.s"), $$"""
            .text
            bc caller
            addiu $t0,$t0,1
            caller: balc callee
            resume: addiu $t1,$t1,1
            bc done
            addiu $t0,$t0,1
            callee: jic $ra,0
            addiu $t0,$t0,1
            done: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.settings.setDelayedBranchingEnabled(delayed)
            val program = machine.assemble(listOf(source))
            val statements = program.machineList!!
            assertEquals(0xc8000001.toInt(), statements[0].binaryStatement)
            assertEquals(0xe8000003.toInt(), statements[2].binaryStatement)
            assertEquals(0xd81f0000.toInt(), statements[6].binaryStatement)
            val base = machine.memory.textBaseAddress
            assertTrue(program.simulate(10))
            assertEquals(0, machine.registerFile.getValue(8))
            assertEquals(1, machine.registerFile.getValue(9))
            assertEquals(base + 12, machine.registerFile.getValue(31))
            repeat(4) { machine.backStepper.backStep() }
            assertEquals(base + 24, machine.registerFile.getPC())
            machine.backStepper.backStep()
            assertEquals(0, machine.registerFile.getValue(31))
        }
    }

    @Test
    fun `MARS64 branch idiom emits BEQ and executes its delay slot`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("b.s"), $$"""
            b target
            addiu $9,$zero,9
            addiu $10,$zero,10
            target: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            val legacy = machine.assemble(listOf(source))
            assertEquals(0x04010002, legacy.machineList!!.first().binaryStatement)
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            assertEquals(0x10000002, program.machineList!!.first().binaryStatement)
            assertTrue(program.simulate(4))
            assertEquals(9L, machine.registerFile.getLongValueNoNotify(9))
            assertEquals(0L, machine.registerFile.getLongValueNoNotify(10))
        }
    }

    @Test
    fun `compact control flow in an active legacy delay slot reports an exception`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("slot.s"), ".text\nb target\nbc target\ntarget: nop\n")
        TestSessions.create().use { machine ->
            machine.settings.setDelayedBranchingEnabled(true)
            val program = machine.assemble(listOf(source))
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(10) }
            assertTrue(failure.message!!.contains("compact control transfer in a delay slot"))
        }
    }
}
