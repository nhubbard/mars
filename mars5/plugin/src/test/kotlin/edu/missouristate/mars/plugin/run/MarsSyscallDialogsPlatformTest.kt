package edu.missouristate.mars.plugin.run

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.closeProjectAsync
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.UiInterceptors
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.plugin.MarsSessionService
import java.awt.event.ActionEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JTextField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsSyscallDialogsPlatformTest {
    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project get() = projectFixture.get()

    @Test
    fun `MARS confirmation dialogs report Yes No and Cancel to the program`() = timeoutRunBlocking<Unit> {
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
            val (_, recording) = run("""
                .data
                prompt: .asciiz "<html>Confirm?"
                .text
                li ${'$'}s0, 3
                repeat:
                la ${'$'}a0, prompt
                li ${'$'}v0, 50
                syscall
                li ${'$'}v0, 1
                syscall
                addiu ${'$'}s0, ${'$'}s0, -1
                bnez ${'$'}s0, repeat
                li ${'$'}v0, 10
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
    fun `MARS input dialogs preserve typed values and cancellation status registers`() = timeoutRunBlocking<Unit> {
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
            val (_, recording) = run("""
                .data
                prompt: .asciiz "Enter a value"
                buffer: .space 16
                .text
                .macro status
                    move ${'$'}a0, ${'$'}s1
                    li ${'$'}v0, 1
                    syscall
                    li ${'$'}a0, 124
                    li ${'$'}v0, 11
                    syscall
                .end_macro
                li ${'$'}s0, 4
                repeat:
                la ${'$'}a0, prompt
                li ${'$'}v0, 51
                syscall
                move ${'$'}s1, ${'$'}a1
                li ${'$'}v0, 1
                syscall
                li ${'$'}a0, 58
                li ${'$'}v0, 11
                syscall
                status
                addiu ${'$'}s0, ${'$'}s0, -1
                bnez ${'$'}s0, repeat
                la ${'$'}a0, prompt
                li ${'$'}v0, 52
                syscall
                move ${'$'}s1, ${'$'}a1
                mov.s ${'$'}f12, ${'$'}f0
                li ${'$'}v0, 2
                syscall
                status
                la ${'$'}a0, prompt
                li ${'$'}v0, 53
                syscall
                move ${'$'}s1, ${'$'}a1
                mov.d ${'$'}f12, ${'$'}f0
                li ${'$'}v0, 3
                syscall
                status
                la ${'$'}a0, prompt
                la ${'$'}a1, buffer
                li ${'$'}a2, 16
                li ${'$'}v0, 54
                syscall
                move ${'$'}s1, ${'$'}a1
                la ${'$'}a0, buffer
                li ${'$'}v0, 4
                syscall
                status
                li ${'$'}v0, 10
                syscall
            """.trimIndent())
            assertEquals(0, recording.exit.await())
            assertEquals("42:0|0:-1|0:-3|0:-2|1.250|2.50|text\n0|", recording.stdout.toString())
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear() }
        }
    }

    @Test
    fun `MARS messages display their severity and formatted argument in IntelliJ`() = timeoutRunBlocking<Unit> {
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
            val (_, recording) = run("""
                .data
                message: .asciiz "Value: "
                suffix: .asciiz "text"
                single: .float 1.25
                double: .double 2.5
                .text
                li ${'$'}s0, 0
                repeat:
                la ${'$'}a0, message
                move ${'$'}a1, ${'$'}s0
                li ${'$'}v0, 55
                syscall
                addiu ${'$'}s0, ${'$'}s0, 1
                blt ${'$'}s0, 5, repeat
                la ${'$'}a0, message
                li ${'$'}a1, 42
                li ${'$'}v0, 56
                syscall
                la ${'$'}a0, message
                l.d ${'$'}f12, double
                li ${'$'}v0, 58
                syscall
                la ${'$'}a0, message
                l.s ${'$'}f12, single
                li ${'$'}v0, 57
                syscall
                la ${'$'}a0, message
                la ${'$'}a1, suffix
                li ${'$'}v0, 59
                syscall
                li ${'$'}v0, 10
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
    fun `a supplied MARS message title is displayed verbatim`() = timeoutRunBlocking<Unit> {
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
                MarsSessionService.createMarsComponent().context().use { session ->
                    val dialogs = MarsIdeSyscallEnvironment(project, "unused.asm", execution, session.syscallEnvironment) {}
                    dialogs.showMessageDialog("Complete", "Simulation result", -1)
                }
            }
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear() }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Stop or project closure dismisses the outstanding MARS dialog before termination`(closeProject: Boolean) = timeoutRunBlocking<Unit> {
        val showing = CompletableDeferred<MarsSyscallDialog>()
        try {
            withContext(Dispatchers.EDT) { interceptSyscallDialog { showing.complete(it) } }
            val (handler, recording) = run("""
                .data
                prompt: .asciiz "Waiting"
                .text
                la ${'$'}a0, prompt
                li ${'$'}v0, 51
                syscall
                li ${'$'}a0, 1
                li ${'$'}v0, 1
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


    private suspend fun run(source: String): Pair<MarsProcessHandler, Recording> {
        val path = withContext(Dispatchers.IO) {
            val file = Path.of(requireNotNull(project.basePath), "dialog.asm")
            Files.writeString(file, source)
            checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file))
            file
        }
        val recording = Recording()
        val handler = project.service<MarsRunService>().createProcess(path, memoryDumps = emptyList())
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
