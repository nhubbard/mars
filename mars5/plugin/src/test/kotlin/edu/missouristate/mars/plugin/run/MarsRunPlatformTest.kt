package edu.missouristate.mars.plugin.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.PsiLocation
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.pollAssertionsAsync
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.UiInterceptors
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.plugin.build.MarsMemoryExportDialog
import edu.missouristate.mars.plugin.build.MemoryExportRegion
import edu.missouristate.mars.plugin.build.interceptMemoryExportDialog
import edu.missouristate.mars.plugin.build.replaceMemoryExportSaveDialog
import edu.missouristate.mars.plugin.language.MipsFileType
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JTextField
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsRunPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val type get() = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java)
    private val executor get() = DefaultRunExecutor.getRunExecutorInstance()
    private var runCount = 0

    @Test
    fun `native Run uses the unsaved include and preserves exit status`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        val recording = Recording()
        try {
            val include = withContext(Dispatchers.EDT) {
                val source = fixture.addFileToProject("main.asm", """
                    .include "value.s"
                    li ${'$'}a0, VALUE
                    li ${'$'}v0, 1
                    syscall
                    li ${'$'}a0, 9
                    li ${'$'}v0, 17
                    syscall
                """.trimIndent())
                val include = fixture.addFileToProject("value.s", ".eqv VALUE 1\n").virtualFile
                val document = FileDocumentManager.getInstance().getDocument(include)!!
                WriteCommandAction.runWriteCommandAction(project) { document.setText(".eqv VALUE 42\n") }

                val context = ConfigurationContext.createEmptyContextForLocation(PsiLocation.fromPsiElement(source))
                val producer = RunConfigurationProducer.getInstance(MarsRunConfigurationProducer::class.java)
                val config = assertInstanceOf(MarsRunConfiguration::class.java, producer.createConfigurationFromContext(context)?.configuration)
                assertEquals("main", config.name)
                assertTrue(producer.isConfigurationFromContext(config, context))
                val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
                val runner = assertInstanceOf(MarsRunRunner::class.java, environment.runner)
                project.messageBus.connect(disposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                        if (env === environment) handler.addProcessListener(recording)
                    }
                })
                runner.execute(environment)
                include.toNioPath()
            }
            assertEquals(9, recording.exit.await())
            assertEquals("42", recording.stdout.toString())
            assertEquals("", recording.stderr.toString())
            assertEquals(".eqv VALUE 1\n", Files.readString(include))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `native Run applies delay slots only to the configuration that enables them`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("delay.asm", """
                li ${'$'}a0, 1
                b print_value
                addiu ${'$'}a0, ${'$'}a0, 2
                print_value: li ${'$'}v0, 1
                syscall
            """.trimIndent()).virtualFile
        }
        for ((enabled, expected) in listOf(true to "3", false to "1")) {
            val disposable = Disposer.newDisposable()
            val recording = Recording()
            val process = CompletableDeferred<MarsProcessHandler>()
            try {
                withContext(Dispatchers.EDT) {
                    val config = type.createTemplateConfiguration(project)
                    config.options.sourcePath = file.path
                    config.options.delayedBranching = enabled
                    val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
                    project.messageBus.connect(disposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                        override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                            if (env === environment) {
                                handler.addProcessListener(recording)
                                process.complete(handler as MarsProcessHandler)
                            }
                        }
                    })
                    environment.runner.execute(environment)
                }
                assertEquals(0, recording.exit.await())
                assertEquals(expected, recording.stdout.toString())
                val alu = if (enabled) 3L else 2L
                assertEquals(listOf(alu, 0L, 1L, 0L, 1L), process.await().instructionStatistics.countsSnapshot().counts)
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    @Test
    fun `configuration serializes a source path and rejects invalid or unsafe launches`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val source = fixture.addFileToProject("entry.s", "nop\n")
            val config = type.createTemplateConfiguration(project)
            assertEquals("MarsMipsRun", type.id)
            val runner = ProgramRunner.findRunnerById("MarsMipsRunner")!!
            assertFalse(runner.canRun("Debug", config))
            assertFalse(runner.canRun(executor.id, unrelatedProfile()))
            for (invalid in listOf(null, "relative.s", "\u0000", source.virtualFile.toNioPath().root.toString())) {
                config.options.sourcePath = invalid
                assertThrows<RuntimeConfigurationError> { config.checkConfiguration() }
            }
            config.options.sourcePath = source.virtualFile.path
            config.options.delayedBranching = true
            config.options.selfModifyingCode = true
            config.options.captureBitmap = true
            config.options.captureReferences = true
            config.options.connectDigitalLab = true
            config.checkConfiguration()
            val state = Element("configuration")
            config.writeExternal(state)
            val restored = type.createTemplateConfiguration(project)
            restored.readExternal(state)
            assertEquals(config.options.sourcePath, restored.options.sourcePath)
            assertTrue(restored.options.delayedBranching)
            assertTrue(restored.options.selfModifyingCode)
            assertTrue(restored.options.captureBitmap)
            assertTrue(restored.options.captureReferences)
            assertTrue(restored.options.connectDigitalLab)
            val previousConfiguration = type.createTemplateConfiguration(project)
            previousConfiguration.readExternal(Element("configuration"))
            assertFalse(previousConfiguration.options.delayedBranching)
            assertFalse(previousConfiguration.options.selfModifyingCode)
            assertFalse(previousConfiguration.options.captureBitmap)
            assertFalse(previousConfiguration.options.captureReferences)
            assertFalse(previousConfiguration.options.connectDigitalLab)
            val editor = restored.configurationEditor
            try {
                editor.resetFrom(type.createTemplateConfiguration(project))
                assertEquals("", UIUtil.findComponentOfType(editor.component, JTextField::class.java)!!.text)
                val flags = UIUtil.findComponentsOfType(editor.component, JCheckBox::class.java).associateBy { it.text }
                val delay = flags.getValue("Enable delayed branching")
                val modify = flags.getValue("Allow self-modifying code")
                val capture = flags.getValue("Capture Bitmap Display from launch")
                val references = flags.getValue("Capture memory references from launch")
                val digitalLab = flags.getValue("Connect Digital Lab Sim from launch")
                assertFalse(delay.isSelected)
                assertFalse(modify.isSelected)
                assertFalse(capture.isSelected)
                assertFalse(references.isSelected)
                assertFalse(digitalLab.isSelected)
                editor.resetFrom(restored)
                assertTrue(delay.isSelected)
                assertTrue(modify.isSelected)
                assertTrue(capture.isSelected)
                assertTrue(references.isSelected)
                assertTrue(digitalLab.isSelected)
                val field = UIUtil.findComponentOfType(editor.component, JTextField::class.java)!!
                assertEquals(source.virtualFile.path, field.text)
                field.text = "  ${source.virtualFile.path}  "
                modify.isSelected = false
                capture.isSelected = false
                references.isSelected = false
                digitalLab.isSelected = false
                editor.applyTo(restored)
                assertEquals(source.virtualFile.path, restored.options.sourcePath)
                assertTrue(restored.options.delayedBranching)
                assertFalse(restored.options.selfModifyingCode)
                assertFalse(restored.options.captureBitmap)
                assertFalse(restored.options.captureReferences)
                assertFalse(restored.options.connectDigitalLab)
            } finally {
                Disposer.dispose(editor)
            }
            val environment = ExecutionEnvironmentBuilder.create(executor, restored).build()
            restored.options.sourcePath = null
            assertThrows<ExecutionException> { restored.getState(executor, environment) }
            restored.options.sourcePath = source.virtualFile.path

            val trustProperty = TrustedProjects.TRUST_HEADLESS_DISABLED_PROPERTY
            val previous = System.getProperty(trustProperty)
            System.setProperty(trustProperty, "false")
            try {
                TrustedProjects.setProjectTrusted(project, false)
                assertThrows<ExecutionException> { restored.getState(executor, environment) }
            } finally {
                TrustedProjects.setProjectTrusted(project, true)
                if (previous == null) System.clearProperty(trustProperty) else System.setProperty(trustProperty, previous)
            }

            val producer = RunConfigurationProducer.getInstance(MarsRunConfigurationProducer::class.java)
            val other = fixture.addFileToProject("other.txt", "text")
            val light = PsiFileFactory.getInstance(project).createFileFromText("light.s", MipsFileType, "nop")
            val nonLocal = PsiManager.getInstance(project).findFile(LightVirtualFile("non-local.s", MipsFileType, "nop"))!!
            for (nonSource in listOf(other, light, nonLocal)) {
                val context = ConfigurationContext.createEmptyContextForLocation(PsiLocation.fromPsiElement(nonSource))
                assertNull(producer.createConfigurationFromContext(context))
                assertFalse(producer.isConfigurationFromContext(restored, context))
            }
            val emptyContext = ConfigurationContext.getFromContext(DataContext.EMPTY_CONTEXT, ActionPlaces.UNKNOWN)
            assertFalse(producer.isConfigurationFromContext(restored, emptyContext))
            val validContext = ConfigurationContext.createEmptyContextForLocation(PsiLocation.fromPsiElement(source))
            restored.options.sourcePath = other.virtualFile.path
            assertFalse(producer.isConfigurationFromContext(restored, validContext))
        }
    }

    @Test
    fun `native run settings add edit remove and round trip memory dump rows`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            withContext(Dispatchers.EDT) {
                val source = fixture.addFileToProject("dump-settings.s", "nop").virtualFile
                val config = type.createTemplateConfiguration(project)
                val editor = config.configurationEditor
                val disposable = Disposer.newDisposable()
                try {
                    editor.resetFrom(config)
                    val form = editor.component
                    val list = UIUtil.findComponentOfType(form, JList::class.java)!!
                    val add = ToolbarDecorator.findAddButton(form)!!
                    val edit = ToolbarDecorator.findEditButton(form)!!
                    val remove = ToolbarDecorator.findRemoveButton(form)!!
                    fun addRow() = add.actionPerformed(TestActionEvent.createTestEvent(add))
                    fun editRow() = edit.actionPerformed(TestActionEvent.createTestEvent(edit))
                    val first = directory.resolve("first.hex")
                    val second = directory.resolve("second.bin")
                    val edited = directory.resolve("edited.bin")
                    val saves = ArrayDeque(listOf(first, edited, second))
                    val suggestions = mutableListOf<Pair<Path?, String?>>()
                    replaceMemoryExportSaveDialog(disposable) { _, base, name ->
                        suggestions.add(base to name)
                        saves.removeFirst()
                    }
                    editRow()
                    interceptMemoryExportDialog { it.doCancelAction() }
                    addRow()
                    assertEquals(0, list.model.size)

                    interceptMemoryExportDialog { dialog ->
                        setDumpDialog(dialog, MemoryExportRegion.RANGE, "HEX", "0x10010000", "0x10010004")
                    }
                    addRow()
                    assertEquals(1, list.model.size)
                    assertEquals(0, list.selectedIndex)
                    interceptMemoryExportDialog { dialog ->
                        val (region, format) = UIUtil.findComponentsOfType(dialog.fields, JComboBox::class.java)
                        assertEquals(MemoryExportRegion.RANGE, region.selectedItem)
                        assertSame(DumpFormatLoader.findByCommandDescriptor("HEX"), format.selectedItem)
                        val (start, end) = UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java)
                        assertEquals("0x10010000", start.text)
                        assertEquals("0x10010004", end.text)
                        setDumpDialog(dialog, MemoryExportRegion.DATA, "Binary")
                    }
                    editRow()
                    interceptMemoryExportDialog { dialog ->
                        val region = UIUtil.findComponentsOfType(dialog.fields, JComboBox::class.java).first()
                        assertEquals(MemoryExportRegion.DATA, region.selectedItem)
                        dialog.doCancelAction()
                    }
                    editRow()
                    UIUtil.findComponentOfType(form, JTextField::class.java)!!.text = source.path
                    interceptMemoryExportDialog { dialog -> setDumpDialog(dialog, MemoryExportRegion.TEXT, "Binary") }
                    addRow()
                    assertEquals(listOf(null to "memory", directory to "first.hex", source.toNioPath().parent to "dump-settings-memory"), suggestions)
                    assertEquals(2, list.model.size)
                    editor.applyTo(config)
                    config.checkConfiguration()
                    val xml = Element("configuration")
                    config.writeExternal(xml)
                    val restored = type.createTemplateConfiguration(project).also { it.readExternal(xml) }
                    assertEquals(listOf(edited, second), restored.options.memoryDumps.map { it.resolve(project)?.output })
                    assertEquals(listOf(MemoryExportRegion.DATA, MemoryExportRegion.TEXT), restored.options.memoryDumps.map { it.region })
                    assertEquals(listOf("Binary", "Binary"), restored.options.memoryDumps.map { it.formatName })
                    editor.resetFrom(restored)
                    list.selectedIndex = 0
                    remove.actionPerformed(TestActionEvent.createTestEvent(remove))
                    editor.applyTo(restored)
                    assertEquals(listOf(second), restored.options.memoryDumps.map { it.resolve(project)?.output })
                    assertEquals(2, config.options.memoryDumps.size)
                    restored.options.memoryDumps.add(RunMemoryDumpState())
                    editor.resetFrom(restored)
                    assertTrue(list.preferredSize.height > 0)
                } finally {
                    Disposer.dispose(editor)
                    Disposer.dispose(disposable)
                    UiInterceptors.clear()
                }
            }
        }

    @Test
    fun `stored after-run dump settings validate formats ranges and destinations before launch`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("validation.s", "nop").virtualFile.toNioPath()
                val config = type.createTemplateConfiguration(project)
                config.options.sourcePath = file.toString()
                val output = directory.resolve("sample.hex")
                fun reject(vararg requests: RunMemoryDumpState, expected: String) {
                    config.options.memoryDumps = arrayListOf(*requests)
                    val check = assertThrows<RuntimeConfigurationError> { config.checkConfiguration() }
                    val message = check.messageHtml.toString()
                    assertTrue(message.contains(expected), message)
                    val env = ExecutionEnvironmentBuilder.create(executor, config).build()
                    val launch = assertThrows<ExecutionException> { config.getState(executor, env) }
                    assertEquals(message, launch.message)
                }
                reject(dumpState(output).apply { formatName = "Unknown" }, expected = "invalid")
                reject(dumpState(output).apply { outputPath = "relative.hex" }, expected = "invalid")
                for ((first, last) in listOf(-4L to 0L, 0x1_0000_0000L to 0x1_0000_0000L,
                    4L to 0L, 0L to 0x1_0000_0000L, 1L to 4L, 0L to 3L)) {
                    reject(dumpState(output, MemoryExportRegion.RANGE, first, last), expected = "invalid")
                }
                reject(dumpState(file), expected = "source file")
                reject(dumpState(output), dumpState(output), expected = "More than one")
                val range = dumpState(output, MemoryExportRegion.RANGE, 0x80000180L, 0x80000184L)
                config.options.memoryDumps = arrayListOf(range)
                config.checkConfiguration()
                val xml = Element("configuration")
                config.writeExternal(xml)
                val restored = type.createTemplateConfiguration(project).also { it.readExternal(xml) }
                assertEquals(0x80000180u..0x80000184u, restored.options.memoryDumps.single().resolve(project)?.options?.range)
                val old = type.createTemplateConfiguration(project).also { it.readExternal(Element("configuration")) }
                assertTrue(old.options.memoryDumps.isEmpty())
            }
        }

    @Test
    fun `configured dumps use final memory and independent failures preserve the program exit code`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val output = directory.resolve("saved.txt").also { Files.writeString(it, "old") }
            val binary = directory.resolve("word.bin")
            val invalidRange = directory.resolve("unchanged").also { Files.writeString(it, "keep") }
            val missingParent = directory.resolve("absent/machine")
            val (include, nestedEmpty) = withContext(Dispatchers.EDT) {
                val empty = fixture.addFileToProject("defs/empty.inc", "").virtualFile.toNioPath()
                fixture.addFileToProject("defs/outer.inc", ".include \"empty.inc\"\n")
                val definitions = fixture.addFileToProject("preserved.s", ".eqv EXIT_STATUS 9\n").virtualFile.toNioPath()
                definitions to empty
            }
            val recording = runConfiguredDump("""
                .include "preserved.s"
                .include "defs/outer.inc"
                .data
                payload: .word 7
                .text
                addiu ${'$'}t0, ${'$'}zero, 42
                sw ${'$'}t0, payload
                li ${'$'}a0, EXIT_STATUS
                li ${'$'}v0, 17
                syscall
            """.trimIndent(), listOf(
                dumpState(include),
                dumpState(nestedEmpty),
                dumpState(invalidRange, MemoryExportRegion.RANGE, 0, 0),
                dumpState(output, MemoryExportRegion.RANGE, 0x10010000, 0x10010000),
                dumpState(missingParent),
                dumpState(binary, MemoryExportRegion.RANGE, 0x10010000, 0x10010000).apply { formatName = "Binary" },
            ))
            assertEquals(9, recording.exit.await())
            assertEquals(listOf("0000002a"), Files.readAllLines(output))
            assertArrayEquals(byteArrayOf(42, 0, 0, 0), Files.readAllBytes(binary))
            assertEquals("keep", Files.readString(invalidRange))
            assertEquals("", Files.readString(nestedEmpty))
            assertEquals(".eqv EXIT_STATUS 9\n", Files.readString(include))
            val errors = recording.stderr.toString()
            assertTrue(errors.contains("program source file"), errors)
            assertTrue(errors.contains("outside the memory available"), errors)
            assertTrue(errors.contains("Could not write"), errors)
            assertTrue(recording.system.toString().contains("Exported MIPS memory to $output"))
            assertTrue(recording.system.toString().contains("Exported MIPS memory to $binary"))
        }

    @Test
    fun `post-run dumps support runtime faults and cliff termination while failed assembly and Stop preserve files`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val fault = directory.resolve("fault.txt")
            val (crashed, crashRecording) = run("break\n", listOf(dumpState(fault)))
            assertEquals(1, crashRecording.exit.await())
            assertEquals(1, crashed.exitCode)
            assertEquals(listOf("0000000d"), Files.readAllLines(fault))
            assertTrue(crashRecording.stderr.toString().contains("MARS runtime error"))

            val cliff = directory.resolve("cliff.txt")
            assertEquals(0, runConfiguredDump("nop\n", listOf(dumpState(cliff))).exit.await())
            assertEquals(listOf("00000000"), Files.readAllLines(cliff))

            val untouched = directory.resolve("untouched.txt").also { Files.writeString(it, "keep") }
            assertEquals(1, runConfiguredDump("b missing\n", listOf(dumpState(untouched))).exit.await())
            assertEquals("keep", Files.readString(untouched))
            val (running, console) = run("li \$v0, 1\nsyscall\nloop: b loop\nnop\n", listOf(dumpState(untouched)))
            console.output.await()
            withContext(Dispatchers.EDT) { running.destroyProcess() }
            assertEquals(130, console.exit.await())
            assertEquals("keep", Files.readString(untouched))

            val (_, bounded) = run("nop\nnop\n", listOf(dumpState(untouched)), limit = 1L)
            assertEquals(1, bounded.exit.await())
            assertEquals("keep", Files.readString(untouched))
        }

    private fun setDumpDialog(
        dialog: MarsMemoryExportDialog, region: MemoryExportRegion, name: String, first: String = "", last: String = "",
    ) {
        val (regionBox, formatBox) = UIUtil.findComponentsOfType(dialog.fields, JComboBox::class.java)
        regionBox.selectedItem = region
        formatBox.selectedItem = DumpFormatLoader.findByCommandDescriptor(name)
        val (start, end) = UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java)
        start.text = first
        end.text = last
        assertTrue(dialog.performValidateAll().isEmpty())
        dialog.performOKAction()
    }

    private fun dumpState(
        output: Path, selected: MemoryExportRegion = MemoryExportRegion.TEXT, first: Long = 0, last: Long = 0,
    ) = RunMemoryDumpState().apply {
        outputPath = output.toString()
        formatName = "HexText"
        region = selected
        firstAddress = first
        lastAddress = last
    }

    private suspend fun runConfiguredDump(source: String, dumps: List<RunMemoryDumpState>): Recording {
        val disposable = Disposer.newDisposable()
        val recording = Recording()
        try {
            withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("configured${++runCount}.s", source).virtualFile
                val config = type.createTemplateConfiguration(project)
                config.options.sourcePath = file.path
                config.options.memoryDumps = ArrayList(dumps)
                config.checkConfiguration()
                val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
                project.messageBus.connect(disposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                        if (env === environment) handler.addProcessListener(recording)
                    }
                })
                environment.runner.execute(environment)
            }
            recording.exit.await()
            return recording
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `run reports assembly errors runtime exceptions and ordinary cliff termination`() = timeoutRunBlocking<Unit> {
        val cases = listOf(
            Triple("b missing\n", 1, "Assembly failed"),
            Triple("break\n", 1, "MARS runtime error"),
            Triple("nop\n", 0, ""),
        )
        for ((source, status, message) in cases) {
            val (handler, recording) = run(source)
            assertEquals(status, recording.exit.await())
            if (message.isEmpty()) assertEquals("", recording.stderr.toString())
            else assertTrue(recording.stderr.toString().contains(message), recording.stderr.toString())
            assertEquals(status, handler.exitCode)
        }
    }

    @Test
    fun `relative file syscalls route raw bytes to the matching console streams`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) { fixture.addFileToProject("input.txt", "µ") }
        val (_, recording) = run("""
            .data
            path: .asciiz "input.txt"
            buffer: .space 2
            .text
            la ${'$'}a0, path
            li ${'$'}a1, 0
            li ${'$'}v0, 13
            syscall
            move ${'$'}a0, ${'$'}v0
            la ${'$'}a1, buffer
            li ${'$'}a2, 2
            li ${'$'}v0, 14
            syscall
            move ${'$'}a2, ${'$'}v0
            li ${'$'}a0, 1
            li ${'$'}v0, 15
            syscall
            li ${'$'}a0, 2
            li ${'$'}v0, 15
            syscall
            li ${'$'}v0, 10
            syscall
        """.trimIndent())
        assertEquals(0, recording.exit.await())
        assertEquals("µ", recording.stdout.toString())
        assertEquals("µ", recording.stderr.toString())
    }

    @Test
    fun `stop and detach wait for the owned simulation to stop`() = timeoutRunBlocking<Unit> {
        for (detach in listOf(false, true)) {
            val (handler, recording) = run("li \$a0, 7\nli \$v0, 1\nsyscall\nloop: b loop\nnop\n")
            recording.output.await()
            assertFalse(handler.detachIsDefault())
            withContext(Dispatchers.EDT) {
                if (detach) handler.detachProcess() else handler.destroyProcess()
            }
            assertEquals(130, recording.exit.await())
            assertEquals("7", recording.stdout.toString())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["UTF-8", "UTF-16"])
    fun `typed console input reaches the line and raw descriptor syscalls`(encoding: String) = timeoutRunBlocking<Unit> {
        val encodingManager = EncodingManager.getInstance()
        val previousEncoding = withContext(Dispatchers.EDT) {
            encodingManager.defaultCharsetName.also { encodingManager.defaultCharsetName = encoding }
        }
        try {
            checkConsoleInput(encoding)
        } finally {
            withContext(Dispatchers.EDT) { encodingManager.defaultCharsetName = previousEncoding }
        }
    }

    private suspend fun checkConsoleInput(encoding: String) {
        val (handler, recording) = run("""
            .data
            buffer: .space 4
            .text
            li ${'$'}v0, 5
            syscall
            move ${'$'}a0, ${'$'}v0
            li ${'$'}v0, 1
            syscall
            li ${'$'}v0, 12
            syscall
            move ${'$'}a0, ${'$'}v0
            li ${'$'}v0, 11
            syscall
            li ${'$'}a0, 0
            la ${'$'}a1, buffer
            li ${'$'}a2, ${if (encoding == "UTF-16") 4 else 2}
            li ${'$'}v0, 14
            syscall
            move ${'$'}t0, ${'$'}v0
            la ${'$'}t1, buffer
        print_byte:
            lbu ${'$'}a0, 0(${'$'}t1)
            li ${'$'}v0, 1
            syscall
            li ${'$'}a0, 32
            li ${'$'}v0, 11
            syscall
            addiu ${'$'}t1, ${'$'}t1, 1
            addiu ${'$'}t0, ${'$'}t0, -1
            bgtz ${'$'}t0, print_byte
            nop
            li ${'$'}v0, 10
            syscall
        """.trimIndent())
        val console = withContext(Dispatchers.EDT) {
            val view = assertInstanceOf(ConsoleViewImpl::class.java, TextConsoleBuilderFactory.getInstance().createBuilder(project).console)
            view.attachToProcess(handler)
            view.component
            view
        }
        try {
            withContext(Dispatchers.EDT) { console.type(console.editor!!, "42\né\nR\n") }
            assertEquals(0, recording.exit.await())
            val expectedBytes = if (encoding == "UTF-16") "0 82 0 10 " else "82 10 "
            assertEquals("42é$expectedBytes", recording.stdout.toString())
            assertEquals("", recording.stderr.toString())
        } finally {
            withContext(Dispatchers.EDT) {
                handler.destroyProcess()
                Disposer.dispose(console)
            }
        }
    }

    @Test
    fun `stop releases a syscall blocked on console input`() = timeoutRunBlocking<Unit> {
        val (handler, recording) = run("li \$v0, 5\nsyscall\n")
        try {
            pollAssertionsAsync(5.seconds, 10.milliseconds) {
                // The prompt alone would race the next instruction. Wait until the simulator enters the read.
                assertTrue(Thread.getAllStackTraces().any { (thread, trace) ->
                    thread.name == "mars-simulator" && trace.any {
                        it.className == SessionSyscallEnvironment::class.java.name && it.methodName == "readLine"
                    }
                }, "simulator is waiting for console input")
            }
            withContext(Dispatchers.EDT) { handler.destroyProcess() }
            assertEquals(130, recording.exit.await())
            assertEquals("", recording.stderr.toString())
        } finally {
            withContext(Dispatchers.EDT) { handler.destroyProcess() }
        }
    }

    @Test
    fun `instruction counter records repeat executions and retains counts across backstep`() = timeoutRunBlocking<Unit> {
        val entry = withContext(Dispatchers.EDT) { fixture.addFileToProject("loop.asm", "again: j again\n").virtualFile }
        val handler = project.getService(MarsRunService::class.java).createProcess(entry.toNioPath(), memoryDumps = emptyList()) {
            val simulator = it.simulator()
            runSlice(simulator, 2)
            backstepSlice(simulator, wholeSource = false)
            runSlice(simulator, 1)
        }
        val recording = Recording()
        handler.addProcessListener(recording)
        withContext(Dispatchers.EDT) { handler.startNotify() }
        assertEquals(1, recording.exit.await())
        assertEquals(3, handler.instructionCounter.snapshot().total)
        assertEquals(3, handler.instructionCounter.snapshot().j)
        assertEquals(listOf(0L, 3L, 0L, 0L, 0L), handler.instructionStatistics.countsSnapshot().counts)
        assertEquals("", recording.stderr.toString())
    }

    @Test
    fun `console output preserves split UTF-8 writes`() {
        val text = StringBuilder()
        ConsoleOutput(text::append).use { stream ->
            val bytes = "é💡".toByteArray()
            for (byte in bytes) {
                stream.write(byte.toInt())
                stream.flush()
            }
            stream.write(0xc3)
        }
        assertEquals("é💡�", text.toString())
    }

    private fun unrelatedProfile() = object : RunProfile {
        override fun getName() = "Other"
        override fun getIcon() = null
        override fun getState(executor: Executor, environment: ExecutionEnvironment) = null
    }

    private suspend fun run(
        source: String, dumps: List<RunMemoryDumpState> = emptyList(), limit: Long = 0L,
    ): Pair<MarsProcessHandler, Recording> {
        val file = withContext(Dispatchers.EDT) { fixture.addFileToProject("run${++runCount}.asm", source).virtualFile }
        val handler = project.getService(MarsRunService::class.java).createProcess(
            file.toNioPath(), memoryDumps = dumps.map { requireNotNull(it.resolve(project)) },
        ) { runSlice(it.simulator(), limit) }
        val recording = Recording()
        handler.addProcessListener(recording)
        withContext(Dispatchers.EDT) { handler.startNotify() }
        return handler to recording
    }

    private class Recording : ProcessListener {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val system = StringBuilder()
        val output = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Int>()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (outputType === ProcessOutputTypes.STDOUT) {
                stdout.append(event.text)
                output.complete(Unit)
            } else if (outputType === ProcessOutputTypes.STDERR) {
                stderr.append(event.text)
            } else if (outputType === ProcessOutputTypes.SYSTEM) {
                system.append(event.text)
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            exit.complete(event.exitCode)
        }
    }
}
