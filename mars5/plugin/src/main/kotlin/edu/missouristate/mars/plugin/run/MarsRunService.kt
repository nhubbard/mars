package edu.missouristate.mars.plugin.run

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.EventDispatcher
import com.intellij.util.io.IOUtil
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.build.MarsMemoryExportService
import edu.missouristate.mars.plugin.build.MemoryExportSelection
import edu.missouristate.mars.plugin.build.memoryExportMessages
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay
import edu.missouristate.mars.plugin.tools.BranchHistoryTable
import edu.missouristate.mars.plugin.tools.DataCacheSimulator
import edu.missouristate.mars.plugin.tools.DigitalLabDevice
import edu.missouristate.mars.plugin.tools.InstructionCounter
import edu.missouristate.mars.plugin.tools.InstructionStatistics
import edu.missouristate.mars.plugin.tools.KeyboardDisplayDevice
import edu.missouristate.mars.plugin.tools.MarsBotDevice
import edu.missouristate.mars.plugin.tools.MarsToolExecution
import edu.missouristate.mars.plugin.tools.MarsToolInstructionListener
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice
import edu.missouristate.mars.plugin.tools.xray.XRayTrace
import edu.missouristate.mars.simulator.SimulatorEngine
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.charset.CodingErrorAction.REPLACE
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.IntPredicate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
internal class MarsRunService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    init {
        project.messageBus.connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                env.getUserData(launchAssemblyKey)?.let(Disposer::dispose)
            }
        })
    }

    override fun dispose() = Unit

    fun prepareLaunch(launch: MarsLaunchAssembly) = scope.async(Dispatchers.Default) {
        withBackgroundProgress(project, runMessages.getMessage("run.before.launch.assemble")) {
            if (launch.hasProgram()) return@withBackgroundProgress true
            if (!Disposer.tryRegister(this@MarsRunService, launch)) return@withBackgroundProgress false
            val program = project.service<MarsBuildService>().prepare(launch.sources, launch.options, activateBuildView = true)
            program != null && launch.store(program)
        }
    }

    fun createProcess(
        entry: Path,
        options: MarsExecutionOptions = project.service<MarsAssemblySettings>().executionOptions,
        memoryDumps: List<MemoryExportSelection>,
        captureBitmap: Boolean = false,
        captureReferences: Boolean = false,
        connectDigitalLab: Boolean = false,
        connectKeyboardDisplay: Boolean = false,
        connectMarsBot: Boolean = false,
        connectScavengerHunt: Boolean = false,
        launch: MarsLaunchAssembly = MarsLaunchAssembly(MarsSourceSet(entry), options),
        execute: suspend MarsProcessHandler.(MarsContext, CapturedAssemblySources) -> StopEvent = { machine, _ -> runSlice(machine.simulator()) },
    ): MarsProcessHandler {
        require(!connectMarsBot || !connectScavengerHunt) { runMessages.getMessage("run.connect.deviceConflict") }
        return MarsProcessHandler(
            project, scope, launch, Pipe.open(), memoryDumps, captureBitmap, captureReferences,
            connectDigitalLab, connectKeyboardDisplay, connectMarsBot, connectScavengerHunt, execute,
        )
    }
}

