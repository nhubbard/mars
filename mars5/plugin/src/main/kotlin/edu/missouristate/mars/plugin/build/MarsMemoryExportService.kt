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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
internal class MarsMemoryExportService(private val project: Project, private val scope: CoroutineScope) {
    private val messages = memoryExportMessages

    fun exportInBackground(entry: Path, options: MemoryExportOptions, output: Path) = scope.launch(Dispatchers.Default) {
        val error = withBackgroundProgress(project, messages.getMessage("export.progress")) {
            export(entry, options, output)
        }
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
            withContext(Dispatchers.IO) { writeMemory(options, output, session) }
        }
    }

    private suspend fun writeMemory(options: MemoryExportOptions, output: Path, session: MarsContext): String? {
        val memory = session.memory
        val (first, limit) = when (options.region) {
            MemoryExportRegion.TEXT -> memory.textBaseAddress to memory.textLimitAddress.toUInt().toLong()
            MemoryExportRegion.DATA -> memory.dataBaseAddress to memory.dataSegmentLimitAddress.toUInt().toLong()
            MemoryExportRegion.RANGE -> options.range.first.toInt() to options.range.last.toLong() + WORD_LENGTH_BYTES
        }
        if (options.format is SegmentWindowDumpFormat) {
            val last = (limit - WORD_LENGTH_BYTES).toInt()
            val userText = memory.inTextSegment(first) && memory.inTextSegment(last)
            val userData = memory.inDataSegment(first) && memory.inDataSegment(last)
            if (!userText && !userData) return messages.getMessage("export.window.range")
        }
        try {
            val start = first.toUInt().toLong()
            var address = start
            while (address < limit) {
                checkCanceled()
                if (memory.getRawWordOrNull(address.toInt()) == null) {
                    if (options.region == MemoryExportRegion.RANGE) {
                        return messages.getMessage("export.unpopulated", Binary.intToHexString(address.toInt()))
                    }
                    break
                }
                address += WORD_LENGTH_BYTES
            }
            if (address == start) return messages.getMessage("export.empty")
            val temporary = Files.createTempFile(output.toAbsolutePath().parent, ".mars-memory-", ".tmp")
            try {
                options.format.dumpMemoryRange(temporary.toFile(), first, (address - WORD_LENGTH_BYTES).toInt(), session)
                checkCanceled()
                Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (e: AddressErrorException) {
            return messages.getMessage("export.invalid.memory", Binary.intToHexString(e.address))
        } catch (e: IOException) {
            return messages.getMessage("export.io.error", output, e.localizedMessage)
        }
        return null
    }
}
