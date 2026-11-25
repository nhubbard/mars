package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.MarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionTransferTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  private fun image(
      body: String,
      padding: Int = 0,
      wide: Boolean = false,
      littleEndian: Boolean = true,
  ): AssemblyImage =
      session().use { machine ->
        machine.memory.byteOrder = littleEndian
        val path = directory.resolve("transfer.asm")
        val source =
            ".data ${if (wide) "0x100000000" else "0x10010000"}\n.space $padding\n.align 3\n" +
                "input: .word 123,0x80ffffff,0x12345678,456\noutput: .space 16\nmarker: .byte 0\n" +
                $$".text\nmain: li $v0,10\nsyscall\nreturned: bc main\nother_return: bc main\nfunction:\n$$body\nli $v0,0\njrc $ra\n"
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun transfer(indices: List<Int> = listOf(1, 0), bytes: Int = 4) =
      FunctionTransferCheck(
          FunctionDataAddress("input", 4),
          FunctionDataAddress("output", 4),
          indices,
          bytes,
      )

  private fun call() =
      FunctionCallCheck(
          "function",
          "returned",
          emptyMap(),
          mapOf("v0" to 0),
          argumentAddresses =
              mapOf(
                  "a0" to FunctionDataAddress("input", 4),
                  "a1" to FunctionDataAddress("output", 4),
              ),
      )

  private fun contract() =
      FunctionCheckCase("permuted word transfer", listOf(call()), transfers = listOf(transfer()))

  private val forward = $$"lw $t0,0($a0)\nlw $t1,4($a0)\nsw $t0,4($a1)\nsw $t1,0($a1)"

  private fun evaluate(body: String, case: FunctionCheckCase = contract()) =
      PreparedFunctionChecks.evaluate(image(body), listOf(case), ::session).single()

  @Test
  fun `different valid schedules relocation wide pointers byte orders and repeated calls are accepted`() {
    val reverse = $$"lw $t1,4($a0)\nsw $t1,0($a1)\nlw $t0,0($a0)\nsw $t0,4($a1)"
    for (body in listOf(forward, reverse)) for (padding in listOf(0, 264)) for (wide in
        listOf(false, true)) for (endian in listOf(false, true)) {
      val case = contract().copy(calls = listOf(call(), call().copy(returnTo = "other_return")))
      val results =
          PreparedFunctionChecks.evaluate(
              image(body, padding, wide, endian),
              listOf(case, case.copy(name = "fresh")),
              ::session,
          )
      assertTrue(results.all { it.passed }, results.toString())
    }
  }

  @Test
  fun `duplicate source and destination visits fail despite identical final copied values`() {
    for ((body, expected) in
        listOf(
            ($$"lw $t0,0($a0)\n$$forward") to "source element 0 was read more than once",
            ($$"$$forward\nsw $t0,4($a1)") to "destination element 1 was written more than once",
            ($$"sw $zero,0($a1)\n$$forward") to "written before source element 1 was read",
        )) {
      val result = evaluate(body)
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains(expected), result.failure)
    }
  }

  @Test
  fun `source writes destination reads missing visits and per-cell causal order fail`() {
    for ((body, expected) in
        listOf(
            $$"sw $zero,0($a0)" to "source must be read-only",
            $$"lw $t0,0($a1)" to "destination must be write-only",
            "" to "source element 0 was not read",
            $$"lw $t0,0($a0)\nlw $t1,4($a0)" to "destination element 0 was not written",
            $$"lw $t0,0($a0)\nsw $t0,0($a1)" to "written before source element 1 was read",
        )) {
      val result = evaluate(body)
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains(expected), result.failure)
    }
  }

  @Test
  fun `partial unaligned and crossing notices cannot stand in for complete scalar elements`() {
    for (body in listOf($$"lbu $t0,0($a0)", $$"lbu $t0,1($a0)", $$"sb $zero,0($a1)")) {
      val result = evaluate(body)
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains("one aligned 4-byte element"), result.failure)
    }
  }

  @Test
  fun `crossing doubleword traffic cannot evade the active word span`() {
    for (body in listOf($$"ld $t0,-4($a0)", $$"ld $t0,4($a0)")) {
      assertFalse(evaluate(body).passed)
      val bounded = contract().copy(accessBounds = listOf(FunctionAccessCheck("input", 16, 4, 8)))
      assertFalse(evaluate(body + "\n" + forward, bounded).passed)
    }
  }

  @Test
  fun `byte and halfword elements observe their native notice widths`() {
    for ((bytes, load, store) in listOf(Triple(1, "lbu", "sb"), Triple(2, "lhu", "sh"))) {
      val body =
          $$"$$load $t0,0($a0)\n$$load $t1,$$bytes($a0)\n$$store $t0,$$bytes($a1)\n$$store $t1,0($a1)"
      val result = evaluate(body, contract().copy(transfers = listOf(transfer(bytes = bytes))))
      assertTrue(result.passed, result.failure)
    }
  }

  @Test
  fun `empty maps allow no active visits and allocation bounds reject an empty-shape read`() {
    val empty = contract().copy(transfers = listOf(transfer(emptyList())))
    assertTrue(evaluate("", empty).passed)
    val bounded = empty.copy(accessBounds = listOf(FunctionAccessCheck("input", 16, 4, 0)))
    val result = evaluate($$"lw $t0,0($a0)", bounded)
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("within 0 bytes"), result.failure)
  }

  @Test
  fun `a second call must perform its own complete transfer instead of inheriting earlier visits`() {
    val body = $$"lbu $t2,marker\nbnezc $t2,skip\nnop\nli $t2,1\nsb $t2,marker\n$$forward\nskip:\n"
    assertTrue(evaluate(body).passed)
    val result =
        evaluate(
            body,
            contract().copy(calls = listOf(call(), call().copy(returnTo = "other_return"))),
        )
    assertFalse(result.passed)
    assertTrue(
        result.failure!!.contains("Call 2") && result.failure.contains("was not read"),
        result.failure,
    )
  }

  @Test
  fun `caller-owned transfer maps are captured before opening machines and revalidated after mutation`() {
    val mapping = mutableListOf(1, 0)
    val maps = mutableListOf(transfer(mapping))
    val case = contract().copy(transfers = maps)
    val result =
        PreparedFunctionChecks.evaluate(
                image(forward),
                listOf(case),
                {
                  mapping.clear()
                  maps.clear()
                  session()
                },
            )
            .single()
    assertTrue(result.passed, result.failure)
    val invalidMapping = mutableListOf(1, 0)
    val invalid = contract().copy(transfers = listOf(transfer(invalidMapping)))
    invalidMapping[0] = 0
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(""),
          listOf(invalid),
          { error("Must validate before opening") },
      )
    }
  }

  @Test
  fun `invalid assembled labels alignment and overlap are source failures and owned sessions close`() {
    for (source in
        listOf(
            FunctionDataAddress("absent"),
            FunctionDataAddress("function"),
            FunctionDataAddress("input", 1),
            FunctionDataAddress("output", 4),
        )) {
      val machines = mutableListOf<MarsContext>()
      val result =
          PreparedFunctionChecks.evaluate(
                  image(""),
                  listOf(contract().copy(transfers = listOf(transfer().copy(source = source)))),
                  { session().also(machines::add) },
              )
              .single()
      assertFalse(result.passed, source.toString())
      assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
    }
  }

  @Test
  fun `external setup and final byte inspection do not count as function traffic`() {
    val case =
        contract()
            .copy(
                initialMemory = listOf(FunctionMemoryCheck("input", "0102030405060708", 4)),
                calls =
                    listOf(
                        call()
                            .copy(
                                memory =
                                    listOf(FunctionMemoryCheck("output", "0506070801020304", 4)),
                            ),
                    ),
            )
    assertTrue(evaluate(forward, case).passed)
  }

  @Test
  fun `closing an observation detaches it and invalid resolved intervals fail before subscribing`() {
    session().use { machine ->
      machine.loadAssemblyImage(image(forward)) {}
      AssemblyCase(machine, checkNotNull(machine.program).symbolMap(), ByteArrayOutputStream())
          .use { execution ->
            val source = execution.readDataAddress("input", 4)
            val destination = execution.readDataAddress("output", 4)
            FunctionTransferObservation(machine.memory, transfer(), source, destination).use {
                observation ->
              execution.setRegister("a0", source)
              execution.setRegister("a1", destination)
              execution.enterFunction("function", "returned")
              execution.runUntil("returned")
              observation.expectComplete()
              observation.close()
              observation.close()
              execution.enterFunction("function", "returned")
              execution.runUntil("returned")
              observation.expectComplete()
            }
          }
      for ((source, destination) in
          listOf(-4L to 16L, 1L to 16L, 0L to 4L, 0L to (Long.MAX_VALUE - 3))) {
        assertThrows<CaseFailure> {
          FunctionTransferObservation(machine.memory, transfer(), source, destination)
        }
      }
    }
  }

  @Test
  fun `invalid or excessive maps reject before execution`() {
    for (invalid in
        listOf(
            { transfer(listOf(0, 0)) },
            { transfer(listOf(-1)) },
            { transfer(listOf(1)) },
            { transfer(bytes = 0) },
            { transfer(bytes = 3) },
            { transfer(bytes = 8) },
            { transfer((0..65536).toList()) },
            { contract().copy(transfers = List(33) { transfer() }) },
            { contract().copy(transfers = List(3) { transfer((0 until 65536).toList(), 4) }) },
        )) assertThrows<IllegalArgumentException> { invalid() }
  }
}
