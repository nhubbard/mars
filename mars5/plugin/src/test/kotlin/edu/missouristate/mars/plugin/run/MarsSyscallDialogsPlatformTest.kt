package edu.missouristate.mars.plugin.run

import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.testFramework.closeProjectAsync
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.UiInterceptors
import com.intellij.util.ui.UIUtil
import java.awt.event.ActionEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JTextField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.jdom.Element
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsSyscallDialogsPlatformTest {
    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project get() = projectFixture.get()

    @Test
    fun `standard input choice persists and isolates simultaneous console and dialog launches`() = timeoutRunBlocking {
        val lifetime = Disposer.newDisposable()
        val handlers = mutableListOf<MarsProcessHandler>()
        val prompts = mutableListOf<String>()
        try {
            val path = writeSource(
              $$"""
                .data
                buffer: .space 8
                .text
                .macro call (%service)
                    li $v0, %service
                    syscall
                .end_macro
                .macro separator
                    li $a0, 124
                    call (11)
                .end_macro
                call (5)
                move $a0, $v0
                call (1)
                separator
                call (6)
                mov.s $f12, $f0
                call (2)
                separator
                call (7)
                mov.d $f12, $f0
                call (3)
                separator
                la $a0, buffer
                li $a1, 5
                call (8)
                la $a0, buffer
                call (4)
                separator
                call (12)
                move $a0, $v0
                call (11)
                separator
                li $a0, 0
                la $a1, buffer
                li $a2, 1
                call (14)
                lbu $a0, buffer
                call (11)
                call (10)
            """.trimIndent())
            val (console, dialogs) = withContext(Dispatchers.EDT) {
                val type = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java)
                val consoleConfig = type.createTemplateConfiguration(project).also { it.readExternal(Element("configuration")) }
                assertEquals(MarsInputMode.CONSOLE, consoleConfig.options.inputMode)
                consoleConfig.options.sourcePath = path.toString()
                val editor = consoleConfig.configurationEditor
                val dialogConfig = type.createTemplateConfiguration(project)
                try {
                    editor.resetFrom(consoleConfig)
                    val mode = UIUtil.findComponentsOfType(editor.component, JComboBox::class.java).single { it.selectedItem is MarsInputMode }
                    assertEquals(MarsInputMode.CONSOLE, mode.selectedItem)
                    mode.selectedItem = MarsInputMode.DIALOG
                    editor.applyTo(dialogConfig)
                    editor.resetFrom(dialogConfig)
                    assertEquals(MarsInputMode.DIALOG, mode.selectedItem)
                } finally {
                    Disposer.dispose(editor)
                }
                val xml = Element("configuration").also(dialogConfig::writeExternal)
                val restored = type.createTemplateConfiguration(project).also { it.readExternal(xml) }
                assertEquals(MarsInputMode.DIALOG, restored.options.inputMode)
                for (response in listOf(" 7 ", "1.25", "2.5", "abcdef", "Zebra")) {
                    interceptSyscallDialog { dialog ->
                        prompts += dialog.messageText()
                        UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java).single().text = response
                        dialog.clickButton("OK")
                    }
                }
                fun start(config: MarsRunConfiguration): Pair<MarsProcessHandler, Recording> {
                    val executor = DefaultRunExecutor.getRunExecutorInstance()
                    val env = ExecutionEnvironmentBuilder.create(executor, config).build()
                    val result = config.getState(executor, env).execute(executor, env.runner)!!
                    Disposer.register(lifetime, result.executionConsole!!)
                    val handler = assertInstanceOf(MarsProcessHandler::class.java, result.processHandler).also(handlers::add)
                    val recording = Recording()
                    handler.addProcessListener(recording)
                    handler.startNotify()
                    return handler to recording
                }
                start(consoleConfig) to start(restored)
            }
            withContext(Dispatchers.IO) {
                val charset = EncodingManager.getInstance().defaultCharset
                console.first.processInput.write(" 9 \n3.5\n4.25\n12345\nXray\n".toByteArray(charset) + 'R'.code.toByte())
                dialogs.first.processInput.write(byteArrayOf('Q'.code.toByte()))
            }
            assertEquals(0, console.second.exit.await())
            assertEquals(0, dialogs.second.exit.await())
            assertEquals("9|3.5|4.25|1234|X|R", console.second.stdout.toString())
            assertEquals("7|1.25|2.5|abcd|Z|Q", dialogs.second.stdout.toString())
            assertEquals(listOf(
                "Enter an integer (syscall 5).",
                "Enter a single-precision number (syscall 6).",
                "Enter a double-precision number (syscall 7).",
                "Enter a string of up to 4 characters (syscall 8).",
                "Enter a character (syscall 12).",
            ), prompts)
        } finally {
            withContext(Dispatchers.EDT) {
                handlers.forEach { it.destroyProcess() }
                Disposer.dispose(lifetime)
                UiInterceptors.clear()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [5, 8, 12])
    fun `canceling standard input follows the existing end-of-input behavior`(syscall: Int) = timeoutRunBlocking {
        try {
            withContext(Dispatchers.EDT) { interceptSyscallDialog { it.doCancelAction() } }
            val (_, recording) = run(
              $$"""
                .data
                buffer: .space 8
                .text
                la $a0, buffer
                li $a1, 8
                li $v0, $$syscall
                syscall
                li $v0, 4
                syscall
                li $v0, 10
                syscall
            """.trimIndent(), MarsInputMode.DIALOG)
            if (syscall == 8) {
                assertEquals(0, recording.exit.await())
                assertEquals("\n", recording.stdout.toString())
            } else {
                assertEquals(1, recording.exit.await())
                val name = if (syscall == 5) "integer" else "char"
                assertTrue(recording.stderr.toString().contains("invalid $name input (syscall $syscall)"))
            }
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear() }
        }
    }

    @Test
    fun `MARS confirmation dialogs report Yes No and Cancel to the program`() = timeoutRunBlocking {
        try {
            withContext(Dispatchers.EDT) {
                for (button in listOf("Yes", "No", "Cancel")) {
                    interceptSyscallDialog { dialog ->
                        assertTrue(dialog.title.startsWith("MARS Confirmation"))
                        assertFalse(dialog.isModal)
                        assertEquals("<html>Confirm?", dialog.messageText())
                        dialog.clickButton(button)
                    }
                }
            }
            val (_, recording) = run(
              $$"""
                .data
                prompt: .asciiz "<html>Confirm?"
                .text
                li $s0, 3
                repeat:
                la $a0, prompt
                li $v0, 50
                syscall
                li $v0, 1
                syscall
                addiu $s0, $s0, -1
                bnez $s0, repeat
                li $v0, 10
                syscall
            """.trimIndent())
            assertEquals(0, recording.exit.await())
            assertEquals("012", recording.stdout.toString())
            assertEquals("", recording.stderr.toString())
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear() }
        }
    }

    @Test
    fun `MARS input dialogs preserve typed values and cancellation status registers`() = timeoutRunBlocking {
        try {
            withContext(Dispatchers.EDT) {
                for (answer in listOf("42", "bad", "", null, "1.25", "2.5", "text")) {
                    interceptSyscallDialog { dialog ->
                        assertTrue(dialog.title.startsWith("MARS Input"))
                        assertEquals("Enter a value", dialog.messageText())
                        val field = UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java).single()
                        assertSame(field, dialog.preferredFocusedComponent)
                        if (answer == null) {
                            dialog.doCancelAction()
                        } else {
                            field.text = answer
                            dialog.clickButton("OK")
                        }
                    }
                }
            }
            val (_, recording) = run(
              $$"""
                .data
                prompt: .asciiz "Enter a value"
                buffer: .space 16
                .text
                .macro status
                    move $a0, $s1
                    li $v0, 1
                    syscall
                    li $a0, 124
                    li $v0, 11
                    syscall
                .end_macro
                li $s0, 4
                repeat:
                la $a0, prompt
                li $v0, 51
                syscall
                move $s1, $a1
                li $v0, 1
                syscall
                li $a0, 58
                li $v0, 11
                syscall
                status
                addiu $s0, $s0, -1
                bnez $s0, repeat
                la $a0, prompt
                li $v0, 52
                syscall
                move $s1, $a1
                mov.s $f12, $f0
                li $v0, 2
                syscall
                status
                la $a0, prompt
                li $v0, 53
                syscall
                move $s1, $a1
                mov.d $f12, $f0
                li $v0, 3
                syscall
                status
                la $a0, prompt
                la $a1, buffer
                li $a2, 16
                li $v0, 54
                syscall
                move $s1, $a1
                la $a0, buffer
                li $v0, 4
                syscall
                status
                li $v0, 10
                syscall
            """.trimIndent())
            assertEquals(0, recording.exit.await())
            assertEquals("42:0|0:-1|0:-3|0:-2|1.250|2.50|text\n0|", recording.stdout.toString())
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear() }
        }
    }

    @Test
    fun `MARS messages display their severity and formatted argument in IntelliJ`() = timeoutRunBlocking {
        val info = Messages.getInformationIcon()
        val messages = listOf(
            "Value: " to Messages.getErrorIcon(),
            "Value: " to info,
            "Value: " to Messages.getWarningIcon(),
            "Value: " to Messages.getQuestionIcon(),
            "Value: " to null,
            "Value: 42" to info,
            "Value: 2.5" to info,
            "Value: 1.25" to info,
            "Value: text" to info,
        )
        val observed = mutableListOf<Pair<String, List<Icon>>>()
        try {
            withContext(Dispatchers.EDT) {
                repeat(messages.size) {
                    interceptSyscallDialog { dialog ->
                        val icons: List<Icon> = UIUtil.findComponentsOfType(dialog.fields, JLabel::class.java).mapNotNull { it.icon }
                        observed.add(dialog.messageText() to icons)
                        dialog.clickButton("OK")
                    }
                }
            }
            val (_, recording) = run(
              $$"""
                .data
                message: .asciiz "Value: "
                suffix: .asciiz "text"
                single: .float 1.25
                double: .double 2.5
                .text
                li $s0, 0
                repeat:
                la $a0, message
                move $a1, $s0
                li $v0, 55
                syscall
                addiu $s0, $s0, 1
                blt $s0, 5, repeat
                la $a0, message
                li $a1, 42
                li $v0, 56
                syscall
                la $a0, message
                l.d $f12, double
                li $v0, 58
                syscall
                la $a0, message
                l.s $f12, single
                li $v0, 57
                syscall
                la $a0, message
                la $a1, suffix
                li $v0, 59
                syscall
                li $v0, 10
                syscall
            """.trimIndent())
            assertEquals(0, recording.exit.await())
            assertEquals(messages.map { (text, icon) -> text to listOfNotNull(icon) }, observed)
            assertEquals("", recording.stderr.toString())
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear() }
        }
    }

    @Test
    fun `a supplied MARS message title is displayed verbatim`() = timeoutRunBlocking {
        val execution = currentCoroutineContext().job
        try {
            withContext(Dispatchers.EDT) {
                interceptSyscallDialog {
                    assertEquals("Simulation result", it.title)
                    assertEquals("Complete", it.messageText())
                    it.clickButton("OK")
                }
            }
            withContext(Dispatchers.Default) {
                MarsSyscallDialogs(project, "unused.asm", execution).showMessageDialog("Complete", "Simulation result", -1)
            }
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear() }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Stop or project closure dismisses the outstanding MARS dialog before termination`(closeProject: Boolean) = timeoutRunBlocking {
        val showing = CompletableDeferred<MarsSyscallDialog>()
        try {
            withContext(Dispatchers.EDT) { interceptSyscallDialog { showing.complete(it) } }
            val (handler, recording) = run(
              $$"""
                .data
                prompt: .asciiz "Waiting"
                .text
                la $a0, prompt
                li $v0, 51
                syscall
                li $a0, 1
                li $v0, 1
                syscall
            """.trimIndent())
            val dialog = showing.await()
            withContext(Dispatchers.EDT) { assertFalse(dialog.isDisposed) }
            if (closeProject) project.closeProjectAsync()
            else withContext(Dispatchers.EDT) { handler.destroyProcess() }
            assertEquals(130, recording.exit.await())
            withContext(Dispatchers.EDT) { assertTrue(dialog.isDisposed) }
            assertEquals("", recording.stdout.toString())
            assertEquals("", recording.stderr.toString())
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear() }
        }
    }

    private fun interceptSyscallDialog(onShow: (MarsSyscallDialog) -> Unit) =
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsSyscallDialog>(MarsSyscallDialog::class.java) {
            override fun doIntercept(component: MarsSyscallDialog) = onShow(component)
        })

    private fun MarsSyscallDialog.messageText() =
        UIUtil.findComponentsOfType(fields, JTextArea::class.java).single().text

    private fun MarsSyscallDialog.clickButton(text: String) {
        val action = createActions().single { UIUtil.removeMnemonic(it.getValue(Action.NAME) as String) == text }
        action.actionPerformed(ActionEvent(fields, ActionEvent.ACTION_PERFORMED, text))
    }

    private suspend fun writeSource(source: String): Path = withContext(Dispatchers.IO) {
        val file = Path.of(requireNotNull(project.basePath), "dialog.asm")
        Files.writeString(file, source)
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file))
        file
    }

    private suspend fun run(source: String, inputMode: MarsInputMode = MarsInputMode.CONSOLE): Pair<MarsProcessHandler, Recording> {
        val path = writeSource(source)
        val recording = Recording()
        val handler = project.service<MarsRunService>().createProcess(path, memoryDumps = emptyList(), inputMode = inputMode)
        handler.addProcessListener(recording)
        withContext(Dispatchers.EDT) { handler.startNotify() }
        return handler to recording
    }

    private class Recording : ProcessListener {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exit = CompletableDeferred<Int>()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            when (outputType) {
                ProcessOutputTypes.STDOUT -> stdout.append(event.text)
                ProcessOutputTypes.STDERR -> stderr.append(event.text)
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            exit.complete(event.exitCode)
        }
    }
}
