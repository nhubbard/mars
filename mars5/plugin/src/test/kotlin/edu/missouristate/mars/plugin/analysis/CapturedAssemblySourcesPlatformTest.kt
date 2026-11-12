package edu.missouristate.mars.plugin.analysis

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.limits.FileSizeLimit
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.language.MipsDocumentationTargetProvider
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.run.MarsExecutionSources
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class CapturedAssemblySourcesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `captures uncommitted entry and transitive include documents before assembly`() = timeoutRunBlocking<Unit> {
        val include = if (SystemInfoRt.isWindows) "/parts/outer.txt" else "./parts/outer.txt"
        val mainText = ".include \"$include\"\nli \$t0, VALUE\n"
        val innerText = ".eqv VALUE 42\n"
        val (main, outer, inner, handler) = withContext(Dispatchers.EDT) {
            val leaf = fixture.addFileToProject("parts/with spaces#é.txt", ".eqv VALUE 1\n").virtualFile
            val entry = fixture.addFileToProject("main.asm", "nop\n").virtualFile
            val intermediate = fixture.addFileToProject("parts/outer.txt", ".include \"${leaf.path}\"\n").virtualFile
            val start = fixture.addFileToProject("startup.s", "addiu \$s0, \$zero, 17\n").virtualFile
            edit(entry, mainText)
            edit(leaf, innerText)
            listOf(entry, intermediate, leaf, start)
        }
        val entry = main.toNioPath()
        val leaf = inner.toNioPath()
        val handlerPath = handler.toNioPath()
        val capture = captureAssemblySources(listOf(entry), handlerPath)
        assertEquals(listOf(entry), capture.snapshot.entries())
        assertEquals(handlerPath, capture.exceptionHandler)
        assertEquals(setOf(entry, outer.toNioPath(), leaf, handlerPath), capture.snapshot.texts().keys)
        assertEquals(capture.snapshot.texts().keys, capture.revisions.keys)
        assertEquals(mainText, capture.snapshot.texts()[entry])
        assertEquals(innerText, capture.snapshot.texts()[leaf])
        assertEquals("nop\n", Files.readString(entry))
        assertEquals(".eqv VALUE 1\n", Files.readString(leaf))

        withContext(Dispatchers.EDT) { edit(inner, ".eqv VALUE 99\n") }
        val newer = captureAssemblySources(listOf(entry), handlerPath)
        assertEquals(".eqv VALUE 99\n", newer.snapshot.texts()[leaf])
        assertEquals(innerText, capture.snapshot.texts()[leaf])
        assertFalse(capture.revisions[leaf] == newer.revisions[leaf])

        val context = project.get().getService(MarsSessionService::class.java).context
        val program = context.assembleSnapshot(capture.snapshot, capture.exceptionHandler, true, false)
        assertEquals(2, program.instructionCount())
        assertTrue(program.simulate(5))
        assertEquals(42, context.registerFile.getValue(8))
        assertEquals(17, context.registerFile.getValue(16))
    }

    @Test
    fun `assembly follows includes and source locations beyond the IntelliJ file preview`() = timeoutRunBlocking<Unit> {
        val comment = "#" + " ".repeat(16_382)
        val diskLine = "$comment\r\n"
        val padding = " ".repeat(72_000)
        val commentCount = FileSizeLimit.getContentLoadLimit("inc") / (diskLine.length * Char.SIZE_BYTES) + 1
        val (entry, large) = withContext(Dispatchers.EDT) {
            val source = fixture.addFileToProject("entry.mars", ".include \"large.inc\"\nemit\npadded\nj target\nj farTarget\n").virtualFile
            fixture.addFileToProject("tail.s", "nop\n")
            val path = source.toNioPath().resolveSibling("large.inc")
            Files.newOutputStream(path).use { output ->
                output.write(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
                output.bufferedWriter(Charsets.UTF_16LE).use { writer ->
                    repeat(commentCount) { writer.write(diskLine) }
                    writer.write(".include \"tail.s\"\r\n\taddiu \$t0, \$zero, 7\r\n")
                    writer.write("#" + " ".repeat(32_768) + "\r\n")
                    writer.write("\taddiu \$t1, \$zero, 8\r\n\ttarget: nop\r\n.macro emit # Macro documentation\r\nnop\r\n.end_macro\r\n")
                    writer.write("${padding}farTarget: nop\r\n.macro ${padding}padded # Wide definition\r\nnop\r\n.end_macro\r\n")
                }
            }
            source.toNioPath() to checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path))
        }
        val largePath = large.toNioPath()
        val instructionLine = commentCount + 2
        val documents = FileDocumentManager.getInstance()
        val preview = readAction { checkNotNull(documents.getDocument(large)) }
        assertTrue(documents.isPartialPreviewOfALargeFile(preview))
        assertTrue(preview.lineCount < instructionLine)
        readAction {
            val file = VirtualFileManager.getInstance().findFileByNioPath(entry)!!
            val syntax = PsiManager.getInstance(project.get()).findFile(file) as MipsFile
            assertTrue(mipsSourceTimeline(syntax).hasIncompleteIncludes)
        }

        val capture = captureAssemblySources(listOf(entry), null)
        assertTrue(capture.snapshot.texts().getValue(largePath).contains("\taddiu \$t1, \$zero, 8\n\ttarget: nop\n"))
        assertTrue(largePath.resolveSibling("tail.s") in capture.snapshot.texts())
        val component = MarsSessionService.createMarsComponent()
        component.context().use { session ->
            val result = capture.assembleIn(component)
            assertTrue(result.succeeded(), result.errors().toString())
            val statements = session.program.machineList
            assertEquals(listOf(0, 0x24080007, 0x24090008, 0, 0, 0, 0), statements.dropLast(2).map { it.binaryStatement })
            val location = Location(largePath, instructionLine)
            assertEquals(location, result.program().sourceMap().origins()[statements[1].address]?.location())
            val ide = project.get()
            val analysis = ide.service<MarsAnalysisService>()
            analysis.publishBuiltSymbols(analysis.beginSymbolBuild(entry), capture, result.program().symbolMap())
            val croppedMacro = readAction {
                val main = PsiManager.getInstance(ide).findFile(VirtualFileManager.getInstance().findFileByNioPath(entry)!!) as MipsFile
                val label = checkNotNull(main.findReferenceAt(main.text.indexOf("target"))?.resolve())
                assertEquals("target", label.text)
                val excerptFile = label.containingFile.virtualFile
                assertNotEquals(large, excerptFile)
                assertFalse(excerptFile.isWritable)
                val macroCall = main.text.indexOf("emit")
                val macro = checkNotNull(main.findReferenceAt(macroCall)?.resolve())
                assertEquals("emit", macro.text)
                assertSame(excerptFile, macro.containingFile.virtualFile)
                val docs = MipsDocumentationTargetProvider().documentationTargets(main, macroCall).single()
                val documentationLink = assertInstanceOf(OpenFileDescriptor::class.java, docs.navigatable)
                assertSame(excerptFile, documentationLink.file)
                assertEquals(macro.textOffset, documentationLink.offset)
                assertNull(analysis.sourceTokenPosition(SourceToken(Location(largePath, instructionLine + 3), 2)))
                val wideLabel = checkNotNull(main.findReferenceAt(main.text.indexOf("farTarget"))?.resolve())
                assertEquals("farTarget", wideLabel.text)
                assertEquals(0, wideLabel.textOffset)
                val wideExcerpt = wideLabel.containingFile.virtualFile
                assertFalse(wideExcerpt.isWritable)
                assertTrue(wideLabel.containingFile.textLength < padding.length)

                val wideCall = main.text.indexOf("padded")
                val wideMacro = checkNotNull(main.findReferenceAt(wideCall)?.resolve())
                assertEquals("padded", wideMacro.text)
                assertEquals(0, wideMacro.textOffset)
                val wideDocs = MipsDocumentationTargetProvider().documentationTargets(main, wideCall).single()
                val wideLink = assertInstanceOf(OpenFileDescriptor::class.java, wideDocs.navigatable)
                assertSame(wideMacro.containingFile.virtualFile, wideLink.file)
                assertEquals(0, wideLink.offset)

                val sources = MarsExecutionSources(capture)
                assertEquals(large, sources.position(Location(largePath, 1)).file)
                assertEquals(8, sources.hyperlink(project.get(), Location(entry, 9), 2).descriptor?.line)
                val position = sources.position(location)
                assertNotEquals(large, position.file)
                assertFalse(position.file.isWritable)
                assertEquals(location, sources.location(position.file, position.line))
                assertNull(MarsExecutionSources(capture).location(position.file, position.line))
                val excerpt = checkNotNull(documents.getDocument(position.file))
                val lineStart = excerpt.getLineStartOffset(position.line)
                assertTrue(excerpt.text.startsWith("\taddiu \$t0, \$zero, 7\n", lineStart))
                val descriptor = sources.hyperlink(project.get(), location, 2).descriptor
                assertSame(position.file, descriptor?.file)
                assertEquals(lineStart + 1, descriptor?.offset)
                val frameLink = assertInstanceOf(OpenFileDescriptor::class.java, position.createNavigatable(project.get()))
                assertSame(position.file, frameLink.file)
                assertEquals(position.line, frameLink.line)

                val nextLocation = Location(largePath, instructionLine + 2)
                val next = sources.position(nextLocation)
                assertEquals(nextLocation, sources.location(next.file, next.line))
                assertSame(next.file, sources.position(Location(largePath, instructionLine + 3)).file)
                val nextExcerpt = checkNotNull(documents.getDocument(next.file))
                assertTrue(nextExcerpt.text.startsWith("# "))
                assertTrue(nextExcerpt.text.startsWith("\taddiu \$t1, \$zero, 8\n", nextExcerpt.getLineStartOffset(next.line)))
                wideLink.file
            }
            withContext(Dispatchers.EDT) {
                fixture.openFileInEditor(croppedMacro)
                assertFalse(fixture.doHighlighting().any { it.severity == HighlightSeverity.ERROR })
            }
            val definition = result.program().symbolMap().locals().getValue(entry).getValue("target")
            val wide = result.program().symbolMap().locals().getValue(entry).getValue("farTarget")
            val withoutColumn = Definition(wide.address(), wide.kind(), wide.origin())
            val badColumn = Definition(definition.address(), definition.kind(), definition.origin(), definition.macroPrivate(), 100)
            analysis.publishBuiltSymbols(analysis.beginSymbolBuild(entry), capture,
                AssemblySymbolMap(mapOf(entry to mapOf("target" to badColumn, "farTarget" to withoutColumn)), emptyMap()))
            readAction {
                val source = VirtualFileManager.getInstance().findFileByNioPath(entry)!!
                val main = PsiManager.getInstance(ide).findFile(source) as MipsFile
                assertNull(main.findReferenceAt(main.text.indexOf("target"))?.resolve())
                assertEquals("farTarget", main.findReferenceAt(main.text.indexOf("farTarget"))?.resolve()?.text)
            }
        }
        val unavailable = readAction {
            collectAssemblySources(listOf(entry), null) { dependency ->
                if (dependency == large) Files.delete(largePath)
            }
        }
        assertFalse(largePath in unavailable.snapshot.texts())
    }

    @Test
    fun `editor macro scope refreshes when an oversized include becomes fully readable`() = timeoutRunBlocking<Unit> {
        val (entry, include) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("scope.mars", ".macro emit\n.include \"closer.inc\"\nemit\n") as MipsFile
            val include = fixture.tempDirFixture.createFile("closer.inc")
            val size = FileSizeLimit.getContentLoadLimit("inc")
            val oversized = ByteArray(size + 1) { ' '.code.toByte() }.apply { this[0] = '#'.code.toByte() }
            Files.write(include.toNioPath(), oversized)
            include.refresh(false, false)
            entry to include
        }
        readAction {
            val timeline = mipsSourceTimeline(entry)
            assertTrue(timeline.hasIncompleteIncludes)
            assertTrue(timeline.macroBoundaryProblems(entry).isEmpty())
        }
        withContext(Dispatchers.EDT) {
            Files.writeString(include.toNioPath(), ".end_macro\n")
            include.refresh(false, false)
        }
        readAction {
            val timeline = mipsSourceTimeline(entry)
            assertFalse(timeline.hasIncompleteIncludes)
            assertTrue(timeline.macroBoundaryProblems(entry).isEmpty())
            assertEquals(setOf(0), timeline.prefix(entry, entry.textLength).macroOverloads("emit").keys)
        }
    }

    @Test
    fun `a directory alias back to the entry closes the cycle without scanning the entry again`() = timeoutRunBlocking<Unit> {
        val (main, outer) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("source/main.asm", ".include \"../parts/outer.s\"\n").virtualFile
            val part = fixture.addFileToProject("parts/outer.s", "nop\n").virtualFile
            val link = Files.createSymbolicLink(part.toNioPath().resolveSibling("shortcut"), entry.toNioPath().parent)
            checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(link))
            edit(part, "# in progress\n.include \"shortcut/main.asm\"\n")
            entry.toNioPath() to part.toNioPath()
        }
        val alias = outer.resolveSibling("shortcut/main.asm")
        val capture = captureAssemblySources(listOf(main), null)
        assertEquals(setOf(main, outer, alias), capture.revisions.keys)
        assertEquals(setOf(main.resolveSibling("../parts/outer.s"), alias), capture.snapshot.resolvedIncludes().keys)
        assertEquals(capture.snapshot.includeIdentity(main), capture.snapshot.includeIdentity(alias))
        val component = MarsSessionService.createMarsComponent()
        component.context().use {
            val result = capture.assembleIn(component)
            assertFalse(result.succeeded())
            val error = result.errors().single()
            assertEquals(outer, error.file())
            assertEquals(2, error.line())
            assertTrue(error.message().contains("Recursive include of file"))
        }
    }

    @Test
    fun `the first alias in assembly order supplies transitive includes before a duplicate is reported`() = timeoutRunBlocking<Unit> {
        val main = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject(
                "main.asm", ".include \"branch.s\"\n.include \"source/shared.s\"\n",
            ).virtualFile
            fixture.addFileToProject("branch.s", ".include \"shortcut/shared.s\"\n")
            val shared = fixture.addFileToProject("source/shared.s", ".include \"leaf.s\"\n").virtualFile
            fixture.addFileToProject("source/leaf.s", "nop\n")
            val link = Files.createSymbolicLink(entry.toNioPath().resolveSibling("shortcut"), shared.toNioPath().parent)
            checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(link))
            entry.toNioPath()
        }
        val capture = captureAssemblySources(listOf(main), null)
        assertTrue(main.resolveSibling("shortcut/leaf.s") in capture.snapshot.texts())
        assertFalse(main.resolveSibling("source/leaf.s") in capture.snapshot.texts())
        val component = MarsSessionService.createMarsComponent()
        component.context().use {
            val error = capture.assembleIn(component).errors().single()
            assertEquals(main, error.file())
            assertEquals(2, error.line())
            assertTrue(error.message().contains("Recursive include of file"))
        }
    }

    @Test
    fun `separately selected directory aliases retain each entry's unsaved source and include set`() = timeoutRunBlocking<Unit> {
        val (main, alias) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("source/main.asm", ".include \"saved.s\"\n").virtualFile
            fixture.addFileToProject("source/saved.s", "addiu \$t0, \$zero, 7\n")
            fixture.addFileToProject("source/edited.s", "addiu \$t1, \$zero, 2\n")
            val link = Files.createSymbolicLink(entry.toNioPath().parent.resolveSibling("shortcut"), entry.toNioPath().parent)
            val shortcut = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(link))
            val aliasFile = checkNotNull(shortcut.findChild("main.asm"))
            edit(aliasFile, ".include \"edited.s\"\n")
            entry.toNioPath() to aliasFile.toNioPath()
        }
        val capture = captureAssemblySources(listOf(main, alias), null)
        assertEquals(listOf(main, alias), capture.snapshot.entries())
        assertEquals(".include \"saved.s\"\n", capture.snapshot.texts()[main])
        assertEquals(".include \"edited.s\"\n", capture.snapshot.texts()[alias])
        assertEquals(capture.snapshot.includeIdentity(main), capture.snapshot.includeIdentity(alias))
        val component = MarsSessionService.createMarsComponent()
        component.context().use { session ->
            val result = capture.assembleIn(component)
            assertTrue(result.succeeded(), result.errors().toString())
            assertEquals(listOf(0x24080007, 0x24090002), session.program.machineList.map { it.binaryStatement })
        }
    }

    @Test
    fun `captures only reachable files and leaves include failures for assembler diagnostics`() = timeoutRunBlocking<Unit> {
        val main = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("ignored.txt", "nop\n")
            fixture.addFileToProject("parts/filler.txt", "nop\n")
            fixture.addFileToProject("cycle.s", ".include \"./main.asm\"\n")
            val entry = fixture.addFileToProject("main.asm", "nop\n").virtualFile
            edit(entry, """
                # .include "ignored.txt"
                .include "absent.txt"
                .include "parts"
                .include "invalid${'\u0000'}name"
                .include "cycle.s"
            """.trimIndent() + "\n")
            entry.toNioPath()
        }
        val capture = captureAssemblySources(listOf(main), null)
        assertEquals(setOf(main, main.resolveSibling("cycle.s")), capture.snapshot.texts().keys)
        assertEquals(capture.snapshot.texts().keys, capture.revisions.keys)
    }

    private fun edit(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project.get()) { document.setText(text) }
    }
}
