package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.run.MarsProcessHandler

internal val marsToolId = Key.create<String>("MARS_TOOL_ID")
internal val memoryGridControls = Key.create<MemoryGridView>("MARS_MEMORY_GRID_CONTROLS")

internal data class SourceToolHeader(val line: Int, val id: String, val properties: Map<String, String>)

private val toolPrefix = Regex("""^#\s*mars:tool(?:\s+|$)""")
private val whitespace = Regex("""\s+""")

internal fun sourceToolHeaders(text: String, warn: (Int, String) -> Unit): List<SourceToolHeader> {
    val tools = linkedMapOf<String, SourceToolHeader>()
    val invalid = mutableSetOf<String>()
    for ((index, raw) in text.lineSequence().withIndex()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (!line.startsWith("#")) break
        val prefix = toolPrefix.find(line) ?: continue
        val tokens = line.substring(prefix.range.last + 1).trim().split(whitespace)
        val id = tokens.first()
        val lineNumber = index + 1
        try {
            require(id.isNotEmpty()) { "Expected a tool ID" }
            val previous = tools[id]
            val properties = previous?.properties.orEmpty().toMutableMap()
            for (token in tokens.drop(1)) {
                val key = token.substringBefore('=')
                val value = token.substringAfter('=', "")
                require(key.isNotEmpty() && value.isNotEmpty()) { "Expected key=value, found $token" }
                require(properties.putIfAbsent(key, value) == null) { "Duplicate setting $key for $id" }
            }
            tools[id] = SourceToolHeader(previous?.line ?: lineNumber, id, properties.toMap())
        } catch (error: IllegalArgumentException) {
            invalid.add(id)
            warn(lineNumber, error.message!!)
        }
    }
    return tools.values.filter { it.id !in invalid }
}

internal fun configureSourceTools(
    process: MarsProcessHandler,
    memory: MemoryState,
    text: String,
    lifetime: Disposable,
    warn: (Int, String) -> Unit,
): List<String> =
    sourceToolHeaders(text, warn).mapNotNull { header ->
        try {
            configureTool(process, memory, header, lifetime)
            header.id
        } catch (error: IllegalArgumentException) {
            warn(header.line, "${header.id}: ${error.message ?: "Invalid tool settings"}")
            null
        }
    }

internal fun revealSourceTools(project: Project, process: MarsProcessHandler, configured: List<String>) {
    val first = configured.firstOrNull() ?: return
    ApplicationManager.getApplication().invokeLater(Runnable {
        if ("bitmap" in configured) openBitmapDisplay(project, process, focus = false)
        val window = ToolWindowManager.getInstance(project).getToolWindow("MARS Tools") ?: return@Runnable
        val manager = window.contentManager
        val content = manager.contents.firstOrNull { it.getUserData(marsToolId) == first } ?: return@Runnable
        manager.setSelectedContent(content)
        content.getUserData(memoryGridControls)?.showSettings(process)
        window.show()
    }, project.disposed)
}

