package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionStoreProtectionTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private val read = $$"lbu $v0,0($a0)\njrc $ra"
  private val restoredWrite = $$"lw $t0,0($a0)\nsw $zero,0($a0)\nsw $t0,0($a0)\n$$read"

  private fun image(
      body: String = read,
      padding: Int = 0,
      littleEndian: Boolean = true,
  ): AssemblyImage =
      session().use { machine ->
        machine.memory.byteOrder = littleEndian
        val path = directory.resolve("protected.asm")
        val source =
            ".data\n.space $padding\n.align 3\nrecord: .byte 1,2,3,4\nguard: .byte 5,6,7,8\ncount: .byte 0\n" +
                $$".text\nmain: li $v0,10\nsyscall\nreturned: bc main\nother_return: bc main\nread_record:\n$$body\n"
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun call() =
      FunctionCallCheck(
          "read_record",
          "returned",
          emptyMap(),
          mapOf("v0" to 17),
          argumentAddresses = mapOf("a0" to FunctionDataAddress("record")),
          memory =
              listOf(
                  FunctionMemoryCheck("record", "11223344"),
                  FunctionMemoryCheck("guard", "05060708"),
              ),
      )

  private fun case() =
      FunctionCheckCase(
          "protected inputs",
          listOf(call()),
          initialMemory = listOf(FunctionMemoryCheck("record", "11223344")),
          noStores = listOf(FunctionNoStoreCheck("record", 4), FunctionNoStoreCheck("guard", 4)),
      )

  @Test
  fun `caller preparation precedes tracing and each case remains independent across relocated objects`() {
    for (padding in listOf(0, 268)) for (littleEndian in listOf(false, true)) {
      val contract = case().copy(calls = listOf(call(), call().copy(returnTo = "other_return")))
      val result =
          PreparedFunctionChecks.evaluate(
              image(padding = padding, littleEndian = littleEndian),
              listOf(contract, contract.copy(name = "fresh protected inputs")),
              ::session,
          )
      assertTrue(result.all { it.passed }, result.toString())
    }
  }

  @Test
  fun `restored and same value stores cannot hide behind final byte equality`() {
    val sameValue = $$"lw $t0,0($a0)\nsw $t0,0($a0)\n$$read"
    val wide = $$"ld $t0,0($a0)\nsd $zero,0($a0)\nsd $t0,0($a0)\n$$read"
    for ((body, region) in
        listOf(
            restoredWrite to FunctionNoStoreCheck("record", 4),
            sameValue to FunctionNoStoreCheck("record", 1, 3),
            wide to FunctionNoStoreCheck("guard", 4),
        )) {
      val assembly = image(body)
      assertTrue(
          PreparedFunctionChecks.evaluate(
                  assembly,
                  listOf(case().copy(noStores = emptyList())),
                  ::session,
              )
              .single()
              .passed,
      )
      val result =
          PreparedFunctionChecks.evaluate(
                  assembly,
                  listOf(case().copy(noStores = listOf(region))),
                  ::session,
              )
              .single()
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains("Expected no stores"), result.failure)
    }
  }

  @Test
  fun `a write only in a later call still fails while unprotected scratch writes are allowed`() {
    val body =
        $$"lbu $t2,count\naddiu $t2,$t2,1\nsb $t2,count\nli $t3,2\n" +
            $$"bnec $t2,$t3,read_only\nnop\n" +
            restoredWrite.substringBefore(read) +
            "read_only:\n$read"
    val assembly = image(body)
    assertTrue(PreparedFunctionChecks.evaluate(assembly, listOf(case()), ::session).single().passed)
    val result =
        PreparedFunctionChecks.evaluate(
                assembly,
                listOf(case().copy(calls = listOf(call(), call().copy(returnTo = "other_return")))),
                ::session,
            )
            .single()
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("Expected no stores"), result.failure)
  }

  @Test
  fun `protected definitions are captured and malformed submitted labels close their owned machine`() {
    val regions = case().noStores.toMutableList()
    val contract = case().copy(noStores = regions)
    val result =
        PreparedFunctionChecks.evaluate(
                image(restoredWrite),
                listOf(contract),
                {
                  regions.clear()
                  session()
                },
            )
            .single()
    assertFalse(result.passed)
    val changed = mutableListOf(FunctionNoStoreCheck("record", 1))
    val invalid = case().copy(noStores = changed)
    repeat(64) { changed += FunctionNoStoreCheck("record", 1) }
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(),
          listOf(invalid),
          { error("Validate before opening") },
      )
    }
    for (label in listOf("missing", "read_record")) {
      val machines = mutableListOf<MarsContext>()
      val missing =
          PreparedFunctionChecks.evaluate(
                  image(),
                  listOf(case().copy(noStores = listOf(FunctionNoStoreCheck(label, 4)))),
                  { session().also(machines::add) },
              )
              .single()
      assertFalse(missing.passed)
      assertTrue(
          missing.failure!!.contains(
              if (label == "missing") "Missing label" else "Expected a data label",
          ),
          missing.failure,
      )
      assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
    }
  }

  @Test
  fun `protected regions cannot request unbounded or invalid ranges`() {
    for (invalid in
        listOf(
            { FunctionNoStoreCheck("../file", 1) },
            { FunctionNoStoreCheck("record", 0) },
            { FunctionNoStoreCheck("record", -1) },
            { FunctionNoStoreCheck("record", 1048577) },
            { FunctionNoStoreCheck("record", 1, 1048577) },
            { case().copy(noStores = List(65) { FunctionNoStoreCheck("record", 1) }) },
            { case().copy(noStores = List(2) { FunctionNoStoreCheck("record", 600000) }) },
        )) assertThrows<IllegalArgumentException> { invalid() }
  }
}
