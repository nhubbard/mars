package edu.missouristate.mars.plugin.language

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.modcommand.ActionContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.xmlb.XmlSerializer
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.inspection.MipsRelease6MergeInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsRelease6MergePlatformTest {
  private val project = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(project)

  @Test
  fun `safe fixes replace only the mnemonic in the editor and are shared with batch inspection`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val inspection = MipsRelease6MergeInspection()
          fixture.enableInspections(inspection)
          for ((source, name, replacement) in
              listOf(
                  Triple(
                      $$"loop: BE<caret>QL $8,$9,loop # compare\n    nop # slot\n",
                      "beql",
                      "beq",
                  ),
                  Triple($$"da<caret>ddi $8,$9,0 # keep\n", "daddi", "daddiu"),
                  Triple($$"da<caret>ddi $8,$zero,-32768\n", "daddi", "daddiu"),
              )) {
            fixture.configureByText("quick-fix.mars", source)
            fixture.highlightAfterMipsRefresh()
            val holder =
                ProblemsHolder(InspectionManager.getInstance(fixture.project), fixture.file, false)
            val visitor = inspection.buildVisitor(holder, false)
            fixture.file.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                  override fun visitElement(element: PsiElement) {
                    element.accept(visitor)
                    super.visitElement(element)
                  }
                },
            )
            val title = "Replace $name with $replacement"
            assertEquals(title, holder.results.single().fixes!!.single().name)
            val intention = fixture.availableIntentions.single { it.text == title }
            val command =
                checkNotNull(release6QuickFix(fixture.file.findElementAt(fixture.caretOffset)!!))
            fixture.launchAction(intention)
            assertEquals(
                source
                    .replace("<caret>", "")
                    .replace(Regex(name, RegexOption.IGNORE_CASE), replacement),
                fixture.editor.document.text,
            )
            assertTrue(
                fixture.highlightAfterMipsRefresh().none {
                  it.description?.startsWith("Release 6 removed") == true
                },
            )
            val context = ActionContext.from(fixture.editor, fixture.file)
            val current = fixture.file.findElementAt(fixture.caretOffset)!!
            assertTrue(!command.isApplicable(current))
            assertEquals(null, command.getPresentation(context))
            assertTrue(command.perform(context).isEmpty)
          }
        }
      }

  @Test
  fun `migration fixes require safe operands and a literal no-op delay slot`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val unsupported =
              listOf(
                  $$"be<caret>ql $8,$9,start\n    addiu $8,$8,1\nstart: nop",
                  $$"be<caret>ql $8,$9,start",
                  $$"da<caret>ddi $8,$9,-1",
                  $$"da<caret>ddi $8,$zero,32768",
                  $$"da<caret>ddi $8,$zero,-32769",
                  $$"da<caret>ddi $8,label,1",
                  $$"da<caret>ddi $8,$zero,label",
                  $$"da<caret>ddi $8,0",
                  $$"da<caret>ddi $8,$zero,@",
                  "be<caret>ql:",
                  "be<caret>ql: nop",
                  $$".eqv beql bnel\nbe<caret>ql $8,$9,start\nnop",
                  $$".macro branch\nbe<caret>ql $8,$9,start\nnop\n.end_macro",
                  $$".include \"missing.inc\"\nbe<caret>ql $8,$9,start\nnop",
              )
          for (source in unsupported) {
            fixture.configureByText("unsafe.mars", source)
            val site = fixture.file.findElementAt(fixture.caretOffset)!!
            assertEquals(null, release6QuickFix(site), source)
          }
          fixture.configureByText("stale.mars", $$"be<caret>ql $8,$9,target\nnop\ntarget: nop\n")
          val fix =
              checkNotNull(release6QuickFix(fixture.file.findElementAt(fixture.caretOffset)!!))
          val document = fixture.editor.document
          WriteCommandAction.runWriteCommandAction(fixture.project) {
            val start = document.getLineStartOffset(1)
            document.replaceString(start, document.getLineEndOffset(1), $$"addiu $8,$8,1")
            PsiDocumentManager.getInstance(fixture.project).commitDocument(document)
          }
          val context = ActionContext.from(fixture.editor, fixture.file)
          assertEquals(null, fix.getPresentation(context))
          assertTrue(fix.perform(context).isEmpty)
        }
      }

  @Test
  fun `only MARS64 warns about removed instructions and their aliases`() = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val inspection = MipsRelease6MergeInspection()
      assertTrue(inspection.runForWholeFile())
      fixture.enableInspections(inspection)
      val source =
          $$"""
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
          mfc1.d $v0,$f2
          .eqv DIVIDE div
          DIVIDE $8,$9
          div $10,$8,$9
          addi $8,$9,-100
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
          """
              .trimIndent()
      fixture.configureByText("migration.mars", source)
      val settings = fixture.project.service<MarsAssemblySettings>()
      val oldSettings =
          Element("state")
              .addContent(
                  Element("option").setAttribute("name", "target").setAttribute("value", "MARS32"),
              )
      settings.loadState(
          XmlSerializer.deserialize(oldSettings, MarsAssemblySettings.Settings::class.java),
      )
      suspend fun mergeWarnings() =
          fixture
              .highlightAfterMipsRefresh()
              .filter { it.description?.startsWith("Release 6 removed") == true }
              .map { source.substring(it.startOffset, it.endOffset) }

      fun batchDiagnostics(): List<String> {
        val holder =
            ProblemsHolder(InspectionManager.getInstance(fixture.project), fixture.file, false)
        val visitor = inspection.buildVisitor(holder, false)
        fixture.file.accept(
            object : PsiRecursiveElementWalkingVisitor() {
              override fun visitElement(element: PsiElement) {
                element.accept(visitor)
                super.visitElement(element)
              }
            },
        )
        return holder.results.map { it.psiElement.text }
      }

      assertEquals(MarsTarget.MARS64, settings.target)
      try {
        val plain = fixture.addFileToProject("notes.txt", "lwl")
        val holder = ProblemsHolder(InspectionManager.getInstance(fixture.project), plain, false)
        assertEquals(PsiElementVisitor.EMPTY_VISITOR, inspection.buildVisitor(holder, false))
        val expected =
            listOf("lwl", "LWR", "swl", "MERGE", "LIKELY", "mflo", "c.eq.s", "mfc1.d", "DIVIDE")
        assertEquals(expected, mergeWarnings())
        assertEquals(expected, batchDiagnostics())
      } finally {
        settings.changeTarget(MarsTarget.MARS32)
      }
      assertEquals(emptyList<String>(), mergeWarnings())
      assertEquals(emptyList<String>(), batchDiagnostics())

      settings.changeTarget(MarsTarget.MARS64)
      fixture.configureByText("malformed.mars", $$"div $8, @")
      assertEquals(emptyList<String>(), batchDiagnostics())
    }
  }
}
