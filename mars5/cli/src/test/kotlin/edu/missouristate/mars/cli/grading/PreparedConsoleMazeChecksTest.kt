package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.DaggerMarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Constant publication probes, deliberately not a general maze implementation. */
class PreparedConsoleMazeChecksTest {
    @TempDir lateinit var directory: Path
    private val guards = listOf("frame_guard", "walls_guard", "start_guard", "goal_guard", "states_guard",
        "parents_guard", "distance_guard", "queue_guard", "path_guard", "metadata_guard")
    private val arrays = listOf("walls", "states", "parents", "distance", "queue", "path")
    private val metadata = listOf("start", "goal", "head", "tail", "current", "phase", "result", "length")
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private fun image(source: String, littleEndian: Boolean = true) = session().use { machine ->
        machine.memory.byteOrder = littleEndian
        val entry = directory.resolve("probe.asm")
        try {
            machine.assembleSnapshot(AssemblySourceSnapshot(listOf(entry), mapOf(entry to source)), null, true, false)
        } catch (failure: ProcessingException) {
            throw AssertionError(failure.errors()?.generateErrorAndWarningReport(), failure)
        }
        machine.captureAssemblyImage {}
    }
    private fun evaluate(source: String, check: ConsoleMazeCheck, littleEndian: Boolean = true): ConsoleCheckResult =
        PreparedConsoleChecks.evaluate(image(source, littleEndian), listOf(ConsoleCheckCase("maze probe", "", "", 30000, maze = check)), ::session).single()

    private fun store(label: String, value: Int, offset: Int = 0, opcode: String = "sw") =
        "li \$t0, $value\nla \$t1, $label\n$opcode \$t0, $offset(\$t1)\n"

    private fun StringBuilder.word(label: String, value: Int, offset: Int = 0) {
        append(store(label, value, offset))
    }
    private fun StringBuilder.bytes(label: String, values: List<Int>) {
        for ((index, value) in values.withIndex()) append(store(label, value, index, "sb"))
    }
    private fun header() = buildString {
        append(".data 0x10010000\nframe: .space 256\nframe_guard: .word 0\n")
        for (label in arrays) append("$label: .space 64\n")
        for (label in guards.drop(1) + metadata) append("$label: .word 0\n")
        append(".text\n.globl main\nmain:\n")
    }
    private fun footer() = "done:\nli \$v0,10\nsyscall\nmaze_ready:\njrc \$ra\n"
    private fun color(wall: Int, state: Int, path: Int) = when {
        wall == 1 -> 0x202020
        path == 1 -> 0x0072b2
        state == 0 -> 0xffffff
        state == 1 -> 0xe69f00
        else -> 0x009e73
    }

    private fun search(open: Set<Int>, start: Int, goal: Int, discoveries: List<List<Int>>, route: List<Int>): Pair<String, ConsoleMazeCheck.Search> {
        val walls = List(64) { if (it in open) 0 else 1 }
        val check = ConsoleMazeCheck.Search(walls.joinToString("") { "0$it" }, start, goal, 23)
        val states = MutableList(64) { 0 }.also { it[start] = 1 }
        val parents = MutableList(64) { 255 }
        val distances = MutableList(64) { 255 }.also { it[start] = 0 }
        val queue = mutableListOf(start)
        val path = MutableList(64) { 0 }
        var head = 0
        var current = -1
        return buildString {
            append(header())
            fun publish(phase: Int, result: Int = -1, length: Int = -1) {
                word("phase", phase); word("head", head); word("tail", queue.size)
                word("current", current); word("result", result); word("length", length)
                bytes("states", states); bytes("parents", parents); bytes("distance", distances)
                bytes("queue", queue + List(64 - queue.size) { 0 }); bytes("path", path)
                for (cell in 0..63) word("frame", color(walls[cell], states[cell], path[cell]), cell * 4)
                append("balc maze_ready\n")
            }
            publish(0)
            for (suffix in discoveries) {
                current = queue[head++]
                states[current] = 2
                for (cell in suffix) { queue += cell; states[cell] = 1; parents[cell] = current; distances[cell] = distances[current] + 1 }
                publish(1)
            }
            for (cell in route) path[cell] = 1
            publish(2, if (route.isEmpty()) 0 else 1, if (route.isEmpty()) -1 else route.size - 1)
            append(footer())
        } to check
    }

    @Test
    fun `different equal shortest paths zero edge and disconnected searches retain exact publication contracts`() {
        val fixtures = listOf(
            search(setOf(0, 1, 8, 9), 0, 9, listOf(listOf(1, 8), listOf(9), emptyList(), emptyList()), listOf(0, 1, 9)),
            search(setOf(0, 1, 8, 9), 0, 9, listOf(listOf(8, 1), listOf(9), emptyList(), emptyList()), listOf(0, 8, 9)),
            search(setOf(63), 63, 63, listOf(emptyList()), listOf(63)),
            search(setOf(7, 8), 7, 8, listOf(emptyList()), emptyList()),
        )
        for ((source, check) in fixtures) for (littleEndian in listOf(true, false)) {
            val result = evaluate(source, check, littleEndian)
            assertTrue(result.passed, result.failure)
        }
    }

