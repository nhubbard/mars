package edu.missouristate.mars.plugin.build

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileSaverDialog
import com.intellij.openapi.fileChooser.impl.FileChooserFactoryImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.replaceService
import com.intellij.ui.UiInterceptors
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import javax.swing.JComboBox
import javax.swing.JTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MarsMemoryExportPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val export get() = project.service<MarsMemoryExportService>()

    @TempDir
    lateinit var directory: Path

    @Test
    fun `all six formats export unsaved included data as exact bytes`() = timeoutRunBlocking<Unit> {
        val entry = withContext(Dispatchers.EDT) {
            val include = fixture.addFileToProject("words.s", ".word 0\n").virtualFile
            val source = fixture.addFileToProject("entry.asm", ".data\n.include \"words.s\"\n.text\naddiu \$t0, \$zero, 7\n")
            val document = FileDocumentManager.getInstance().getDocument(include)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText(".word 0x41424344\n") }
            source.virtualFile.toNioPath()
        }
        val newline = System.lineSeparator()
        val outputs = mapOf(
            "AsciiText" to HexFormat.of().formatHex("  A  B  C  D".toByteArray()),
            "Binary" to "44434241",
            "BinaryText" to HexFormat.of().formatHex("01000001010000100100001101000100".toByteArray()),
            "HexText" to HexFormat.of().formatHex("41424344".toByteArray()),
            "HEX" to HexFormat.of().formatHex(":0400000041424344F2${newline}:00000001FF".toByteArray()),
            "SegmentWindow" to HexFormat.of().formatHex("0x10010000    0x41424344 ".toByteArray()),
        )
        assertEquals(outputs.keys, DumpFormatLoader.FORMATS.map { it.commandDescriptor }.toSet())
        for (format in DumpFormatLoader.FORMATS) {
            val output = directory.resolve(format.commandDescriptor)
            val range = 0x10010000u..0x10010000u
            assertNull(export.export(entry, MemoryExportOptions(format, MemoryExportRegion.RANGE, range), output))
            val lineEnd = if (format.commandDescriptor == "Binary") "" else HexFormat.of().formatHex(newline.toByteArray())
            assertEquals(outputs.getValue(format.commandDescriptor) + lineEnd, HexFormat.of().formatHex(Files.readAllBytes(output)), format.toString())
        }
        val text = directory.resolve("text")
        assertNull(export.export(entry, MemoryExportOptions(format("HexText"), MemoryExportRegion.TEXT), text))
        assertEquals("24080007$newline", Files.readString(text))
        val listing = directory.resolve("listing")
        assertNull(export.export(entry, MemoryExportOptions(format("SegmentWindow"), MemoryExportRegion.TEXT), listing))
        assertTrue(Files.readAllLines(listing).last().startsWith("0x00400000  0x24080007"))
        val data = directory.resolve("data")
        assertNull(export.export(entry, MemoryExportOptions(format("HexText"), MemoryExportRegion.DATA), data))
        assertEquals("41424344", Files.readAllLines(data).first())
    }

    @Test
    fun `empty memory invalid ranges assembly failures and IO failures preserve the destination`() = timeoutRunBlocking<Unit> {
        val entry = source("entry.s", ".text\nnop\n")
        val broken = source("broken.asm", ".text\nnot_an_instruction\n")
        val destination = directory.resolve("existing").also { Files.writeString(it, "preserve") }
        val data = MemoryExportOptions(format("HexText"), MemoryExportRegion.DATA)
        val text = data.copy(region = MemoryExportRegion.TEXT)
        fun range(first: UInt, last: UInt) = data.copy(region = MemoryExportRegion.RANGE, range = first..last)
        val cases = listOf(
            Triple(entry, data, "empty"),
            Triple(entry, range(0x00400000u, 0x00400004u), "unpopulated word at 0x00400004"),
            Triple(entry, range(0u, 0u), "outside the memory available"),
            Triple(entry, range(0x00400000u, 0x10010000u).copy(format = format("SegmentWindow")), "wholly inside"),
            Triple(entry, range(0x10010000u, 0x80000000u).copy(format = format("SegmentWindow")), "wholly inside"),
            Triple(broken, text, "Assembly failed"),
        )
        for ((source, options, expected) in cases) {
            assertTrue(export.export(source, options, destination)?.contains(expected) == true, expected)
            assertEquals("preserve", Files.readString(destination))
        }
        val existingDirectory = Files.createDirectory(directory.resolve("occupied"))
        assertTrue(export.export(entry, text, existingDirectory)?.contains("Could not write") == true)
        assertTrue(Files.isDirectory(existingDirectory))
        Files.list(directory).use { assertEquals(setOf(destination, existingDirectory), it.toList().toSet()) }

        val kernel = source("kernel.s", ".ktext 0x80000180\naddiu \$t0, \$zero, 7\n")
        assertNull(export.export(kernel, range(0x80000180u, 0x80000180u), destination))
        assertEquals(listOf("24080007"), Files.readAllLines(destination))
    }

    @Test
    fun `registered Build action validates addresses and respects both dialog cancellations`() = timeoutRunBlocking<Unit> {
        val entry = source("entry.s", ".text\nnop\n")
        val file = withContext(Dispatchers.EDT) { fixture.findFileInTempDir("entry.s") }
        val nonSource = withContext(Dispatchers.EDT) { fixture.addFileToProject("plain.txt", "").virtualFile }
        val action = ActionManager.getInstance().getAction("Mars.ExportMemory")
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
        val enabled = event(action, project, file)
        readAction {
            action.update(enabled)
            assertTrue(enabled.presentation.isEnabledAndVisible)
        }
        for (event in listOf(event(action, null, file), event(action, project, null),
            event(action, project, nonSource), event(action, project, LightVirtualFile("loose.s")))) {
            readAction {
                action.update(event)
                assertFalse(event.presentation.isEnabledAndVisible)
            }
            withContext(Dispatchers.EDT) { action.actionPerformed(event) }
        }

        val disposable = Disposer.newDisposable()
        val notifications = Channel<Notification>(Channel.UNLIMITED)
        project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "MARS Memory Export") notifications.trySend(notification)
            }
        })
        val output = directory.resolve("machine.hex")
        var saved = false
        var cancelSave = true
        try {
            withContext(Dispatchers.EDT) {
                val application = ApplicationManager.getApplication()
                application.replaceService(FileChooserFactory::class.java, object : FileChooserFactoryImpl() {
                    override fun createSaveFileDialog(descriptor: FileSaverDescriptor, project: Project?): FileSaverDialog {
                        assertEquals("Save MIPS Memory Dump", descriptor.title)
                        return object : FileSaverDialog {
                            override fun save(baseDir: VirtualFile?, filename: String?): VirtualFileWrapper? =
                                error("Expected a NIO base directory")

                            override fun save(baseDir: Path?, filename: String?): VirtualFileWrapper? {
                                assertEquals(entry.parent, baseDir)
                                assertEquals("entry-memory", filename)
                                saved = true
                                return if (cancelSave) null else VirtualFileWrapper(output.toFile())
                            }
                        }
                    }
                }, disposable)

                intercept { it.doCancelAction() }
                action.actionPerformed(enabled)
                assertFalse(saved)

                intercept {
                    assertTrue(it.performValidateAll().isEmpty())
                    it.performOKAction()
                }
                action.actionPerformed(enabled)
                assertTrue(saved)
                assertFalse(Files.exists(output))

                cancelSave = false
                intercept { dialog ->
                    val combos = UIUtil.findComponentsOfType(dialog.fields, JComboBox::class.java)
                    combos[0].selectedItem = MemoryExportRegion.RANGE
                    combos[1].selectedItem = format("HEX")
                    val (first, last) = UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java)
                    assertEquals(first, dialog.performValidateAll().single().component)
                    first.text = " 0X00400000 "
                    last.text = "0x00400001"
                    assertEquals(last, dialog.performValidateAll().single().component)
                    last.text = "0"
                    assertTrue(dialog.performValidateAll().single().message.contains("at or after"))
                    last.text = "4194304"
                    assertTrue(dialog.performValidateAll().isEmpty())
                    dialog.performOKAction()
                }
                action.actionPerformed(enabled)
            }
            assertEquals(NotificationType.INFORMATION, notifications.receive().type)
            assertEquals(listOf(":0400000000000000FC", ":00000001FF"), Files.readAllLines(output))

            export.exportInBackground(entry, MemoryExportOptions(format("HEX"), MemoryExportRegion.DATA), output).join()
            val failure = notifications.receive()
            assertEquals(NotificationType.ERROR, failure.type)
            assertTrue(failure.content.contains("empty"))
        } finally {
            withContext(Dispatchers.EDT) {
                Disposer.dispose(disposable)
                UiInterceptors.clear()
            }
        }
    }

    private suspend fun source(name: String, text: String): Path = withContext(Dispatchers.EDT) {
        fixture.addFileToProject(name, text).virtualFile.toNioPath()
    }

    private fun format(name: String) = DumpFormatLoader.FORMATS.single { it.commandDescriptor == name }

    private fun intercept(onShow: (MarsMemoryExportDialog) -> Unit) =
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsMemoryExportDialog>(MarsMemoryExportDialog::class.java) {
            override fun doIntercept(component: MarsMemoryExportDialog) = onShow(component)
        })

    private fun event(action: AnAction, project: Project?, file: VirtualFile?): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file).build()
        return AnActionEvent.createEvent(action, context, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
    }
}
