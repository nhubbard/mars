package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class MarsNativeAssessmentPlanTest {
    @TempDir lateinit var temporary: Path
    private val profile = javaClass.getResourceAsStream("/learning/byte-plus-one.cases.json")!!.readBytes()
    private val content = """{"formatVersion":4,"introduction":"Write independently.","conclusion":"Explain the algorithm.","steps":[{"kind":"assessment","id":"plus-one","explanation":"Write the program body.","source":"independent/plus-one.asm","caseProfile":"independent/plus-one.cases.json","success":"Explain input 255.","hints":[{"id":"retain","text":"Retain all three results."}]}]}"""
    private fun change(edit: JsonObject.() -> Unit) = JsonParser.parseString(content).asJsonObject.apply(edit).toString().toByteArray()
    private fun JsonObject.step() = getAsJsonArray("steps")[0].asJsonObject
    private fun parse(bytes: ByteArray = content.toByteArray()) = MarsNativeLessonPlan.parse(bytes) { profile }

    @Test
    fun `format four binds assessment sources and immutable profiles in the authored activity order`() {
        val step = parse().steps.single() as MarsNativeAssessmentStep
        assertEquals("independent/plus-one.asm", step.source)
        assertEquals("independent/plus-one.cases.json", step.caseProfiles.single())
        assertEquals(7, step.cases.size)
        assertNull(step.anchor)
        assertEquals(listOf(MarsLessonHint("retain", "Retain all three results.")), step.hints)
        assertThrows<IllegalArgumentException> { MarsNativeLessonPlan.parse(content.toByteArray()) }
        var reads = 0
        val two = MarsNativeLessonPlan.parse(change { getAsJsonArray("steps").add(step().deepCopy().apply {
            addProperty("id", "repeat"); remove("hints")
        }) }) { reads++; profile }
        assertEquals(1, reads)
        assertSame((two.steps[0] as MarsNativeAssessmentStep).cases, (two.steps[1] as MarsNativeAssessmentStep).cases)
    }

    @Test
    fun `assessment requires its explicit format and safe data references`() {
        for (edit in listOf<JsonObject.() -> Unit>(
            { addProperty("formatVersion", 3) }, { step().addProperty("action", "Run") },
            { step().remove("source") }, { step().remove("caseProfile") },
            { step().addProperty("caseProfile", "private/answers.json") },
            { step().addProperty("source", "../outside.asm") },
            { step().addProperty("source", "/outside.asm") },
        )) assertThrows<IllegalArgumentException> { parse(change(edit)) }
    }

    private fun archive(omit: String? = null, badProfile: ByteArray? = null): Path {
        val path = Files.createTempFile(temporary, "assessment", ".zip")
        val manifest = """{"formatVersion":1,"id":"004-byte-test","version":"0.4.0","title":"Public cases","entry":"main.asm","assignment":"assignment.md","nativeLesson":"lesson.json","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
        val files = mapOf("package.json" to manifest.toByteArray(), "main.asm" to ".text\nnop\n".toByteArray(),
            "assignment.md" to "Write a program.".toByteArray(), "lesson.json" to content.toByteArray(),
            "independent/plus-one.asm" to ".text\nmain:\nnop\n".toByteArray(),
            "independent/plus-one.cases.json" to (badProfile ?: profile))
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, bytes) in files) if (name != omit) {
                zip.putNextEntry(ZipEntry("004-byte-test/$name")); zip.write(bytes); zip.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `archive preparation validates profiles and source existence before preview and keeps private data immutable`() {
        val path = archive()
        MarsLessonPackages.prepare(path).use { prepared ->
            val step = prepared.nativeLesson!!.steps.single() as MarsNativeAssessmentStep
            val folder = Files.createDirectory(temporary.resolve("project"))
            val installed = prepared.install(folder)
            Files.writeString(installed.directory.resolve(step.caseProfiles.single()), "changed working profile")
            Files.writeString(path, "changed selected ZIP")
            assertEquals(7, step.cases.size)
            assertEquals("0\n", step.cases.first().input)
        }
        for (missing in listOf("independent/plus-one.asm", "independent/plus-one.cases.json")) {
            assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(omit = missing)).close() }
        }
        for (bad in listOf("{}".toByteArray(), ByteArray(MarsConsoleCaseData.MAX_BYTES + 1))) {
            assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(badProfile = bad)).close() }
        }
    }
}