    @Test
    fun `search rejects incorrect discoveries overwritten history intermediate state path and extra publication`() {
        val (source, check) = search(setOf(0, 1, 8, 9), 0, 9,
            listOf(listOf(1, 8), listOf(9), emptyList(), emptyList()), listOf(0, 1, 9))
        val faults = listOf(
            source.replaceFirst(store("queue", 1, 1, "sb"), store("queue", 8, 1, "sb")),
            source.replaceFirst(store("queue", 0, 0, "sb"), store("queue", 1, 0, "sb")),
            source.replaceFirst(store("phase", 1, 0), store("phase", 2, 0)),
            source.replaceFirst(store("parents", 255, 0, "sb"), store("parents", 0, 0, "sb")),
            source.replaceFirst(store("distance", 0, 0, "sb"), store("distance", 1, 0, "sb")),
            source.replaceFirst(store("length", 2, 0), store("length", 3, 0)),
            source.replace("done:\n", "balc maze_ready\ndone:\n"),
            source.replace("done:\n", "li \$t0,0\nsb \$t0,path\ndone:\n"),
            source.replace("main:\n", "main:\nlbu \$t0,walls\nsb \$t0,walls\n"),
            source.replaceFirst(store("path", 0, 0, "sb"), store("path", 1, 0, "sb")),
            source.replaceFirst(store("head", 0, 0), store("head", 1, 0)),
            source.replaceFirst(store("states", 0, 1, "sb"), store("states", 2, 1, "sb")),
            source.replaceFirst("li \$t0, 15113984", "li \$t0, 0"),
        )
        for ((index, fault) in faults.withIndex()) {
            assertTrue(fault != source, "fault $index must change the probe")
            val result = evaluate(fault, check)
            assertFalse(result.passed, "fault $index escaped")
        }
        val omitted = search(setOf(0, 1), 0, 1, listOf(emptyList()), emptyList())
        assertFalse(evaluate(omitted.first, omitted.second).passed)
        val diagonal = search(setOf(0, 9), 0, 9, listOf(listOf(9), emptyList()), listOf(0, 9))
        assertFalse(evaluate(diagonal.first, diagonal.second).passed)
    }

    private fun renderer(fixture: Int): String = buildString {
        append(header()); append("bc done\nrender:\n")
        for (cell in 0..63) {
            val wall = if (fixture == 1 || fixture >= 3 && cell % 4 == 0) 1 else 0
            val state = if (fixture < 2) 0 else (cell + fixture) % 3
            val path = if (fixture >= 3 && cell % (fixture - 1) == 0) 1 else 0
            word("frame", color(wall, state, path), cell * 4)
        }
        append("bc maze_ready\n"); append(footer())
    }

    @Test
    fun `renderer checks every pixel and preserves full registers and protected data at its boundary`() {
        for (fixture in 0..4) {
            val result = evaluate(renderer(fixture), ConsoleMazeCheck.Renderer(fixture))
            assertTrue(result.passed, result.failure)
        }
        for (injection in listOf(
            "li \$s7,7\n", "addiu \$sp,\$sp,-16\n", "li \$t0,0\nla \$t1,frame\nsw \$t0,252(\$t1)\n",
            "lbu \$t0,states\nsb \$t0,states\n", "li \$t0,0\nsw \$t0,metadata_guard\n",
        )) {
            val result = evaluate(renderer(3).replace("bc maze_ready", injection + "bc maze_ready"), ConsoleMazeCheck.Renderer(3))
            assertFalse(result.passed, injection)
        }
        val lateRestore = renderer(0).replace("bc maze_ready", "move \$t1,\$s0\nli \$s0,0\nbc maze_ready")
            .replace("maze_ready:\n", "maze_ready:\nmove \$s0,\$t1\n")
        assertFalse(evaluate(lateRestore, ConsoleMazeCheck.Renderer(0)).passed)
    }

    @Test
    fun `cancellation at a publication unwinds memory observers and a clean retry uses a fresh machine`() {
        val (source, check) = search(setOf(63), 63, 63, listOf(emptyList()), listOf(63))
        val entry = directory.resolve("cancel.asm")
        session().use { machine ->
            val program = machine.assembleSnapshot(AssemblySourceSnapshot(listOf(entry), mapOf(entry to source)), null, true, false)
            assertThrows<CancellationException> {
                AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { execution ->
                    check.run(execution) { throw CancellationException("cancel at snapshot") }
                }
            }
            assertEquals(0, machine.memory.countObservers())
        }
        assertTrue(evaluate(source, check).passed)
        session().use { machine ->
            val program = machine.assembleSnapshot(AssemblySourceSnapshot(listOf(entry), mapOf(entry to source)), null, true, false)
            AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { check.verify(it) }
            assertEquals(0, machine.memory.countObservers())
        }
    }

    @Test
    fun `renderer rejects unaligned public byte arrays rather than silently accepting a different ABI`() {
        val source = renderer(0).replace("walls: .space 64", ".space 1\nwalls: .space 64")
        val result = evaluate(source, ConsoleMazeCheck.Renderer(0))
        assertFalse(result.passed)
        assertTrue(result.failure!!.contains("walls must have a four-byte-aligned base"), result.failure)
    }

    @Test
    fun `missing labels traps and exhausted instruction budgets are formative failures`() {
        val check = ConsoleMazeCheck.Search("00".repeat(64), 0, 63, 0)
        assertFalse(evaluate(".text\nli \$v0,10\nsyscall\n", check).passed)
        assertFalse(evaluate(header() + "break\n" + footer(), check).passed)
        assertFalse(evaluate(header() + "again: bc again\n" + footer(), check).passed)
    }
}
