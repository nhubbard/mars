package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.testFramework.junit5.TestApplication
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MarsNativeLessonPlanTest {
  @TempDir lateinit var temporary: Path
  private val content =
      """{"formatVersion":1,"introduction":"Interpret one byte.","conclusion":"Explain its width.","steps":[{"id":"unsigned","explanation":"The same bits may have different meanings.","question":"What is 0x96 unsigned?","choices":[{"id":"a","text":"150","feedback":"Add the set place values."},{"id":"b","text":"-106","feedback":"That is the signed-byte meaning."}],"correctChoice":"a","anchor":{"label":"interpret","mnemonic":"andi","occurrence":0},"hints":[{"id":"place-values","text":"Start with bit seven."}]}]}"""

  private fun changed(change: JsonObject.() -> Unit) =
      JsonParser.parseString(content).asJsonObject.apply(change).toString().toByteArray()

  private fun JsonObject.step() = getAsJsonArray("steps")[0].asJsonObject

  private fun reject(change: JsonObject.() -> Unit) {
    assertThrows<IllegalArgumentException> { MarsNativeLessonPlan.parse(changed(change)) }
  }

  @Test
  fun `definitions retain explanations feedback anchors and immutable collections`() {
    val plan = MarsNativeLessonPlan.parse(content.toByteArray())
    assertEquals("Interpret one byte.", plan.introduction)
    assertEquals("Explain its width.", plan.conclusion)
    val step = plan.steps.single() as MarsNativeLessonStep
    assertEquals("The same bits may have different meanings.", step.explanation)
    assertEquals("unsigned", step.prediction.id)
    assertEquals("What is 0x96 unsigned?", step.prediction.question)
    assertEquals("Add the set place values.", step.prediction.answerExplanation)
    assertEquals(MarsLessonAnchor("interpret", "andi", 0), step.anchor)
    assertEquals(MarsLessonHint("place-values", "Start with bit seven."), step.hints.single())
    assertThrows<UnsupportedOperationException> { (plan.steps as MutableList).clear() }
    assertThrows<UnsupportedOperationException> { (step.hints as MutableList).clear() }
    assertThrows<UnsupportedOperationException> { (step.prediction.choices as MutableList).clear() }
    val minimal =
        MarsNativeLessonPlan.parse(
                changed {
                  step().remove("anchor")
                  step().remove("hints")
                },
            )
            .steps
            .single()
    assertNull(minimal.anchor)
    assertTrue(minimal.hints.isEmpty())
  }

  @Test
  fun `format field and object shapes cannot silently acquire unsupported behavior`() {
    for (raw in listOf("", "[]", "true", "null", "1", "\"text\"", "{bad", "$content {}")) {
      assertThrows<Exception>(raw) { MarsNativeLessonPlan.parse(raw.toByteArray()) }
    }
    reject { addProperty("formatVersion", 4) }
    reject { addProperty("formatVersion", "1") }
    reject { addProperty("runAction", "ExecuteAnything") }
    reject { remove("conclusion") }
    reject { addProperty("introduction", true) }
    reject { add("steps", JsonPrimitive(1)) }
    reject { step().add("explanation", JsonArray()) }
    reject { step().add("question", null) }
    reject { step().addProperty("gradingScript", "private.kts") }
    reject { getAsJsonArray("steps").set(0, JsonPrimitive("not an object")) }
    assertThrows<CharacterCodingException> {
      MarsNativeLessonPlan.parse(byteArrayOf(0xc3.toByte()))
    }
  }

  @Test
  fun `duplicate keys are rejected at every nested object depth`() {
    for (raw in
        listOf(
            """{"formatVersion":1,"formatVersion":1}""",
            """{"steps":[{"id":"a","id":"b"}]}""",
            """{"steps":[{"choices":[{"id":"a","id":"b"}]}]}""",
        )) {
      val error =
          assertThrows<IllegalArgumentException> { MarsNativeLessonPlan.parse(raw.toByteArray()) }
      assertTrue(error.message!!.contains("Duplicate native lesson field"))
    }
  }

  @Test
  fun `native message text rejects XML control characters before rendering`() {
    for (invalid in listOf("\u0000", "\u0001", "\u000b")) {
      reject { addProperty("introduction", "Invalid $invalid text") }
    }
    assertThrows<IllegalArgumentException> {
      MarsNativeLessonPlan.parse(
          content.replace("Interpret one byte.", "Invalid \\ud800 text").toByteArray(),
      )
    }
    assertEquals(
        "Valid < & >\n\ttext",
        MarsNativeLessonPlan.parse(
                changed {
                  addProperty("introduction", "Valid < & >\n\ttext")
                },
            )
            .introduction,
    )
    assertThrows<IllegalArgumentException> {
      MarsLessonPackages.prepare(
              archive(
                  manifest.replace("Native predictions", "Invalid\\u0001title"),
                  content.toByteArray(),
              ),
          )
          .close()
    }
    assertThrows<IllegalArgumentException> {
      MarsLessonPackages.prepare(
              archive(
                  manifest.replace("Native predictions", "<html><b>Title</b></html>"),
                  content.toByteArray(),
              ),
          )
          .close()
    }
  }

  @Test
  fun `step and choice identities and the declared answer must be coherent`() {
    reject { step().addProperty("id", "bad id") }
    reject { step().addProperty("id", "x".repeat(65)) }
    reject { step().addProperty("question", " ") }
    reject { step().addProperty("explanation", "x".repeat(8193)) }
    reject { step().addProperty("correctChoice", "missing") }
    reject { getAsJsonArray("steps").add(step().deepCopy()) }
    reject { step().add("choices", JsonArray()) }
    reject { step().getAsJsonArray("choices").remove(1) }
    reject { step().getAsJsonArray("choices").add(step().getAsJsonArray("choices")[0].deepCopy()) }
    reject { step().getAsJsonArray("choices")[0].asJsonObject.remove("feedback") }
    reject { step().getAsJsonArray("choices")[0].asJsonObject.addProperty("id", "invalid choice") }
    reject { step().getAsJsonArray("choices")[0].asJsonObject.add("text", JsonObject()) }
    reject {
      val choice = step().getAsJsonArray("choices")[0].deepCopy()
      repeat(5) { step().getAsJsonArray("choices").add(choice.deepCopy()) }
    }
  }

  @Test
  fun `anchors specify a label instruction and bounded occurrence rather than a stale line number`() {
    reject { step().getAsJsonObject("anchor").addProperty("label", "escape:") }
    reject { step().getAsJsonObject("anchor").addProperty("mnemonic", "call an action") }
    for (value in listOf("true", "{}", "\"0\"", "-1", "0.5", "128", "1000")) {
      reject { step().getAsJsonObject("anchor").add("occurrence", JsonParser.parseString(value)) }
    }
    val boundary =
        MarsNativeLessonPlan.parse(
            changed { step().getAsJsonObject("anchor").addProperty("occurrence", 127) },
        )
    assertEquals(127, boundary.steps.single().anchor!!.occurrence)
  }

  @Test
  fun `graduated hints and total content have explicit limits`() {
    reject { step().getAsJsonArray("hints").add(step().getAsJsonArray("hints")[0].deepCopy()) }
    reject {
      val hint = step().getAsJsonArray("hints")[0].deepCopy()
      repeat(4) { step().getAsJsonArray("hints").add(hint.deepCopy()) }
    }
    reject { add("steps", JsonArray()) }
    reject {
      val step = step().deepCopy()
      add(
          "steps",
          JsonArray().apply {
            repeat(129) { add(step.deepCopy().apply { addProperty("id", "s$it") }) }
          },
      )
    }
    val maximum =
        MarsNativeLessonPlan.parse(
            changed {
              val step = step().deepCopy()
              add(
                  "steps",
                  JsonArray().apply {
                    repeat(128) { add(step.deepCopy().apply { addProperty("id", "s$it") }) }
                  },
              )
            },
        )
    assertEquals(128, maximum.steps.size)
    val padded = content.padEnd(MarsNativeLessonPlan.MAX_BYTES, ' ').toByteArray()
    assertEquals(1, MarsNativeLessonPlan.parse(padded).steps.size)
    assertThrows<IllegalArgumentException> { MarsNativeLessonPlan.parse(padded + byteArrayOf(32)) }
  }

  private fun archive(manifest: String, lesson: ByteArray?): Path {
    val path = Files.createTempFile(temporary, "native-lesson-", ".zip")
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      val files =
          linkedMapOf(
              "package.json" to manifest.toByteArray(),
              "main.asm" to ".text\nnop\n".toByteArray(),
              "assignment.md" to "Explain the result.".toByteArray(),
          )
      if (lesson != null) files["lesson.json"] = lesson
      for ((name, bytes) in files) {
        zip.putNextEntry(ZipEntry("001-native/$name"))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return path
  }

  private val manifest =
      """{"formatVersion":1,"id":"001-native","version":"1.0.0","title":"Native predictions","entry":"main.asm","assignment":"assignment.md","nativeLesson":"lesson.json","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""

  @Test
  fun `optional native content is validated before preview and bound to its private snapshot`() {
    val path = archive(manifest, content.toByteArray())
    MarsLessonPackages.prepare(path).use { preview ->
      assertEquals("lesson.json", preview.metadata.nativeLesson)
      assertEquals(
          "unsigned",
          (preview.nativeLesson!!.steps.single() as MarsNativeLessonStep).prediction.id,
      )
      Files.writeString(path, "changed after preparation")
      assertEquals("Interpret one byte.", preview.nativeLesson.introduction)
      val project = Files.createDirectory(temporary.resolve("project"))
      val installed = preview.install(project)
      assertEquals(content, Files.readString(installed.directory.resolve("lesson.json")))
    }
    for ((json, bytes) in
        listOf(
            manifest to null,
            manifest to "{}".toByteArray(),
            manifest to ByteArray(MarsNativeLessonPlan.MAX_BYTES + 1),
            manifest.replace("\"lesson.json\"", "42") to content.toByteArray(),
            manifest.replace("lesson.json", "../escape.json") to content.toByteArray(),
        )) {
      assertThrows<IllegalArgumentException> {
        MarsLessonPackages.prepare(archive(json, bytes)).close()
      }
    }
    val legacy =
        JsonParser.parseString(manifest).asJsonObject.apply { remove("nativeLesson") }.toString()
    MarsLessonPackages.prepare(archive(legacy, null)).use {
      assertNull(it.nativeLesson)
      assertNull(it.metadata.nativeLesson)
    }
  }
}
