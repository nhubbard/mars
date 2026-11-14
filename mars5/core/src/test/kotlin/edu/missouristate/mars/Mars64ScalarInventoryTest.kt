package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64ScalarInventoryTest {
    @Test
    fun `each implemented scalar form emits its Release 6 word`(@TempDir directory: Path) {
        val forms = checkNotNull(javaClass.getResourceAsStream("/mips64-scalar-forms.tsv"))
            .bufferedReader().useLines { lines ->
                lines.filterNot { it.isBlank() || it.startsWith('#') }.mapNotNull { row ->
                    val fields = row.split('\t')
                    if (fields[3].startsWith("implemented")) fields[4] to fields[5] else null
                }.toList()
            }
        assertEquals(328, forms.size)
        val source = Files.writeString(
            directory.resolve("release6.mars"),
            forms.joinToString("\n", prefix = ".text\n", postfix = "\ntarget: nop\n") { it.first },
        )
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val statements = checkNotNull(machine.assemble(listOf(source)).machineList)
            assertEquals(forms.size + 1, statements.size)
            for ((index, form) in forms.withIndex()) {
                val (example, expected) = form
                val word = statements[index].binaryStatement.toUInt().toString(16).padStart(8, '0')
                assertEquals(expected, word, example)
            }
        }
    }
}
