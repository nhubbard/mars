package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.xdebugger.XSourcePosition
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.learning.MarsLearningProgress.CheckTicket
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Source evidence for the first unit's worked single-file execution observations. */
internal class MarsLearningSource(
    project: Project, lifetime: Disposable, private val entry: Path, private val document: Document,
    private val identity: MarsLessonIdentity,
) {
    data class Attempt(val ticket: CheckTicket, val text: String, val stamp: Long)
    private val progress = project.service<MarsLearningProgress>()

    init {
        val file = requireNotNull(FileDocumentManager.getInstance().getFile(document))
        require(file.toNioPath() == entry)
        progress.open(identity, revision(document.text))
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : BulkAwareDocumentListener.Simple {
            override fun beforeDocumentChange(changed: Document) {
                if (changed === document) progress.open(identity, "editing:${UUID.randomUUID()}")
            }
        }, lifetime)
        // Invalidate synchronously before a rename, deletion or refresh can race a result.
        project.messageBus.connect(lifetime).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) {
                if (events.any { !it.isFromSave && entry.startsWith(Path.of(it.path)) }) {
                    progress.open(identity, "vfs:${UUID.randomUUID()}")
                }
            }
        })
    }

    fun supports(handler: MarsProcessHandler) = handler.sourceSet == MarsSourceSet(entry) && handler.executionOptions == options

    fun begin(step: String): Attempt = runReadActionBlocking {
        val text = document.text
        Attempt(progress.beginCheck(identity, step, revision(text)), text, document.modificationStamp)
    }

    /** Called with read access; the process capture identifies what actually executed. */
    fun matches(attempt: Attempt, handler: MarsProcessHandler): Boolean {
        val capture = handler.capturedProgram?.sources
        val sourcesMatch = capture?.let {
            it.exceptionHandler == null && it.snapshot.texts() == mapOf(entry to attempt.text)
        } == true
        return document.modificationStamp == attempt.stamp && sourcesMatch
    }

    fun matchesPosition(position: XSourcePosition?): Boolean {
        if (position == null) return false
        return position.file.isInLocalFileSystem && position.file.toNioPath() == entry
    }

    fun current(attempt: Attempt) = progress.isCurrent(attempt.ticket)
    fun finish(attempt: Attempt, passed: Boolean) = progress.finishCheck(attempt.ticket, passed)
    fun passed(step: String): Boolean {
        val record = progress.snapshot(identity) ?: return false
        return step in record.successfulChecks
    }

    companion object {
        val options = MarsExecutionOptions()
        fun identity(id: String, contract: String) = MarsLessonIdentity("mars-book", id, "0.1.0",
            digest("$id/0.1.0\u0000$contract\u0000$options\u0000" +
                MarsLearningSource::class.java.getResourceAsStream("/messages/MarsLearningBundle.properties")!!
                    .use { it.readBytes().toString(Charsets.UTF_8) }))
        private fun revision(text: String) = digest("$options\u0000$text")
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
