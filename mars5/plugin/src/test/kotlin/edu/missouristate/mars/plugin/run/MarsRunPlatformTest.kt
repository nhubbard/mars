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
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.pollAssertionsAsync
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.plugin.language.MipsFileType
import java.nio.file.Files
import javax.swing.JCheckBox
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
            try {
                withContext(Dispatchers.EDT) {
                    val config = type.createTemplateConfiguration(project)
                    config.options.sourcePath = file.path
                    config.options.delayedBranching = enabled
                    val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
                    project.messageBus.connect(disposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                        override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                            if (env === environment) handler.addProcessListener(recording)
                        }
                    })
                    environment.runner.execute(environment)
                }
                assertEquals(0, recording.exit.await())
                assertEquals(expected, recording.stdout.toString())
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
            config.checkConfiguration()
            val state = Element("configuration")
            config.writeExternal(state)
            val restored = type.createTemplateConfiguration(project)
            restored.readExternal(state)
            assertEquals(config.options.sourcePath, restored.options.sourcePath)
            assertTrue(restored.options.delayedBranching)
            assertTrue(restored.options.selfModifyingCode)
            val previousConfiguration = type.createTemplateConfiguration(project)
            previousConfiguration.readExternal(Element("configuration"))
            assertFalse(previousConfiguration.options.delayedBranching)
            assertFalse(previousConfiguration.options.selfModifyingCode)
            val editor = restored.configurationEditor
            try {
                editor.resetFrom(type.createTemplateConfiguration(project))
                assertEquals("", UIUtil.findComponentOfType(editor.component, JTextField::class.java)!!.text)
                val flags = UIUtil.findComponentsOfType(editor.component, JCheckBox::class.java).associateBy { it.text }
                val delay = flags.getValue("Enable delayed branching")
                val modify = flags.getValue("Allow self-modifying code")
                assertFalse(delay.isSelected)
                assertFalse(modify.isSelected)
                editor.resetFrom(restored)
                assertTrue(delay.isSelected)
                assertTrue(modify.isSelected)
                val field = UIUtil.findComponentOfType(editor.component, JTextField::class.java)!!
                assertEquals(source.virtualFile.path, field.text)
                field.text = "  ${source.virtualFile.path}  "
                modify.isSelected = false
                editor.applyTo(restored)
                assertEquals(source.virtualFile.path, restored.options.sourcePath)
                assertTrue(restored.options.delayedBranching)
                assertFalse(restored.options.selfModifyingCode)
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

    private suspend fun run(source: String): Pair<MarsProcessHandler, Recording> {
        val file = withContext(Dispatchers.EDT) { fixture.addFileToProject("run${++runCount}.asm", source).virtualFile }
        val handler = project.getService(MarsRunService::class.java).createProcess(file.toNioPath())
        val recording = Recording()
        handler.addProcessListener(recording)
        withContext(Dispatchers.EDT) { handler.startNotify() }
        return handler to recording
    }

    private class Recording : ProcessListener {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val output = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Int>()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (outputType === ProcessOutputTypes.STDOUT) {
                stdout.append(event.text)
                output.complete(Unit)
            } else if (outputType === ProcessOutputTypes.STDERR) {
                stderr.append(event.text)
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            exit.complete(event.exitCode)
        }
    }
}
