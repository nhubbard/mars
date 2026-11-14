package edu.missouristate.mars.plugin.language

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
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
    fun `only MARS64 warns about removed instructions and their aliases`() = timeoutRunBlocking {
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
                .eqv LIKELY beql
                LIKELY $8,$9,target
                mflo $8
                c.eq.s $f0,$f2
                addi $8,$9,100000
                target: nop
                lwl: nop
                ulw $9,0($8)
                unknown_operation $9
                .macro emit (%value)
                    nop
                .end_macro
                emit(1)
                .eqv SITE generated:
                SITE
                .eqv REGISTER $9
                REGISTER $8
                .eqv SECTION .text
                SECTION
            """.trimIndent()
            fixture.configureByText("migration.mars", source)
            val settings = fixture.project.service<MarsAssemblySettings>()
            suspend fun mergeWarnings() = fixture.highlightAfterMipsRefresh()
                .filter { it.description?.startsWith("Release 6 removed") == true }
                .map { source.substring(it.startOffset, it.endOffset) }

            fun batchDiagnostics(): List<String> {
                val holder = ProblemsHolder(InspectionManager.getInstance(fixture.project), fixture.file, false)
                val visitor = inspection.buildVisitor(holder, false)
                fixture.file.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        element.accept(visitor)
                        super.visitElement(element)
                    }
                })
                return holder.results.map { it.psiElement.text }
            }

            assertEquals(emptyList<String>(), mergeWarnings())
            assertEquals(emptyList<String>(), batchDiagnostics())
            settings.changeTarget(MarsTarget.MARS64)
            try {
                val plain = fixture.addFileToProject("notes.txt", "lwl")
                val holder = ProblemsHolder(InspectionManager.getInstance(fixture.project), plain, false)
                assertEquals(PsiElementVisitor.EMPTY_VISITOR, inspection.buildVisitor(holder, false))
                val expected = listOf("lwl", "LWR", "swl", "MERGE", "LIKELY", "mflo", "c.eq.s")
                assertEquals(expected, mergeWarnings())
                assertEquals(expected, batchDiagnostics())
            } finally {
                settings.changeTarget(MarsTarget.MARS32)
            }
            assertEquals(emptyList<String>(), mergeWarnings())
        }
    }
}
