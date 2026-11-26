package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class MarsNativeActivitySourceTest {
  @TempDir lateinit var temporary: Path
  private val run =
      """{"kind":"run","id":"run","explanation":"Run this range.","integerInputs":[],"stdout":"","success":"The program exited."}"""
  private val debug =
      $$"""{"kind":"debug","id":"debug","explanation":"Inspect this range.","integerInputs":[],"stops":[{"id":"test","explanation":"Before the count test.","anchor":{"label":"loop","mnemonic":"beqzc","occurrence":0},"registers":{"$t1":"0x0000000000000000"},"success":"The range is empty."}],"success":"No glyph was read."}"""

  private fun lesson(activity: String, version: Int = 5) =
      """{"formatVersion":$version,"introduction":"Compare ranges.","conclusion":"Explain the boundary.","steps":[$activity]}"""
          .toByteArray()

  private fun source(activity: String, path: String) =
      JsonParser.parseString(activity)
          .asJsonObject
          .apply {
            addProperty("source", path)
          }
          .toString()

  @Test
  fun `format five sources are optional and older editions retain their entry source`() {
    for ((activity, version) in listOf(run to 2, debug to 3)) {
      // A nonempty output is compatible with the original Run schema too.
      val old = activity.replace("\"stdout\":\"\"", "\"stdout\":\"ok\"")
      for (edition in version..6) {
        val step = MarsNativeLessonPlan.parse(lesson(old, edition)).steps.single()
        assertNull(
            when (step) {
              is MarsNativeRunStep -> step.source
              is MarsNativeDebugStep -> step.source
              else -> error("Expected execution")
            },
        )
        if (edition < 5)
            assertThrows<IllegalArgumentException> {
              MarsNativeLessonPlan.parse(lesson(source(old, "ranges/empty.asm"), edition))
            }
      }
    }
    val plan =
        MarsNativeLessonPlan.parse(
            lesson(source(run, "ranges/full.asm") + "," + source(debug, "ranges/empty.asm")),
        )
    assertEquals("ranges/full.asm", (plan.steps[0] as MarsNativeRunStep).source)
    assertEquals("ranges/empty.asm", (plan.steps[1] as MarsNativeDebugStep).source)
  }

  @Test
  fun `activity sources are bounded archive paths rather than executable instructions`() {
    for (activity in listOf(run, debug)) {
      for (path in
          listOf(
              "",
              " ",
              "../escape.asm",
              "/absolute.asm",
              "a/../b.asm",
              "a\\b.asm",
              "x".repeat(241),
          )) {
        assertThrows<IllegalArgumentException>(path) {
          MarsNativeLessonPlan.parse(lesson(source(activity, path)))
        }
      }
      for (value in listOf(JsonNull.INSTANCE, JsonPrimitive(true), JsonPrimitive(1))) {
        val invalid = JsonParser.parseString(activity).asJsonObject.apply { add("source", value) }
        assertThrows<IllegalArgumentException> {
          MarsNativeLessonPlan.parse(lesson(invalid.toString()))
        }
      }
    }
  }

  @Test
  fun `archive preview requires every declared Run and Debug source`() {
    val manifest =
        """{"formatVersion":1,"id":"005-ranges","version":"1.0.0","title":"Ranges","entry":"main.asm","assignment":"assignment.md","nativeLesson":"lesson.json","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
    for (missing in listOf(null, "ranges/full.asm", "ranges/empty.asm")) {
      val archive = Files.createTempFile(temporary, "ranges-", ".zip")
      val files =
          mapOf(
              "package.json" to manifest.toByteArray(),
              "assignment.md" to "Compare ranges.".toByteArray(),
              "main.asm" to ".text\nnop\n".toByteArray(),
              "ranges/full.asm" to ".text\nnop\n".toByteArray(),
              "ranges/empty.asm" to ".text\nnop\n".toByteArray(),
              "lesson.json" to
                  lesson(source(run, "ranges/full.asm") + "," + source(debug, "ranges/empty.asm")),
          )
      ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
        for ((name, bytes) in files) if (name != missing) {
          zip.putNextEntry(ZipEntry("005-ranges/$name"))
          zip.write(bytes)
          zip.closeEntry()
        }
      }
      if (missing != null)
          assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive).close() }
      else
          MarsLessonPackages.prepare(archive).use { prepared ->
            assertEquals(listOf("run", "debug"), prepared.nativeLesson!!.steps.map { it.id })
          }
    }
  }

  @Test
  fun `published loop archive binds all guided variants and version three independent checks`() {
    val archive = temporary.resolve("loop.zip")
    javaClass.getResourceAsStream("/learning/loop-ranges-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      val plan = prepared.nativeLesson!!
      val content =
          MarsDownloadedLessonContent(
              prepared.install(Files.createDirectory(temporary.resolve("project"))),
              plan,
          )
      assertEquals(4, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debugging = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(
          listOf(
              Path.of("range-counter.asm"),
              Path.of("ranges/empty.asm"),
              Path.of("ranges/tail.asm"),
          ),
          debugging.map {
            content.installed.directory.relativize(content.source(it))
          },
      )
      assertEquals(10, debugging.sumOf { it.stops.size })
      val run = plan.steps.filterIsInstance<MarsNativeRunStep>().single()
      assertEquals("", run.stdout)
      assertEquals(content.installed.entry, content.source(run))
      val independent = plan.steps.filterIsInstance<MarsNativeAssessmentStep>().single()
      assertEquals(
          content.installed.directory.resolve("independent/end-pointer.asm"),
          content.source(independent),
      )
      assertEquals(10, independent.cases.size)
      assertEquals(2, independent.cases.first().stores.size)
      assertEquals(1, independent.cases.first().reads.size)
    }
  }
}
