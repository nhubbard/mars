package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class MarsNativeAssessmentProfilesTest {
    @TempDir lateinit var directory: Path
    private val content = """{"formatVersion":8,"introduction":"Inspect the prefix.","conclusion":"Explain the boundary.","steps":[{"kind":"assessment","id":"scan","explanation":"Check the scanner.","source":"scan.asm","caseProfiles":["first.json","second.json"],"checkKind":"function","success":"Explain the results."}]}"""
    private fun root() = JsonParser.parseString(content).asJsonObject
    private fun JsonObject.step() = getAsJsonArray("steps")[0].asJsonObject
    private fun paths(vararg names: String) = JsonArray().apply { names.forEach(::add) }
    private fun profile(prefix: String, count: Int = 1, calls: Int = 1, budget: Int = 1_000_000): ByteArray {
        val call = """{"entry":"scan_delta","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"}}"""
        val rows = (0 until count).joinToString(",") {
            """{"name":"$prefix-$it","instructionLimit":$budget,"calls":[${List(calls) { call }.joinToString(",")}]}"""
        }
        return """{"formatVersion":9,"cases":[$rows]}""".toByteArray()
    }
    private fun parse(root: JsonObject = root(), data: (String) -> ByteArray = { profile(it) }) =
        MarsNativeLessonPlan.parse(root.toString().toByteArray(), data)

    @Test
    fun `one student check retains profile order budgets and immutable results`() {
        val reads = mutableListOf<String>()
        val plan = parse(data = { reads += it; profile(it, count = 10) })
        val step = plan.steps.single() as MarsNativeAssessmentStep
        assertEquals(listOf("first.json", "second.json"), step.caseProfiles)
        assertEquals(reads, step.caseProfiles)
        assertEquals(20, step.functionCases.size)
        assertEquals(listOf(10, 10), step.functionCaseBatches.map { it.size })
        assertThrows<UnsupportedOperationException> { (step.functionCaseBatches as MutableList).clear() }
        assertThrows<IllegalArgumentException> { step.copy(functionCaseBatches = listOf(step.functionCases.take(1))) }
        assertThrows<IllegalArgumentException> { step.copy(consoleCaseBatches = listOf(listOf(
            ConsoleCheckCase("unexpected", "", "", 100)))) }
        assertEquals(20_000_000L, step.functionCases.sumOf { it.instructionLimit })
        assertEquals(listOf("first.json-9", "second.json-0"), step.functionCases.slice(9..10).map { it.name })
        assertThrows<UnsupportedOperationException> { (step.caseProfiles as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (step.functionCases as MutableList).clear() }
    }

    @Test
    fun `one profile stays compatible and shared data is read once across activities`() {
        val root = root().apply { step().remove("caseProfiles"); step().addProperty("caseProfile", "first.json") }
        root.getAsJsonArray("steps").add(root.step().deepCopy().apply { addProperty("id", "again") })
        var reads = 0
        val plan = parse(root) { reads++; profile("shared") }
        assertEquals(1, reads)
        val steps = plan.steps.map { it as MarsNativeAssessmentStep }
        assertSame(steps[0].functionCases, steps[1].functionCases)
        assertEquals(listOf("first.json"), steps[0].caseProfiles)
    }

    @Test
    fun `multi-profile assessment uses existing console parser and can reach the full 32 profile bound`() {
        val root = root().apply { step().addProperty("checkKind", "console"); step().add("caseProfiles", paths(*(0..31).map { "$it.json" }.toTypedArray())) }
        val plan = parse(root) { name -> """{"formatVersion":1,"cases":[{"name":"$name","input":"","stdout":"","instructionLimit":1000000}]}""".toByteArray() }
        val step = plan.steps.single() as MarsNativeAssessmentStep
        assertEquals(32, step.cases.size)
        assertEquals(32_000_000L, step.cases.sumOf { it.instructionLimit })
        assertThrows<UnsupportedOperationException> { (step.cases as MutableList).clear() }
    }

    @Test
    fun `references require an explicit format one spelling distinct safe bounded paths and valid arrays`() {
        val edits = listOf<JsonObject.() -> Unit>(
            { addProperty("formatVersion", 7) },
            { step().addProperty("caseProfile", "first.json") },
            { step().remove("caseProfiles") },
            { step().add("caseProfiles", paths()) },
            { step().add("caseProfiles", paths("first.json", "first.json")) },
            { step().add("caseProfiles", paths("../outside.json")) },
            { step().add("caseProfiles", paths("private/answers.json")) },
            { step().add("caseProfiles", paths("a".repeat(241))) },
            { step().add("caseProfiles", paths("")) },
            { step().add("caseProfiles", paths(*(0..32).map { "$it.json" }.toTypedArray())) },
            { step().addProperty("caseProfiles", "first.json") },
            { step().add("caseProfiles", JsonArray().apply { add(1) }) },
            { step().add("caseProfiles", JsonArray().apply { add(JsonObject()) }) },
        )
        for (edit in edits) assertThrows<IllegalArgumentException> { parse(root().apply(edit)) }
        for (version in 4..7) assertThrows<IllegalArgumentException> { parse(root().apply { addProperty("formatVersion", version) }) }
    }

    @Test
    fun `profile errors duplicate case names and aggregate case and call excesses fail before execution`() {
        assertThrows<IllegalArgumentException> { parse { profile("same") } }
        assertThrows<IllegalArgumentException> { parse { "{}".toByteArray() } }
        assertThrows<IllegalArgumentException> { parse { profile(it, count = 11) } }
        assertThrows<IllegalArgumentException> { parse { profile(it, count = 7, calls = 300, budget = 100) } }
        val root = root().apply { step().add("caseProfiles", paths("first.json", "second.json", "third.json")) }
        assertThrows<IllegalArgumentException> { parse(root) { profile(it, count = if (it == "third.json") 1 else 256, budget = 100) } }
        val accepted = parse { profile(it, count = 256, budget = 100) }.steps.single() as MarsNativeAssessmentStep
        assertEquals(512, accepted.functionCases.size)
        val console = root().apply { step().addProperty("checkKind", "console") }
        assertThrows<IllegalArgumentException> {
            parse(console) { """{"formatVersion":1,"cases":[{"name":"duplicate","input":"","stdout":"","instructionLimit":100}]}""".toByteArray() }
        }
    }

    @Test
    fun `archive validates every referenced profile before preview and snapshots all imported checks`() {
        fun archive(omitSecond: Boolean = false, badSecond: Boolean = false): Path {
            val archive = Files.createTempFile(directory, "profiles", ".zip")
            val manifest = """{"formatVersion":1,"id":"023-delta-test","version":"0.1.0","title":"Scanner checks","entry":"main.asm","assignment":"assignment.md","nativeLesson":"lesson.json","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
            val files = mutableMapOf("package.json" to manifest.toByteArray(), "main.asm" to ".text\nmain: nop\n".toByteArray(),
                "assignment.md" to "Write the scanner.".toByteArray(), "lesson.json" to content.toByteArray(),
                "scan.asm" to ".text\nscan_delta: nop\n".toByteArray(), "first.json" to profile("first"))
            if (!omitSecond) files["second.json"] = if (badSecond) "{}".toByteArray() else profile("second")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry("023-delta-test/$name")); zip.write(bytes); zip.closeEntry() }
            }
            return archive
        }
        MarsLessonPackages.prepare(archive()).use { prepared ->
            val step = prepared.nativeLesson!!.steps.single() as MarsNativeAssessmentStep
            val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
            for (path in step.caseProfiles) {
                assertTrue(Files.isRegularFile(installed.directory.resolve(path)))
                Files.writeString(installed.directory.resolve(path), "changed working copy")
            }
            assertEquals(listOf("first-0", "second-0"), step.functionCases.map { it.name })
        }
        assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(omitSecond = true)).close() }
        assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(badSecond = true)).close() }
    }
}
