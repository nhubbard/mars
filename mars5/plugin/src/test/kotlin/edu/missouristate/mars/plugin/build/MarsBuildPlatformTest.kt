package edu.missouristate.mars.plugin.build

import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.SuccessResult
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.include.FileIncludeManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.replaceService
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.assembleIn
import edu.missouristate.mars.plugin.analysis.captureAssemblySources
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import java.nio.file.Files
import java.util.HexFormat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsBuildPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()

    @Test
    fun `an included file uses earlier definitions in the selected entry even after a failed build`() = timeoutRunBlocking<Unit> {
        val source = """
            li ${'$'}t0, %value
            li ${'$'}t1, COUNT
            .end_macro
            helper ARGS
            la ${'$'}t4, reservation
            j publicAfter
            li ${'$'}t2, LATE
        """.trimIndent()
        fun entry(values: String) = """
            .eqv UNUSED 1
            .eqv RESERVE .extern
            RESERVE reservation 4
            .include "$values"
            .macro helper (%number)
              li ${'$'}t3, %number
            .end_macro
            .eqv DEFINE .eqv
            DEFINE ARGS (9)
            .macro body (%value)
              .include "parts/wrapper.s"
            body (42)
            .eqv LATE 99
            publicAfter: nop
        """.trimIndent()
        val (first, second) = withContext(Dispatchers.EDT) {
            val shared = fixture.addFileToProject("parts/shared.s", source).virtualFile
            fixture.addFileToProject("parts/wrapper.s", ".include \"shared.s\"\n")
            fixture.addFileToProject("first-values.s", ".eqv COUNT 7\n")
            fixture.addFileToProject("second-values.s", ".eqv COUNT 11\n")
            val first = fixture.addFileToProject("first.s", entry("first-values.s")).virtualFile
            val second = fixture.addFileToProject("second.s", entry("second-values.s")).virtualFile
            fixture.configureFromExistingVirtualFile(shared)
            first to second
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        fun target(name: String) = fixture.file.findReferenceAt(source.indexOf(name))?.resolve()
        suspend fun assemble(file: VirtualFile): Boolean {
            val component = MarsSessionService.createMarsComponent()
            return component.context().use {
                project.service<MarsBuildService>().assemble(file.toNioPath(), component, activateBuildView = false)
            }
        }
        readAction {
            assertNull(target("COUNT"))
            assertNull(target("%value"))
            assertNull(target("reservation"))
            assertNull(target("publicAfter"))
        }
        for ((entry, values) in listOf(first to "first-values.s", second to "second-values.s")) {
            assertFalse(assemble(entry))
            readAction {
                assertEquals(values, target("COUNT")?.containingFile?.name)
                assertEquals(entry, target("%value")?.containingFile?.virtualFile)
                assertEquals("%value", target("%value")?.text)
                assertEquals(entry, target("helper")?.containingFile?.virtualFile)
                assertEquals("helper (%number)", target("helper")?.text)
                assertEquals(entry, target("ARGS")?.containingFile?.virtualFile)
                assertEquals(entry, target("reservation")?.containingFile?.virtualFile)
                assertEquals("RESERVE", target("reservation")?.text)
                assertEquals(entry, target("publicAfter")?.containingFile?.virtualFile)
                assertEquals("publicAfter:", target("publicAfter")?.text)
                assertNull(target("LATE"))
            }
        }
        withContext(Dispatchers.EDT) {
            edit(second, ".eqv COUNT\n" + entry("second-values.s"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        readAction { assertEquals("second-values.s", target("COUNT")?.containingFile?.name) }
        withContext(Dispatchers.EDT) {
            edit(second, entry("second-values.s").replace(".include \"parts/wrapper.s\"", ".end_macro"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        readAction {
            assertEquals("first-values.s", target("COUNT")?.containingFile?.name)
            assertEquals(first, target("%value")?.containingFile?.virtualFile)
            assertEquals(first, target("helper")?.containingFile?.virtualFile)
            assertEquals(first, target("reservation")?.containingFile?.virtualFile)
            assertEquals(first, target("publicAfter")?.containingFile?.virtualFile)
        }
        val plain = withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) { second.delete(this) }
            fixture.addFileToProject("entry.txt", entry("second-values.s")).virtualFile
        }
        readAction { assertEquals("first-values.s", target("COUNT")?.containingFile?.name) }
        assertFalse(assemble(plain))
        readAction { assertEquals("first-values.s", target("COUNT")?.containingFile?.name) }
    }

    @Test
    fun `an included data continuation inherits the inferred and selected entry segment and list`() = timeoutRunBlocking<Unit> {
        val (entry, data) = withContext(Dispatchers.EDT) {
            val data = fixture.addFileToProject("values.s", "value\n").virtualFile
            val entry = fixture.addFileToProject("entry.s", """
                .eqv DATA_SEG .data
                .eqv WORDS .word
                DATA_SEG
                value: WORDS 42
                .include "values.s"
                .text
                nop
            """.trimIndent() + "\n").virtualFile
            fixture.configureFromExistingVirtualFile(data)
            entry to data
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        fun target() = fixture.file.findReferenceAt(1)?.resolve()
        readAction { assertEquals(entry, target()?.containingFile?.virtualFile) }
        project.service<MarsBuildService>().assemble(entry.toNioPath())
        readAction {
            assertEquals(data, fixture.file.virtualFile)
            assertEquals(entry, target()?.containingFile?.virtualFile)
            assertEquals("value:", target()?.text)
        }
    }

    @Test
    fun `choosing a containing entry changes navigation and completion in a shared include`() = timeoutRunBlocking<Unit> {
        val source = "j selectedName\n"
        val (first, second, shared) = withContext(Dispatchers.EDT) {
            val shared = fixture.addFileToProject("parts/shared.s", source).virtualFile
            fixture.addFileToProject("parts/wrapper.s", ".include \"shared.s\"\n")
            val first = fixture.addFileToProject("first_entry.asm", "selectedName: nop\nselectedAlpha: nop\n.include \"parts/wrapper.s\"\n").virtualFile
            val second = fixture.addFileToProject("second.asm", "selectedBeta: nop\nselectedName: nop\n.include \"parts/wrapper.s\"\n").virtualFile
            fixture.configureFromExistingVirtualFile(shared)
            fixture.editor.caretModel.moveToOffset(5)
            Triple(first, second, shared)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val group = containingEntriesGroup()
        val actions = readAction { group.getChildren(event(group, project, shared)) }
        assertEquals(listOf(first.presentableUrl, second.presentableUrl), actions.map { it.templatePresentation.text })
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            fun target() = GotoDeclarationAction.findTargetElement(project, fixture.editor, fixture.editor.caretModel.offset)
            suspend fun choose(action: AnAction, entry: VirtualFile) {
                withContext(Dispatchers.EDT) { action.actionPerformed(event(action, project, shared)) }
                val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
                assertEquals("Assemble ${entry.name}", start.buildDescriptor.title)
                assertInstanceOf(SuccessResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
            }
            fun assertNames(vararg names: String) {
                fixture.editor.caretModel.moveToOffset(5)
                fixture.completeBasic()
                assertEquals(names.toSet(), fixture.lookupElementStrings.orEmpty().filter { it.startsWith("sel") }.toSet())
                fixture.lookup.hideLookup(true)
            }

            readAction { assertNull(target()) }
            choose(actions[0], first)
            val unrelated = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("unrelated.s", "j sel\n") as MipsFile
            }
            readAction {
                assertEquals(first, target()?.containingFile?.virtualFile)
                val analysis = project.service<MarsAnalysisService>()
                val include = fixture.file as MipsFile
                assertTrue(analysis.labelTargets(include, include.findElementAt(0)!!, allowExternal = true).isEmpty())
                assertTrue(analysis.labelCompletionNames(unrelated, unrelated.findElementAt(2)!!, "sel").isEmpty())
            }
            withContext(Dispatchers.EDT) {
                assertNames("selectedName", "selectedAlpha")
                edit(shared, "j sel\n")
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                assertNames("selectedName", "selectedAlpha")
            }
            readAction { assertNull(target()) }
            withContext(Dispatchers.EDT) {
                edit(shared, source)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            choose(actions[1], second)
            readAction {
                val declaration = target()
                assertEquals(second, declaration?.containingFile?.virtualFile)
                assertEquals("selectedBeta: nop\n".length, declaration?.textOffset)
            }
            withContext(Dispatchers.EDT) {
                assertNames("selectedName", "selectedBeta")
                edit(second, "# changed entry\n" + Files.readString(second.toNioPath()))
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            readAction {
                val declaration = target()
                assertEquals(second, declaration?.containingFile?.virtualFile)
                assertEquals("# changed entry\nselectedBeta: nop\n".length, declaration?.textOffset)
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `containing entry menu follows only MIPS roots and handles cycles and indexing`() = timeoutRunBlocking<Unit> {
        val (payload, entry, cycle) = withContext(Dispatchers.EDT) {
            val payload = fixture.addFileToProject("payload.txt", "nop\n").virtualFile
            val entry = fixture.addFileToProject("entry.s", ".include \"payload.txt\"\n").virtualFile
            fixture.addFileToProject("foreign.xml", """<xi:include xmlns:xi="http://www.w3.org/2001/XInclude" href="payload.txt"/>""")
            val cycle = fixture.addFileToProject("cycle.s", ".include \"other.s\"\n").virtualFile
            fixture.addFileToProject("other.s", ".include \"cycle.s\"\n")
            Triple(payload, entry, cycle)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val group = containingEntriesGroup()
        val ignored = withContext(Dispatchers.EDT) {
            lateinit var file: VirtualFile
            WriteCommandAction.runWriteCommandAction(project) {
                file = payload.parent.createChildData(this, ".DS_Store")
            }
            file
        }
        readAction {
            assertEquals(ActionUpdateThread.BGT, group.actionUpdateThread)
            assertTrue(group.templatePresentation.isPopupGroup)
            assertTrue(group.templatePresentation.isHideGroupIfEmpty)
            assertEquals("Assemble Containing MIPS Entry", group.templatePresentation.text)
            assertTrue(group.getChildren(null).isEmpty())
            assertTrue(group.getChildren(event(group, null, payload)).isEmpty())
            assertTrue(group.getChildren(event(group, project, null)).isEmpty())
            assertTrue(group.getChildren(event(group, project, payload.parent)).isEmpty())
            assertTrue(group.getChildren(event(group, project, ignored)).isEmpty())
            assertTrue(group.getChildren(event(group, project, cycle)).isEmpty())
            assertTrue(group.getChildren(event(group, project, entry)).isEmpty())
            assertEquals(setOf("entry.s", "foreign.xml"), FileIncludeManager.getManager(project).getIncludingFiles(payload, true).map { it.name }.toSet())
            val child = group.getChildren(event(group, project, payload)).single()
            assertEquals(entry.presentableUrl, child.templatePresentation.text)
        }
        withContext(Dispatchers.EDT) {
            DumbModeTestUtils.runInDumbModeSynchronously(project) {
                assertTrue(group.getChildren(event(group, project, payload)).isEmpty())
            }
        }
    }

    private fun containingEntriesGroup() = assertInstanceOf(
        MarsContainingEntriesGroup::class.java, ActionManager.getInstance().getAction("Mars.AssembleContainingEntry"),
    )

    @Test
    fun `completion keeps generated names while typing a direct instruction operand`() = timeoutRunBlocking<Unit> {
        val source = """
            .include "macro.s"
            emit (generatedOne)
            emit (generated_M0)
            reserve (generatedStorage)
            .extern ) 4
            .extern ${'$'}t0 4
            .globl generatedOne
            nop: nop
            j generatedOne
        """.trimIndent()
        val body = """
            .macro emit (%entry)
              generatedPrivate: nop
              %entry: nop
            .end_macro
            .macro reserve (%name)
              .extern %name 4
            .end_macro
        """.trimIndent()
        val (main, include) = withContext(Dispatchers.EDT) {
            val definitions = fixture.addFileToProject("macro.s", body).virtualFile
            val entry = fixture.addFileToProject("entry.asm", source).virtualFile
            fixture.configureFromExistingVirtualFile(entry)
            entry to definitions
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        project.service<MarsBuildService>().assemble(main.toNioPath())
        withContext(Dispatchers.EDT) {
            val names = setOf("generatedOne", "generated_M0", "generatedStorage")
            fun candidates(caret: Int): Set<String> {
                fixture.editor.caretModel.moveToOffset(caret)
                fixture.completeBasic()
                return fixture.lookupElementStrings.orEmpty().filter { it.startsWith("gen") }.toSet()
            }
            fun change(file: VirtualFile, text: String) {
                edit(file, text)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }

            val originalOperand = source.lastIndexOf("generatedOne")
            assertEquals(names, candidates(originalOperand + 3))
            fixture.lookup.hideLookup(true)
            val partial = source.substring(0, originalOperand) + "genOld"
            change(main, partial)
            assertEquals(names, candidates(originalOperand + 3))
            fixture.lookup.setCurrentItem(fixture.lookup.items.single { it.lookupString == "generatedOne" })
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals(source, fixture.editor.document.text)

            change(main, "$source\nj unmatched")
            assertTrue(candidates(fixture.editor.document.textLength).isEmpty())
            val changedInvocation = source.replace("emit (generatedOne)", "emit (anotherName)")
            change(main, "$changedInvocation\nj gen")
            assertTrue(candidates(fixture.editor.document.textLength).isEmpty())

            change(main, "$source\nj gen")
            assertEquals(names, candidates(fixture.editor.document.textLength))
            fixture.lookup.hideLookup(true)
            change(include, "# an include changed\n$body")
            assertTrue(candidates(fixture.editor.document.textLength).isEmpty())
        }
    }

    @Test
    fun `successful assembly navigates generated labels until a captured include changes`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val source = """
                .include "definitions.s"
                emit (generated)
                emit (privateLabel)
                reserve (storage)
                j generated
                j privateLabel
                la ${'$'}t0, storage
                .globl generated
            """.trimIndent()
            val body = ".macro emit (%entry)\n  %entry: nop\n.end_macro\n" +
                ".macro reserve (%name)\n  .extern %name 4\n.end_macro\n"
            val offset = source.indexOf("j generated") + 3
            val (main, include) = withContext(Dispatchers.EDT) {
                val entry = fixture.addFileToProject("main.asm", source).virtualFile
                val definitions = fixture.addFileToProject("definitions.s", "# unchanged on disk\n").virtualFile
                edit(definitions, body)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                fixture.configureFromExistingVirtualFile(entry)
                fixture.editor.caretModel.moveToOffset(offset)
                entry to definitions
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            suspend fun assemble() {
                invokeAction(main)
                assertInstanceOf(StartBuildEvent::class.java, events.receive())
                assertInstanceOf(SuccessResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
            }
            fun target(at: Int = offset): PsiElement? = fixture.file.findReferenceAt(at)?.resolve()
            readAction { assertNull(target()) }
            assemble()
            readAction {
                val declaration = GotoDeclarationAction.findTargetElement(project, fixture.editor, offset)!!
                assertEquals(include, declaration.containingFile.virtualFile)
                assertEquals("%entry", declaration.text)
                assertEquals(body.lastIndexOf("%entry"), declaration.textOffset)
                assertEquals(declaration, target(source.lastIndexOf("generated")))
                assertEquals(declaration, target(source.lastIndexOf("privateLabel")))
                val storage = source.lastIndexOf("storage")
                assertEquals(".extern", target(storage)?.text)
                val file = fixture.file as MipsFile
                val analysis = project.service<MarsAnalysisService>()
                assertTrue(analysis.labelTargets(file, file.findElementAt(storage)!!, allowExternal = false).isEmpty())
                assertTrue(analysis.labelTargets(file, file.findElementAt(source.indexOf("emit"))!!, allowExternal = true).isEmpty())
            }
            val moved = "# moved in the editor\n$body"
            withContext(Dispatchers.EDT) {
                edit(include, moved)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            readAction { assertNull(target()) }
            assemble()
            readAction { assertEquals(moved.lastIndexOf("%entry"), target()?.textOffset) }
            val parent = withContext(Dispatchers.EDT) {
                include.parent.also { WriteCommandAction.runWriteCommandAction(project) { include.delete(this) } }
            }
            readAction { assertNull(target()) }
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) { parent.createChildDirectory(this, "definitions.s") }
            }
            readAction { assertNull(target()) }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `assembled targets observe committed PSI file type and the latest build request`() = timeoutRunBlocking<Unit> {
        val (entry, other, include) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("entry.asm", ".include \"macro.s\"\nemit (generated)\nj generated\n")
            val unrelated = fixture.addFileToProject("other.asm", "j generated\n")
            val definitions = fixture.addFileToProject("macro.s", ".macro emit (%entry)\n%entry: nop\n.end_macro\n")
            Triple(main, unrelated, definitions)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val analysis = project.service<MarsAnalysisService>()
        val path = entry.virtualFile.toNioPath()
        val includePath = include.virtualFile.toNioPath()
        val capture = captureAssemblySources(listOf(path), null)
        val component = MarsSessionService.createMarsComponent()
        val program = component.context().use { capture.assembleIn(component).program() }
        val older = analysis.beginSymbolBuild(path)
        val current = analysis.beginSymbolBuild(path)
        analysis.publishBuiltSymbols(older, capture, program.symbolMap())
        fun target(file: PsiElement) = file.containingFile.findReferenceAt(file.text.lastIndexOf("generated"))?.resolve()
        readAction { assertNull(target(entry)) }
        analysis.publishBuiltSymbols(current, capture, program.symbolMap())
        readAction {
            assertEquals("%entry", target(entry)?.text)
            assertNull(target(other))
        }
        val missingRevision = capture.copy(revisions = capture.revisions - setOf(includePath))
        analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), missingRevision, program.symbolMap())
        readAction { assertNull(target(entry)) }

        val plain = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("macro.txt", include.text).virtualFile
        }
        val plainPath = plain.toNioPath()
        withContext(Dispatchers.EDT) {
            val file = entry.virtualFile
            edit(file, entry.text.replace("macro.s", "macro.txt"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val plainCapture = captureAssemblySources(listOf(path), null)
        val plainComponent = MarsSessionService.createMarsComponent()
        val plainProgram = plainComponent.context().use { plainCapture.assembleIn(plainComponent).program() }
        analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), plainCapture, plainProgram.symbolMap())
        assertEquals(plainPath, plainProgram.symbolMap().locals()[path]?.get("generated")?.origin()?.location()?.file())
        readAction { assertNull(target(entry)) }

        val changedText = "# still uncommitted\n" + capture.snapshot.texts().getValue(includePath)
        val changed = AssemblySourceSnapshot(listOf(path), capture.snapshot.texts() + (includePath to changedText))
        val changedComponent = MarsSessionService.createMarsComponent()
        val changedResult = changedComponent.context().use {
            changedComponent.assembler().assembleSnapshot(changed, null, true, false)
        }
        assertTrue(changedResult.succeeded())
        withContext(Dispatchers.EDT) {
            edit(entry.virtualFile, capture.snapshot.texts().getValue(path))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val documents = FileDocumentManager.getInstance()
            val mainDocument = documents.getDocument(entry.virtualFile)!!
            val includeDocument = documents.getDocument(include.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(project) {
                includeDocument.setText(changedText)
                val revisions = mapOf(path to mainDocument.modificationStamp, includePath to includeDocument.modificationStamp)
                val updated = capture.copy(snapshot = changed, revisions = revisions)
                analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), updated, changedResult.program().symbolMap())
                assertFalse(PsiDocumentManager.getInstance(project).isCommitted(includeDocument))
                assertNull(target(entry))
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        readAction { assertEquals("%entry", target(entry)?.text) }
    }

    @Test
    fun `marked Unicode files assemble with the same image and source map from disk and IntelliJ documents`() =
        timeoutRunBlocking<Unit> {
            val entryText = "\r\n.include \"pièce.s\"\r\n.text\r\nentrée: addiu \$t0, \$zero, LIMIT"
            val includeText = ".eqv LIMIT 7\r.data\rtexte: .asciiz \"é\uFEFF\""
            val (entry, include) = withContext(Dispatchers.EDT) {
                val source = fixture.tempDirFixture.createFile("entrée.asm")
                val part = fixture.tempDirFixture.createFile("pièce.s")
                val hex = HexFormat.of()
                WriteCommandAction.runWriteCommandAction(project) {
                    source.setBinaryContent(hex.parseHex("efbbbf") + entryText.toByteArray(Charsets.UTF_8))
                    part.setBinaryContent(hex.parseHex("fffe") + includeText.toByteArray(Charsets.UTF_16LE))
                }
                fixture.configureFromExistingVirtualFile(source)
                source.toNioPath() to part.toNioPath()
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            readAction {
                val file = fixture.file as MipsFile
                assertEquals(entryText.replace("\r\n", "\n"), file.text)
                assertTrue(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isEmpty())
                assertEquals(include, file.findReferenceAt(file.text.indexOf("pièce.s"))
                    ?.resolve()?.containingFile?.virtualFile?.toNioPath())
            }

            val capture = captureAssemblySources(listOf(entry), null)
            assertEquals(
                mapOf(entry to entryText.replace("\r\n", "\n"), include to includeText.replace('\r', '\n')),
                capture.snapshot.texts(),
            )
            val component = MarsSessionService.createMarsComponent()
            MarsSessionService.createMarsComponent().context().use { disk ->
                val expected = disk.assemble(listOf(entry))
                component.context().use { ide ->
                    val result = capture.assembleIn(component)
                    assertTrue(result.succeeded(), result.errors().toString())
                    val expectedWords = expected.machineList.map { it.binaryStatement }
                    assertEquals(listOf(0x24080007), expectedWords)
                    assertEquals(expectedWords, ide.program.machineList.map { it.binaryStatement })
                    assertEquals(expected.sourceMap(), result.program().sourceMap())
                    assertEquals(expected.symbolMap(), result.program().symbolMap())
                    val base = ide.memory.dataBaseAddress
                    assertEquals(listOf(0xe9, 0xff, 0), (0..2).map { ide.memory.getByte(base + it) })
                }
            }
        }

    @Test
    fun `build and include navigation follow a symlink before resolving a parent segment`() = timeoutRunBlocking<Unit> {
        val source = """
            .include "shortcut/../value.s"
            .include "value.s"
            .text
            addiu ${'$'}t0, ${'$'}zero, REMOTE
            addiu ${'$'}t1, ${'$'}zero, LOCAL
            addiu ${'$'}t2, ${'$'}zero, NESTED
        """.trimIndent()
        val (entry, remote, local) = withContext(Dispatchers.EDT) {
            val temp = fixture.tempDirFixture
            val main = fixture.addFileToProject("local/main.asm", source).virtualFile
            val localValue = fixture.addFileToProject("local/value.s", ".eqv LOCAL 2\n").virtualFile
            val remoteValue = fixture.addFileToProject("actual/value.s", ".eqv REMOTE 7\n.include \"next.s\"\n").virtualFile
            fixture.addFileToProject("actual/next.s", ".eqv NESTED 8\n")
            val target = temp.findOrCreateDir("actual/dir").toNioPath()
            val link = Files.createSymbolicLink(main.toNioPath().resolveSibling("shortcut"), target)
            checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(link))
            fixture.configureFromExistingVirtualFile(main)
            Triple(main.toNioPath(), remoteValue, localValue)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val file = fixture.file
            val first = source.indexOf("value.s")
            val second = source.lastIndexOf("value.s")
            assertEquals(remote, file.findReferenceAt(first)?.resolve()?.containingFile?.virtualFile)
            assertEquals(local, file.findReferenceAt(second)?.resolve()?.containingFile?.virtualFile)
            assertEquals(
                setOf(remote, local),
                FileIncludeManager.getManager(project).getIncludedFiles(file.virtualFile, true).toSet(),
            )
        }
        val expected = listOf(0x24080007, 0x24090002, 0x240a0008)
        MarsSessionService.createMarsComponent().context().use { disk ->
            assertEquals(expected, disk.assemble(listOf(entry)).machineList.map { it.binaryStatement })
        }
        val capture = captureAssemblySources(listOf(entry), null)
        assertEquals(
            setOf(entry, remote.toNioPath(), local.toNioPath(), remote.toNioPath().resolveSibling("next.s")),
            capture.revisions.keys,
        )
        val component = MarsSessionService.createMarsComponent()
        component.context().use { session ->
            val build = project.service<MarsBuildService>()
            assertTrue(build.assemble(entry, component, activateBuildView = false))
            assertEquals(expected, session.program.machineList.map { it.binaryStatement })

            withContext(Dispatchers.EDT) { edit(remote, ".eqv REMOTE 11\n.include \"next.s\"\n") }
            assertTrue(build.assemble(entry, component, activateBuildView = false))
            assertEquals(listOf(0x2408000b, 0x24090002, 0x240a0008), session.program.machineList.map { it.binaryStatement })
            assertTrue(Files.readString(remote.toNioPath()).contains(".eqv REMOTE 7"))

            val broken = withContext(Dispatchers.EDT) {
                fixture.tempDirFixture.createFile("local/broken.asm", ".include \"value.s\"\n.include \"missing/../value.s\"\n")
            }.toNioPath()
            val failure = captureAssemblySources(listOf(broken), null).assembleIn(component)
            assertFalse(failure.succeeded())
            val error = failure.errors().single()
            assertEquals(broken, error.file())
            assertEquals(2, error.line())
            assertTrue(error.message().contains("Error reading include file"))
        }
    }

    @Test
    fun `build links a recursive include to the directive that closes the cycle`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val path = "\".././main.asm\""
            val child = "# child\n  .include $path\n"
            val (entry, include) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"parts/child.s\"\nnop\n").virtualFile
                val part = fixture.addFileToProject("parts/child.s", "nop\n").virtualFile
                edit(part, child)
                main to part
            }

            invokeAction(entry)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertTrue(error.message.contains("Recursive include of file"))
            assertEquals(include.toNioPath(), error.filePosition.path)
            assertEquals(1, error.filePosition.startLine)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(include, link.file)
                assertEquals(child.indexOf(path), link.offset)
            }
            assertInstanceOf(FailureResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)

            withContext(Dispatchers.EDT) { edit(include, "nop\n") }
            invokeAction(entry)
            assertInstanceOf(StartBuildEvent::class.java, events.receive())
            assertInstanceOf(SuccessResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `build reports each malformed token once at its physical source`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val mainSource = ".include \"child.s\"\n\tfake/.include \"missing.s\"\nnop\n"
            val childSource = "\tbad%name: nop\n"
            val (main, child) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", mainSource).virtualFile
                val child = fixture.addFileToProject("child.s", "nop\n").virtualFile
                edit(child, childSource)
                main to child
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            for ((file, source, token) in listOf(
                Triple(child, childSource, "bad%name"),
                Triple(main, mainSource, "fake/.include"),
            )) {
                val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
                assertEquals(start.id, error.parentId)
                assertTrue(error.message.endsWith("Invalid language element: $token"), error.message)
                assertEquals(file.toNioPath(), error.filePosition.path)
                val offset = source.indexOf(token)
                assertEquals(source.take(offset).count { it == '\n' }, error.filePosition.startLine)
                assertEquals(1, error.filePosition.startColumn)
                readAction {
                    val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                    assertEquals(file, link.file)
                    assertEquals(offset, link.offset)
                }
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `build reports an unfinished string on an unsaved included data continuation`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val line = "  \"unfinished"
            val source = ".data\n.asciiz \"head\"\n$line\n"
            val (main, include) = withContext(Dispatchers.EDT) {
                val entry = fixture.addFileToProject("main.asm", ".include \"strings.s\"\n.text\nnop\n").virtualFile
                val part = fixture.addFileToProject("strings.s", ".data\n.asciiz \"saved\"\n").virtualFile
                edit(part, source)
                entry to part
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertEquals("Unterminated string literal", error.message)
            assertEquals(include.toNioPath(), error.filePosition.path)
            assertEquals(2, error.filePosition.startLine)
            assertEquals(2, error.filePosition.startColumn)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(include, link.file)
                assertEquals(source.indexOf(line) + 2, link.offset)
            }
            assertInstanceOf(FailureResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
            assertEquals(".data\n.asciiz \"saved\"\n", Files.readString(include.toNioPath()))

            withContext(Dispatchers.EDT) { edit(include, source.replace(line, "$line\"")) }
            invokeAction(main)
            assertInstanceOf(StartBuildEvent::class.java, events.receive())
            assertInstanceOf(SuccessResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `registered action assembles unsaved sources and reports clickable diagnostics`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val source = "\tétiquette: j missing\n"
            val (main, include) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"child.s\"\n").virtualFile
                val child = fixture.addFileToProject("child.s", "nop\n").virtualFile
                edit(child, source)
                main to child
            }
            val session = project.getService(MarsSessionService::class.java).context
            val existingProgram = session.assemble(listOf(include.toNioPath()))

            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            assertEquals("Assemble main.asm", start.buildDescriptor.title)
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertEquals(include.toNioPath(), error.filePosition.path)
            assertEquals(0, error.filePosition.startLine)
            assertEquals(source.indexOf("missing"), error.filePosition.startColumn)
            assertTrue(error.message.contains("missing"))
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(include, link.file)
                assertEquals(source.indexOf("missing"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
            assertSame(existingProgram, session.program)
            assertEquals("nop\n", Files.readString(include.toNioPath()))

            withContext(Dispatchers.EDT) { edit(include, "nop\n") }
            invokeAction(main)
            val next = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val done = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(next.id, done.id)
            assertInstanceOf(SuccessResult::class.java, done.result)
            assertSame(existingProgram, session.program)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `build reports a lexical error once at its written column after equivalence expansion`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val use = "\t.byte FIRST, SECOND, 'ab'"
            val source = ".eqv FIRST 100\n.eqv SECOND 2000\n.data\n$use\n"
            val (main, child) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"child.s\"\n.text\nnop\n").virtualFile
                val child = fixture.addFileToProject("child.s", ".data\n.byte 0\n").virtualFile
                edit(child, source)
                main to child
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertTrue(error.message.endsWith("Invalid language element: 'ab'"), error.message)
            assertEquals(child.toNioPath(), error.filePosition.path)
            assertEquals(3, error.filePosition.startLine)
            assertEquals(use.indexOf("'ab'"), error.filePosition.startColumn)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(child, link.file)
                assertEquals(source.indexOf("'ab'"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `build links invalid operands after equivalence expansion to the written operand`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val use = "\tentry: lwc1 DEST, ADDRESS \$s29)"
            val source = ".eqv DEST \$f0\n.eqv ADDRESS OFFSET\n.eqv OFFSET -4(\n$use\n"
            val (main, child) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"child.s\"\n").virtualFile
                val child = fixture.addFileToProject("child.s", "nop\n").virtualFile
                edit(child, source)
                main to child
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertTrue(error.message.contains("operand is of incorrect type"), error.message)
            assertEquals(child.toNioPath(), error.filePosition.path)
            assertEquals(3, error.filePosition.startLine)
            assertEquals(use.indexOf("\$s29"), error.filePosition.startColumn)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(child, link.file)
                assertEquals(source.indexOf("\$s29"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `circular equivalences keep valid editor syntax and link a build error to the original use`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val use = "\tOPERATION \$t0, FIRST"
            val source = ".eqv OPERATION li\n.eqv FIRST SECOND\n.eqv SECOND FIRST + 1\n$use\n"
            val (main, include) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"child.s\"\nnop\n").virtualFile
                val include = fixture.addFileToProject("child.s", "nop\n").virtualFile
                edit(include, source)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                main to include
            }
            readAction {
                val psi = PsiManager.getInstance(project).findFile(include)!!
                assertNull(PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java))
                assertEquals(source, psi.text)
            }

            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val diagnostic = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, diagnostic.parentId)
            assertEquals(include.toNioPath(), diagnostic.filePosition.path)
            assertEquals(3, diagnostic.filePosition.startLine)
            assertEquals(use.indexOf("FIRST"), diagnostic.filePosition.startColumn)
            assertTrue(diagnostic.message.contains("FIRST -> SECOND -> FIRST"))
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, diagnostic.getNavigatable(project))
                assertEquals(include, link.file)
                assertEquals(source.lastIndexOf("FIRST"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `action only accepts valid local MIPS sources`() = timeoutRunBlocking<Unit> {
        val (text, removed) = withContext(Dispatchers.EDT) {
            val text = fixture.addFileToProject("sample.txt", "nop\n").virtualFile
            val removed = fixture.addFileToProject("removed.s", "nop\n").virtualFile
            WriteCommandAction.runWriteCommandAction(project) { removed.delete(this) }
            text to removed
        }
        val action = assertInstanceOf(
            MarsAssembleAction::class.java, ActionManager.getInstance().getAction("Mars.AssembleCurrentFile"),
        )
        readAction {
            assertEquals("BGT", action.actionUpdateThread.name)
            val noProject = event(action, null, null)
            action.update(noProject)
            assertFalse(noProject.presentation.isEnabled)
            for (file in listOf(null, text, text.parent, LightVirtualFile("light.s", MipsFileType, "nop"))) {
                val invocation = event(action, project, file)
                action.update(invocation)
                assertFalse(invocation.presentation.isEnabled)
                assertFalse(invocation.presentation.isVisible)
            }
            // A retained action context can supply a file which was deleted after selection.
            val retainedSelection = DataContext { name ->
                when (name) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.VIRTUAL_FILE.name -> removed
                    else -> null
                }
            }
            val invalid = AnActionEvent.createEvent(action, retainedSelection, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
            action.update(invalid)
            assertFalse(invalid.presentation.isEnabled)
        }
        withContext(Dispatchers.EDT) {
            action.actionPerformed(event(action, null, null))
            action.actionPerformed(event(action, project, null))
        }
    }

    @Test
    fun `cancellation emits a single skipped finish after start`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val entry = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("cancel.asm", "nop\n").virtualFile.toNioPath()
            }
            val job = async(start = CoroutineStart.LAZY) {
                project.getService(MarsBuildService::class.java).assemble(entry)
            }
            val events = recordBuildEvents(disposable) { if (it is StartBuildEvent) job.cancel() }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(SkippedResult::class.java, finish.result)
            assertTrue(events.tryReceive().isFailure)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `cancelled build coroutine stops repeated data writes inside the assembler`() = timeoutRunBlocking<Unit> {
        val entry = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("repeat.asm", ".data\n.word 7:200\n.text\nnop\n").virtualFile.toNioPath()
        }
        val capture = captureAssemblySources(listOf(entry), null)
        val component = MarsSessionService.createMarsComponent()
        component.context().use { session ->
            val build = async(start = CoroutineStart.LAZY) { capture.assembleIn(component) }
            val firstWord = session.memory.dataBaseAddress
            session.memory.addObserver({ build.cancel() }, firstWord)
            build.start()
            build.join()
            assertTrue(build.isCancelled)
            assertEquals(7, session.memory.getWordNoNotify(firstWord))
            assertEquals(0, session.memory.getWordNoNotify(firstWord + 4))
            assertNull(session.program)
        }
    }

    @Test
    fun `unavailable or stale diagnostic positions remain messages without misleading links`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("positions.asm", "nop\n").virtualFile
        }
        readAction {
            val path = file.toNioPath()
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            val stamps = mapOf(path to document.modificationStamp)
            val id = Any()
            val warning = AssemblyError(path, 1, 1, "Warning", true)
            val fresh = diagnosticEvent(project, id, warning, stamps)
            assertInstanceOf(FileMessageEvent::class.java, fresh)
            assertEquals("WARNING", fresh.kind.name)
            for ((diagnostic, revisions) in listOf(
                warning to emptyMap(),
                warning to mapOf(path to (document.modificationStamp + 1)),
                AssemblyError(path, 5, 1, "Past the last line", false) to stamps,
                AssemblyError(path, 0, 0, "Unknown line", false) to stamps,
                AssemblyError(path.resolveSibling("absent.s"), 1, 1, "Missing", false) to stamps,
                AssemblyError(null, 0, 0, "Unknown", false) to stamps,
            )) {
                val event = diagnosticEvent(project, id, diagnostic, revisions)
                assertFalse(event is FileMessageEvent)
                assertNull(event.getNavigatable(project))
                assertEquals(diagnostic.message(), event.message)
            }
        }
    }

    private suspend fun invokeAction(file: VirtualFile) {
        val action = assertInstanceOf(
            MarsAssembleAction::class.java, ActionManager.getInstance().getAction("Mars.AssembleCurrentFile"),
        )
        val invocation = event(action, project, file)
        readAction {
            action.update(invocation)
            assertTrue(invocation.presentation.isEnabledAndVisible)
        }
        withContext(Dispatchers.EDT) { action.actionPerformed(invocation) }
    }

    private fun event(action: AnAction, project: Project?, file: VirtualFile?): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file).build()
        return AnActionEvent.createEvent(action, context, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
    }

    private fun edit(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
    }

    private fun recordBuildEvents(disposable: Disposable, onEvent: (BuildEvent) -> Unit = {}): Channel<BuildEvent> {
        val events = Channel<BuildEvent>(Channel.UNLIMITED)
        project.replaceService(BuildViewManager::class.java, object : BuildViewManager(project) {
            override fun onEvent(buildId: Any, event: BuildEvent) {
                onEvent(event)
                check(events.trySend(event).isSuccess)
            }
        }, disposable)
        return events
    }
}
