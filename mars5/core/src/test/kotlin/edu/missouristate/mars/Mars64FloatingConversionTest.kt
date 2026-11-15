package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64FloatingConversionTest {
    @ParameterizedTest
    @CsvSource(
        "cvt.s.d,46201fe0,3ff0000010000000,3f800000,3f800001",
        "cvt.s.w,46801fe0,1000001,4b800000,4b800001",
        "cvt.s.l,46a01fe0,10000000001,53800000,53800001",
        "cvt.d.s,46001fe1,3f800001,3ff0000020000000,3ff0000020000000",
        "cvt.d.w,46801fe1,7fffffff,41dfffffffc00000,41dfffffffc00000",
        "cvt.d.l,46a01fe1,20000000000001,4340000000000000,4340000000000001",
    )
    fun `conversions match LLVM encodings and respect the directed rounding mode`(
        mnemonic: String, encoding: String, inputHex: String, nearestHex: String, upwardHex: String, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("convert.s"), $$"""
            $$mnemonic $f31,$f3
            ctc1 $8,$31
            $$mnemonic $f31,$f3
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, extended = false, warnings = false)
            val word = encoding.toInt(16)
            val statement = program.machineList!!.first()
            assertEquals(word, statement.binaryStatement)
            assertEquals(mnemonic, ProgramStatement(word, statement.address, session.instructionSet).instruction!!.name)
            val fpu = session.coprocessor1
            session.editCoprocessor1Pair(3, inputHex.toLong(16))
            session.editIntegerRegister(8, 2)
            program.simulate(1)
            assertEquals(nearestHex.toLong(16), fpu.getDoublewordNoNotify(31))
            assertEquals(if (nearestHex == upwardHex) 0x000c0000 else 0x000c1004, fpu.floatingStatus)
            program.simulate(2)
            assertEquals(upwardHex.toLong(16), fpu.getDoublewordNoNotify(31))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `narrowing handles overflow underflow and signed directed results`(mode: Int, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("narrow.s"), buildString {
            repeat(4) {
                appendLine($$"ctc1 $8,$31")
                appendLine($$"cvt.s.d $f31,$f3")
            }
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, extended = false, warnings = false)
            val fpu = session.coprocessor1
            val nearZero = -Math.scalb(1.0, -151)
            val inputs = listOf(Double.MAX_VALUE, -Double.MAX_VALUE, nearZero, Math.scalb(1.0, -149))
            val words = listOf(
                if (mode == 0 || mode == 2) 0x7f800000 else 0x7f7fffff,
                if (mode == 0 || mode == 3) 0xff800000 else 0xff7fffff,
                if (mode == 3) 0x80000001 else 0x80000000,
                1,
            )
            val statuses = listOf(0x000c5014, 0x000c5014, 0x000c300c, 0x000c0000)
            session.editIntegerRegister(8, mode)
            for ((index, value) in inputs.withIndex()) {
                session.editCoprocessor1Pair(3, value.toRawBits())
                program.simulate(2)
                assertEquals(words[index].toInt(), fpu.getValue(31))
                assertEquals(statuses[index] or mode, fpu.floatingStatus)
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "7ff0000060000000,0,7fc00003,d0040,false",
        "7ff0000060000000,800,0,d0800,true",
        "36a0000000000000,1000000,0,10c300c,false",
        "36a0000000000000,100,0,c2100,true",
        "1,1000000,0,10c1004,false",
    )
    fun `narrowing preserves NaN payloads and handles flushing and enabled traps`(
        input: String, control: String, result: String, status: String, trap: Boolean, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("traps.s"), $$"""
            ctc1 $8,$31
            cvt.s.d $f31,$f3
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, extended = false, warnings = false)
            session.editIntegerRegister(8, control.toInt(16))
            session.editCoprocessor1Pair(3, input.toLong(16))
            program.simulate(1)
            if (trap) assertThrows(ProcessingException::class.java) { program.simulate(1) }
            else program.simulate(1)
            assertEquals(result.toInt(16), session.coprocessor1.getValue(31))
            assertEquals(status.toInt(16), session.coprocessor1.floatingStatus)
        }
    }
}
