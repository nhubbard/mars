package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsParameterInfoPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native parameter info presents every distinct basic and pseudo form`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val hint = info("entry: DIV \$t0 \$t1, <caret>").orEmpty()
            val rows = hint.split("\n-\n")
            val forms = InstructionDefaults.get().matchOperator("div").map { it.exampleFormat }.toSet()
            assertEquals(forms, rows.map { it.replace("<b>", "").replace("</b>", "") }.toSet())
            assertEquals(forms.size, rows.size)
            assertTrue("div \$t1,\$t2,<b>\$t3</b>" in rows, hint)
            assertTrue("div \$t1,\$t2,<b>-100</b>" in rows, hint)
            assertTrue("div \$t1,\$t2" in rows, hint)
        }
    }

    @Test
    fun `current token follows separators and incomplete memory addresses`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            assertEquals("sll <b>\$t1</b>,\$t2,10", info("sll<caret>"))
            assertEquals("sll <b>\$t1</b>,\$t2,10", info("sll \$t<caret>0, \$t1, 4"))
            assertEquals("sll \$t1,<b>\$t2</b>,10", info("sll \$t0 <caret>"))
            val address = info("  ,,lw \$t0,, -4(<caret>").orEmpty()
            assertTrue("lw \$t1,-100(<b>\$t2</b>)" in address.split("\n-\n"), address)
            val symbol = info("lw \$t0, entry+<caret>").orEmpty()
            assertTrue("lw \$t1,label+<b>100000</b>" in symbol.split("\n-\n"), symbol)
            assertEquals("nop", info("nop <caret>"))
        }
    }

    @Test
    fun `parameter info stays off comments and noninstruction text`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            for (source in listOf("   <caret>", "@<caret>", "a<caret>dd \$t0", "entry: <caret>", ".word <caret>1",
                "custom \$t0 <caret>", "add \$t0, \$t1 # <caret>comment")) {
                assertNull(info(source), source)
            }
        }
    }

    private fun info(source: String): String? {
        fixture.configureByText("parameter-info.asm", "$source\n")
        return fixture.parameterInfoAtCaret
    }
}
