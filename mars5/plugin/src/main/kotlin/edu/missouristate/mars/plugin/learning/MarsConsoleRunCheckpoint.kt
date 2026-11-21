package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
internal class MarsLearningExecution(private val project: Project, private val scope: CoroutineScope) {
    fun observeRun(lifetime: Disposable, entry: Path, document: Document) =
        MarsConsoleRunCheckpoint(project, scope, lifetime, entry, document)
}

/** Observes the first unit's ordinary Run; it never launches or alters a simulated program. */
internal class MarsConsoleRunCheckpoint(
    private val project: Project,
    private val scope: CoroutineScope,
    private val lifetime: Disposable,
    private val entry: Path,
    private val document: Document,
) {
    private val progress = project.service<MarsLearningProgress>()
    @Volatile private var closed = false

    init {
        val file = requireNotNull(FileDocumentManager.getInstance().getFile(document))
        require(file.toNioPath() == entry)
        progress.open(identity, revision(document.text))
        Disposer.register(lifetime) { closed = true }
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : BulkAwareDocumentListener.Simple {
            override fun beforeDocumentChange(changed: Document) {
                if (changed === document) {
                    // Invalidate before any write, including a bulk edit that later restores the text.
                    progress.open(identity, "editing:${UUID.randomUUID()}")
                }
            }
        }, lifetime)
        // Evidence must be invalidated synchronously before a rename, deletion or disk refresh.
        // Async preparation alone would leave a window for a late process result to pass.
        project.messageBus.connect(lifetime).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) {
                if (events.any { !it.isFromSave && entry.startsWith(Path.of(it.path)) }) {
                    progress.open(identity, "vfs:${UUID.randomUUID()}")
                }
            }
        })
        project.messageBus.connect(lifetime).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                starting(executorId, handler)
            }
        })
    }

    /** Called by the native pre-start event; package visibility also permits real-process fixture tests. */
    fun starting(executorId: String, handler: ProcessHandler) {
        if (closed || executorId != DefaultRunExecutor.EXECUTOR_ID || handler !is MarsProcessHandler) return
        if (handler.sourceSet != MarsSourceSet(entry) || handler.executionOptions != options) return
        val (text, stamp, ticket) = runReadActionBlocking {
            val text = document.text
            Triple(text, document.modificationStamp, progress.beginCheck(identity, STEP, revision(text)))
        }
        val output = Transcript()
        val listener = object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = output.append(event.text, outputType)

            override fun processTerminated(event: ProcessEvent) {
                handler.removeProcessListener(this)
                scope.launch {
                    readAction {
                        val capture = handler.capturedProgram?.sources
                        val sourcesMatch = capture?.let {
                            it.exceptionHandler == null && it.snapshot.texts() == mapOf(entry to text)
                        } == true
                        val passed = event.exitCode == 0 && output.matches() &&
                            document.modificationStamp == stamp && sourcesMatch
                        progress.finishCheck(ticket, passed)
                    }
                }
            }
        }
        handler.addProcessListener(listener)
        Disposer.register(lifetime) {
            handler.removeProcessListener(listener)
            progress.finishCheck(ticket, false)
        }
    }

    fun passed(): Boolean {
        val record = progress.snapshot(identity) ?: return false
        return STEP in record.successfulChecks
    }

    /** Bounded transcript storage: excessive output fails the checkpoint instead of retaining it. */
    internal class Transcript {
        private val stdout = StringBuilder()
        private var failed = false

        @Synchronized
        fun append(text: String, type: Key<*>) {
            when (type) {
                ProcessOutputTypes.STDOUT -> if (text.length <= EXPECTED.length - stdout.length) stdout.append(text) else failed = true
                ProcessOutputTypes.STDERR -> if (text.isNotEmpty()) failed = true
            }
        }

        @Synchronized
        fun matches() = !failed && stdout.toString() == EXPECTED
    }

    companion object {
        const val STEP = "run-twenty"
        const val EXPECTED = "Enter a small integer: Twice your number plus one is: 41\n"
        val options = MarsExecutionOptions()
        val identity = MarsLessonIdentity("mars-book", "console-run", "0.1.0", digest("console-run/0.1.0\u0000$EXPECTED\u0000$options\u0000" +
            MarsConsoleRunCheckpoint::class.java.getResourceAsStream("/messages/MarsLearningBundle.properties")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }))

        private fun revision(text: String) = digest("$options\u0000$text")
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
