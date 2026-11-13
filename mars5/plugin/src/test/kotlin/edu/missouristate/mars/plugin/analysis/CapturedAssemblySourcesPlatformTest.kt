package edu.missouristate.mars.plugin.analysis

import com.intellij.build.events.FileMessageEvent
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.limits.FileSizeLimit
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.containers.MultiMap
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.diagnosticEvent
import edu.missouristate.mars.plugin.language.mipsAssemblySourceOrigin
import edu.missouristate.mars.plugin.language.MipsDocumentationTargetProvider
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsSymbolRenameProcessor
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
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
    fun `captures uncommitted entry and transitive include documents before assembly`() = timeoutRunBlocking {
        val include = if (SystemInfoRt.isWindows) "/parts/outer.txt" else "./parts/outer.txt"
        val mainText = $$".include \"$$include\"\nli $t0, VALUE\n"
        val innerText = ".eqv VALUE 42\n"
        val (main, outer, inner, handler) = withContext(Dispatchers.EDT) {
            val leaf = fixture.addFileToProject("parts/with spaces#é.txt", ".eqv VALUE 1\n").virtualFile
            val entry = fixture.addFileToProject("main.asm", "nop\n").virtualFile
            val intermediate = fixture.addFileToProject("parts/outer.txt", ".include \"${leaf.path}\"\n").virtualFile
            val start = fixture.addFileToProject("startup.s", $$"addiu $s0, $zero, 17\n").virtualFile
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
        withContext(Dispatchers.IO) { assertEquals("nop\n", Files.readString(entry)) }
        withContext(Dispatchers.IO) { assertEquals(".eqv VALUE 1\n", Files.readString(leaf)) }

        withContext(Dispatchers.EDT) { edit(inner, ".eqv VALUE 99\n") }
        val newer = captureAssemblySources(listOf(entry), handlerPath)
        assertEquals(".eqv VALUE 99\n", newer.snapshot.texts()[leaf])
        assertEquals(innerText, capture.snapshot.texts()[leaf])
        assertFalse(capture.revisions[leaf] == newer.revisions[leaf])

        val context = project.get().getService(MarsSessionService::class.java).context
        val program = context.assembleSnapshot(capture.snapshot, capture.exceptionHandler, extended = true, warnings = false)
        assertEquals(2, program.instructionCount())
        assertTrue(program.simulate(5))
        assertEquals(42, context.registerFile.getValue(8))
        assertEquals(17, context.registerFile.getValue(16))
    }

    @Test
    fun `binary-classified include uses disk source text and invalidates after a VFS refresh`() = timeoutRunBlocking {
        val (main, include) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("entry.mars", ".include \"payload.png\"\nemit\nj destination\nj raw_target\n") as MipsFile
            fixture.addFileToProject("tail.inc", "destination: nop\n")
            val path = main.virtualFile.toNioPath().resolveSibling("payload.png")
            val text =
              $$".include \"tail.inc\"\r\nraw_target: j destination\r\n.macro emit\r\naddiu $t0, $zero, 42\r\n.end_macro\r\n"
            Files.write(path, byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE))
            val include = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path))
            main to include
        }
        val entry = main.virtualFile.toNioPath()
        val path = include.toNioPath()
        assertTrue(include.fileType.isBinary)

        val initial = readAction { checkNotNull(collectEditorAssembly(main)) }
        val sources = initial.sources
        assertEquals(setOf(path), sources.diskSources)
        assertEquals(setOf(entry, path, path.resolveSibling("tail.inc")), sources.snapshot.texts().keys)
        assertEquals($$".include \"tail.inc\"\nraw_target: j destination\n.macro emit\naddiu $t0, $zero, 42\n.end_macro\n", sources.snapshot.texts()[path])

        val component = MarsSessionService.createMarsComponent()
        component.context().use {
            val disk = component.assembler().assemble(listOf(entry))
            assertTrue(disk.succeeded(), disk.errors().toString())
            val diskWords = it.program!!.machineList!!.map { statement -> statement.binaryStatement }
            val captured = sources.assembleIn(component)
            assertTrue(captured.succeeded(), captured.errors().toString())
            assertEquals(diskWords, it.program!!.machineList!!.map { statement -> statement.binaryStatement })
            assertEquals(0x2408002a, diskWords[2])
            val analysis = project.get().service<MarsAnalysisService>()
            analysis.publishBuiltSymbols(analysis.beginSymbolBuild(entry), sources, captured.program()!!.symbolMap())
        }

        val selected = readAction { checkNotNull(collectEditorAssembly(main)) }
        val execution = MarsExecutionSources(sources)
        val origin = Location(path, 4)
        readAction {
            assertTrue(selected.validate(main) { _, document, revision -> document.modificationStamp == revision })
            assertTrue(execution.isCurrent())
            val position = execution.position(origin)
            assertFalse(position.file.isWritable)
            assertNotEquals(include, position.file)
            assertEquals(origin, execution.location(position.file, position.line))
            assertEquals(include to origin, execution.originalLine(position.file, position.line))
            val originView = checkNotNull(execution.currentLineDescriptor(project.get(), origin)).file
            assertEquals(include, originView.mipsAssemblySourceOrigin)
            assertFalse(originView.isWritable)
            assertEquals(main.virtualFile, execution.currentLineDescriptor(project.get(), Location(entry, 1))?.file)
            val macro = checkNotNull(main.findReferenceAt(main.text.indexOf("\nemit") + 1)?.resolve())
            assertEquals("emit", macro.text)
            assertFalse(macro.containingFile.virtualFile.isWritable)
            val diskLabel = checkNotNull(main.findReferenceAt(main.text.indexOf("raw_target"))?.resolve())
            assertEquals("raw_target", (diskLabel as MipsDeclaration).name)
            assertFalse(diskLabel.containingFile.virtualFile.isWritable)
            val targetFile = PsiManager.getInstance(project.get()).findFile(include.parent.findChild("tail.inc")!!)
            val target = checkNotNull(MipsDeclaration.findAt(targetFile, 0, "destination"))
            val analysis = project.get().service<MarsAnalysisService>()
            assertNull(analysis.sourceTokenPosition(SourceToken(Location(path, 2), 3)))
            val (excerpt, offset) = analysis.assembledExcerptUsages(target, GlobalSearchScope.allScope(project.get())).single()
            assertTrue(excerpt.text.startsWith("destination", offset))
            assertFalse(excerpt.virtualFile.isWritable)
            val diagnostic = diagnosticEvent(project.get(), Any(), AssemblyError(path, 4, 1, "Test warning", true),
                sources)
            assertInstanceOf(FileMessageEvent::class.java, diagnostic)
            assertEquals("Test warning", diagnostic.message)
        }

        withContext(Dispatchers.EDT) {
            val targetFile = PsiManager.getInstance(project.get()).findFile(include.parent.findChild("tail.inc")!!) as MipsFile
            val target = checkNotNull(MipsDeclaration.findAt(targetFile, 0, "destination"))
            val processor = MipsSymbolRenameProcessor()
            processor.findCollisions(target, "renamed", mapOf(target to "renamed"), mutableListOf())
            val conflicts = MultiMap.create<PsiElement, String>()
            processor.findExistingNameConflicts(target, "renamed", conflicts)
            assertTrue(conflicts[target].single().contains("written declaration"))
        }

        val lifetime = Disposer.newDisposable()
        val plain = try {
            withContext(Dispatchers.EDT) {
                CodeInsightTestFixtureImpl.associateExtensionTemporarily(PlainTextFileType.INSTANCE, "png", lifetime)
            }
            readAction {
                assertFalse(execution.isCurrent())
                assertNull(execution.currentLineDescriptor(project.get(), origin))
                assertFalse(execution.position(origin).file.isWritable)
                assertFalse(sources.validateSourceDocuments { _, _, _ -> true })
                val plainCapture = checkNotNull(collectEditorAssembly(main)).sources
                assertFalse(path in plainCapture.diskSources)
                assertTrue(path in plainCapture.revisions)
                plainCapture
            }
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
        readAction {
            assertFalse(plain.validateSourceDocuments { _, _, _ -> true })
            val formerText = MarsExecutionSources(plain)
            assertFalse(formerText.isCurrent())
            assertNull(formerText.currentLineDescriptor(project.get(), origin))
        }

        withContext(Dispatchers.EDT) {
            Files.writeString(path, $$".include \"tail.inc\"\n.macro emit\naddiu $t0, $zero, 17\n.end_macro\n")
            include.refresh(false, false)
        }
        readAction {
            assertFalse(selected.validate(main) { _, document, revision -> document.modificationStamp == revision })
            assertFalse(execution.isCurrent())
            val updated = checkNotNull(collectEditorAssembly(main))
            assertTrue(updated.sources.snapshot.texts().getValue(path).contains("17"))
            assertNotEquals(sources.revisions[path], updated.sources.revisions[path])
        }
    }

    @Test
    fun `oversized binary include keeps assembled macro and label navigation`() = timeoutRunBlocking {
        val (main, include) = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("entry.mars", ".include \"large.png\"\nemit\nj target\n") as MipsFile
            val path = file.virtualFile.toNioPath().resolveSibling("large.png")
            val comment = "#" + " ".repeat(16_382) + "\n"
            Files.newBufferedWriter(path).use { writer ->
                writer.write(".macro emit\nnop\n.end_macro\ntarget: nop\n")
                repeat(FileSizeLimit.getContentLoadLimit("png") / comment.length + 1) { writer.write(comment) }
            }
            file to checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path))
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val capture = readAction {
            assertTrue(mipsSourceTimeline(main).hasIncompleteIncludes)
            checkNotNull(collectEditorAssembly(main))
        }
        val component = MarsSessionService.createMarsComponent()
        component.context().use {
            val result = capture.sources.assembleIn(component)
            assertTrue(result.succeeded(), result.errors().toString())
            val analysis = project.get().service<MarsAnalysisService>()
            analysis.publishBuiltSymbols(analysis.beginSymbolBuild(capture.source), capture.sources, result.program()!!.symbolMap())
        }
        readAction {
            val sources = MarsExecutionSources(capture.sources)
            val location = Location(include.toNioPath(), 2)
            val line = checkNotNull(sources.currentLineDescriptor(project.get(), location))
            assertFalse(line.file.isWritable)
            assertTrue(line.file.name.startsWith("large.png"))
            assertSame(line.file, sources.hyperlink(project.get(), location).descriptor?.file)
            val diagnostic = diagnosticEvent(project.get(), Any(),
                AssemblyError(location.file(), location.line(), 1, "Test warning", true), capture.sources, sources)
            val link = assertInstanceOf(OpenFileDescriptor::class.java, diagnostic.getNavigatable(project.get()))
            assertSame(line.file, link.file)
            for (name in listOf("emit", "target")) {
                val target = checkNotNull(main.findReferenceAt(main.text.indexOf(name))?.resolve())
                assertEquals(name, target.text)
                val file = target.containingFile.virtualFile
                assertNotEquals(include, file)
                assertFalse(file.isWritable)
            }
        }
    }

    @Test
    fun `disk include removed before reading is reported at its include site`() = timeoutRunBlocking {
        val (main, include) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("entry.mars", ".include \"gone.png\"\n")
            val path = main.virtualFile.toNioPath().resolveSibling("gone.png")
            Files.writeString(path, "nop\n")
            main.virtualFile.toNioPath() to checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path))
        }
        val unavailable = readAction {
            collectAssemblySources(listOf(main), null) { dependency ->
                if (dependency == include) {
                    val source = PsiManager.getInstance(project.get()).findFile(include)
                    Files.deleteIfExists(include.toNioPath())
                    assertNull(assemblySourceSyntax(checkNotNull(source)))
                }
            }
        }
        assertEquals(setOf(main), unavailable.snapshot.texts().keys)
        val directory = main.parent
        assertTrue(captureAssemblySources(listOf(directory), null).snapshot.texts().isEmpty())
        val component = MarsSessionService.createMarsComponent()
        component.context().use {
            val diagnostic = unavailable.assembleIn(component).errors().single()
            assertEquals(main, diagnostic.file)
            assertEquals(1, diagnostic.line)
        }
    }

    @Test
    fun `assembly follows includes and source locations beyond the IntelliJ file preview`() = timeoutRunBlocking {
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
                    writer.write($$".include \"tail.s\"\r\n\taddiu $t0, $zero, 7\r\n")
                    writer.write("#" + " ".repeat(32_768) + "\r\n")
                    writer.write($$"\taddiu $t1, $zero, 8\r\n\ttarget: nop\r\n.macro emit # Macro documentation\r\nnop\r\n.end_macro\r\n")
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
        assertTrue(capture.snapshot.texts().getValue(largePath).contains($$"\taddiu $t1, $zero, 8\n\ttarget: nop\n"))
        assertTrue(largePath.resolveSibling("tail.s") in capture.snapshot.texts())
        val component = MarsSessionService.createMarsComponent()
        component.context().use { session ->
            val result = capture.assembleIn(component)
            assertTrue(result.succeeded(), result.errors().toString())
            val statements = session.program!!.machineList!!
            assertEquals(listOf(0, 0x24080007, 0x24090008, 0, 0, 0, 0), statements.dropLast(2).map { it.binaryStatement })
            val location = Location(largePath, instructionLine)
            assertEquals(location, result.program()!!.sourceMap().origins()[statements[1].address]?.location)
            val ide = project.get()
            val analysis = ide.service<MarsAnalysisService>()
            analysis.publishBuiltSymbols(analysis.beginSymbolBuild(entry), capture, result.program()!!.symbolMap())
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
                assertTrue(excerpt.text.startsWith($$"\taddiu $t0, $zero, 7\n", lineStart))
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
                assertTrue(nextExcerpt.text.startsWith($$"\taddiu $t1, $zero, 8\n", nextExcerpt.getLineStartOffset(next.line)))
                wideLink.file
            }
            withContext(Dispatchers.EDT) {
                fixture.openFileInEditor(croppedMacro)
                assertFalse(fixture.doHighlighting().any { it.severity == HighlightSeverity.ERROR })
            }
            val definition = result.program()!!.symbolMap().locals().getValue(entry).getValue("target")
            val wide = result.program()!!.symbolMap().locals().getValue(entry).getValue("farTarget")
            val withoutColumn = Definition(wide.address, wide.kind, wide.origin)
            val badColumn = Definition(definition.address, definition.kind, definition.origin, definition.macroPrivate, 100)
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
    fun `editor macro scope refreshes when an oversized include becomes fully readable`() = timeoutRunBlocking {
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
    fun `a directory alias back to the entry closes the cycle without scanning the entry again`() = timeoutRunBlocking {
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
        assertEquals(setOf(main.resolveSibling("../parts/outer.s"), alias), capture.snapshot.resolvedIncludes()!!.keys)
        assertEquals(capture.snapshot.includeIdentity(main), capture.snapshot.includeIdentity(alias))
        val component = MarsSessionService.createMarsComponent()
        component.context().use {
            val result = capture.assembleIn(component)
            assertFalse(result.succeeded())
            val error = result.errors().single()
            assertEquals(outer, error.file)
            assertEquals(2, error.line)
            assertTrue(error.message!!.contains("Recursive include of file"))
        }
    }

    @Test
    fun `the first alias in assembly order supplies transitive includes before a duplicate is reported`() = timeoutRunBlocking {
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
            assertEquals(main, error.file)
            assertEquals(2, error.line)
            assertTrue(error.message!!.contains("Recursive include of file"))
        }
    }

    @Test
    fun `separately selected directory aliases retain each entry's unsaved source and include set`() = timeoutRunBlocking {
        val (main, alias) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("source/main.asm", ".include \"saved.s\"\n").virtualFile
            fixture.addFileToProject("source/saved.s", $$"addiu $t0, $zero, 7\n")
            fixture.addFileToProject("source/edited.s", $$"addiu $t1, $zero, 2\n")
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
            assertEquals(listOf(0x24080007, 0x24090002), session.program!!.machineList!!.map { it.binaryStatement })
        }
    }

    @Test
    fun `captures only reachable files and leaves include failures for assembler diagnostics`() = timeoutRunBlocking {
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

    // Kotlin exposes the reported Java setters as read-only properties.
    @Suppress("UsePropertyAccessSyntax")
    private fun edit(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project.get()) { document.setText(text) }
    }
}
