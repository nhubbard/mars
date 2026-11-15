package edu.missouristate.mars

import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.Coprocessor1State
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException
import edu.missouristate.mars.mips.hardware.Register
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.InstructionLoader
import edu.missouristate.mars.simulator.Exceptions.BREAKPOINT_EXCEPTION
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION
import java.util.Arrays
import java.util.Objects
import java.util.function.Consumer
import java.util.stream.Stream
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Architectural test vectors: expected results are specified independently of instruction code. */
internal class BasicInstructionSemanticsTest {
    @Test
    fun conditionalSelectTestsAllBitsAndWritesZeroWhenFalse() {
        TestSessions.create().use { session ->
            val registers: IntegerRegisterFile = session.registerFile
            val context = InstructionContext(session, session.syscallEnvironment)
            registers.updateRegister(10, -0x76543211)
            for (condition in intArrayOf(0, Int.MIN_VALUE)) {
                registers.updateRegister(11, condition)
                val pc = registers.getPC()
                context.execute(ProgramStatement(0x014b4835, pc, session.instructionSet))
                Assertions.assertThat(registers.getValue(9))
                    .isEqualTo(if (condition == 0) -0x76543211 else 0)
                context.execute(ProgramStatement(0x014b4837, pc + 4, session.instructionSet))
                Assertions.assertThat(registers.getValue(9))
                    .isEqualTo(if (condition == 0) 0 else -0x76543211)
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instructions")
    fun executesDefinedSemanticsAndReversesHardwareChanges(instruction: BasicInstruction) {
        TestSessions.create().use { session ->
            val context = InstructionContext(session, session.syscallEnvironment)
            val registers: IntegerRegisterFile = session.registerFile
            val floating: Coprocessor1State = session.coprocessor1
            val pc = registers.getPC()
            val address: Int = session.memory.dataBaseAddress
            registers.updateRegister(2, 10)
            registers.updateRegister(4, 3)
            registers.updateRegister(33, 1)
            registers.updateRegister(34, 2)
            session.memory.setWord(address, -0x76543211)
            session.memory.setWord(address + 4, 0x01234567)
            val name: String = instruction.javaClass.getSimpleName().substring(5)
            val base =
                if (
                    name.matches(
                        "(Mov[ft]([SD])?|Jalr|Break|Bc1[tf]|C(Eq|Le|Lt)[SD])[12]".toRegex(),
                    )
                )
                    name.substring(0, name.length - 1)
                else name
            var operands = intArrayOf(8, 2, 4)
            val verify: Consumer<MarsContext?>
            var exception: Int? = null
            val result =
                when (base) {
                    "Add",
                    "Addu" -> 13
                    "Sub",
                    "Subu" -> 7
                    "Addi",
                    "Addiu",
                    "Ori",
                    "Xori" -> 14
                    "And" -> 2
                    "Or" -> 11
                    "Xor" -> 9
                    "Nor" -> 11.inv()
                    "Andi",
                    "Slt",
                    "Sltu",
                    "Slti",
                    "Sltiu",
                    "Srl",
                    "Sra" -> 0
                    "Sll" -> 160
                    "Sllv" -> 80
                    "Srlv",
                    "Srav" -> 1
                    "Mul" -> 30
                    "Aui" -> 0x0004000a
                    "Rotr" -> -0x60000000
                    "Rotrv" -> 0x40000001
                    "Seleqz" -> 0
                    "Selnez" -> 10
                    else -> null
                }
            if (result != null) {
                val expected = result
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(expected)
                }
            } else if (
                mutableSetOf<String?>(
                        "Mult",
                        "Multu",
                        "Madd",
                        "Maddu",
                        "Msub",
                        "Msubu",
                        "Div",
                        "Divu",
                    )
                    .contains(
                        base,
                    )
            ) {
                operands = intArrayOf(2, 4)
                val expectedHi =
                    when (base) {
                        "Madd",
                        "Maddu",
                        "Div",
                        "Divu" -> 1
                        else -> 0
                    }
                val expectedLo =
                    when (base) {
                        "Mult",
                        "Multu" -> 30
                        "Madd",
                        "Maddu" -> 32
                        "Msub",
                        "Msubu" -> -28
                        else -> 3
                    }
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(33)).isEqualTo(expectedHi)
                    Assertions.assertThat(s.registerFile.getValue(34)).isEqualTo(expectedLo)
                }
            } else if (mutableSetOf<String?>("Mfhi", "Mflo", "Mthi", "Mtlo").contains(base)) {
                operands = intArrayOf(8)
                registers.updateRegister(8, 10)
                val target = if (base == "Mthi") 33 else if (base == "Mtlo") 34 else 8
                val expected = if (base == "Mfhi") 1 else if (base == "Mflo") 2 else 10
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(target)).isEqualTo(expected)
                }
            } else if (base == "Lui") {
                operands = intArrayOf(8, 0xabcd)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(-0x54330000)
                }
            } else if (mutableSetOf<String?>("Muh", "Muhu", "Mod", "Modu").contains(base)) {
                registers.updateRegister(2, -10)
                registers.updateRegister(4, 3)
                val expected =
                    when (base) {
                        "Muh",
                        "Mod" -> -1
                        "Muhu" -> 2
                        else -> 0
                    }
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(expected)
                    Assertions.assertThat(s.registerFile.getValue(33)).isEqualTo(1)
                    Assertions.assertThat(s.registerFile.getValue(34)).isEqualTo(2)
                }
            } else if (mutableSetOf<String?>("Addiupc", "Auipc", "Aluipc", "Lwpc").contains(base)) {
                operands = intArrayOf(8, if (base == "Lwpc") 0 else -4)
                val expected =
                    when (base) {
                        "Addiupc" -> pc - 4
                        "Auipc" -> pc - 0x40000
                        "Aluipc" -> (pc - 0x40000) and -0x10000
                        else -> 0
                    }
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(expected)
                }
            } else if (mutableSetOf<String?>("Clo", "Clz").contains(base)) {
                operands = intArrayOf(8, 2)
                registers.updateRegister(2, if (base == "Clo") -0x1000000 else 0x00ffffff)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(8)
                }
            } else if (mutableSetOf<String?>("Align", "Lsa", "Ext", "Ins").contains(base)) {
                registers.updateRegister(2, -0x55443323)
                registers.updateRegister(4, 0x11223344)
                registers.updateRegister(8, 0x12345678)
                val field = base == "Ext" || base == "Ins"
                operands = if (field) intArrayOf(8, 2, 4, 8) else intArrayOf(8, 2, 4, 2)
                val expected =
                    when (base) {
                        "Align" -> 0x3344aabb
                        "Lsa" -> -0x43ee9948
                        "Ext" -> 0xcd
                        else -> 0x12345dd8
                    }
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(expected)
                }
            } else if (mutableSetOf<String?>("Bitswap", "Seb", "Seh", "Wsbh").contains(base)) {
                operands = intArrayOf(8, 2)
                registers.updateRegister(2, 0x0123a5e7)
                val expected =
                    when (base) {
                        "Bitswap" -> -0x7f3b5a19
                        "Seb" -> -0x19
                        "Seh" -> -0x5a19
                        else -> 0x2301e7a5
                    }
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(expected)
                }
            } else if (mutableSetOf<String?>("Movn", "Movz", "Movf", "Movt").contains(base)) {
                if (base == "Movz") {
                    registers.updateRegister(4, 0)
                }
                if (base == "Movt") {
                    floating.setConditionFlag(if (name.endsWith("2")) 4 else 0)
                }
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(10)
                }
            } else if (
                mutableSetOf<String?>(
                        "Beq",
                        "Bne",
                        "Bgez",
                        "Bgezal",
                        "Bgtz",
                        "Blez",
                        "Bltz",
                        "Bltzal",
                    )
                    .contains(
                        base,
                    )
            ) {
                val taken: Boolean = !mutableSetOf<String?>("Blez", "Bltz", "Bltzal").contains(base)
                if (base == "Beq") {
                    registers.updateRegister(4, 10)
                }
                operands =
                    if (base == "Beq" || base == "Bne") intArrayOf(2, 4, 2) else intArrayOf(2, 2)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getPC())
                        .isEqualTo(pc + (if (taken) 12 else 4))
                    if (base == "Bgezal") {
                        Assertions.assertThat(s.registerFile.getValue(31)).isEqualTo(pc + 4)
                    }
                }
            } else if (base == "CompactCompareBranch") {
                val mnemonic = instruction.name
                val zero: Boolean =
                    mutableSetOf<String?>("blezc", "bgezc", "bgtzc", "bltzc").contains(mnemonic)
                val taken: Boolean =
                    !mutableSetOf<String?>("blezc", "bltzc", "bltc", "bltuc").contains(mnemonic)
                operands = if (zero) intArrayOf(2, 4) else intArrayOf(2, 4, 4)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getPC())
                        .isEqualTo(pc + (if (taken) 20 else 4))
                }
            } else if (base == "CompactZeroBranch") {
                registers.updateRegister(2, if (instruction.name == "beqzc") 0 else 1)
                operands = intArrayOf(2, 4)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getPC()).isEqualTo(pc + 20)
                }
            } else if (mutableSetOf<String?>("Bc", "Balc", "Jic", "Jialc").contains(base)) {
                val branch = base == "Bc" || base == "Balc"
                val link = base == "Balc" || base == "Jialc"
                registers.updateRegister(2, pc + 20)
                operands = if (branch) intArrayOf(4) else intArrayOf(2, -4)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getPC())
                        .isEqualTo(pc + (if (branch) 20 else 16))
                    Assertions.assertThat(s.registerFile.getValue(31))
                        .isEqualTo(if (link) pc + 4 else 0)
                }
            } else if (mutableSetOf<String?>("J", "Jal", "Jr", "Jalr").contains(base)) {
                registers.updateRegister(2, pc + 32)
                operands =
                    if (base == "J" || base == "Jal") intArrayOf((pc + 32) ushr 2)
                    else if (base == "Jalr" && name.endsWith("1")) intArrayOf(8, 2)
                    else intArrayOf(2)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getPC()).isEqualTo(pc + 32)
                    if (base == "Jal") {
                        Assertions.assertThat(s.registerFile.getValue(31)).isEqualTo(pc + 4)
                    }
                    if (base == "Jalr") {
                        Assertions.assertThat(
                                s.registerFile.getValue(if (name.endsWith("1")) 8 else 31),
                            )
                            .isEqualTo(pc + 4)
                    }
                }
            } else if (
                mutableSetOf<String?>(
                        "Lw",
                        "Ll",
                        "Lwl",
                        "Lwr",
                        "Lb",
                        "Lbu",
                        "Lh",
                        "Lhu",
                        "Sw",
                        "Sc",
                        "Swl",
                        "Swr",
                        "Sb",
                        "Sh",
                        "Lwc1",
                        "Ldc1",
                        "Swc1",
                        "Sdc1",
                    )
                    .contains(base)
            ) {
                registers.updateRegister(2, address)
                registers.updateRegister(8, 0x12345678)
                floating.setRegisterToInt(8, 0x12345678)
                floating.setRegisterToInt(9, 0x76543210)
                operands = intArrayOf(8, if (base == "Lwl" || base == "Swl") 3 else 0, 2)
                val expectedLoad =
                    when (base) {
                        "Lw",
                        "Ll",
                        "Lwl",
                        "Lwr" -> -0x76543211
                        "Lb" -> -17
                        "Lbu" -> 239
                        "Lh" -> -12817
                        "Lhu" -> 52719
                        else -> null
                    }
                verify = Consumer { s: MarsContext? ->
                    if (expectedLoad != null) {
                        Assertions.assertThat(s!!.registerFile.getValue(8)).isEqualTo(expectedLoad)
                    } else {
                        when (base) {
                            "Lwc1",
                            "Ldc1" -> {
                                Assertions.assertThat(s!!.coprocessor1.getValue(8))
                                    .isEqualTo(-0x76543211)
                                if (base == "Ldc1") {
                                    Assertions.assertThat(s.coprocessor1.getValue(9))
                                        .isEqualTo(0x01234567)
                                }
                            }

                            else -> {
                                val expectedWord =
                                    if (base == "Sb") -0x76543288
                                    else if (base == "Sh") -0x7654a988 else 0x12345678
                                try {
                                    Assertions.assertThat(s!!.memory.getWord(address))
                                        .isEqualTo(expectedWord)
                                    if (base == "Sdc1") {
                                        Assertions.assertThat(s.memory.getWord(address + 4))
                                            .isEqualTo(0x76543210)
                                    }
                                } catch (e: AddressErrorException) {
                                    throw AssertionError(e)
                                }
                                if (base == "Sc") {
                                    Assertions.assertThat(s.registerFile.getValue(8)).isEqualTo(1)
                                }
                            }
                        }
                    }
                }
            } else if (base == "Mfc0" || base == "Mtc0") {
                operands = intArrayOf(8, Coprocessor0State.EPC)
                session.coprocessor0.updateRegister(Coprocessor0State.EPC, 42)
                registers.updateRegister(8, 99)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(
                            if (base == "Mfc0") s!!.registerFile.getValue(8)
                            else s!!.coprocessor0.getValue(Coprocessor0State.EPC),
                        )
                        .isEqualTo(if (base == "Mfc0") 42 else 99)
                }
            } else if (base == "Release6Float") {
                val doubles: Boolean = instruction.name.endsWith(".d")
                if (doubles) {
                    floating.setRegisterPairToDouble(2, -4.0)
                    floating.setRegisterPairToDouble(4, 2.0)
                    floating.setRegisterPairToDouble(8, 1.0)
                } else {
                    floating.setRegisterToFloat(2, -4.0f)
                    floating.setRegisterToFloat(4, 2.0f)
                    floating.setRegisterToFloat(8, 1.0f)
                }
                val expected =
                    when (
                        instruction.name
                            .split("\\.".toRegex())
                            .dropLastWhile { it.isEmpty() }
                            .toTypedArray()[0]
                    ) {
                        "sel",
                        "seleqz",
                        "min",
                        "maxa" -> -4.0
                        "selnez" -> 0.0
                        "maddf" -> -7.0
                        "msubf" -> 9.0
                        "mina",
                        "max" -> 2.0
                        else -> throw AssertionError(instruction.name)
                    }
                verify = Consumer { s: MarsContext? ->
                    if (doubles) {
                        Companion.assertDouble(s!!, expected)
                    } else {
                        Assertions.assertThat(s!!.coprocessor1.getFloatFromRegister(8))
                            .isEqualTo(expected.toFloat())
                    }
                }
            } else if (base == "ClassS" || base == "ClassD") {
                operands = intArrayOf(2, 4)
                floating.setRegisterPairToLong(4, -0x7fffffff00800000L)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.coprocessor1.getValue(2))
                        .isEqualTo(if (base == "ClassS") 4 else 16)
                }
            } else if (base == "Mfhc1" || base == "Mthc1") {
                operands = intArrayOf(8, 4)
                registers.updateRegister(8, 99)
                floating.setRegisterPairToLong(4, 0x0000002a12345678L)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.coprocessor1.getValue(4)).isEqualTo(0x12345678)
                    if (base == "Mfhc1") {
                        Assertions.assertThat(s.registerFile.getValue(8)).isEqualTo(42)
                    } else {
                        Assertions.assertThat(s.coprocessor1.getValue(5)).isEqualTo(99)
                    }
                }
            } else if (base == "Mfc1" || base == "Mtc1") {
                operands = intArrayOf(8, 2)
                floating.setRegisterToInt(2, 42)
                registers.updateRegister(8, 99)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(
                            if (base == "Mfc1") s!!.registerFile.getValue(8)
                            else s!!.coprocessor1.getValue(2),
                        )
                        .isEqualTo(if (base == "Mfc1") 42 else 99)
                }
            } else if (base == "Bc1t" || base == "Bc1f") {
                val flag = if (name.endsWith("2")) 4 else 0
                if (base == "Bc1t") {
                    floating.setConditionFlag(flag)
                }
                operands = if (name.endsWith("2")) intArrayOf(flag, 2) else intArrayOf(2)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getPC()).isEqualTo(pc + 12)
                }
            } else if (base.startsWith("CEq") || base.startsWith("CLe") || base.startsWith("CLt")) {
                val doubles: Boolean = base.endsWith("D")
                val flag = if (name.endsWith("2")) 4 else 0
                if (doubles) {
                    floating.setRegisterPairToDouble(2, 2.0)
                    floating.setRegisterPairToDouble(4, 4.0)
                } else {
                    floating.setRegisterToFloat(2, 2f)
                    floating.setRegisterToFloat(4, 4f)
                }
                operands = if (name.endsWith("2")) intArrayOf(flag, 2, 4) else intArrayOf(2, 4)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.coprocessor1.getConditionFlag(flag))
                        .isEqualTo(if (base.startsWith("CEq")) 0 else 1)
                }
            } else if (base.endsWith("S") || base.endsWith("D") || base.startsWith("Cvt")) {
                val doubles: Boolean = base.endsWith("D")
                if (doubles) {
                    floating.setRegisterPairToDouble(2, 4.75)
                    floating.setRegisterPairToDouble(4, 2.0)
                } else {
                    floating.setRegisterToFloat(2, 4.75f)
                    floating.setRegisterToFloat(4, 2f)
                }
                operands = intArrayOf(8, 2, 4)
                val operation: String = base.substring(0, base.length - 1)
                if (base.startsWith("Cvt")) {
                    when (base) {
                        "CvtDS" -> {
                            floating.setRegisterToFloat(2, 4.75f)
                            verify = Consumer { s: MarsContext? ->
                                Companion.assertDouble(s!!, 4.75)
                            }
                        }

                        "CvtDW" -> {
                            floating.setRegisterToInt(2, 42)
                            verify = Consumer { s: MarsContext? ->
                                Companion.assertDouble(s!!, 42.0)
                            }
                        }

                        "CvtSD" -> {
                            floating.setRegisterPairToDouble(2, 4.75)
                            verify = Consumer { s: MarsContext? ->
                                Assertions.assertThat(
                                        s!!.coprocessor1.getFloatFromRegister(8),
                                    )
                                    .isEqualTo(4.75f)
                            }
                        }

                        "CvtSW" -> {
                            floating.setRegisterToInt(2, 42)
                            verify = Consumer { s: MarsContext? ->
                                Assertions.assertThat(
                                        s!!.coprocessor1.getFloatFromRegister(8),
                                    )
                                    .isEqualTo(42f)
                            }
                        }

                        "CvtWD",
                        "CvtWS" -> verify = Consumer { s: MarsContext? ->
                                Assertions.assertThat(s!!.coprocessor1.getValue(8)).isEqualTo(4)
                            }

                        else -> throw AssertionError(base)
                    }
                } else if (operation.startsWith("Mov")) {
                    if (operation == "Movt") {
                        floating.setConditionFlag(if (name.endsWith("2")) 4 else 0)
                    }
                    if (operation == "Movz") {
                        registers.updateRegister(4, 0)
                    }
                    verify = Consumer { s: MarsContext? ->
                        if (doubles) {
                            Companion.assertDouble(s!!, 4.75)
                        } else {
                            Assertions.assertThat(s!!.coprocessor1.getFloatFromRegister(8))
                                .isEqualTo(4.75f)
                        }
                    }
                } else if (
                    mutableSetOf<String?>("FloorW", "CeilW", "RoundW", "TruncW").contains(operation)
                ) {
                    val expected = if (operation == "FloorW" || operation == "TruncW") 4 else 5
                    verify = Consumer { s: MarsContext? ->
                        Assertions.assertThat(s!!.coprocessor1.getValue(8)).isEqualTo(expected)
                    }
                } else {
                    val expected =
                        when (operation) {
                            "Add" -> 6.75
                            "Sub" -> 2.75
                            "Mul" -> 9.5
                            "Div" -> 2.375
                            "Sqrt" -> Math.sqrt(4.75)
                            "Abs" -> 4.75
                            "Neg" -> -4.75
                            else -> throw AssertionError("Missing vector for " + name)
                        }
                    verify = Consumer { s: MarsContext? ->
                        if (doubles) {
                            Companion.assertDouble(s!!, expected)
                        } else {
                            Assertions.assertThat(s!!.coprocessor1.getFloatFromRegister(8))
                                .isEqualTo(expected.toFloat())
                        }
                    }
                }
            } else if (base.startsWith("T")) {
                operands = intArrayOf(2, if (base.endsWith("i") || base.endsWith("iu")) 3 else 4)
                if (base.startsWith("Teq")) {
                    registers.updateRegister(2, 3)
                }
                val traps: Boolean = !base.startsWith("Tlt")
                exception = if (traps) TRAP_EXCEPTION else null
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getValue(8)).isZero()
                }
            } else if (base == "Break") {
                operands = intArrayOf(0)
                exception = BREAKPOINT_EXCEPTION
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.coprocessor0.getValue(Coprocessor0State.EPC))
                        .isEqualTo(pc)
                }
            } else if (base == "Syscall") {
                registers.updateRegister(2, 10)
                exception = -1
                verify = Consumer { `_`: MarsContext? ->
                    Assertions.assertThat(context.exitCode).isZero()
                }
            } else if (base == "Eret") {
                session.coprocessor0.updateRegister(Coprocessor0State.EPC, pc + 32)
                session.coprocessor0.updateRegister(Coprocessor0State.STATUS, 3)
                verify = Consumer { s: MarsContext? ->
                    Assertions.assertThat(s!!.registerFile.getPC()).isEqualTo(pc + 32)
                    Assertions.assertThat(s.coprocessor0.getValue(Coprocessor0State.STATUS))
                        .isEqualTo(1)
                }
            } else {
                if (base == "Nop") {
                    verify = Consumer { s: MarsContext? ->
                        Assertions.assertThat(s!!.registerFile.getPC()).isEqualTo(pc + 4)
                    }
                } else {
                    throw AssertionError("Missing semantic vector for " + name)
                }
            }

            val beforeRegisters =
                Arrays.stream(registers.registers).mapToInt(Register::value).toArray()
            val beforeFloating =
                Arrays.stream(floating.registers).mapToInt(Register::value).toArray()
            val beforeCp0 =
                Arrays.stream(session.coprocessor0.registers).mapToInt(Register::value).toArray()
            val beforeFlags = floating.conditionFlags
            val statement =
                ProgramStatement(
                    null,
                    instruction.exampleFormat,
                    null,
                    null,
                    instruction,
                    pc,
                    1,
                )
            System.arraycopy(
                operands,
                0,
                Objects.requireNonNull<IntArray?>(statement.operands),
                0,
                operands.size,
            )
            if (exception == null) {
                context.execute(statement)
            } else {
                val failure =
                    Assertions.catchThrowable { context.execute(statement) }
                Assertions.assertThat<Throwable?>(failure)
                    .isInstanceOf(ProcessingException::class.java)
                if (exception == -1) {
                    Assertions.assertThat((failure as ProcessingException).errors())
                        .isNull()
                } else {
                    Assertions.assertThat(
                            (session.coprocessor0.getValue(Coprocessor0State.CAUSE) ushr 2) and 31,
                        )
                        .isEqualTo(exception)
                }
            }
            verify.accept(session)
            Assertions.assertThat(registers.getValue(0)).isZero()
            session.backStepper.backStep()
            Assertions.assertThat(
                    Arrays.stream(registers.registers).mapToInt(Register::value).toArray(),
                )
                .containsExactly(*beforeRegisters)
            Assertions.assertThat(
                    Arrays.stream(floating.registers).mapToInt(Register::value).toArray(),
                )
                .containsExactly(*beforeFloating)
            Assertions.assertThat(
                    Arrays.stream(session.coprocessor0.registers)
                        .mapToInt(Register::value)
                        .toArray(),
                )
                .containsExactly(*beforeCp0)
            Assertions.assertThat(floating.conditionFlags).isEqualTo(beforeFlags)
            Assertions.assertThat(registers.getPC()).isEqualTo(pc)
            Assertions.assertThat(session.memory.getWord(address)).isEqualTo(-0x76543211)
            Assertions.assertThat(session.memory.getWord(address + 4)).isEqualTo(0x01234567)
        }
    }

    companion object {
        @JvmStatic
        fun instructions(): Stream<BasicInstruction> {
            return InstructionLoader().loadBasicInstructions().stream()
        }

        private fun assertDouble(session: MarsContext, expected: Double) {
            try {
                Assertions.assertThat(session.coprocessor1.getDoubleFromRegisterPair(8))
                    .isEqualTo(expected)
            } catch (error: InvalidRegisterAccessException) {
                throw AssertionError(error)
            }
        }
    }
}
