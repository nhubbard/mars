package edu.missouristate.mars.plugin.language

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.mipsSourceTimeline
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.language.inspection.MipsMacroParameterInspection
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsMacroInspectionPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)

    @Test
    fun `uninvoked macros diagnose duplicate formals and unknown parameters at their source tokens`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val inspection = MipsMacroParameterInspection()
            assertTrue(inspection.runForWholeFile(), "Header edits must refresh diagnostics throughout the macro")
            fixture.enableInspections(inspection)
            fixture.configureByText("plain.asm", "nop")
            assertTrue(warnings().isEmpty())
            val source = $$"""
                .text
                .macro example (%used, %used, $ok, $ok)
                    addiu $t0, $zero, %used
                    addiu $t1, $zero, $ok
                    addiu $t2, $zero, %missing
                    addiu $t3, $zero, $missing
                %destination: nop
                    .asciiz "%quoted $quoted"
                    # %comment
                .end_macro
                addiu $t4, $zero, %outside
            """.trimIndent()
            fixture.configureByText("parameters.asm", source)
            assertEquals(
                listOf(
                    Warning(source.indexOf("%used, %used") + "%used, ".length, "%used", duplicate("%used")),
                    Warning(source.indexOf($$"$ok, $ok") + $$"$ok, ".length, $$"$ok", duplicate($$"$ok")),
                    Warning(source.indexOf("%missing"), "%missing", unknown("example", "%missing")),
                    Warning(source.indexOf($$"$missing"), $$"$missing", unknown("example", $$"$missing")),
                    Warning(source.indexOf("%destination"), "%destination", unknown("example", "%destination")),
                ),
                warnings(),
            )
            WriteCommandAction.runWriteCommandAction(fixture.project) {
                val headerEnd = source.indexOf(')')
                fixture.editor.document.insertString(headerEnd, $$", %missing, $missing, %destination")
            }
            assertEquals(listOf(duplicate("%used"), duplicate($$"$ok")), warnings().map(Warning::message))
        }
    }

    @Test
    fun `alias generated parameters point to the original alias and ignore invalid expansions`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            fixture.enableInspections(MipsMacroParameterInspection::class.java)
            val source = $$"""
                .eqv BEGIN .macro
                .eqv END .end_macro
                .eqv FORM %item, %item, %item
                .eqv UNKNOWN %absent, %absent
                .eqv BOUND %item
                .eqv FIRST SECOND
                .eqv SECOND FIRST
                BEGIN aliased (FORM)
                    addiu $t0, $zero, BOUND
                    .word UNKNOWN
                    .word %skipped, FIRST
                    ,,,
                    .macro invalid (%nested)
                    .end_macro %ignored
                END
                .macro direct (FORM)
                    addiu $t0, $zero, BOUND
                .end_macro
                .macro valid (%ok)
                    addiu $t0, $zero, %ok
                .end_macro
                nop
            """.trimIndent()
            fixture.configureByText("mapped.s", source)
            assertEquals(
                listOf(
                    Warning(source.indexOf("(FORM)") + 1, "FORM", duplicate("%item")),
                    Warning(source.indexOf(".word UNKNOWN") + ".word ".length, "UNKNOWN", unknown("aliased", "%absent")),
                    Warning(source.lastIndexOf("(FORM)") + 1, "FORM", duplicate("%item")),
                ),
                warnings(),
            )
        }
    }

    @Test
    fun `the include prepass discards written macro parameter candidates on the replaced line`() = timeoutRunBlocking {
        val source = $$".macro emit (%value)\n%discarded: .include \"body.s\"\nli $t0, %missing\n.end_macro\n"
        val project = projectFixture.get()
        withContext(Dispatchers.EDT) {
            fixture.enableInspections(MipsMacroParameterInspection::class.java)
            fixture.addFileToProject("body.s", "nop\n")
            fixture.addFileToProject("entry.asm", source).also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            assertEquals(listOf(Warning(source.indexOf("%missing"), "%missing", unknown("emit", "%missing"))), warnings())
        }
    }

    @Test
    fun `a malformed expanded closer reports its written token and leaves the macro open`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val source = $$"""
                .eqv CLOSE .EnD_MaCrO
                .eqv BAD .end_macro extra
                .eqv LABEL outside:
                .macro emit (%value)
                CLOSE 7
                BAD
                body: addiu $t0, $zero, %value
                CLOSE # actual closer
                emit(42)
                LABEL .end_macro extra
            """.trimIndent()
            val entry = directory.resolve("bad-closers.asm").also { it.writeText(source.replace("emit(42)\n", "")) }
            val context = projectFixture.get().service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
            assertEquals(
                List(3) { "invalid text after .END_MACRO" },
                failure.errors()!!.errorMessages.map { it.message },
            )
            withContext(Dispatchers.EDT) { fixture.configureByText("bad-closers.asm", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
            withContext(Dispatchers.EDT) {
                assertEquals(
                    listOf("CLOSE" to trailingCloseMessage, "BAD" to trailingCloseMessage, ".end_macro" to trailingCloseMessage),
                    errors(),
                )
                readAction {
                    val file = fixture.file
                    assertEquals("%value", file.findReferenceAt(source.lastIndexOf("%value") + 1)?.resolve()?.text)
                    assertEquals("emit", (file.findReferenceAt(source.indexOf("emit(42)"))?.resolve() as? MipsDeclaration)?.name)
                }
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    fixture.editor.document.setText(source.replace("CLOSE 7\nBAD\n", "").substringBefore("\nLABEL "))
                }
                assertTrue(errors().isEmpty())
            }
        }

    @Test
    fun `a shared malformed closer waits for the selected entry`() = timeoutRunBlocking {
        val current = projectFixture.get()
        val entry = withContext(Dispatchers.EDT) {
            val first = fixture.addFileToProject("first.asm", ".include \"shared-close.s\"\n")
            fixture.addFileToProject("second.asm", ".include \"shared-close.s\"\n")
            val shared = fixture.addFileToProject("shared-close.s", ".eqv STOP .end_macro\n.macro emit\nSTOP extra\n.end_macro\n")
            fixture.configureFromExistingVirtualFile(shared.virtualFile)
            first.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            assertTrue(errors().isEmpty())
            current.service<MarsAssemblySettings>().changeSources(listOf(entry.path))
            assertEquals(listOf("STOP" to trailingCloseMessage), errors())
        }
    }

    @Test
    fun `a malformed alias closer waits for an unresolved earlier include`() = timeoutRunBlocking {
        val source = ".include \"pending.s\"\n.eqv STOP .end_macro\n.macro emit\nSTOP extra\n.end_macro\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("entry.asm", source).also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertTrue(errors().none { it.second == trailingCloseMessage })
            fixture.addFileToProject("pending.s", "nop\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) { assertEquals(listOf("STOP" to trailingCloseMessage), errors()) }
    }

    @Test
    fun `an included text file with another file type preserves later macro diagnostics`() = timeoutRunBlocking {
        val source = ".include \"foreign.xml\"\n.eqv STOP .end_macro\n.macro emit\nSTOP extra\n.end_macro\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("foreign.xml", "# MARS text in a file with an XML extension\n")
            fixture.addFileToProject("entry.asm", source).also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) { assertEquals(listOf("STOP" to trailingCloseMessage), errors()) }
    }

    @Test
    fun `unmatched closers and the unclosed opener point to their written tokens`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val source = $$"""
                .eqv OPEN .macro
                .eqv STOP .end_macro
                .eqv LABEL alias_label
                .end_macro
                STOP
                outside: .end_macro # the assembler strips this label
                LABEL: .end_macro
                OPEN emit (%value)
                inside: .end_macro # macro bodies retain their labels
                li $t0, %value
            """.trimIndent()
            val entry = directory.resolve("boundaries.asm").also { it.writeText(source) }
            val context = projectFixture.get().service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
            assertEquals(
                List(4) { ".END_MACRO without .MACRO" } + "Macro started but not ended (no .end_macro directive)",
                failure.errors()!!.errorMessages.map { it.message },
            )
            withContext(Dispatchers.EDT) { fixture.configureByText("boundaries.asm", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
            withContext(Dispatchers.EDT) {
                assertEquals(
                    listOf(".end_macro", "STOP", ".end_macro", ".end_macro").map { it to unmatchedEndMessage } +
                        ("OPEN" to missingEndMessage),
                    errors(),
                )
                assertEquals("%value", fixture.file.findReferenceAt(source.lastIndexOf("%value") + 1)?.resolve()?.text)
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    val repairedBody = source.substringAfter("LABEL: .end_macro\n").lineSequence()
                        .filterNot { it.startsWith("inside:") }.joinToString("\n")
                    fixture.editor.document.setText(source.substringBefore("\n.end_macro") + "\n" + repairedBody + "\nSTOP\nemit(1)\n")
                }
                assertEquals(emptyList<Pair<String, String>>(), errors())
            }
        }

    @Test
    fun `commenting out an unmatched macro closer preserves a separately written label`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val project = projectFixture.get()
            val repaired = withContext(Dispatchers.EDT) {
                val direct = "  .end_<caret>macro # keep\ntarget: nop\nj target\n"
                val aliased = ".eqv CLOSE .end_macro\ntarget: CL<caret>OSE # keep\nnop\nj target\n"
                listOf(direct to ".end_macro", aliased to "CLOSE").map { (source, closer) ->
                    fixture.configureByText("unmatched-end.mars", source)
                    assertEquals(listOf(unmatchedEndMessage), errors().map { it.second })
                    val action = fixture.availableIntentions.single { it.text == commentUnmatchedEnd }
                    val fixed = source.replace("<caret>", "").replace("$closer # keep", "# $closer # keep")
                    fixture.launchAction(action)
                    assertEquals(fixed, fixture.editor.document.text)
                    assertTrue(errors().isEmpty())
                    val command = checkNotNull(action.asModCommandAction())
                    val context = ActionContext.from(fixture.editor, fixture.file)
                    assertEquals(null, command.getPresentation(context))
                    assertTrue(command.perform(context).isEmpty)
                    fixed
                }
            }
            val context = project.service<MarsSessionService>().context
            for ((index, source) in repaired.withIndex()) {
                val entry = directory.resolve("unmatched-$index.mars").also { it.writeText(source) }
                assertEquals(listOf(0, 0x08100000), context.assemble(listOf(entry)).machineList!!.map { it.binaryStatement })
            }
        }

    @Test
    fun `an unmatched closer fix cannot discard a label supplied by the same alias`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            for (source in listOf(
                ".eqv CLOSE target: .end_macro\nCL<caret>OSE\n",
                ".eqv CLOSE : .end_macro\ntarget CL<caret>OSE\n",
            )) {
                fixture.configureByText("compound-closer.mars", source)
                assertEquals(listOf("CLOSE" to unmatchedEndMessage), errors())
                assertTrue(fixture.availableIntentions.none { it.text == commentUnmatchedEnd })
            }
        }
    }

    @Test
    fun `an unmatched closer fix checks other entries sharing the included source`() = timeoutRunBlocking {
        val project = projectFixture.get()
        val includeLine = ".include \"shared-end.inc\"\n"
        val matched = ".macro emit\n$includeLine"
        val (entry, other) = withContext(Dispatchers.EDT) {
            val include = fixture.addFileToProject("shared-end.inc", ".end_macro\n")
            val root = fixture.addFileToProject("unmatched-entry.mars", includeLine)
            val alternative = fixture.addFileToProject("other-entry.mars", matched)
            fixture.configureFromExistingVirtualFile(include.virtualFile)
            fixture.editor.caretModel.moveToOffset(1)
            root to alternative
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val documents = PsiDocumentManager.getInstance(project)
            fun updateOtherEntry(text: String) {
                WriteCommandAction.runWriteCommandAction(project) { documents.getDocument(other)!!.setText(text) }
                documents.commitAllDocuments()
            }
            val settings = project.service<MarsAssemblySettings>()
            settings.changeSources(listOf(entry.virtualFile.path))
            assertEquals(listOf(".end_macro" to unmatchedEndMessage), errors())
            assertTrue(fixture.availableIntentions.none { it.text == commentUnmatchedEnd })
            updateOtherEntry(includeLine)
            assertEquals(listOf(".end_macro" to unmatchedEndMessage), errors())
            val action = fixture.availableIntentions.single { it.text == commentUnmatchedEnd }
            val context = ActionContext.from(fixture.editor, fixture.file)
            val command = checkNotNull(action.asModCommandAction())
            settings.changeSources(emptyList())
            assertEquals(null, command.getPresentation(context))
            assertTrue(command.perform(context).isEmpty)
            settings.changeSources(listOf(entry.virtualFile.path))
            updateOtherEntry(matched)
            assertEquals(null, command.getPresentation(context))
            assertTrue(command.perform(context).isEmpty)
            updateOtherEntry("nop\n")
            assertTrue(mipsSourceTimeline(other as MipsFile).macroBoundaryProblems(fixture.file as MipsFile).isEmpty())
            updateOtherEntry(includeLine)
            fixture.launchAction(action)
            assertEquals("# .end_macro\n", fixture.editor.document.text)
            assertTrue(errors().isEmpty())
        }
    }

    @Test
    fun `an invalid expanded label does not expose a trailing closer as an operation`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            fixture.configureByText("not-closers.asm", """
                .eqv NUMBER 7
                .eqv DOUBLE one: two
                .eqv FIRST SECOND
                .eqv SECOND FIRST
                NUMBER: .end_macro
                DOUBLE: .end_macro
                FIRST: .end_macro
                .word .end_macro
                '1234\#' .end_macro
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) { assertTrue(errors().none { it.second == unmatchedEndMessage }) }
    }

    @Test
    fun `macro boundaries follow the selected entry through includes`() = timeoutRunBlocking {
        val current = projectFixture.get()
        val (valid, open) = withContext(Dispatchers.EDT) {
            val opener = fixture.addFileToProject("opener.s", ".macro emit\nnop\n")
            fixture.addFileToProject("closer.s", ".end_macro\n")
            val valid = fixture.addFileToProject("valid.asm", ".include \"opener.s\"\n.include \"closer.s\"\n")
            val open = fixture.addFileToProject("open.asm", ".include \"opener.s\"\n")
            fixture.configureFromExistingVirtualFile(opener.virtualFile)
            valid.virtualFile to open.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val settings = current.service<MarsAssemblySettings>()
            assertTrue(errors().isEmpty())
            settings.changeSources(listOf(open.path))
            assertEquals(listOf(".macro" to missingEndMessage), errors())
            settings.changeSources(listOf(valid.path))
            assertTrue(errors().isEmpty())
        }
    }

    @Test
    fun `labeled macro syntax is checked only when the selected entry executes the directive`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val source = """
                .eqv LABEL alias_tail
                bad: .macro 7
                missing: .macro
                tail: .end_macro .end_macro
                LABEL: .end_macro extra
                formal: .macro emit (7)
                .end_macro
                nop
            """.trimIndent()
            val validSource = ".macro outer\n.include \"shared.s\"\n"
            val invalidSource = ".include \"shared.s\"\n"
            directory.resolve("shared.s").writeText(source)
            val validEntry = directory.resolve("valid.asm").also { it.writeText(validSource) }
            val invalidEntry = directory.resolve("invalid.asm").also { it.writeText(invalidSource) }
            val project = projectFixture.get()
            val context = project.service<MarsSessionService>().context
            assertEquals(1, context.assemble(listOf(validEntry), null, true, false).machineList!!.size)
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(invalidEntry), null, true, false) }
            assertEquals(
                listOf(
                    "Invalid Macro name \"7\"", "\".macro\" directive requires at least one argument.",
                    "invalid text after .END_MACRO", "invalid text after .END_MACRO", "Invalid macro argument '7'",
                ),
                failure.errors()!!.errorMessages.map { it.message },
            )
            val (valid, invalid) = withContext(Dispatchers.EDT) {
                val shared = fixture.addFileToProject("shared.s", source)
                val valid = fixture.addFileToProject("valid.asm", validSource)
                val invalid = fixture.addFileToProject("invalid.asm", invalidSource)
                fixture.configureFromExistingVirtualFile(shared.virtualFile)
                valid.virtualFile to invalid.virtualFile
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            withContext(Dispatchers.EDT) {
                assertTrue(errors().isEmpty())
                val settings = project.service<MarsAssemblySettings>()
                settings.changeSources(listOf(invalid.path))
                assertEquals(
                    listOf(
                        "7" to invalidNameMessage, ".macro" to "Expected a macro name",
                        ".end_macro" to trailingCloseMessage, ".end_macro" to trailingCloseMessage,
                        "7" to "Invalid MARS macro parameter '7'",
                    ),
                    errors(),
                )
                settings.changeSources(listOf(valid.path))
                assertTrue(errors().isEmpty())
            }
        }

    @Test
    fun `a pending include can supply either macro boundary`() = timeoutRunBlocking {
        val source = ".include \"start.s\"\n.end_macro\n.macro second\n.include \"finish.s\"\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("entry.asm", source).also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertTrue(errors().none { it.second == unmatchedEndMessage || it.second == missingEndMessage })
            fixture.addFileToProject("start.s", ".macro first\n")
            fixture.addFileToProject("finish.s", ".end_macro\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) { assertTrue(errors().isEmpty()) }
    }

    @Test
    fun `missing macro closer quick fix appends once and invalidates stale actions`() = timeoutRunBlocking {
        val project = projectFixture.get()
        withContext(Dispatchers.EDT) {
            for (source in listOf("<caret>.macro emit\nnop\n", ".eqv OPEN .macro\n<caret>OPEN emit\nnop")) {
                fixture.configureByText("missing-end.mars", source)
                assertEquals(listOf(missingEndMessage), errors().map { it.second })
                val action = fixture.availableIntentions.single { it.text == "Add .end_macro at end of file" }
                fixture.launchAction(action)
                assertEquals(source.replace("<caret>", "").trimEnd('\n') + "\n.end_macro\n", fixture.editor.document.text)
                assertTrue(errors().isEmpty())
                val context = ActionContext.from(fixture.editor, fixture.file)
                val command = checkNotNull(action.asModCommandAction())
                assertEquals(null, command.getPresentation(context))
                assertTrue(command.perform(context).isEmpty)
            }
        }
    }

    @Test
    fun `closing a macro from an included header appends to the entry after the body`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val project = projectFixture.get()
            val body = $$".include \"header.inc\"\naddiu $t0, $zero, %value"
            val header = ".macro emit (%value)\n"
            val (entry, other) = withContext(Dispatchers.EDT) {
                val include = fixture.addFileToProject("header.inc", header)
                val root = fixture.addFileToProject("entry.mars", body)
                val alternative = fixture.addFileToProject("closed.mars", ".include \"header.inc\"\n.end_macro\n")
                fixture.configureFromExistingVirtualFile(include.virtualFile)
                fixture.editor.caretModel.moveToOffset(1)
                root to alternative
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            val completed = withContext(Dispatchers.EDT) {
                assertTrue(errors().isEmpty())
                assertTrue(fixture.availableIntentions.none { it.text.startsWith("Add .end_macro") })
                val settings = project.service<MarsAssemblySettings>()
                settings.changeSources(listOf(entry.virtualFile.path))
                assertEquals(listOf(".macro" to missingEndMessage), errors())
                val action = fixture.availableIntentions.single { it.text == "Add .end_macro to entry.mars" }
                val context = ActionContext.from(fixture.editor, fixture.file)
                val command = checkNotNull(action.asModCommandAction())
                settings.changeSources(emptyList())
                assertEquals(null, command.getPresentation(context))
                settings.changeSources(listOf(other.virtualFile.path))
                assertEquals(null, command.getPresentation(context))
                settings.changeSources(listOf(entry.virtualFile.path))
                fixture.launchAction(action)
                val document = PsiDocumentManager.getInstance(project).getDocument(entry)!!
                assertEquals(body + "\n.end_macro\n", document.text)
                assertEquals(header, fixture.editor.document.text)
                assertTrue(errors().isEmpty())
                document.text
            }
            directory.resolve("header.inc").writeText(header)
            val source = directory.resolve("entry.mars").also { it.writeText(completed + "emit(7)\n") }
            val context = project.service<MarsSessionService>().context
            assertEquals(listOf(0x24080007), context.assemble(listOf(source)).machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `commenting out macro closer text preserves the suffix and closes the macro`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val project = projectFixture.get()
            val repaired = withContext(Dispatchers.EDT) {
                val direct = $$".macro emit\naddiu $t0, $zero, 7\n.end_macro st<caret>ray # keep\nemit\n"
                val aliased =
                  $$".eqv CLOSE .end_macro\n.macro emit\naddiu $t0, $zero, 7\nCL<caret>OSE stray # keep\nemit\n"
                listOf(direct, aliased).map { source ->
                    fixture.configureByText("macro-tail.mars", source)
                    assertTrue(errors().any { it.second == trailingCloseMessage })
                    val action = fixture.availableIntentions.single { it.text == "Comment out text after .end_macro" }
                    fixture.launchAction(action)
                    val fixed = source.replace("<caret>", "").replace("stray", "# stray")
                    assertEquals(fixed, fixture.editor.document.text)
                    assertTrue(errors().isEmpty())
                    val command = checkNotNull(action.asModCommandAction())
                    val context = ActionContext.from(fixture.editor, fixture.file)
                    assertEquals(null, command.getPresentation(context))
                    assertTrue(command.perform(context).isEmpty)
                    if (source == aliased) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            val document = fixture.editor.document
                            document.setText(fixed.replace(".eqv CLOSE .end_macro", ".eqv CLOSE .word").replace("# stray", "stray"))
                        }
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        assertEquals(null, command.getPresentation(context))
                    }
                    fixed
                }
            }
            val context = project.service<MarsSessionService>().context
            for ((index, source) in repaired.withIndex()) {
                val entry = directory.resolve("tail-$index.mars").also { it.writeText(source) }
                assertEquals(listOf(0x24080007), context.assemble(listOf(entry)).machineList!!.map { it.binaryStatement })
            }
        }

    @Test
    fun `macro closer tail quick fix requires a separate editable suffix in a known body`() = timeoutRunBlocking {
        val project = projectFixture.get()
        withContext(Dispatchers.EDT) {
            for (source in listOf(
                ".end_macro ext<caret>ra\n",
                ".macro emit\n.include \"missing.inc\"\n.end_macro ext<caret>ra\n",
                ".macro emit\n.end_macro .inc<caret>lude \"unfinished\n",
                ".eqv CLOSE .end_macro extra\n.macro emit\nCL<caret>OSE\n",
            )) {
                fixture.configureByText("macro-tail.mars", source)
                errors()
                assertTrue(fixture.availableIntentions.none { it.text == "Comment out text after .end_macro" }, source)
            }
            val aliased = ".eqv CLOSE .end_macro\n.macro emit\nCL<caret>OSE extra\n"
            fixture.configureByText("macro-tail.mars", aliased)
            errors()
            val beforeInclude = checkNotNull(
                fixture.availableIntentions.single { it.text == "Comment out text after .end_macro" }.asModCommandAction(),
            )
            WriteCommandAction.runWriteCommandAction(project) {
                val editor = fixture.editor
                val line = editor.caretModel.logicalPosition.line
                editor.document.insertString(editor.document.getLineEndOffset(line), " .include \"unfinished")
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEquals(null, beforeInclude.getPresentation(ActionContext.from(fixture.editor, fixture.file)))

            val source = ".macro emit\n.end_macro extra\n"
            val shared = fixture.addFileToProject("shared.inc", source)
            fixture.configureFromExistingVirtualFile(shared.virtualFile)
            fixture.editor.caretModel.moveToOffset(source.indexOf("extra"))
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val stale = withContext(Dispatchers.EDT) {
            errors()
            val action = fixture.availableIntentions.single { it.text == "Comment out text after .end_macro" }
            fixture.addFileToProject("first.mars", ".include \"shared.inc\"\n")
            fixture.addFileToProject("second.mars", ".include \"shared.inc\"\n")
            checkNotNull(action.asModCommandAction())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            assertEquals(null, stale.getPresentation(ActionContext.from(fixture.editor, fixture.file)))
        }
    }

    @Test
    fun `an unterminated include takes precedence over macro errors on its line and later lines`() = timeoutRunBlocking {
        val source = """
            .eqv OPEN .macro
            .end_macro
            OPEN .include "start.s
            .end_macro
            .macro second
            .include "finish.s
        """.trimIndent()
        val project = projectFixture.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("start.s", ".macro first\n")
            fixture.addFileToProject("finish.s", ".end_macro\n")
            fixture.addFileToProject("entry.asm", source).also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            assertEquals(
                listOf(".end_macro" to unmatchedEndMessage, "\"start.s" to unterminatedIncludeMessage, "\"finish.s" to unterminatedIncludeMessage),
                errors(),
            )
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.editor.document.setText(source.replace("start.s", "start.s\"").replace("finish.s", "finish.s\""))
            }
            assertEquals(listOf(".end_macro" to unmatchedEndMessage), errors())
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.editor.document.setText(".macro second\n.set \"unfinished operand\n")
            }
            assertEquals(listOf(".macro" to missingEndMessage), errors())
        }
    }

    @Test
    fun `an unterminated include in a dependency suppresses later macro errors in the entry`() = timeoutRunBlocking {
        val project = projectFixture.get()
        val dependency = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("ordinary.s", "nop\n")
            val dependency = fixture.addFileToProject("dependency.s", ".include \"ordinary.s")
            val entry = fixture.addFileToProject("entry.asm", ".macro outer\n.include \"dependency.s\"\n.macro inner\n.end_macro\n")
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            dependency
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            assertTrue(errors().isEmpty())
            val documents = PsiDocumentManager.getInstance(project)
            val document = documents.getDocument(dependency)!!
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\"") }
            documents.commitDocument(document)
            assertEquals(listOf(".macro" to nestedMacroMessage), errors())
        }
    }

    @Test
    fun `nested openers report their written tokens without replacing the outer macro`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val source = $$"""
                .eqv OPEN .macro
                .eqv LABEL inside_alias
                .eqv PREFIX generated:
                .eqv BAD_NAME 7
                .macro outer (%value)
                .macro nested (%other, 9)
                OPEN expanded (%other)
                inside: .macro labeled (%other)
                LABEL: .macro alias_labeled (%other)
                %value: .macro parameter_labeled (%other)
                PREFIX .macro generated_labeled (%other)
                OPEN BAD_NAME
                .macro BAD_NAME (9)
                li $t0, %value
                .end_macro
            """.trimIndent()
            val entry = directory.resolve("nested.asm").also { it.writeText(source) }
            val project = projectFixture.get()
            val context = project.service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
            assertEquals(
                listOf(nestedMacroMessage, nestedMacroMessage, "Invalid Macro name \"7\"", "Invalid Macro name \"7\""),
                failure.errors()!!.errorMessages.map { it.message },
            )
            withContext(Dispatchers.EDT) { fixture.configureByText("nested.asm", "$source\nouter(7)\n") }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            withContext(Dispatchers.EDT) {
                assertEquals(
                    listOf(
                        ".macro" to nestedMacroMessage, "OPEN" to nestedMacroMessage,
                        "BAD_NAME" to invalidNameMessage, "BAD_NAME" to invalidNameMessage,
                    ),
                    errors(),
                )
                readAction {
                    val file = fixture.file as MipsFile
                    assertEquals(setOf("outer"), project.service<MarsAnalysisService>().editorTimeline(file).macroNames)
                    assertEquals("%value", file.findReferenceAt(source.lastIndexOf("%value") + 1)?.resolve()?.text)
                    assertEquals("outer", (file.findReferenceAt(source.length + 1)?.resolve() as? MipsDeclaration)?.name)
                }
                WriteCommandAction.runWriteCommandAction(project) {
                    fixture.editor.document.setText(
                        source.substringBefore(".macro nested") + source.substringAfter(".macro BAD_NAME (9)\n") + "\nouter(7)\n",
                    )
                }
                assertTrue(errors().isEmpty())
            }
        }

    @Test
    fun `a shared opener is nested only in the entry that opens a macro before including it`() = timeoutRunBlocking {
        val current = projectFixture.get()
        val (standalone, nested) = withContext(Dispatchers.EDT) {
            val inner = fixture.addFileToProject("inner.s", ".macro emit (9)\nnop\n")
            val standalone = fixture.addFileToProject("standalone.asm", ".include \"inner.s\"\n.end_macro\n")
            val nested = fixture.addFileToProject("nested.asm", ".macro outer\n.include \"inner.s\"\n.end_macro\n")
            fixture.configureFromExistingVirtualFile(inner.virtualFile)
            standalone.virtualFile to nested.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val settings = current.service<MarsAssemblySettings>()
            assertEquals(listOf("9" to "Invalid MARS macro parameter"), errors())
            settings.changeSources(listOf(nested.path))
            assertEquals(listOf(".macro" to nestedMacroMessage), errors())
            settings.changeSources(listOf(standalone.path))
            assertEquals(listOf("9" to "Invalid MARS macro parameter"), errors())
        }
    }

    @Test
    fun `a pending include can close the macro before a suspected nested opener`() = timeoutRunBlocking {
        val source = ".macro outer\n.include \"closer.s\"\n.macro next\nnop\n.end_macro\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("entry.asm", source).also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertTrue(errors().none { it.second == nestedMacroMessage })
            fixture.addFileToProject("closer.s", ".end_macro\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) { assertTrue(errors().isEmpty()) }
    }

    @Test
    fun `macro diagnostics mark the full signed token or its written aliases`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val source = """
                .eqv OPEN .macro
                .eqv SIGNED -7
                .eqv SEVEN 7
                .macro -7
                .macro + 7
                label: .macro +7
                OPEN -7
                OPEN -SEVEN
                .macro SIGNED
                OPEN emit (%accepted, +7)
                .end_macro
                nop
            """.trimIndent()
            val entry = directory.resolve("signed-macro-errors.mars").also { it.writeText(source) }
            val project = projectFixture.get()
            val context = project.service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry)) }
            assertEquals(
                listOf("-7", "+", "+7", "-7", "-7", "-7").map { "Invalid Macro name \"$it\"" } +
                    "Invalid macro argument '+7'",
                failure.errors()!!.errorMessages.map { it.message },
            )
            withContext(Dispatchers.EDT) { fixture.configureByText("signed-macro-errors.mars", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            withContext(Dispatchers.EDT) {
                assertEquals(
                    listOf("-7", "+", "+7", "-7", "-SEVEN", "SIGNED").map { it to invalidNameMessage } +
                        ("+7" to "Invalid MARS macro parameter '+7'"),
                    errors(),
                )
            }
        }

    @Test
    fun `missing macro names mark the directive before comments and at the end of the file`() = timeoutRunBlocking {
        val source = ".MaCrO,, # keep\nnop\n.eqv OPEN .macro\nOPEN # keep\nnop\n.macro"
        val expected = listOf(".MaCrO", "OPEN", ".macro").map { it to "Expected a macro name" }
        withContext(Dispatchers.EDT) {
            for (extension in listOf("s", "asm", "mars", "inc")) {
                fixture.configureByText("missing-macro-name.$extension", source)
                assertEquals(expected, errors(), extension)
            }
        }
    }

    @Test
    fun `expanded openers diagnose missing and invalid names at the original tokens`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val source = $$"""
                .eqv OPEN .macro
                .eqv BAD_NAME 7
                .eqv WHOLE .macro $t0
                OPEN # missing name
                .macro BAD_NAME
                WHOLE
                .macro real (%value)
                    li $t0, %value
                .end_macro
                real(42)
            """.trimIndent()
            val entry = directory.resolve("bad-macro-names.asm").also { it.writeText(source) }
            val project = projectFixture.get()
            val context = project.service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
            assertEquals(
                listOf("\".macro\" directive requires at least one argument.", "Invalid Macro name \"7\"",
                  $$"Invalid Macro name \"$t0\""
                ),
                failure.errors()!!.errorMessages.map { it.message },
            )
            withContext(Dispatchers.EDT) { fixture.configureByText("bad-macro-names.asm", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            withContext(Dispatchers.EDT) {
                assertEquals(
                    listOf("OPEN" to "Expected a macro name", "BAD_NAME" to invalidNameMessage, "WHOLE" to invalidNameMessage),
                    errors(),
                )
                readAction {
                    val file = fixture.file as MipsFile
                    assertEquals(setOf("real"), project.service<MarsAnalysisService>().editorTimeline(file).macroNames)
                    assertEquals("real", (file.findReferenceAt(source.lastIndexOf("real"))?.resolve() as? MipsDeclaration)?.name)
                }
                WriteCommandAction.runWriteCommandAction(project) {
                    fixture.editor.document.setText(
                        source.replace("OPEN # missing name", "OPEN first\n.end_macro")
                            .replace("BAD_NAME 7", "BAD_NAME second")
                            .replace(".macro BAD_NAME\n", ".macro BAD_NAME\n.end_macro\n")
                            .replace($$".macro $t0", ".macro third").replace("\nWHOLE\n", "\nWHOLE\n.end_macro\n"),
                    )
                }
                assertTrue(errors().isEmpty())
            }
        }

    @Test
    fun `a shared macro name uses the alias from the selected assembly entry`() = timeoutRunBlocking {
        val current = projectFixture.get()
        val (valid, invalid) = withContext(Dispatchers.EDT) {
            val valid = fixture.addFileToProject("valid.asm", ".eqv NAME emit\n.include \"shared.s\"\n")
            val invalid = fixture.addFileToProject("invalid.asm", ".eqv NAME 7\n.include \"shared.s\"\n")
            val shared = fixture.addFileToProject("shared.s", ".macro NAME\nnop\n.end_macro\n")
            fixture.configureFromExistingVirtualFile(shared.virtualFile)
            valid.virtualFile to invalid.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val settings = current.service<MarsAssemblySettings>()
            assertTrue(errors().isEmpty())
            settings.changeSources(listOf(invalid.path))
            assertEquals(
                listOf("NAME" to invalidNameMessage, ".end_macro" to unmatchedEndMessage),
                errors(),
            )
            settings.changeSources(listOf(valid.path))
            assertTrue(errors().isEmpty())
        }
    }

    @Test
    fun `the first rejected formal takes precedence over a later parser error`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val source = """
                .eqv FORM 7
                .eqv SEVEN 7
                .macro ordinary (%kept, rejected, 9)
                .end_macro
                .macro expanded (FORM, 9)
                .end_macro
                .macro signed (-SEVEN, 9)
                .end_macro
                nop
            """.trimIndent()
            val project = projectFixture.get()
            val entry = directory.resolve("first-invalid-formal.mars").also { it.writeText(source) }
            val context = project.service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry)) }
            assertEquals(listOf("rejected", "7", "-7").map { "Invalid macro argument '$it'" },
                failure.errors()!!.errorMessages.map { it.message })
            withContext(Dispatchers.EDT) { fixture.configureByText("first-invalid-formal.mars", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            withContext(Dispatchers.EDT) {
                assertEquals(
                    listOf(
                        "rejected" to "Invalid MARS macro parameter 'rejected'",
                        "FORM" to "Invalid MARS macro parameter '7'",
                        "-SEVEN" to "Invalid MARS macro parameter '-7'",
                    ),
                    errors(),
                )
                val physicalError = PsiTreeUtil.findChildOfType(fixture.file, PsiErrorElement::class.java)!!
                DumbModeTestUtils.runInDumbModeSynchronously(project) {
                    assertTrue(MipsAssemblySyntaxErrorFilter().shouldHighlightErrorElement(physicalError))
                }
                WriteCommandAction.runWriteCommandAction(project) {
                    fixture.editor.document.setText(source.replace(".eqv FORM 7", ".eqv FORM %accepted"))
                }
                assertEquals("9" to "Invalid MARS macro parameter", errors()[1])
            }
        }

    @Test
    fun `an earlier unresolved include leaves the physical formal diagnostic available`() = timeoutRunBlocking {
        val source = ".include \"formals.inc\"\n.macro emit (FORM, 9)\nnop\n.end_macro\n"
        val project = projectFixture.get()
        withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("waiting.mars", source)
            fixture.configureFromExistingVirtualFile(file.virtualFile)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            assertEquals(listOf("9" to "Invalid MARS macro parameter"), errors().filter { "macro parameter" in it.second })
            fixture.addFileToProject("formals.inc", ".eqv FORM 7\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            assertEquals(listOf("FORM" to "Invalid MARS macro parameter '7'"), errors())
        }
    }

    @Test
    fun `an unresolved include can change whether a header is nested`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val source = ".macro outer\n.include \"missing.inc\"\n.macro inner (9)\n.end_macro\n"
            fixture.configureByText("nested-invalid-formal.mars", source)
            assertEquals(listOf("9" to "Invalid MARS macro parameter"),
                errors().filter { "macro parameter" in it.second || it.second == nestedMacroMessage })
        }
    }

    @Test
    fun `invalid expanded formals point to their written tokens and refresh after edits`() = timeoutRunBlocking {
        val source = $$"""
            .eqv FORMS %kept, 7, 8
            .macro emit (FORMS)
                li $t0, %kept
            .end_macro
            .macro literal (%first, 9)
                nop
            .end_macro
            .eqv OPEN .macro
            OPEN generated (ordinary)
                nop
            .end_macro
        """.trimIndent()
        withContext(Dispatchers.EDT) { fixture.configureByText("bad-formals.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertEquals(
                listOf(
                    "FORMS" to "Invalid MARS macro parameter '7'",
                    "9" to "Invalid MARS macro parameter",
                    "ordinary" to "Invalid MARS macro parameter 'ordinary'",
                ),
                errors(),
            )
            val use = source.indexOf("%kept", source.indexOf("li"))
            assertEquals("FORMS", fixture.file.findReferenceAt(use + 1)?.resolve()?.text)
            WriteCommandAction.runWriteCommandAction(fixture.project) {
                fixture.editor.document.setText(
                    source.replace("7, 8", "%second, %third").replace("9)", "%second)").replace("(ordinary)", "(%ordinary)"),
                )
            }
            assertTrue(errors().isEmpty())
        }
    }

    @Test
    fun `a lexical error on a generated header gets one diagnostic`() = timeoutRunBlocking {
        val source = $$".eqv OPEN .macro\n.eqv FORM %value\nOPEN emit (FORM, @)\nli $t0, %value\n.end_macro\n"
        withContext(Dispatchers.EDT) { fixture.configureByText("bad-generated-formal.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertEquals(1, errors().size)
            assertEquals("FORM", fixture.file.findReferenceAt(source.lastIndexOf("%value") + 1)?.resolve()?.text)
        }
    }

    @Test
    fun `a shared macro header waits for an entry before diagnosing its expanded formal`() = timeoutRunBlocking {
        val current = projectFixture.get()
        val (valid, invalid) = withContext(Dispatchers.EDT) {
            val first = fixture.addFileToProject("valid.asm", ".eqv FORM %value\n.include \"shared.s\"\n")
            val second = fixture.addFileToProject("invalid.asm", ".eqv FORM 17\n.include \"shared.s\"\n")
            val shared = fixture.addFileToProject("shared.s", ".macro emit (FORM, 9)\nnop\n.end_macro\n")
            fixture.configureFromExistingVirtualFile(shared.virtualFile)
            first.virtualFile to second.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            assertEquals(listOf("9" to "Invalid MARS macro parameter"), errors())
            val settings = current.service<MarsAssemblySettings>()
            settings.changeSources(listOf(invalid.path))
            assertEquals(listOf("FORM" to "Invalid MARS macro parameter '17'"), errors())
            settings.changeSources(listOf(valid.path))
            assertEquals(listOf("9" to "Invalid MARS macro parameter"), errors())
        }
    }

    private val trailingCloseMessage = "Unexpected text after .end_macro"
    private val unmatchedEndMessage = ".end_macro without a matching .macro"
    private val commentUnmatchedEnd = "Comment out unmatched .end_macro"
    private val missingEndMessage = "Missing .end_macro for this macro"
    private val nestedMacroMessage = "Nested macros are not allowed"
    private val invalidNameMessage = "Invalid MARS macro name"
    private val unterminatedIncludeMessage = "Unterminated .include path"

    private suspend fun errors(): List<Pair<String, String>> = fixture.highlightAfterMipsRefresh()
        .filter { it.severity == HighlightSeverity.ERROR }
        .sortedBy { it.startOffset }
        .map { fixture.file.text.substring(it.startOffset, it.endOffset) to it.description.orEmpty() }

    @Test
    fun `included body uses the enclosing macro from the inferred and selected assembly entry`() = timeoutRunBlocking {
        val project = projectFixture.get()
        val (entry, body) = withContext(Dispatchers.EDT) {
            fixture.enableInspections(MipsMacroParameterInspection::class.java)
            fixture.addFileToProject("macro-start.s", ".macro scoped (%used)\n")
            val body = fixture.addFileToProject("macro-body.s",
              $$"addiu $t0, $zero, %used\naddiu $t1, $zero, %absent\n"
            )
            val entry = fixture.addFileToProject("entry.asm", """
                .text
                .include "macro-start.s"
                .include "macro-body.s"
                .end_macro
                nop
            """.trimIndent())
            fixture.configureFromExistingVirtualFile(body.virtualFile)
            entry.virtualFile to body.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        fun expectedWarnings(): List<Warning> {
            val text = fixture.file.text
            return listOf(Warning(text.indexOf("%absent"), "%absent", unknown("scoped", "%absent")))
        }
        withContext(Dispatchers.EDT) { assertEquals(expectedWarnings(), warnings()) }
        val component = MarsSessionService.createMarsComponent()
        component.context().use {
            assertTrue(project.service<MarsBuildService>().assemble(entry.toNioPath(), component, activateBuildView = false))
        }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(entry)
            assertTrue(warnings().isEmpty())
            fixture.configureFromExistingVirtualFile(body)
            assertEquals(expectedWarnings(), warnings())
        }
    }

    private data class Warning(val offset: Int, val token: String, val message: String)

    private suspend fun warnings(): List<Warning> = fixture.highlightAfterMipsRefresh()
        .filter { it.severity == HighlightSeverity.WARNING }
        .mapNotNull { info ->
            val message = info.description ?: return@mapNotNull null
            if (!message.startsWith("Duplicate macro parameter") && !message.startsWith("Macro '")) return@mapNotNull null
            Warning(info.startOffset, fixture.file.text.substring(info.startOffset, info.endOffset), message)
        }
        .sortedBy(Warning::offset)

    private fun duplicate(name: String) = "Duplicate macro parameter '$name'. References bind to the first declaration."
    private fun unknown(macro: String, name: String) = "Macro '$macro' has no parameter named '$name'."
}
