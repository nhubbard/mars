package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvFileSource

class GnuMips64EncodingTest {
    @ParameterizedTest(name = "{0}, {1} endian")
    @CsvFileSource(resources = ["/gnu-mips64/linked-memory.tsv"], delimiter = '\t')
    fun `linked memory encodings and operands match GNU binutils`(
        source: String, byteOrder: String, word: String, mnemonic: String, operands: String, @TempDir directory: Path,
    ) {
        val file = Files.writeString(directory.resolve("linked-memory.s"), source + "\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.memory.byteOrder = byteOrder == "little"
            val statement = machine.assemble(listOf(file), null, extended = false, warnings = false)
                .machineList!!.single()
            val expectedWord = word.toUInt(16).toInt()
            assertEquals(expectedWord, statement.binaryStatement)
            val decoded = ProgramStatement(expectedWord, statement.addressBits, machine.instructionSet)
            assertEquals(mnemonic, decoded.instruction?.name)
            assertEquals(operands.split(',').map(String::toInt), decoded.operands.take(3))
        }
    }
}
