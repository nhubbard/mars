package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.VADDR
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarsAddressWidthTest {
    @ParameterizedTest
    @ValueSource(booleans = [MemoryState.LITTLE_ENDIAN, MemoryState.BIG_ENDIAN])
    fun `assembly places source and label fixups above four GiB`(littleEndian: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("wide-layout.s"), $$"""
            .data 0x100000000
            entry_ptr: .dword main
            cell: .dword 0x0123456789abcdef
            .text 0x100400000
            .globl main
            main: dla $a0,cell
            la $a1,entry_ptr
            ld $t0,0($a0)
            ld $t1,0($a1)
            dli $t2,81985529216486895
            beq $t0,$t2,matched
            nop
            break
            matched: j done
            addiu $t3,$zero,1
            break
            done: nop
        """.trimIndent())
        val entry = 0x1_0040_0000L
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.settings.setStartAtMainEnabled(true)
            machine.memory.byteOrder = littleEndian
            val program = machine.assemble(listOf(source))
            assertEquals(entry, machine.registerFile.programCounterBits)
            assertEquals(entry, program.symbolMap().globals().getValue("main").addressBits)
            assertEquals(entry, program.sourceMap().originsByAddress.keys.min())
            assertTrue(program.sourceMap().breakpointsByAddress.values.any { entry in it })
            assertEquals(entry, program.pseudoInstructions {}.first().addressBits)
            val image = machine.captureAssemblyImage {}
            assertTrue(program.simulate(40))
            assertEquals(0x0123_4567_89ab_cdefL, machine.registerFile.getLongValueNoNotify(8))
            assertEquals(entry, machine.registerFile.getLongValueNoNotify(9))
            assertEquals(1L, machine.registerFile.getLongValueNoNotify(11))
            TestSessions.create().use { restored ->
                restored.loadAssemblyImage(image) {}
                assertEquals(entry, restored.registerFile.programCounterBits)
                assertTrue(restored.program!!.simulate(40))
                assertEquals(entry, restored.registerFile.getLongValueNoNotify(9))
            }
        }
    }

    @Test
    fun `opt-in generated code preserves wide PC links branches and EPC`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("generated.s"), $$"""
            jialc $a0,0
            nop
            entry: addiupc $t0,0
            b target
            addiu $t1,$zero,1
            break
            target: balc callee
            break
            callee: lwpc $t2,0
            jic $ra,0
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.settings.setSelfModifyingCodeEnabled(true)
            val program = machine.assemble(listOf(source))
            val high = 0x1_0000_1000L
            val generated = program.machineList!!.drop(2)
            generated.forEachIndexed { index, statement ->
                machine.memory.writeData(high + index * 4, 4, statement.binaryStatement)
            }
            machine.editIntegerRegister(4, high)
            val call = program.executeSessionInstruction()
            assertEquals(machine.memory.textBaseAddress.toLong() + 4, (call.transfer as StepEvent.Call).returnAddressBits)
            assertEquals(high, machine.registerFile.programCounterBits)

            assertEquals(high, program.executeSessionInstruction().pcBits)
            assertEquals(high, machine.registerFile.getLongValueNoNotify(8))
            program.executeSessionInstruction()
            program.executeSessionInstruction()
            assertEquals(1L, machine.registerFile.getLongValueNoNotify(9))
            assertEquals(high + 16, machine.registerFile.programCounterBits)
            machine.backStepper.backStep()
            assertEquals(high + 8, machine.registerFile.programCounterBits)
            assertEquals(0L, machine.registerFile.getLongValueNoNotify(9))
            assertEquals(high + 4, machine.backStepper.previousExecution()?.addressBits)
            program.executeSessionInstruction()

            val compactCall = program.executeSessionInstruction()
            assertEquals(high + 20, (compactCall.transfer as StepEvent.Call).returnAddressBits)
            assertEquals(high + 20, machine.registerFile.getLongValueNoNotify(31))
            program.executeSessionInstruction()
            assertEquals(generated[6].binaryStatement, machine.registerFile.getValue(10))
            val jump = program.executeSessionInstruction()
            assertEquals(high + 20, (jump.transfer as StepEvent.Jump).targetBits)
            val fault = assertThrows(ProcessingException::class.java) { program.executeSessionInstruction() }
            assertTrue(fault.message.orEmpty().contains("0x0000000100001014"))
            assertEquals(high + 20, machine.coprocessor0.getRegister(14)!!.longValueNoNotify)
            machine.backStepper.backStep()
            assertEquals(high + 20, machine.registerFile.programCounterBits)
            assertEquals(0L, machine.coprocessor0.getRegister(14)!!.longValueNoNotify)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [MemoryState.LITTLE_ENDIAN, MemoryState.BIG_ENDIAN])
    fun `native memory instructions use a distinct data segment above four GiB`(
        littleEndian: Boolean, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("high-data.s"), $$"""
            .text
            ori $a0,$zero,1
            dsll32 $a0,$a0,0
            ori $a0,$a0,0x1000
            ori $a1,$zero,0x0123
            dsll $a1,$a1,16
            ori $a1,$a1,0x4567
            dsll $a1,$a1,16
            ori $a1,$a1,0x89ab
            dsll $a1,$a1,16
            ori $a1,$a1,0xcdef
            sd $a1,1($a0)
            ld $t0,1($a0)
            ldc1 $f3,1($a0)
            sdc1 $f3,24($a0)
            lld $t1,24($a0)
            daddiu $t1,$t1,1
            scd $t1,24($a0)
            ld.b $w5,0($a0)
            st.b $w5,32($a0)
            ld $t2,33($a0)
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.memory.byteOrder = littleEndian
            val program = machine.assemble(listOf(source))
            val address = 0x1_0000_1000L
            val payload = 0x0123_4567_89ab_cdefL
            assertTrue(program.simulate(40))
            assertEquals(payload, machine.registerFile.getLongValueNoNotify(8))
            assertEquals(1L, machine.registerFile.getLongValueNoNotify(9))
            assertEquals(payload, machine.registerFile.getLongValueNoNotify(10))
            assertEquals(payload, machine.coprocessor1.getDoublewordNoNotify(3))
            assertEquals(address + 47, machine.backStepper.previousMemoryWrite()?.addressBits)
            assertEquals(if (littleEndian) 0xf0 else 0x01, machine.memory.readData(address + 24, 1))
            repeat(4) { machine.backStepper.backStep() }
            assertEquals(if (littleEndian) 0xef else 0x01, machine.memory.readData(address + 24, 1))
            assertEquals(0, machine.memory.readData(address + 33, 1))
            assertEquals(payload + 1, machine.registerFile.getLongValueNoNotify(9))
            assertTrue(program.simulate(5))
            assertEquals(1L, machine.registerFile.getLongValueNoNotify(9))
            val image = machine.captureAssemblyImage {}
            TestSessions.create().use { restored ->
                restored.loadAssemblyImage(image) {}
                assertEquals(if (littleEndian) 0xf0 else 0x01, restored.memory.readData(address + 24, 1))
                assertEquals(if (littleEndian) 0xef else 0x01, restored.memory.readData(address + 33, 1))
            }
        }
    }

    @Test
    fun `a store crossing the end of high data faults without modifying the mapped prefix`(
        @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("boundary.s"), $$"""
            ori $a0,$zero,1
            dsll $a0,$a0,16
            ori $a0,$a0,0x40
            dsll $a0,$a0,16
            daddiu $a0,$a0,-4
            daddiu $a1,$zero,-1
            sd $a1,0($a0)
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            assertThrows(ProcessingException::class.java) { program.simulate(20) }
            assertEquals(0x1_003f_fffcL, machine.coprocessor0.getRegister(VADDR)!!.longValueNoNotify)
            assertEquals(0, machine.memory.readData(0x1_003f_fffcL, 4))
        }
    }

    @Test
    fun `doubleword CP0 transfers preserve address registers and reverse wide writes`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("cp0.s"), $$"""
            dmtc0 $9,$14
            dmfc0 $10,$14,0
            dmtc0 $9,$12
            dmfc0 $11,$12
            dmfc0 $10,$14,1
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source), null, false, false)
            assertEquals(listOf(0x40a97000, 0x402a7000, 0x40a96000, 0x402b6000, 0x402a7001),
                program.machineList!!.map(ProgramStatement::binaryStatement))
            val bits = 0x1234_5678_fedc_ba98L
            machine.editIntegerRegister(9, bits)
            program.simulate(4)
            assertEquals(bits, machine.registerFile.getLongValueNoNotify(10))
            assertEquals(0xfedc_ba98L, machine.registerFile.getLongValueNoNotify(11))
            assertThrows(ProcessingException::class.java) { program.simulate(1) }
            repeat(5) { machine.backStepper.backStep() }
            assertEquals(0L, machine.coprocessor0.getRegister(14)!!.longValueNoNotify)
            assertEquals(0L, machine.registerFile.getLongValueNoNotify(10))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [MemoryState.LITTLE_ENDIAN, MemoryState.BIG_ENDIAN])
    fun `doubleword address forms use one full register in MARS64`(littleEndian: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("wide-memory.s"), $$"""
            .data
            cell: .dword 0x123456789abcdef0
            copy: .dword 0
            .text
            ld $t0,cell
            sd $t0,cell+8($zero)
            la $a0,copy
            ld $t2,($a0)
            ld $t3,copy($zero)
            ld $t4,cell+8
            ld $t5,0x10010008
            ld $t6,65544($a1)
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.memory.byteOrder = littleEndian
            val program = machine.assemble(listOf(source))
            assertEquals(6, program.machineList!!.count { it.instruction?.name == "ld" })
            assertEquals(1, program.machineList!!.count { it.instruction?.name == "sd" })
            machine.editIntegerRegister(5, machine.memory.dataBaseAddress - 65536)
            machine.editIntegerRegister(9, 42)
            assertTrue(program.simulate(30))
            for (register in listOf(8, 10, 11, 12, 13, 14)) {
                assertEquals(0x1234_5678_9abc_def0L, machine.registerFile.getLongValueNoNotify(register), "register $register")
            }
            assertEquals(42L, machine.registerFile.getLongValueNoNotify(9))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "lb \$t0,0(\$a0)", "lbu \$t0,0(\$a0)", "lh \$t0,0(\$a0)", "lhu \$t0,0(\$a0)",
        "lw \$t0,0(\$a0)", "lwu \$t0,0(\$a0)", "ll \$t0,0(\$a0)", "ulw \$t0,1(\$a0)", "ulhu \$t0,1(\$a0)",
        "ld \$t0,0(\$a0)", "lwc1 \$f0,0(\$a0)", "ldc1 \$f0,0(\$a0)",
        "sb \$t0,0(\$a0)", "sh \$t0,0(\$a0)", "sw \$t0,0(\$a0)", "sc \$t0,0(\$a0)", "usw \$t0,1(\$a0)",
        "ush \$t0,1(\$a0)", "sd \$t0,0(\$a0)", "swc1 \$f0,0(\$a0)", "sdc1 \$f0,0(\$a0)",
    ])
    fun `unmapped high bits cannot alias mapped data`(instruction: String, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("data.s"), ".data\n.word 42,0\n.text\n$instruction\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            val mapped = machine.memory.dataBaseAddress
            val unmapped = mapped.toLong() + 0x1_0000_0000L
            machine.editIntegerRegister(4, unmapped)
            machine.editIntegerRegister(8, 99)
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            val effectiveAddress = unmapped + if (instruction.startsWith('u')) 1 else 0
            assertTrue(failure.message!!.contains("outside mapped memory: 0x" + effectiveAddress.toString(16).padStart(16, '0')))
            assertEquals(effectiveAddress, machine.coprocessor0.getRegister(VADDR)!!.longValueNoNotify)
            val expectedCause = if (instruction.removePrefix("u").startsWith('s')) ADDRESS_EXCEPTION_STORE else ADDRESS_EXCEPTION_LOAD
            assertEquals(expectedCause, (machine.coprocessor0.getValue(CAUSE) ushr 2) and 31)
            assertEquals(42, machine.memory.getWord(mapped))
            assertEquals(99, machine.registerFile.getValue(8))
            machine.backStepper.backStep()
            assertEquals(0L, machine.coprocessor0.getRegister(VADDR)!!.longValueNoNotify)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jr \$a0", "jalr \$a0", "jalr \$ra,\$a0", "jic \$a0,4", "jialc \$a0,4"])
    fun `unmapped high bits cannot alias text or change a link`(instruction: String, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("jump.s"), "$instruction\nnop\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            machine.editIntegerRegister(4, 0x2_0000_0000L + machine.memory.textBaseAddress)
            machine.editIntegerRegister(31, 0x2_0000_0000L)
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertTrue(failure.message!!.contains("outside mapped memory"))
            assertEquals(0x2_0000_0000L, machine.registerFile.getLongValueNoNotify(31))
            assertEquals(ADDRESS_EXCEPTION_LOAD, (machine.coprocessor0.getValue(CAUSE) ushr 2) and 31)
        }
    }

    @Test
    fun `effective address applies the signed offset before checking the map`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("offset.s"), $$"""
            .data
            cell: .word 42,0
            .text
            lw $t0,-4($a0)
            ld $t1,-4($a0)
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            machine.editIntegerRegister(4, machine.memory.dataBaseAddress + 4)
            assertTrue(program.simulate(3))
            assertEquals(42, machine.registerFile.getValue(8))
            assertEquals(42L, machine.registerFile.getLongValueNoNotify(9))
        }
    }
}
