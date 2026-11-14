package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Release6MigrationTest {
    @Test
    fun `removed forms explain their replacement after equivalence expansion`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("migration.s"), $$"""
            .eqv MERGE lwr
            MERGE $8,0($9)
            SWL $8,0($9)
            mflo $8
            bc1f target
            bgezal $8,target
            target: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.assemble(listOf(source))
            machine.selectTarget(MarsTarget.MARS64)
            val failure = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            val diagnostics = failure.errors()!!.generateErrorReport()
            for ((instruction, replacement) in listOf(
                "lwr" to "Use lw", "SWL" to "Use sw", "mflo" to "Use explicit destination registers",
                "bc1f" to "Use a cmp instruction", "bgezal" to "Use bal",
            )) {
                assertTrue(diagnostics.contains("Release 6 removed \"$instruction\". $replacement"), diagnostics)
            }
        }
    }
}
