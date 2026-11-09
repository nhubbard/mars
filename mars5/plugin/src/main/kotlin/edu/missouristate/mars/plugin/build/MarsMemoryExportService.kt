package edu.missouristate.mars.plugin.build

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.dump.MemoryDumpSnapshot
import edu.missouristate.mars.mips.dump.SegmentWindowDumpFormat
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState.WORD_LENGTH_BYTES
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.util.Binary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
internal class MarsMemoryExportService(private val project: Project, private val scope: CoroutineScope) {
    private val messages = memoryExportMessages

    fun exportInBackground(entry: Path, options: MemoryExportOptions, output: Path) =
        exportInBackground(output) { export(entry, options, output) }

    fun exportCapturedInBackground(
        options: MemoryExportOptions, output: Path, capture: suspend () -> MemoryExportCapture,
    ) = exportInBackground(output) { writeCapture(options, output, capture()) }

    private fun exportInBackground(output: Path, export: suspend () -> String?) = scope.launch(Dispatchers.Default) {
        val error = withBackgroundProgress(project, messages.getMessage("export.progress")) { export() }
        val group = NotificationGroupManager.getInstance().getNotificationGroup("MARS Memory Export")
        if (error == null) {
            LocalFileSystem.getInstance().refreshNioFiles(listOf(output), true, false, null)
            group.createNotification(escapeXmlEntities(messages.getMessage("export.done", output)), NotificationType.INFORMATION)
                .notify(project)
        } else {
            group.createNotification(escapeXmlEntities(error), NotificationType.ERROR).notify(project)
        }
    }

    suspend fun export(entry: Path, options: MemoryExportOptions, output: Path): String? {
        val component = MarsSessionService.createMarsComponent()
        return component.context().use { session ->
            if (!project.service<MarsBuildService>().assemble(entry, component, activateBuildView = true)) {
                return messages.getMessage("run.assembly.failed")
            }
            val caller = currentCoroutineContext()
            val capture = captureMemory(options, session, caller::ensureActive)
            writeCapture(options, output, capture)
        }
    }

    private suspend fun writeCapture(
        options: MemoryExportOptions, output: Path, capture: MemoryExportCapture,
    ): String? = when (capture) {
        is MemoryExportCapture.Failed -> capture.message
        is MemoryExportCapture.Ready -> withContext(Dispatchers.IO) { writeMemory(options, output, capture.snapshot) }
    }

    private suspend fun writeMemory(options: MemoryExportOptions, output: Path, snapshot: MemoryDumpSnapshot): String? {
        try {
            val temporary = Files.createTempFile(output.toAbsolutePath().parent, ".mars-memory-", ".tmp")
            try {
                options.format.write(temporary.toFile(), snapshot)
                checkCanceled()
                Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (e: IOException) {
            return messages.getMessage("export.io.error", output, e.localizedMessage)
        }
        return null
    }
}

internal sealed interface MemoryExportCapture {
    data class Ready(val snapshot: MemoryDumpSnapshot) : MemoryExportCapture
    data class Failed(val message: String) : MemoryExportCapture
}

internal fun captureMemory(
    options: MemoryExportOptions, session: MarsContext, checkCancellation: () -> Unit,
): MemoryExportCapture {
    val messages = memoryExportMessages
    val memory = session.memory
    val (first, last) = when (options.region) {
        MemoryExportRegion.TEXT -> memory.textBaseAddress to memory.textLimitAddress - WORD_LENGTH_BYTES
        MemoryExportRegion.DATA -> memory.dataBaseAddress to memory.dataSegmentLimitAddress - WORD_LENGTH_BYTES
        MemoryExportRegion.RANGE -> options.range.first.toInt() to options.range.last.toInt()
    }
    if (options.format is SegmentWindowDumpFormat) {
        val userText = memory.inTextSegment(first) && memory.inTextSegment(last)
        val userData = memory.inDataSegment(first) && memory.inDataSegment(last)
        if (!userText && !userData) return MemoryExportCapture.Failed(messages.getMessage("export.window.range"))
    }
    val snapshot = try {
        options.format.capture(session, first, last, checkCancellation)
    } catch (e: AddressErrorException) {
        return MemoryExportCapture.Failed(messages.getMessage("export.invalid.memory", Binary.intToHexString(e.address)))
    }
    val nextAddress = first.toUInt().toLong() + snapshot.size().toLong() * WORD_LENGTH_BYTES
    if (options.region == MemoryExportRegion.RANGE && nextAddress <= last.toUInt().toLong()) {
        return MemoryExportCapture.Failed(messages.getMessage("export.unpopulated", Binary.intToHexString(nextAddress.toInt())))
    }
    if (snapshot.size() == 0) return MemoryExportCapture.Failed(messages.getMessage("export.empty"))
    return MemoryExportCapture.Ready(snapshot)
}
