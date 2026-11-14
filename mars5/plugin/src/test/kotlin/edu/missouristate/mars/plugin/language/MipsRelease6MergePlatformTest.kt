package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.inspection.MipsRelease6MergeInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsRelease6MergePlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `only MARS64 warns about direct and substituted merge mnemonics`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val inspection = MipsRelease6MergeInspection()
            assertTrue(inspection.runForWholeFile())
            fixture.enableInspections(inspection)
            val source = $$"""
                .text
                .eqv MERGE swr
                lwl $9,3($8)
                LWR $9,0($8)
                swl $9,3($8)
                MERGE $9,0($8)
                lwl: nop
                ulw $9,0($8)
                unknown_operation $9
                .macro emit (%value)
                    nop
                .end_macro
                emit(1)
            """.trimIndent()
            fixture.configureByText("migration.mars", source)
            val settings = fixture.project.service<MarsAssemblySettings>()
            suspend fun mergeWarnings() = fixture.highlightAfterMipsRefresh()
                .filter { it.description?.startsWith("Release 6 removed") == true }
                .map { source.substring(it.startOffset, it.endOffset) }

            assertEquals(emptyList<String>(), mergeWarnings())
            settings.changeTarget(MarsTarget.MARS64)
            try {
                assertEquals(listOf("lwl", "LWR", "swl", "MERGE"), mergeWarnings())
            } finally {
                settings.changeTarget(MarsTarget.MARS32)
            }
            assertEquals(emptyList<String>(), mergeWarnings())
        }
    }
}
