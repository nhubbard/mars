package edu.missouristate.mars.plugin.language

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.editorconfig.Utils
import org.editorconfig.configmanagement.extended.EditorConfigCodeStyleSettingsModifier
import org.editorconfig.settings.EditorConfigSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@TestApplication
class MipsEditorConfigPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native EditorConfig indentation is per file and respects the MARS language override`() = timeoutRunBlocking {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            val temporary = CodeStyleSettingsManager.getInstance(current).temporarySettings
            CodeStyle.dropTemporarySettings(current)
            val editorConfig = CodeStyle.getSettings(current).getCustomSettings(EditorConfigSettings::class.java)
            val wasEnabled = editorConfig.ENABLED
            val wasEnabledInTests = Utils.isEnabledInTests
            editorConfig.ENABLED = true
            // EditorConfig's own platform tests enable its normal modifier in unit-test mode.
            Utils.isEnabledInTests = true
            EditorConfigCodeStyleSettingsModifier.Handler.setEnabledInTests(true)
            try {
                fixture.addFileToProject(".editorconfig", """
                    root = true

                    [*.asm]
                    indent_style = space
                    indent_size = 2

                    [*.s]
                    indent_style = tab
                    indent_size = tab
                    tab_width = 8
                """.trimIndent())
                fixture.addFileToProject("nested/.editorconfig", "[*.asm]\nij_mars-mips_indent_size = 6\n")
                val source = $$".macro emit (%value)\nnop\n.end_macro\n.text\nmain:\naddiu $a0,$zero,42\n"
                for ((path, size, prefix) in listOf(
                    Triple("main.asm", 2, "  "),
                    Triple("other.s", 8, "\t"),
                    Triple("nested/child.asm", 6, "      "),
                )) {
                    val file = fixture.addFileToProject(path, source)
                    fixture.configureFromExistingVirtualFile(file.virtualFile)
                    val options = CodeStyle.getIndentOptions(file)
                    assertEquals(size, options.INDENT_SIZE, path)
                    assertEquals(prefix == "\t", options.USE_TAB_CHARACTER, path)
                    val expected =
                      $$".macro emit (%value)\n$${prefix}nop\n.end_macro\n.text\nmain:\n$${prefix}addiu $a0, $zero, 42\n"
                    repeat(2) {
                        WriteCommandAction.runWriteCommandAction(current) { CodeStyleManager.getInstance(current).reformat(file) }
                        fixture.checkResult(expected)
                    }
                }
            } finally {
                EditorConfigCodeStyleSettingsModifier.Handler.setEnabledInTests(false)
                Utils.isEnabledInTests = wasEnabledInTests
                editorConfig.ENABLED = wasEnabled
                temporary?.let { CodeStyle.setTemporarySettings(current, it) }
            }
        }
    }
}
