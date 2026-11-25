package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParser
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

class MarsNativeFunctionPlanTest {
  @TempDir lateinit var temporary: Path
  private val profile =
      """{"formatVersion":1,"cases":[{"name":"one call","instructionLimit":1000,"calls":[{"entry":"invert","returnTo":"returned","arguments":{"a0":"0x0000000000000016"},"expectedRegisters":{"v0":"0x0000000000000097"}}]}]}"""
          .toByteArray()
  private val console =
      """{"formatVersion":1,"cases":[{"name":"silence","input":"","stdout":"","instructionLimit":1000}]}"""
          .toByteArray()
  private val lesson =
      """{"formatVersion":6,"introduction":"Follow the continuation.","conclusion":"Explain what survived.","steps":[{"kind":"assessment","id":"invert","explanation":"Write a callable function.","source":"independent/invert.asm","caseProfile":"function.cases.json","checkKind":"function","success":"Explain the return."}]}"""

  private fun parse(text: String = lesson) =
      MarsNativeLessonPlan.parse(text.toByteArray()) {
        if (it == "console.cases.json") console else profile
      }

  @Test
  fun `actual student function archive retains the complete instructional and callable case grid`() {
    val archive = temporary.resolve("student.zip")
    javaClass.getResourceAsStream("/learning/row-functions-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      val plan = prepared.nativeLesson!!
      assertEquals("003-row-functions", prepared.metadata.id)
      assertEquals(5, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      assertEquals(8, plan.steps.filterIsInstance<MarsNativeDebugStep>().single().stops.size)
      assertEquals("", plan.steps.filterIsInstance<MarsNativeRunStep>().single().stdout)
      val step = plan.steps.filterIsInstance<MarsNativeAssessmentStep>().single()
      assertTrue(step.cases.isEmpty())
      assertEquals(3, step.functionCases.size)
      assertEquals(768, step.functionCases.sumOf { it.calls.size })
    }
  }

  @Test
  fun `actual sprite archive retains memory results data pointers and both caller continuations`() {
    val archive = temporary.resolve("sprite-student.zip")
    javaClass.getResourceAsStream("/learning/sprite-rows-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      val plan = prepared.nativeLesson!!
      assertEquals("003-sprite-rows", prepared.metadata.id)
      assertEquals(5, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      assertEquals(8, plan.steps.filterIsInstance<MarsNativeDebugStep>().single().stops.size)
      assertEquals("", plan.steps.filterIsInstance<MarsNativeRunStep>().single().stdout)
      val step = plan.steps.filterIsInstance<MarsNativeAssessmentStep>().single()
      assertTrue(step.cases.isEmpty())
      assertEquals(5, step.functionCases.size)
      assertEquals("independent/vertical-sprite.asm", step.source)
      for (case in step.functionCases) {
        assertEquals(4, case.initialMemory.size)
        assertEquals(10, case.preservedRegisters.size)
        assertTrue(case.helpers.isEmpty())
        assertEquals(listOf("after_vertical", "after_repeat"), case.calls.map { it.returnTo })
        for (call in case.calls) {
          assertTrue(call.expectedRegisters.isEmpty())
          assertEquals("sprite", call.argumentAddresses.getValue("a0").label)
          assertEquals("frame", call.argumentAddresses.getValue("a1").label)
          assertEquals(512, call.memory.first { it.label == "frame" }.byteLength)
          assertEquals(
              setOf("frame", "sprite", "frame_guard", "rows_guard"),
              call.memory.map { it.label }.toSet(),
          )
        }
      }
    }
  }

  @Test
  fun `actual raster archive retains protected inputs painted ties and repeated continuations`() {
    val archive = temporary.resolve("raster-student.zip")
    javaClass.getResourceAsStream("/learning/integer-raster-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      val plan = prepared.nativeLesson!!
      assertEquals("007-integer-raster", prepared.metadata.id)
      assertEquals(5, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      assertEquals(8, plan.steps.filterIsInstance<MarsNativeDebugStep>().single().stops.size)
      assertEquals("", plan.steps.filterIsInstance<MarsNativeRunStep>().single().stdout)
      val step = plan.steps.filterIsInstance<MarsNativeAssessmentStep>().single()
      assertEquals("independent/dashed-line.asm", step.source)
      assertEquals(16, step.functionCases.size)
      assertTrue(step.cases.isEmpty())
      for (case in step.functionCases) {
        assertEquals(8, case.initialMemory.size)
        assertEquals(10, case.preservedRegisters.size)
        assertEquals(32, case.callerStackBytes)
        assertEquals(16, case.stackAlignment)
        assertEquals(10000L, case.instructionLimit)
        assertEquals(
            mapOf(
                "endpoints" to 16,
                "color" to 4,
                "frame_guard" to 4,
                "endpoints_guard" to 4,
                "color_guard" to 4,
                "count_guard" to 4,
            ),
            case.noStores.associate { it.label to it.byteLength },
        )
        assertEquals(listOf("line_complete", "line_repeat"), case.calls.map { it.returnTo })
        for ((entry, _, arguments, expectedRegisters, argumentAddresses, memory) in case.calls) {
          assertEquals("draw_dashed", entry)
          assertEquals("endpoints", argumentAddresses.getValue("a0").label)
          assertEquals("frame", argumentAddresses.getValue("a1").label)
          assertEquals(256, memory.first { it.label == "frame" }.byteLength)
          assertEquals(setOf("v0"), expectedRegisters.keys)
          assertEquals(setOf("a2"), arguments.keys)
        }
      }
      assertTrue(step.functionCases.any { it.name == "dashed 0,0 to 4,1" })
      assertTrue(step.functionCases.any { it.name == "dashed 0,0 to 1,4" })
    }
  }

  @Test
  fun `actual Life archive protects both physical role orders and repeated snapshot calls`() {
    val archive = temporary.resolve("life-student.zip")
    javaClass.getResourceAsStream("/learning/life-buffers-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      val plan = prepared.nativeLesson!!
      assertEquals("008-life-buffers", prepared.metadata.id)
      assertEquals(5, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      assertEquals(8, plan.steps.filterIsInstance<MarsNativeDebugStep>().single().stops.size)
      assertEquals("", plan.steps.filterIsInstance<MarsNativeRunStep>().single().stdout)
      val step = plan.steps.filterIsInstance<MarsNativeAssessmentStep>().single()
      assertEquals("independent/life-changes.asm", step.source)
      assertEquals(14, step.functionCases.size)
      assertTrue(step.cases.isEmpty())
      for ((index, case) in step.functionCases.withIndex()) {
        val source = if (index % 2 == 0) "board_a" else "board_b"
        val destination = if (index % 2 == 0) "board_b" else "board_a"
        assertEquals(25, case.noStores.size)
        assertEquals(160, case.noStores.sumOf { it.byteLength })
        assertEquals(100, case.noStores.single { it.label == source }.byteLength)
        val halo =
            case.noStores
                .filter { it.label == destination }
                .flatMap { it.offset until it.offset + it.byteLength }
                .toSet()
        assertEquals(
            (0 until 100).filter { it / 10 in setOf(0, 9) || it % 10 in setOf(0, 9) }.toSet(),
            halo,
        )
        assertEquals(8, case.initialMemory.size)
        assertEquals(10, case.preservedRegisters.size)
        assertEquals(50000L, case.instructionLimit)
        assertTrue(case.helpers.isEmpty())
        assertEquals(listOf("generation_built", "life_done"), case.calls.map { it.returnTo })
        for ((entry, _, arguments, expectedRegisters, argumentAddresses, memory) in case.calls) {
          assertEquals("life_changes", entry)
          assertEquals(source, argumentAddresses.getValue("a0").label)
          assertEquals(destination, argumentAddresses.getValue("a1").label)
          assertEquals(100, memory.single { it.label == source }.byteLength)
          assertEquals(100, memory.single { it.label == destination }.byteLength)
          assertEquals(setOf("v0"), expectedRegisters.keys)
          assertTrue(arguments.isEmpty())
        }
      }
    }
  }

  @Test
  fun `actual sorting archive keeps the half exchange and all bounded active spans`() {
    val archive = temporary.resolve("sorting-student.zip")
    javaClass.getResourceAsStream("/learning/sorting-costs-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      val plan = prepared.nativeLesson!!
      assertEquals("009-sorting-costs", prepared.metadata.id)
      assertEquals(5, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val stops = plan.steps.filterIsInstance<MarsNativeDebugStep>().single().stops
      assertEquals(8, stops.size)
      assertEquals("insertion_compare_end", stops[2].anchor.label)
      assertEquals("sw", stops[2].anchor.mnemonic)
      assertEquals(1, stops[2].anchor.occurrence)
      assertEquals("", plan.steps.filterIsInstance<MarsNativeRunStep>().single().stdout)
      val step = plan.steps.filterIsInstance<MarsNativeAssessmentStep>().single()
      assertEquals("independent/partition-negative.asm", step.source)
      assertEquals(56, step.functionCases.size)
      assertTrue(step.cases.isEmpty())
      for (case in step.functionCases) {
        assertTrue(case.protectCallerStack)
        assertEquals(32, case.callerStackBytes)
        assertEquals(10, case.preservedRegisters.size)
        assertEquals(6, case.noStores.size)
        assertEquals(listOf("partition_done", "partition_exit"), case.calls.map { it.returnTo })
        val range = case.accessBounds.single()
        assertEquals("values", range.label)
        assertEquals(40, range.byteLength)
        assertEquals(-4, range.offset)
        for (call in case.calls) {
          val address = call.argumentAddresses.getValue("a0")
          assertEquals("values", address.label)
          assertEquals(address.offset, range.permittedOffset)
          assertEquals(4 * call.arguments.getValue("a1").toInt(), range.permittedByteLength)
          assertEquals(32, call.memory.single { it.label == "values" }.byteLength)
        }
        assertEquals(case.calls[0].memory, case.calls[1].memory)
      }
    }
  }

  @Test
  fun `actual fixed point archive retains every scalar trial and separate caller boundaries`() {
    val archive = temporary.resolve("fixed-student.zip")
    javaClass.getResourceAsStream("/learning/fixed-point-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      val plan = prepared.nativeLesson!!
      assertEquals("012-fixed-point", prepared.metadata.id)
      assertEquals(5, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val stops = plan.steps.filterIsInstance<MarsNativeDebugStep>().single().stops
      assertEquals(8, stops.size)
      assertEquals(
          listOf(
              "frame_ready",
              "advance_axis",
              "advance_axis",
              "frame_ready",
              "reflect_high",
              "frame_ready",
              "reflect_high",
              "frame_ready",
          ),
          stops.map { it.anchor.label },
      )
      assertEquals("", plan.steps.filterIsInstance<MarsNativeRunStep>().single().stdout)
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf("interpolate-raw", "caller-exit"), assessments.map { it.id })
      assertEquals(1, assessments.map { it.source }.distinct().size)
      val functions = assessments.first().functionCases
      assertEquals(8, functions.size)
      assertEquals(3368, functions.dropLast(1).sumOf { it.calls.size })
      assertTrue(functions.dropLast(1).all { it.calls.size <= 512 && it.noStores.size == 6 })
      for (case in functions) {
        assertTrue(case.protectCallerStack)
        assertEquals(32, case.callerStackBytes)
        assertEquals(10, case.preservedRegisters.size)
      }
      for ((index, call) in functions.dropLast(1).flatMap { it.calls }.withIndex()) {
        assertEquals("interpolate_raw", call.entry)
        assertEquals(if (index % 2 == 0) "interpolate_done" else "interpolate_exit", call.returnTo)
        val a = call.arguments.getValue("a0")
        val b = call.arguments.getValue("a1")
        val w = call.arguments.getValue("a2")
        assertEquals(a.toInt().toLong(), a)
        assertEquals(b.toInt().toLong(), b)
        assertTrue(w in 0L..256L)
        assertEquals(
            Math.floorDiv((256L - w) * a + w * b + 128L, 256L),
            call.expectedRegisters.getValue("v0"),
        )
      }
      val caller = functions.last().calls.single()
      assertEquals("main", caller.entry)
      assertEquals("interpolate_exit", caller.returnTo)
      assertEquals(-64L, caller.expectedRegisters.getValue("v0"))
      assertEquals(6, caller.memory.size)
      assertEquals(5, functions.last().noStores.size)
      assertTrue(assessments.last().functionCases.isEmpty())
      assertEquals("", assessments.last().cases.single().expectedOutput)
    }
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `format six explicitly selects function data and retains console assessments`() {
    val step = parse().steps.single() as MarsNativeAssessmentStep
    assertTrue(step.cases.isEmpty())
    assertEquals(151L, step.functionCases.single().calls.single().expectedRegisters.getValue("v0"))
    val root = JsonParser.parseString(lesson).asJsonObject
    val another =
        root.getAsJsonArray("steps")[0].deepCopy().asJsonObject.apply { addProperty("id", "again") }
    root.getAsJsonArray("steps").add(another)
    var reads = 0
    val repeated =
        MarsNativeLessonPlan.parse(root.toString().toByteArray()) {
          reads++
          profile
        }
    assertEquals(1, reads)
    assertSame(
        (repeated.steps[0] as MarsNativeAssessmentStep).functionCases,
        (repeated.steps[1] as MarsNativeAssessmentStep).functionCases,
    )
    another.addProperty("id", "console")
    another.addProperty("checkKind", "console")
    another.addProperty("caseProfile", "console.cases.json")
    val mixed = parse(root.toString()).steps.filterIsInstance<MarsNativeAssessmentStep>()
    assertEquals(1, mixed[0].functionCases.size)
    assertEquals(1, mixed[1].cases.size)
    another.remove("checkKind")
    assertEquals(1, (parse(root.toString()).steps.last() as MarsNativeAssessmentStep).cases.size)
    assertThrows<IllegalArgumentException> { step.copy(functionCases = emptyList()) }
    assertThrows<IllegalArgumentException> { step.copy(cases = mixed[1].cases) }
  }

  @Test
  fun `older formats unknown kinds and crossed profile types reject rather than acquire function execution`() {
    for (version in 1..5) assertThrows<IllegalArgumentException> {
      parse(lesson.replace("\"formatVersion\":6", "\"formatVersion\":$version"))
    }
    for (kind in listOf("script", "Function", "", "console")) assertThrows<
        IllegalArgumentException,
    > {
      parse(lesson.replace("\"function\"", "\"$kind\""))
    }
    assertThrows<IllegalArgumentException> {
      parse(lesson.replace("function.cases.json", "console.cases.json"))
    }
    assertThrows<IllegalArgumentException> {
      parse(lesson.replace("function.cases.json", "../private.kts"))
    }
  }

  @Test
  fun `archive preview binds function definitions to its snapshot and requires both source and case data`() {
    val manifest =
        """{"formatVersion":1,"id":"003-row-functions","version":"0.1.0","title":"Calls and saved state","entry":"main.asm","assignment":"assignment.md","nativeLesson":"lesson.json","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
    for (missing in listOf(null, "function.cases.json", "independent/invert.asm")) {
      val archive = Files.createTempFile(temporary, "function-", ".zip")
      val files =
          mapOf(
              "package.json" to manifest.toByteArray(),
              "lesson.json" to lesson.toByteArray(),
              "main.asm" to ".text\nnop\n".toByteArray(),
              "assignment.md" to "Explain the call.".toByteArray(),
              "independent/invert.asm" to ".text\nnop\n".toByteArray(),
              "function.cases.json" to profile,
          )
      ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
        for ((name, bytes) in files) if (name != missing) {
          zip.putNextEntry(ZipEntry("003-row-functions/$name"))
          zip.write(bytes)
          zip.closeEntry()
        }
      }
      if (missing != null)
          assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive).close() }
      else
          MarsLessonPackages.prepare(archive).use { prepared ->
            val installed = prepared.install(Files.createDirectory(temporary.resolve("project")))
            Files.writeString(
                installed.directory.resolve("function.cases.json"),
                "changed working profile",
            )
            Files.writeString(archive, "changed original ZIP")
            val step = prepared.nativeLesson!!.steps.single() as MarsNativeAssessmentStep
            assertEquals("invert", step.functionCases.single().calls.single().entry)
            assertTrue(step.cases.isEmpty())
          }
    }
  }
}
