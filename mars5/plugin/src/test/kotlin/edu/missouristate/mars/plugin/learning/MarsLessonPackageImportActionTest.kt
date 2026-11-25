@file:Suppress("JetBrainsInternalApiUsage", "OverrideOnly")

package edu.missouristate.mars.plugin.learning

import com.intellij.mock.MockProject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MarsLessonPackageImportActionTest {
  private val fixture = codeInsightProjectFixture()
  @TempDir lateinit var temporary: Path
  private val action
    get() = ActionManager.getInstance().getAction("Mars.Learning.ImportPackage")

  private fun event(data: DataContext) =
      AnActionEvent.createEvent(action, data, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)

  private fun archive(
      json: String =
          """{"formatVersion":1,"id":"001-example","version":"1.0.0","title":"Example <plain> & text","entry":"starter/main.asm","assignment":"assignment.md","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}""",
  ): Path {
    val zip = Files.createTempFile(temporary, "native-lab-", ".zip")
    ZipOutputStream(Files.newOutputStream(zip)).use { out ->
      for ((path, content) in
          mapOf(
              "package.json" to json,
              "starter/main.asm" to ".text\nnop\n",
              "assignment.md" to "Explain the result.",
          )) {
        out.putNextEntry(ZipEntry("001-example/$path"))
        out.write(content.toByteArray())
        out.closeEntry()
      }
    }
    return zip
  }

  private fun fileDialog(path: String, accept: Boolean = true) =
      object :
          UiInterceptors.UiInterceptor<MarsLessonPackageFileDialog>(
              MarsLessonPackageFileDialog::class.java,
          ) {
        override fun doIntercept(dialog: MarsLessonPackageFileDialog) {
          dialog.archiveField.text = path
          dialog.close(if (accept) DialogWrapper.OK_EXIT_CODE else DialogWrapper.CANCEL_EXIT_CODE)
        }
      }

  @Test
  fun `native file selection validates paths and cancellation leaves the project untouched`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
          val empty = event(DataContext.EMPTY_CONTEXT)
          action.update(empty)
          assertFalse(empty.presentation.isEnabled)
          action.actionPerformed(empty)
          val lifetime = Disposer.newDisposable()
          try {
            val noRoot = MockProject(null, lifetime)
            val noRootEvent =
                event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, noRoot).build())
            action.update(noRootEvent)
            assertFalse(noRootEvent.presentation.isEnabled)
            action.actionPerformed(noRootEvent)
          } finally {
            Disposer.dispose(lifetime)
          }
          val project = fixture.get()
          val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
          val normal = event(data)
          action.update(normal)
          assertTrue(normal.presentation.isEnabled)
          val dialog = MarsLessonPackageFileDialog(project)
          try {
            assertTrue(dialog.preferredFocusedComponent === dialog.archiveField.textField)
            for (path in listOf("", "relative.zip", "bad\u0000path")) {
              dialog.archiveField.text = path
              assertTrue(dialog.doValidate() != null)
            }
            dialog.archiveField.text = temporary.resolve("student.zip").toString()
            assertNull(dialog.doValidate())
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
          UiInterceptors.register(fileDialog("", false))
          AutoCloseable { UiInterceptors.clear() }.use { action.actionPerformed(normal) }
          assertFalse(Files.exists(Path.of(project.basePath!!).resolve("mars-labs/packages")))
        }
      }

  @Suppress("UsePropertyAccessSyntax")
  @Test
  fun `native preview cancellation and acceptance preserve work and select the imported starter`() =
      timeoutRunBlocking {
        val zip = archive()
        withContext(Dispatchers.EDT) {
          val project = fixture.get()
          val normal =
              event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())
          for (accept in listOf(false, true, true)) {
            UiInterceptors.register(fileDialog(zip.toString()))
            UiInterceptors.register(
                object :
                    UiInterceptors.UiInterceptor<MarsLessonPackagePreviewDialog>(
                        MarsLessonPackagePreviewDialog::class.java,
                    ) {
                  override fun doIntercept(dialog: MarsLessonPackagePreviewDialog) {
                    assertTrue(dialog.report.contains("Example <plain> & text"))
                    assertTrue(dialog.report.contains("Version: 1.0.0"))
                    assertTrue(dialog.report.contains("Content SHA-256:"))
                    assertTrue(dialog.report.contains("importing does not run them"))
                    dialog.close(
                        if (accept) DialogWrapper.OK_EXIT_CODE else DialogWrapper.CANCEL_EXIT_CODE,
                    )
                  }
                },
            )
            AutoCloseable { UiInterceptors.clear() }.use { action.actionPerformed(normal) }
            if (!accept) {
              assertFalse(Files.exists(Path.of(project.basePath!!).resolve("mars-labs/packages")))
            } else {
              val manager = FileEditorManager.getInstance(project)
              val selected = manager.selectedTextEditor!!
              assertTrue(manager.openFiles.any { it.name == "assignment.md" })
              assertEquals("main.asm", manager.selectedEditor!!.file.name)
              assertTrue(
                  selected.document.text.endsWith("nop\n") ||
                      selected.document.text == "# edited by student\n",
              )
              WriteCommandAction.runWriteCommandAction(project) {
                selected.document.setText("# edited by student\n")
              }
              FileDocumentManager.getInstance().saveDocument(selected.document)
            }
          }
          assertEquals(
              "# edited by student\n",
              FileEditorManager.getInstance(project).selectedTextEditor!!.document.text,
          )
        }
      }

  @Test
  fun `invalid ZIP metadata and unsupported packages show native errors without creating work`() =
      timeoutRunBlocking {
        val invalidZip = Files.writeString(temporary.resolve("broken.zip"), "not a zip")
        for (path in
            listOf(
                invalidZip,
                archive("{}"),
                archive("{\"formatVersion\":1, bad"),
                archive("{\"formatVersion\":1,\"other\":[}"),
            )) {
          withContext(Dispatchers.EDT) {
            val project = fixture.get()
            var shown = false
            val previous = TestDialogManager.setTestDialog { text ->
              assertFalse(text.isBlank())
              shown = true
              Messages.OK
            }
            UiInterceptors.register(fileDialog(path.toString()))
            try {
              AutoCloseable { UiInterceptors.clear() }
                  .use {
                    action.actionPerformed(
                        event(
                            SimpleDataContext.builder()
                                .add(CommonDataKeys.PROJECT, project)
                                .build(),
                        ),
                    )
                  }
            } finally {
              TestDialogManager.setTestDialog(previous)
            }
            assertTrue(shown)
            assertFalse(Files.exists(Path.of(project.basePath!!).resolve("mars-labs/packages")))
          }
        }
        assertEquals(
            packageMessages.getMessage("import.error.unknown"),
            packageImportFailure(IOException()),
        )
        assertEquals("specific error", packageImportFailure(IOException("specific error")))
      }

  @Test
  fun `reopening reports a missing working file rather than fabricating its contents`() =
      timeoutRunBlocking {
        val project = fixture.get()
        val installed =
            MarsLessonPackages.prepare(archive()).use { it.install(Path.of(project.basePath!!)) }
        Files.delete(installed.entry)
        assertThrows<IOException> { openInstalledPackage(project, installed) }
        assertFalse(Files.exists(installed.entry))
      }
}
