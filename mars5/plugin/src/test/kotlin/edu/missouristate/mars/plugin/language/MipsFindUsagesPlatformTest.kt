package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.Processor
import com.intellij.util.IncorrectOperationException
import edu.missouristate.mars.plugin.build.MarsAssemblyMode
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsMacroFormal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsFindUsagesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native usage search resolves nested includes externals and macro overloads`() = timeoutRunBlocking {
        val source = $$"""
            .include "parts/outer.s"
            j .target.label
            addiu $t0, $zero, LIMIT
            emit()
            emit($t0)
            la $t1, $buffer
            .eqv LIMIT
            # j .target.label
            .data
            .asciiz ".target.label $buffer"
        """.trimIndent()
        val definitions = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/outer.s", ".include \"defs.s\"\n")
            val defs = fixture.addFileToProject("parts/defs.s", $$"""
                .eqv LIMIT 7
                .extern $buffer 4
                .target.label: nop
                .macro emit
                nop
                .end_macro
                .macro emit (%arg)
                move $a0, %arg
                .end_macro
            """.trimIndent())
            fixture.addFileToProject("unrelated.s", ".target.label: nop\nj .target.label\n")
            fixture.configureFromExistingVirtualFile(fixture.addFileToProject("entry.asm", source).virtualFile)
            defs
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val targets = readAction { declarations(definitions).sortedBy { it.textOffset } }
        val names = listOf("LIMIT", $$"$buffer", ".target.label", "emit", "emit")
        val types = listOf("constant", "external symbol", "label", "macro", "macro")
        readAction {
            assertEquals(names, targets.map { it.name })
            assertEquals(types, targets.map(LanguageFindUsages::getType))
            assertEquals(names, targets.map(LanguageFindUsages::getDescriptiveName))
            assertEquals(names, targets.map { LanguageFindUsages.getNodeText(it, false) })
            assertTrue(targets.all(LanguageFindUsages::canFindUsagesFor))
            assertNotNull(LanguageFindUsages.getHelpId(targets.first()))
        }
        val sites = listOf("LIMIT", $$"$buffer", ".target.label", "emit()", $$"emit($t0)")
        for ((target, site) in targets.zip(sites)) {
            val offsets = if (site == "LIMIT") setOf(source.indexOf(site), source.lastIndexOf(site)) else setOf(source.indexOf(site))
            assertEquals(offsets.map { "entry.asm" to it }.toSet(), usageLocations(target), site)
        }
    }

    @Test
    fun `native usage search finds labels consisting entirely of periods`() = timeoutRunBlocking {
        val source = ".: nop\n..: nop\nj .\nj ..\n"
        val file = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("periods.s", source)
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val (one, two) = readAction { declarations(file).sortedBy { it.textOffset } }
        assertEquals(setOf("periods.s" to source.indexOf("j .") + 2), usageLocations(one))
        assertEquals(setOf("periods.s" to source.indexOf("j ..") + 2), usageLocations(two))
    }

    @Test
    fun `native usage search distinguishes macro-local labels from a same-named outer label`() = timeoutRunBlocking {
        val source = """
            target: nop
            .macro emit
            target: nop
            b target
            .end_macro
            emit
            j target
            .eqv 7 8
        """.trimIndent()
        val file = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("shadow.s", source)
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val (outer, local) = readAction {
            val nodes = declarations(file)
            val unnamed = nodes.single { it.name == null }
            assertFalse(LanguageFindUsages.canFindUsagesFor(unnamed))
            assertEquals(".eqv 7 8", LanguageFindUsages.getDescriptiveName(unnamed))
            val instruction = file.findElementAt(source.indexOf("nop"))!!
            assertFalse(LanguageFindUsages.canFindUsagesFor(instruction))
            assertEquals("", LanguageFindUsages.getType(instruction))
            assertEquals("nop", LanguageFindUsages.getNodeText(instruction, true))
            nodes.filter { it.name == "target" }.sortedBy { it.textOffset }
        }
        assertEquals(setOf("shadow.s" to source.indexOf("j target") + 2), usageLocations(outer))
        assertEquals(setOf("shadow.s" to source.indexOf("b target") + 2), usageLocations(local))
    }

    @Test
    fun `native usage search resolves written and aliased macro formals across includes`() = timeoutRunBlocking {
        val header = $$".macro emit (%?, $value, FORMALS, %?, BROKEN)\n"
        val entry = $$"""
            .eqv FORMALS (%first, %second)
            .include "header.s"
            move $a0, $value
            %?(%first)
            li $a1, %second
            # %? %first
            .asciiz "%second"
            .macro ignored (%orphan)
            .end_macro
            .macro other (%first, $value)
            li $a0, %first
            .end_macro
        """.trimIndent()
        val current = project.get()
        val (headerFile, entryFile) = withContext(Dispatchers.EDT) {
            val signature = fixture.addFileToProject("header.s", header)
            val root = fixture.addFileToProject("formals.asm", entry)
            fixture.configureFromExistingVirtualFile(root.virtualFile)
            signature to root
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val formals = readAction {
            PsiTreeUtil.findChildrenOfType(headerFile, MipsMacroFormal::class.java).sortedBy { it.textOffset }
        }
        val (operation, value, aliases, duplicate, invalid) = formals
        val operationUse = entry.indexOf("%?(")
        val firstUse = operationUse + "%?(".length
        val secondUse = entry.indexOf("%second", entry.indexOf("li "))
        readAction {
            assertEquals(listOf("%?", $$"$value", "FORMALS", "%?", "BROKEN"), formals.map { it.name })
            assertEquals(listOf(true, true, true, true, false), formals.map(LanguageFindUsages::canFindUsagesFor))
            val orphan = entryFile.findElementAt(entry.indexOf("%orphan"))!!.parent
            assertFalse(LanguageFindUsages.canFindUsagesFor(orphan))
            assertEquals("macro parameter", LanguageFindUsages.getType(operation))
            assertEquals("%?", LanguageFindUsages.getDescriptiveName(operation))
            assertThrows(IncorrectOperationException::class.java) { value.setName("%") }
            val flags = TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED or TargetElementUtil.ELEMENT_NAME_ACCEPTED
            assertEquals(aliases, TargetElementUtil.getInstance().findTargetElement(fixture.editor, flags, firstUse + 1))
            assertEquals(operation, entryFile.findReferenceAt(operationUse)?.resolve())
        }
        assertEquals(setOf("formals.asm" to operationUse), usageLocations(operation))
        assertEquals(setOf("formals.asm" to entry.indexOf($$"$value", entry.indexOf("move"))), usageLocations(value))
        assertEquals(setOf("formals.asm" to firstUse, "formals.asm" to secondUse), usageLocations(aliases))
        assertTrue(usageLocations(duplicate).isEmpty())
        withContext(Dispatchers.EDT) {
            DumbModeTestUtils.runInDumbModeSynchronously(current) { assertFalse(LanguageFindUsages.canFindUsagesFor(value)) }
        }
    }

    @Test
    fun `native usage search joins realized macro calls from current assemblies`() = timeoutRunBlocking {
        val left = """
            .include "defs.s"
            .eqv CALL inner
            .macro outer (%operation)
                inner(1)
                CALL(2)
                %operation(3)
            .end_macro
            outer(inner)
            CALL(5)
            nop
        """.trimIndent()
        val right = ".include \"defs.s\"\n.macro outer (%operation)\n.include \"body.inc\"\n.include \"body.inc~\"\n.end_macro\nouter(inner)\nnop"
        val body = "# expanded body\n%operation(4)\n"
        val current = project.get()
        val (definitions, entryFiles) = withContext(Dispatchers.EDT) {
            val defs = fixture.addFileToProject("defs.s", ".macro inner (%value)\n.end_macro\n.macro alternative (%value)\n.end_macro\n")
            fixture.addFileToProject("body.inc", body)
            fixture.tempDirFixture.createFile("body.inc~", "%operation(5)\n")
            defs to listOf(
                fixture.addFileToProject("left.asm", left), fixture.addFileToProject("right.asm", right),
                fixture.addFileToProject("unrelated.asm", "nop\n"),
            )
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            for (file in entryFiles) {
                fixture.configureFromExistingVirtualFile(file.virtualFile)
                fixture.highlightAfterMipsRefresh()
            }
        }
        val leftFile = entryFiles.first()
        val (inner, alternative) = readAction { declarations(definitions).sortedBy { it.textOffset } }
        val literal = "left.asm" to left.indexOf("inner(1)")
        val alias = "left.asm" to left.indexOf("CALL(2)")
        val directAlias = "left.asm" to left.indexOf("CALL(5)")
        val parameter = "left.asm" to left.indexOf("%operation(3)")
        val included = "body.inc" to body.indexOf("%operation")
        assertEquals(setOf(literal, alias, directAlias, parameter, included), realizedUsageLocations(inner))
        assertTrue(realizedUsageLocations(alternative).isEmpty())
        readAction {
            val aliasToken = leftFile.findElementAt(alias.second)!!
            assertEquals("CALL", (aliasToken.reference?.resolve() as MipsDeclaration).name)
            val parameterToken = leftFile.findElementAt(parameter.second)!!
            assertEquals("%operation", parameterToken.reference?.resolve()?.text)
            val scope = LocalSearchScope(aliasToken)
            assertTrue(ReferencesSearch.search(definitions, scope).findAll().isEmpty())
            val query = ReferencesSearch.search(inner, scope)
            assertFalse(query.forEach(Processor { reference ->
                assertEquals(inner, reference.resolve())
                false
            }))
            assertEquals(listOf(aliasToken), query.findAll().map { it.element })
        }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(leftFile.virtualFile)
            val document = fixture.editor.document
            val call = left.lastIndexOf("inner")
            WriteCommandAction.runWriteCommandAction(current) {
                document.replaceString(call, call + "inner".length, "alternative")
            }
            PsiDocumentManager.getInstance(current).commitDocument(document)
            assertEquals(setOf(included), realizedUsageLocations(inner))
            fixture.highlightAfterMipsRefresh()
        }
        val movedDirectAlias = "left.asm" to left.replace("outer(inner)", "outer(alternative)").indexOf("CALL(5)")
        assertEquals(setOf(literal, alias, movedDirectAlias, included), realizedUsageLocations(inner))
        assertEquals(setOf(parameter), realizedUsageLocations(alternative))
    }

    @Test
    fun `native label usage search finds assembled aliases and forwarded arguments`() = timeoutRunBlocking {
        val source = """
            .include "defs.s"
            .eqv DEST target
            .include "operands.txt"
            outer(target)
            j DEST
            j target
        """.trimIndent()
        val body = """
            .data
            .word DEST
            .text
            .macro inner (%address)
                j %address
            .end_macro
            .macro outer (%address)
                inner(%address)
            .end_macro
        """.trimIndent()
        val current = project.get()
        val definitions = withContext(Dispatchers.EDT) {
            val defs = fixture.addFileToProject("defs.s", "target: nop\nother: nop\n")
            fixture.addFileToProject("operands.txt", body)
            fixture.configureFromExistingVirtualFile(fixture.addFileToProject("entry.asm", source).virtualFile)
            defs
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val (target, other) = readAction { declarations(definitions).sortedBy { it.textOffset } }
        val direct = "entry.asm" to source.lastIndexOf("target")
        val forwarded = "entry.asm" to (source.indexOf("outer(target)") + "outer(".length)
        val alias = "entry.asm" to source.lastIndexOf("DEST")
        val data = "operands.txt" to body.indexOf("DEST")
        assertEquals(setOf(direct), realizedUsageLocations(target))
        withContext(Dispatchers.EDT) { fixture.highlightAfterMipsRefresh() }
        assertEquals(setOf(direct, forwarded, alias, data), realizedUsageLocations(target))
        readAction {
            val caller = fixture.file.findElementAt(forwarded.second)!!
            val query = ReferencesSearch.search(target, LocalSearchScope(caller))
            assertFalse(query.forEach(Processor { false }))
            assertEquals(target, query.findFirst()!!.resolve())
        }
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(current) {
                fixture.editor.document.setText(source.replace(".eqv DEST target", ".eqv DEST other"))
            }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
        }
        val changedDirect = "entry.asm" to direct.second - 1
        val changedForwarded = "entry.asm" to forwarded.second - 1
        val changedAlias = "entry.asm" to alias.second - 1
        assertEquals(setOf(changedDirect), realizedUsageLocations(target))
        withContext(Dispatchers.EDT) { fixture.highlightAfterMipsRefresh() }
        assertEquals(setOf(changedDirect, changedForwarded), realizedUsageLocations(target))
        assertEquals(setOf(changedAlias, data), realizedUsageLocations(other))
    }

    @Test
    fun `native usage search respects the directory assembly inputs and assembler policy`() = timeoutRunBlocking {
        val source = ".include \"parts/defs.s\"\n.eqv CALL inner\n.macro outer\nCALL\n.end_macro\nouter\nnop\n"
        val current = project.get()
        val (entry, definitions) = withContext(Dispatchers.EDT) {
            val defs = fixture.addFileToProject("parts/defs.s", ".macro inner\n.end_macro\n")
            fixture.addFileToProject("dir.asm", source).virtualFile to defs
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val settings = current.service<MarsAssemblySettings>()
        val sources = MarsSourceSet(entry.toNioPath(), MarsAssemblyMode.DIRECTORY)
        assertNotNull(current.service<MarsBuildService>().prepare(sources, settings.executionOptions, activateBuildView = false))
        val target = readAction { declarations(definitions).single() }
        val expected = setOf("dir.asm" to source.lastIndexOf("CALL"))
        assertEquals(expected, realizedUsageLocations(target))
        withContext(Dispatchers.EDT) {
            settings.changeAssemblerOptions(allowPseudoInstructions = false, warningsAreErrors = false)
            assertTrue(realizedUsageLocations(target).isEmpty())
            settings.changeAssemblerOptions(allowPseudoInstructions = true, warningsAreErrors = false)
            settings.changeExceptionHandler(definitions.virtualFile.path)
            assertTrue(realizedUsageLocations(target).isEmpty())
            settings.changeExceptionHandler("")
            assertEquals(expected, realizedUsageLocations(target))
            fixture.addFileToProject("new-sibling.s", "nop\n")
            assertTrue(realizedUsageLocations(target).isEmpty())
        }
    }

    private suspend fun realizedUsageLocations(target: MipsDeclaration): Set<Pair<String, Int>> = readAction {
        val results = fixture.findUsages(target).map { usage ->
            requireNotNull(usage.virtualFile).name to requireNotNull(usage.segment).startOffset
        }
        assertEquals(results.size, results.toSet().size)
        results.toSet()
    }

    private fun declarations(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java)

    private suspend fun usageLocations(target: PsiElement): Set<Pair<String, Int>> = readAction {
        fixture.findUsages(target).map { usage ->
            val reference = requireNotNull(usage.reference)
            reference.element.containingFile.name to (reference.element.textRange.startOffset + reference.rangeInElement.startOffset)
        }.toSet()
    }
}
