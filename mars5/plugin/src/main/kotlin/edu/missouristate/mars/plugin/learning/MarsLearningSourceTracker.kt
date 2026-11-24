package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Path
import java.util.UUID

/** Source-bound evidence must expire on edits even after the learner leaves its lesson. */
@Service(Service.Level.PROJECT)
internal class MarsLearningSourceTracker(private val project: Project) : Disposable {
  private data class Binding(
      val identity: MarsLessonIdentity,
      val entry: Path,
      val file: VirtualFile,
  )

  private val bindings = mutableMapOf<Pair<String, Path>, Binding>()

  init {
    EditorFactory.getInstance()
        .eventMulticaster
        .addDocumentListener(
            object : BulkAwareDocumentListener.Simple {
              override fun beforeDocumentChange(document: Document) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                invalidate { it.file == file }
              }
            },
            this,
        )
    project.messageBus
        .connect(this)
        .subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
              override fun before(events: List<VFileEvent>) {
                invalidate { binding ->
                  events.any { !it.isFromSave && binding.entry.startsWith(Path.of(it.path)) }
                }
              }
            },
        )
  }

  @Synchronized
  fun track(identity: MarsLessonIdentity, entry: Path, file: VirtualFile) {
    bindings[identity.key to entry] = Binding(identity, entry, file)
  }

  private fun invalidate(matches: (Binding) -> Boolean) {
    val watched = synchronized(this) { bindings.values.toList() }
    val progress = project.service<MarsLearningProgress>()
    for (binding in watched) {
      if (matches(binding) && progress.snapshot(binding.identity) != null) {
        progress.open(binding.identity, "source-event:${UUID.randomUUID()}")
      }
    }
  }

  override fun dispose() = Unit
}
