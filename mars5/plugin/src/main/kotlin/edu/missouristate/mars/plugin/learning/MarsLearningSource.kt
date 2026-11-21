package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XSourcePosition
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.learning.MarsLearningProgress.CheckTicket
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import java.nio.file.Path
import java.security.MessageDigest

/** Source evidence for the first unit's worked single-file execution observations. */
internal class MarsLearningSource(
    project: Project, private val entry: Path, private val document: Document,
    private val identity: MarsLessonIdentity,
) {
    data class Attempt(val ticket: CheckTicket, val text: String, val stamp: Long)
    private val progress = project.service<MarsLearningProgress>()
    private val file = requireNotNull(FileDocumentManager.getInstance().getFile(document))

    init {
        require(file.toNioPath() == entry)
        project.service<MarsLearningSourceTracker>().track(identity, entry, file)
        progress.open(identity, revision(document.text))
    }

    /** Synchronize after a reset without changing evidence for unchanged source. */
    fun refreshRevision() = runReadActionBlocking { progress.open(identity, revision(document.text)) }

    fun isBound() = file.isValid && file.toNioPath() == entry

    fun supports(handler: MarsProcessHandler) = handler.sourceSet == MarsSourceSet(entry) && handler.executionOptions == options

    fun begin(step: String): Attempt = runReadActionBlocking {
        val text = document.text
        Attempt(progress.beginCheck(identity, step, revision(text)), text, document.modificationStamp)
    }

    /** Called with read access; the process capture identifies what actually executed. */
    fun matches(attempt: Attempt, handler: MarsProcessHandler): Boolean = matches(attempt, handler.capturedProgram?.sources)

    fun matches(attempt: Attempt, capture: CapturedAssemblySources?): Boolean {
        val sourcesMatch = capture?.let {
            it.exceptionHandler == null && it.snapshot.texts() == mapOf(entry to attempt.text)
        } == true
        return isBound() && document.modificationStamp == attempt.stamp && sourcesMatch
    }

    fun matchesPosition(position: XSourcePosition?): Boolean {
        if (position == null) return false
        return position.file.isInLocalFileSystem && position.file.toNioPath() == entry
    }

    fun current(attempt: Attempt) = progress.isCurrent(attempt.ticket)
    fun finish(attempt: Attempt, passed: Boolean) = progress.finishCheck(attempt.ticket, passed)
    fun finishAssessment(attempt: Attempt, passed: Boolean, limit: Int) = progress.finishAssessment(attempt.ticket, passed, limit)
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
        fun revision(text: String) = digest("$options\u0000$text")
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
