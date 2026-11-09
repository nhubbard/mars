package edu.missouristate.mars.plugin.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.io.IOUtil
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsBuildService
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.charset.CodingErrorAction.REPLACE
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
internal class MarsRunService(private val project: Project, private val scope: CoroutineScope) {
    fun createProcess(entry: Path) = MarsProcessHandler(project, scope, entry, Pipe.open())
}

internal class MarsProcessHandler(
    private val project: Project, scope: CoroutineScope, private val entry: Path, private val input: Pipe,
) : ProcessHandler() {
    // As in IntelliJ's external-task runner, ConsoleView writes to the sink on its input worker.
    private val consoleInput = Channels.newOutputStream(input.sink())
    private var status = 1
    private val execution = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        status = runProgram()
    }

    init {
        execution.invokeOnCompletion { cause ->
            try {
                IOUtil.closeSafe(thisLogger(), input.source(), input.sink())
            } finally {
                notifyProcessTerminated(if (cause is CancellationException) 130 else status)
            }
        }
    }

    override fun startNotify() {
        super.startNotify()
        execution.start()
    }

    override fun destroyProcessImpl() { execution.cancel() }
    override fun detachProcessImpl() = destroyProcessImpl()
    override fun detachIsDefault() = false
    override fun getProcessInput() = consoleInput

    private suspend fun runProgram(): Int {
        val component = MarsSessionService.createMarsComponent()
        return component.context().use { session ->
            if (!project.getService(MarsBuildService::class.java).assemble(entry, component, activateBuildView = false)) {
                notifyTextAvailable(runMessages.getMessage("run.assembly.failed") + "\n", ProcessOutputTypes.STDERR)
                return@use 1
            }
            val stdout = ConsoleOutput { notifyTextAvailable(it, ProcessOutputTypes.STDOUT) }
            val stderr = ConsoleOutput { notifyTextAvailable(it, ProcessOutputTypes.STDERR) }
            PrintStream(stdout, true, Charsets.UTF_8).use { output ->
                PrintStream(stderr, true, Charsets.UTF_8).use { error ->
                    val stdin = BufferedInputStream(Channels.newInputStream(input.source()))
                    SessionSyscallEnvironment(session.memory, stdin, output, error, entry.parent).use { io ->
                        session.program.setSyscallEnvironment(io)
                        val simulator = session.simulator()
                        simulator.addListener(object : SimulatorListener {
                            override fun onStep(event: StepEvent) = Unit
                            override fun onStop(event: StopEvent) = Unit
                            override fun onError(event: ErrorEvent) {
                                if (execution.isActive) {
                                    notifyTextAvailable(runMessages.getMessage("run.runtime.error", event.message()) + "\n", ProcessOutputTypes.STDERR)
                                }
                            }
                        })
                        execution.ensureActive()
                        val completion = simulator.runAsync(0, null).asDeferred()
                        val stop = try {
                            completion.await()
                        } catch (cancel: CancellationException) {
                            simulator.close()
                            // Closing the source releases the simulator even if a syscall is blocked in read().
                            IOUtil.closeSafe(thisLogger(), input.source())
                            withContext(NonCancellable) { completion.await() }
                            throw cancel
                        }
                        when (stop.reason()) {
                            StopEvent.Reason.NORMAL_EXIT, StopEvent.Reason.CLIFF_TERMINATION -> stop.exitCode()
                            else -> 1
                        }
                    }
                }
            }
        }
    }
}

/** A descriptor write can split a UTF-8 code point across multiple flushes. */
internal class ConsoleOutput(private val emit: (String) -> Unit) : ByteArrayOutputStream() {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(REPLACE).onUnmappableCharacter(REPLACE)

    override fun flush() = drain(false)
    override fun close() = drain(true)

    private fun drain(endOfInput: Boolean) {
        val input = ByteBuffer.wrap(buf, 0, count)
        val decoded = CharBuffer.allocate(count)
        decoder.decode(input, decoded, endOfInput)
        count = input.remaining()
        input.get(buf, 0, count)
        if (decoded.position() > 0) emit(decoded.flip().toString())
    }
}
