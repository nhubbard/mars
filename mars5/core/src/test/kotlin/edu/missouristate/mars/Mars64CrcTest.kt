package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.CRC32C
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Mars64CrcTest {
    @ParameterizedTest
    @CsvSource(
        "crc32b,7d49000f,1,false", "crc32h,7d49004f,2,false",
        "crc32w,7d49008f,4,false", "crc32d,7d4900cf,8,false",
        "crc32cb,7d49010f,1,true", "crc32ch,7d49014f,2,true",
        "crc32cw,7d49018f,4,true", "crc32cd,7d4901cf,8,true",
    )
    fun `CRC encodings and results agree with LLVM and JVM checksums`(
        mnemonic: String, encoding: String, bytes: Int, castagnoli: Boolean, @TempDir directory: Path,
    ) {
        val path = Files.writeString(directory.resolve("crc.s"), $$"$$mnemonic $9,$10,$9\n$$mnemonic $9,$11,$9\n")
        TestSessions.create().use { session ->
            val word = encoding.toInt(16)
            assertNull(session.instructionSet.findByBinaryCode(word))
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(path), null, false, false)
            val statement = program.machineList!!.first()
            assertEquals(word, statement.binaryStatement)
            val decoded = ProgramStatement(word, statement.address, session.instructionSet)
            assertEquals(mnemonic, decoded.instruction?.name)
            assertEquals(listOf(9, 10, 9), decoded.operands.take(3))
            val checksum = if (castagnoli) CRC32C() else CRC32()
            val seed = 0x1234_5678_ffff_ffffL
            val first = 0xfedc_ba98_7654_3210uL.toLong()
            val second = 0x0132_5476_98ba_dcfeL
            session.editIntegerRegister(9, seed)
            session.editIntegerRegister(10, first)
            session.editIntegerRegister(11, second)
            for (message in listOf(first, second)) {
                for (index in 0 until bytes) checksum.update((message ushr (index * Byte.SIZE_BITS)).toInt())
                program.simulate(1)
                assertEquals(checksum.value.toInt().inv().toLong(), session.registerFile.getLongValueNoNotify(9))
            }
            repeat(2) { session.backStepper.backStep() }
            assertEquals(seed, session.registerFile.getLongValueNoNotify(9))
            Files.writeString(path, $$"$$mnemonic $9,$10,$11\n")
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(path), null, false, false) }
        }
    }
}
