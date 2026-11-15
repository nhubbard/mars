package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64MsaInventoryTest {
    private data class Form(val mnemonic: String, val source: String, val word: Int)

    @Test
    fun `every form in the MSA instruction reference assembles and decodes`(@TempDir directory: Path) {
        val forms = checkNotNull(javaClass.getResourceAsStream("/mips64-msa-forms.tsv"))
            .bufferedReader().useLines { lines ->
                lines.filterNot { it.isBlank() || it.startsWith('#') }.map { row ->
                    val fields = row.split('\t')
                    val (mnemonic, operands) = fields
                    val source = operands.split(',').joinToString(",") { checkNotNull(operandExamples[it]) { row } }
                    Form(mnemonic, "$mnemonic $source", fields.last().toUInt(16).toInt())
                }.toList()
            }
        assertEquals(531, forms.size)
        val source = Files.writeString(
            directory.resolve("msa.mars"),
            forms.joinToString("\n", prefix = ".text\n", postfix = "\ntarget: nop\n", transform = Form::source),
        )
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source), null, false, false)
            val statements = checkNotNull(program.machineList)
            assertEquals(forms.size + 1, statements.size)
            for ((index, form) in forms.withIndex()) {
                val word = statements[index].binaryStatement
                assertEquals(form.word.toUInt().toString(16), word.toUInt().toString(16), form.source)
                assertEquals(form.mnemonic, machine.instructionSet.findByBinaryCode(word)?.name, form.source)
            }
        }
    }

    private companion object {
        val operandExamples = mapOf(
            "rd" to "$9", "rs" to "$10", "rt" to "$11", "sa" to "2",
            "wd" to $$"$w8", "ws" to $$"$w10", "wt" to $$"$w13",
            "cd" to "$1", "cs" to "$1",
            "wd[n]" to $$"$w8[1]", "ws[n]" to $$"$w10[1]", "ws[0]" to $$"$w10[0]", "ws[rt]" to $$"$w10[$11]",
            "s5" to "-2", "u5" to "2", "i8" to "2", "m" to "1",
            "s10" to "2", "s10(rs)" to "8($10)", "s16" to "target",
        )
    }
}