private fun configureTool(
    process: MarsProcessHandler, memory: MemoryState, header: SourceToolHeader, lifetime: Disposable,
) {
    val settings = ToolSettings(header.properties)
    when (header.id) {
        "bitmap", "references" -> {
            val bitmap = header.id == "bitmap"
            val display = if (bitmap) process.bitmapDisplay else process.memoryReferences
            val connected = settings.boolean("connected", true)
            val defaults = display.controls().options
            val base = settings.choice("base", defaults.base)
            require(!bitmap || base !in listOf(MemoryGridDisplay.Base.TEXT, MemoryGridDisplay.Base.WIDE_TEXT)) {
                "Bitmap Display requires a data base"
            }
            require(base.range(memory) != null) { "Base ${base.name.lowercase()} is unavailable in this memory layout" }
            val options = defaults.copy(
                width = settings.integer("width", defaults.width),
                height = settings.integer("height", defaults.height),
                unitWidth = settings.integer("unitWidth", defaults.unitWidth),
                unitHeight = settings.integer("unitHeight", defaults.unitHeight),
                base = base,
                wordsPerCell = if (bitmap) 1 else settings.integer("wordsPerCell", defaults.wordsPerCell),
            )
            require(bitmap || options.width in MemoryGridDisplay.displaySizes && options.height in MemoryGridDisplay.displaySizes) {
                "Use a standard display size for memory references"
            }
            settings.finish()
            display.configure(options)
            display.setSourceConnection(connected)
        }
        "counter" -> process.instructionCounter.setConnected(settings.connection())
        "statistics" -> process.instructionStatistics.setConnected(settings.connection())
        "digitalLab" -> process.digitalLab.setSourceConnection(settings.connection())
        "marsBot" -> {
            val connected = settings.boolean("connected", true)
            val instructions = settings.integer("instructionsPerTick", MarsBotDevice.DEFAULT_INSTRUCTIONS_PER_TICK)
            settings.finish()
            require(!connected || !process.connectScavengerHunt) { toolsMessages.getMessage("run.connect.deviceConflict") }
            process.marsBot.setInstructionsPerTick(instructions)
            process.marsBot.setSourceConnection(connected)
        }
        "xray" -> process.xray.setCaptureEnabled(settings.connection())
        "float" -> settings.finish()
        "scavenger" -> {
            val connected = settings.connection()
            require(!connected || !process.marsBot.snapshot().connected) { toolsMessages.getMessage("run.connect.deviceConflict") }
            process.connectScavengerHunt = connected
        }
        "branchHistory" -> {
            val connected = settings.boolean("connected", true)
            val defaults = BranchHistoryTable.Options()
            val options = BranchHistoryTable.Options(
                settings.integer("size", defaults.size),
                settings.integer("historyBits", defaults.historyBits),
                settings.boolean("initialTaken", defaults.initialTaken),
            )
            settings.finish()
            process.branchHistory.configure(options)
            process.branchHistory.setConnected(connected)
        }
        "cache" -> {
            val connected = settings.boolean("connected", true)
            val logging = settings.boolean("logging", false)
            val defaults = DataCacheSimulator.Options()
            val blocks = settings.integer("blocks", defaults.blocks)
            val placement = settings.choice("placement", defaults.placement)
            val options = DataCacheSimulator.Options(
                blocks, settings.integer("wordsPerBlock", defaults.wordsPerBlock), placement,
                settings.integer("ways", placement.wayChoices(blocks).firstOrNull() ?: 1),
                settings.choice("replacement", defaults.replacement),
            )
            settings.finish()
            process.dataCache.configure(options)
            process.dataCache.setConnected(connected)
            process.dataCache.setLogging(logging)
        }
        "keyboardDisplay" -> {
            val connected = settings.boolean("connected", true)
            val defaults = KeyboardDisplayDevice.Options()
            val options = KeyboardDisplayDevice.Options(
                settings.choice("delay", defaults.delay),
                settings.integer("instructions", defaults.instructions),
                settings.boolean("displayAfterDelay", defaults.displayAfterDelay),
            )
            settings.finish()
            process.keyboardDisplay.configure(options)
            process.keyboardDisplay.setSourceConnection(connected)
        }
        else -> {
            val provider = MarsToolProvider.EP.extensionList.firstOrNull { it.id == header.id }
            requireNotNull(provider) { "Unknown tool ID" }
            if (provider is MarsSourceConfiguredToolProvider) {
                provider.configureExecution(process, header.properties, lifetime)
            } else {
                settings.finish()
            }
        }
    }
}

private class ToolSettings(properties: Map<String, String>) {
    private val remaining = properties.toMutableMap()

    fun integer(key: String, default: Int): Int = remaining.remove(key)?.let {
        requireNotNull(it.toIntOrNull()) { "Expected an integer for $key" }
    } ?: default

    fun boolean(key: String, default: Boolean): Boolean = remaining.remove(key)?.let {
        requireNotNull(it.toBooleanStrictOrNull()) { "Expected true or false for $key" }
    } ?: default

    inline fun <reified E : Enum<E>> choice(key: String, default: E): E = remaining.remove(key)?.let { value ->
        requireNotNull(enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }) {
            "Expected one of ${enumValues<E>().joinToString { it.name.lowercase() }} for $key"
        }
    } ?: default

    fun connection(): Boolean = boolean("connected", true).also { finish() }

    fun finish() {
        require(remaining.isEmpty()) { "Unknown settings: ${remaining.keys.joinToString()}" }
    }
}
