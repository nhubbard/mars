package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class MarsPredictionSourceTest {
  @TempDir lateinit var directory: Path
  private val lesson =
      """{"formatVersion":11,"introduction":"Compare examples.","conclusion":"Explain the result.","steps":[{"kind":"prediction","id":"predict","source":"worked/variant.asm","explanation":"Predict the selected variant.","question":"Which value?","choices":[{"id":"a","text":"one","feedback":"One."},{"id":"b","text":"two","feedback":"Two."}],"correctChoice":"a","anchor":{"label":"variant","mnemonic":"nop","occurrence":0}}]}"""

  @Test
  fun `prediction source is versioned optional and uses the existing portable source path contract`() {
    val parsed =
        MarsNativeLessonPlan.parse(lesson.toByteArray()).steps.single() as MarsNativeLessonStep
    assertEquals("worked/variant.asm", parsed.source)
    for (version in 1..10) {
      val root = JsonParser.parseString(lesson).asJsonObject
      root.addProperty("formatVersion", version)
      val step = root.getAsJsonArray("steps")[0].asJsonObject
      if (version == 1) step.remove("kind")
      assertThrows<IllegalArgumentException> {
        MarsNativeLessonPlan.parse(root.toString().toByteArray())
      }
      step.remove("source")
      assertNull(
          (MarsNativeLessonPlan.parse(root.toString().toByteArray()).steps.single()
                  as MarsNativeLessonStep)
              .source,
      )
    }
    val optional = JsonParser.parseString(lesson).asJsonObject
    optional.getAsJsonArray("steps")[0].asJsonObject.remove("source")
    assertNull(
        (MarsNativeLessonPlan.parse(optional.toString().toByteArray()).steps.single()
                as MarsNativeLessonStep)
            .source,
    )
    for (path in
        listOf(
            "",
            "../outside.asm",
            "/absolute.asm",
            "a/../variant.asm",
            "a\\variant.asm",
            "private/solution.asm",
            "a".repeat(241),
        )) {
      val root = JsonParser.parseString(lesson).asJsonObject
      root.getAsJsonArray("steps")[0].asJsonObject.addProperty("source", path)
      assertThrows<IllegalArgumentException> {
        MarsNativeLessonPlan.parse(root.toString().toByteArray())
      }
    }
    val wrongType = JsonParser.parseString(lesson).asJsonObject
    wrongType.getAsJsonArray("steps")[0].asJsonObject.addProperty("source", 1)
    assertThrows<IllegalArgumentException> {
      MarsNativeLessonPlan.parse(wrongType.toString().toByteArray())
    }
  }

  @Test
  fun `archive must contain the prediction variant and selected content routes to that installed file`() {
    fun archive(includeVariant: Boolean): Path {
      val archive = Files.createTempFile(directory, "prediction", ".zip")
      val manifest =
          """{"formatVersion":1,"id":"030-source-test","version":"0.1.0","title":"Prediction source","entry":"main.asm","assignment":"assignment.md","nativeLesson":"lesson.json","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
      val files =
          mutableMapOf(
              "package.json" to manifest,
              "lesson.json" to lesson,
              "main.asm" to ".text\nmain: nop\n",
              "assignment.md" to "Compare.",
          )
      if (includeVariant) files["worked/variant.asm"] = ".text\nvariant: nop\n"
      ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
        for ((name, text) in files) {
          zip.putNextEntry(ZipEntry("030-source-test/$name"))
          zip.write(text.toByteArray())
          zip.closeEntry()
        }
      }
      return archive
    }
    assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(false)).close() }
    MarsLessonPackages.prepare(archive(true)).use { prepared ->
      val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
      val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
      val step = content.plan.steps.single() as MarsNativeLessonStep
      assertEquals(installed.directory.resolve("worked/variant.asm"), content.source(step))
      assertEquals(installed.entry, content.source(step.copy(source = null)))
    }
  }
}