internal class MarsProcessHandler(
    private val project: Project, scope: CoroutineScope, private val launch: MarsLaunchAssembly, private val input: Pipe,
    private val memoryDumps: List<MemoryExportSelection>,
    captureBitmap: Boolean,
    captureReferences: Boolean,
    connectDigitalLab: Boolean,
    connectKeyboardDisplay: Boolean,
    connectMarsBot: Boolean,
    private val connectScavengerHunt: Boolean,
    private val execute: suspend MarsProcessHandler.(MarsContext, CapturedAssemblySources) -> StopEvent,
) : ProcessHandler(), MarsToolExecution {
    private val entry = launch.sources.entry
    private val toolInstructions = EventDispatcher.create(MarsToolInstructionListener::class.java)

    override fun addInstructionListener(listener: MarsToolInstructionListener, lifetime: Disposable) {
        toolInstructions.addListener(listener, lifetime)
    }

    val instructionCounter = InstructionCounter()
    val instructionStatistics = InstructionStatistics()
    val branchHistory = BranchHistoryTable()
    val dataCache = DataCacheSimulator()
    val digitalLab = DigitalLabDevice(connectDigitalLab)
    val keyboardDisplay = KeyboardDisplayDevice(connectKeyboardDisplay)
    val marsBot = MarsBotDevice(connectMarsBot)
    private var scavengerHunt: ScavengerHuntDevice? = null
    private var finishedHunt: ScavengerHuntDevice.Snapshot? = null
    val xray = XRayTrace()
    val bitmapDisplay = MemoryGridDisplay(captureBitmap)
    val memoryReferences = MemoryGridDisplay(captureReferences, MemoryGridDisplay.Mode.REFERENCES)
    @Volatile private var readFloatingRegister: ((Int) -> Int)? = null

    @Synchronized
    fun scavengerHuntSnapshot() = scavengerHunt?.snapshot() ?: finishedHunt

    @Synchronized
    fun setScavengerHuntConnected(value: Boolean) {
        if (!value || !marsBot.snapshot().connected) scavengerHunt?.setConnected(value)
    }

    @Synchronized
    fun setMarsBotConnected(value: Boolean) {
        if (!value || scavengerHunt?.snapshot()?.connected != true) marsBot.setConnected(value)
    }

    @Synchronized
    fun resetScavengerHunt() {
        scavengerHunt?.requestReset()
    }

    fun releaseScavengerHuntView() {
        if (!connectScavengerHunt) setScavengerHuntConnected(false)
    }

    fun floatingRegisterBits(number: Int): Int? = readFloatingRegister?.invoke(number)

    // As in IntelliJ's external-task runner, ConsoleView writes to the sink on its input worker.
    private val consoleInput = Channels.newOutputStream(input.sink())
    private val inputCharset = EncodingManager.getInstance().defaultCharset
    private var status = 1
    private val execution = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        status = runProgram()
    }

    init {
        execution.invokeOnCompletion { cause ->
            try {
                IOUtil.closeSafe(thisLogger(), input.source(), input.sink())
            } finally {
                // Native debugger listeners clean up services even when the simulation was canceled.
                ProgressManager.getInstance().executeNonCancelableSection {
                    notifyProcessTerminated(if (cause is CancellationException) 130 else status)
                }
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

    suspend fun runSlice(simulator: SimulatorEngine, steps: Long = 0, breakpoint: IntPredicate? = null): StopEvent =
        awaitSlice(simulator) {
            if (breakpoint == null) simulator.runAsync(steps, null) else simulator.runUntilAsync(steps, breakpoint)
        }

    suspend fun backstepSlice(simulator: SimulatorEngine, wholeSource: Boolean) {
        awaitSlice(simulator) { if (wholeSource) simulator.backstepSourceAsync() else simulator.backstepAsync() }
    }

    private suspend fun <T> awaitSlice(simulator: SimulatorEngine, start: () -> CompletableFuture<T>): T {
        execution.ensureActive()
        val completion = start().asDeferred()
        try {
            return completion.await()
        } catch (cancel: CancellationException) {
            simulator.close()
            // Closing the source releases the simulator even if a syscall is blocked in read().
            IOUtil.closeSafe(thisLogger(), input.source())
            withContext(NonCancellable) { completion.join() }
            throw cancel
        }
    }

    private suspend fun runProgram(): Int {
        val program = launch.take() ?: project.service<MarsBuildService>().prepare(launch.sources, launch.options, activateBuildView = false)
        if (program == null) {
            notifyTextAvailable(runMessages.getMessage("run.assembly.failed") + "\n", ProcessOutputTypes.STDERR)
            return 1
        }
        return MarsSessionService.createMarsComponent().context().use { session ->
            launch.options.applyTo(session.settings)
            session.loadAssemblyImage(program.image, execution::ensureActive)
            val stdout = ConsoleOutput { notifyTextAvailable(it, ProcessOutputTypes.STDOUT) }
            val stderr = ConsoleOutput { notifyTextAvailable(it, ProcessOutputTypes.STDERR) }
            PrintStream(stdout, true, Charsets.UTF_8).use { output ->
                PrintStream(stderr, true, Charsets.UTF_8).use { error ->
                    val stdin = BufferedInputStream(Channels.newInputStream(input.source()))
                    SessionSyscallEnvironment(session.memory, stdin, output, error, entry.parent, inputCharset).use { io ->
                        session.program.setSyscallEnvironment(io)
                        val simulator = session.simulator()
                        simulator.addListener(object : SimulatorListener {
                            override fun onStep(event: StepEvent) {
                                val word = event.instruction()
                                val instruction = session.instructionSet.findByBinaryCode(word)
                                instructionCounter.record(instruction)
                                instructionStatistics.record(word, instruction)
                                branchHistory.record(event)
                                xray.record(event)
                                digitalLab.instructionCompleted(session)
                                keyboardDisplay.instructionCompleted(session)
                                marsBot.instructionCompleted()
                                if (toolInstructions.hasListeners()) toolInstructions.multicaster.instructionCompleted(event)
                            }
                            override fun onBackstep(stepCount: Long) = xray.rewind(stepCount)
                            override fun onStop(event: StopEvent) = Unit
                            override fun onError(event: ErrorEvent) {
                                if (execution.isActive) {
                                    notifyTextAvailable(runMessages.getMessage("run.runtime.error", event.message()) + "\n", ProcessOutputTypes.STDERR)
                                }
                            }
                        })
                        val floatingRegisters = session.coprocessor1.registers
                        readFloatingRegister = { floatingRegisters[it].valueNoNotify }
                        val stop = try {
                            bitmapDisplay.observe(session.memory).use {
                                memoryReferences.observe(session.memory).use {
                                    dataCache.observe(session.memory).use {
                                        digitalLab.observe(session).use {
                                            keyboardDisplay.observe(session).use {
                                                executeWithWorldDevices(session, program.sources)
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            // The execution tab can still show the final words after the session closes.
                            readFloatingRegister = IntArray(floatingRegisters.size) { floatingRegisters[it].valueNoNotify }::get
                        }
                        if (memoryDumps.isNotEmpty() && stop.reason() in dumpReasons) exportAfterRun(session, program.sources.snapshot.texts().keys + entry)
                        when (stop.reason()) {
                            StopEvent.Reason.NORMAL_EXIT, StopEvent.Reason.CLIFF_TERMINATION -> stop.exitCode()
                            else -> 1
                        }
                    }
                }
            }
        }
    }
    private suspend fun executeWithWorldDevices(session: MarsContext, sources: CapturedAssemblySources): StopEvent {
        val (bot, hunt) = synchronized(this) {
            val device = ScavengerHuntDevice(session, connectScavengerHunt).also { scavengerHunt = it }
            marsBot.observe(session) to device
        }
        return bot.use {
            try {
                execute(session, sources)
            } finally {
                synchronized(this) {
                    hunt.close()
                    finishedHunt = hunt.snapshot()
                    scavengerHunt = null
                }
            }
        }
    }

    private suspend fun exportAfterRun(session: MarsContext, sources: Set<Path>) {
        val exporter = project.service<MarsMemoryExportService>()
        for (selection in memoryDumps) {
            val output = selection.output
            val error = try {
                val overwritesSource = withContext(Dispatchers.IO) {
                    val target = output.parent.toRealPath().resolve(output.fileName).toFile().canonicalFile
                    sources.any { FileUtil.filesEqual(it.toFile().canonicalFile, target) }
                }
                if (overwritesSource) runMessages.getMessage("run.export.source", output)
                else exporter.exportSession(session, selection)
            } catch (e: IOException) {
                memoryExportMessages.getMessage("export.io.error", output, e.localizedMessage)
            }
            if (error == null) {
                val message = memoryExportMessages.getMessage("export.done", output)
                notifyTextAvailable("$message\n", ProcessOutputTypes.SYSTEM)
            } else {
                val message = runMessages.getMessage("run.export.failed", output, error)
                notifyTextAvailable("$message\n", ProcessOutputTypes.STDERR)
            }
        }
    }

}

private val dumpReasons = setOf(StopEvent.Reason.NORMAL_EXIT, StopEvent.Reason.CLIFF_TERMINATION, StopEvent.Reason.EXCEPTION)

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
