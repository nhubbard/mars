package edu.missouristate.mars.plugin.debug

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.build.MemoryExportCapture
import edu.missouristate.mars.plugin.build.MemoryExportOptions
import edu.missouristate.mars.plugin.build.captureMemory
import edu.missouristate.mars.plugin.build.memoryExportMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

internal suspend fun MarsSuspendContext.captureForExport(options: MemoryExportOptions): MemoryExportCapture {
    val result = CompletableDeferred<MemoryExportCapture>(currentCoroutineContext().job)
    val request = MemoryExportRequest(options, result)
    if (memoryExports.trySend(request).isFailure) request.unavailable()
    return result.await()
}

internal class MemoryExportRequest(
    private val options: MemoryExportOptions, private val result: CompletableDeferred<MemoryExportCapture>,
) {
    fun capture(machine: MarsContext) {
        try {
            result.ensureActive()
            val capture = machine.claimStoppedSession().use {
                captureMemory(options, machine, result::ensureActive)
            }
            result.complete(capture)
        } catch (cancel: CancellationException) {
            result.cancel(cancel)
        }
    }

    fun unavailable() {
        result.complete(MemoryExportCapture.Failed(memoryExportMessages.getMessage("export.suspension.unavailable")))
    }
}
