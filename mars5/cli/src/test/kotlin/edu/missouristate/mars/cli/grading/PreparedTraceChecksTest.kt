package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedTraceChecksTest {
  @TempDir lateinit var directory: Path
  private val base = 0x10010000L
  private val port = 0xffff0010L

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private fun image(
      source: String,
      target: MarsTarget = MarsTarget.DEFAULT,
      little: Boolean = true,
  ): AssemblyImage =
      session().use {
        it.selectTarget(target)
        it.memory.byteOrder = little
        val file = directory.resolve("protocol.asm")
        it.assembleSnapshot(
            AssemblySourceSnapshot(listOf(file), mapOf(file to source)),
            null,
            extended = true,
            warnings = false,
        )
        it.captureAssemblyImage {}
      }

  private fun source(target: MarsTarget = MarsTarget.DEFAULT) =
      $$"""
        .data
        payload: .byte 0x11, 0x22, 0x33, 0x44
        guard: .word 0x13579bdf
        .text
        la $t0, payload
        lbu $t1, 1($t0)
        lbu $t2, 2($t0)
        $${if (target == MarsTarget.MARS64) "dli" else "li"} $t7, 0xffff0010
        sb $t1, 0($t7)
        sb $t1, 0($t7)
        sb $t2, 0($t7)
        li $v0, 10
        syscall
    """
          .trimIndent()

  private fun case(hex: String = "aabbcc") =
      ConsoleCheckCase(
          "protocol",
          "",
          "",
          100,
          memory = listOf(ConsoleMemoryCheck(base, "11$hex")),
          initialMemory = listOf(ConsoleMemoryCheck(base + 1, hex)),
          stores =
              listOf(
                  ConsoleStoreCheck(
                      0xffff0000L,
                      65536,
                      port,
                      hex.take(2).repeat(2) + hex.substring(2, 4),
                  ),
                  ConsoleStoreCheck(base, 8),
              ),
          reads = listOf(ConsoleReadCheck(base, 8, base + 1, 2)),
      )

  @Test
  fun `prepared input octets keep byte order and real repeated port transactions on both targets`() {
    for (target in MarsTarget.entries) for (little in listOf(false, true)) {
      val untouched =
          case()
              .copy(
                  name = "original image",
                  initialMemory = emptyList(),
                  memory = listOf(ConsoleMemoryCheck(base, "11223344")),
                  stores =
                      listOf(
                          ConsoleStoreCheck(0xffff0000L, 65536, port, "222233"),
                          ConsoleStoreCheck(base, 8),
                      ),
              )
      val cases = listOf(case(), case("070700").copy(name = "repeated values"), untouched)
      val results =
          PreparedConsoleChecks.evaluate(image(source(target), target, little), cases, ::session)
      assertTrue(results.all { it.passed }, results.toString())
    }
  }

  @Test
  fun `same final port value does not hide missing wider extra or restored writes and illegal reads`() {
    val original = source()
    val faults =
        listOf(
            original.replace($$"sb $t1, 0($t7)\nsb $t1, 0($t7)\n", "") to "Expected 3 byte stores",
            original.replace($$"sb $t1", $$"sw $t1") to "4-byte store",
            original.replace($$"li $v0, 10", $$"sb $zero, 1($t7)\nli $v0, 10") to
                "Expected 3 byte stores",
            original.replace(
                $$"li $v0, 10",
                $$"lbu $t3, 0($t0)\nsb $zero, 0($t0)\nsb $t3, 0($t0)\nli $v0, 10",
            ) to "Expected no stores",
            original.replace($$"li $v0, 10", $$"lbu $t3, 4($t0)\nli $v0, 10") to
                "Expected reads within",
        )
    for ((program, message) in faults) {
      val machines = mutableListOf<MarsContext>()
      val result =
          PreparedConsoleChecks.evaluate(
                  image(program),
                  listOf(case()),
                  { session().also(machines::add) },
              )
              .single()
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains(message), result.failure)
      assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
    }
  }

  @Test
  fun `empty permitted reads catch unused reads and setup mapping errors remain independent case failures`() {
    val program = image($$".data\n.word 0\n.text\nlbu $t0,0x10010000\nli $v0,10\nsyscall\n")
    val results =
        PreparedConsoleChecks.evaluate(
            program,
            listOf(
                ConsoleCheckCase(
                    "empty selection",
                    "",
                    "",
                    reads = listOf(ConsoleReadCheck(base, 4, base, 0)),
                ),
                ConsoleCheckCase(
                    "mapped I O is not input data",
                    "",
                    "",
                    initialMemory = listOf(ConsoleMemoryCheck(port, "00")),
                ),
                ConsoleCheckCase(
                    "text is not input data",
                    "",
                    "",
                    initialMemory = listOf(ConsoleMemoryCheck(0x400000, "00")),
                ),
                ConsoleCheckCase(
                    "ordinary read",
                    "",
                    "",
                    reads = listOf(ConsoleReadCheck(base, 4, base, 4)),
                ),
            ),
            ::session,
        )
    assertTrue(results[0].failure!!.contains("Expected reads within 0 bytes"))
    assertTrue(results[1].failure!!.contains("Cannot prepare user-data byte"))
    assertTrue(results[2].failure!!.contains("Cannot prepare user-data byte"))
    assertTrue(results[3].passed)
  }

  @Test
  fun `setup is atomic before execution observationally external and supports unaligned wide data`() {
    for (little in listOf(false, true)) {
      val code =
          $$".data 0x100000000\n.byte 0x11,0x22,0x33,0x44,0x55,0x66,0x77,0x88\n.text\nli $v0,10\nsyscall\n"
      session().use { machine ->
        machine.loadAssemblyImage(image(code, little = little)) {}
        AssemblyCase(machine, machine.program!!.symbolMap(), ByteArrayOutputStream()).use {
            execution ->
          val writes = execution.traceMemoryStores(0x100000000, 8)
          val reads = execution.traceMemoryAccesses(0x100000000, 8)
          execution.setBytes(0x100000001, 0xaa, 0xbb, 0xcc, 0xdd)
          execution.expectBytes(0x100000000, 0x11, 0xaa, 0xbb, 0xcc, 0xdd, 0x66, 0x77, 0x88)
          writes.expectNone()
          assertTrue(reads.snapshot().isEmpty())
          assertFalse(machine.backStepper.canBackstep())
          val last = machine.memory.wideDataRange!!.last
          machine.editMemoryWord(last - 3, 0)
          assertThrows<CaseFailure> { execution.setBytes(last, 0xff, 0xff) }
          execution.expectBytes(last, 0)
          assertThrows<IllegalArgumentException> { execution.setBytes(-1, 0) }
          assertThrows<IllegalArgumentException> { execution.setBytes(Long.MAX_VALUE, 0, 0) }
          assertThrows<IllegalArgumentException> { execution.setBytes(0x100000000, 256) }
          assertThrows<IllegalArgumentException> {
            execution.setBytes(0x100000000, *IntArray(ConsoleMemoryCheck.MAX_BYTES + 1))
          }
          execution.setBytes(Long.MAX_VALUE)
          execution.runToExit()
          assertThrows<CaseFailure> { execution.setBytes(0x100000000, 0) }
        }
      }
    }
  }

  @Test
  fun `caller mutation cannot remove captured setup traces or read bounds`() {
    val setup = mutableListOf(ConsoleMemoryCheck(base + 1, "aabbcc"))
    val stores = mutableListOf(ConsoleStoreCheck(0xffff0000L, 65536, port, "aaaabb"))
    val reads = mutableListOf(ConsoleReadCheck(base, 8, base + 1, 2))
    val checks = listOf(case().copy(initialMemory = setup, stores = stores, reads = reads))
    val result =
        PreparedConsoleChecks.evaluate(
                image(source()),
                checks,
                {
                  setup.clear()
                  stores.clear()
                  reads.clear()
                  session()
                },
            )
            .single()
    assertTrue(result.passed, result.failure)
    val wrong = mutableListOf(ConsoleMemoryCheck(base, "00"))
    val mutated = case().copy(initialMemory = wrong)
    wrong += ConsoleMemoryCheck(base, "ff")
    assertThrows<IllegalArgumentException> {
      PreparedConsoleChecks.evaluate(
          image(source()),
          listOf(mutated),
          { error("Validate before creating a session") },
      )
    }
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `trace definitions reject ambiguous overflowing or excessive configuration`() {
    assertThrows<IllegalArgumentException> { ConsoleStoreCheck(-1, 1) }
    assertThrows<IllegalArgumentException> { ConsoleStoreCheck(0, 0) }
    assertThrows<IllegalArgumentException> { ConsoleStoreCheck(Long.MAX_VALUE, 2) }
    assertThrows<IllegalArgumentException> {
      ConsoleStoreCheck(0, ConsoleMemoryCheck.MAX_BYTES + 1)
    }
    assertThrows<IllegalArgumentException> { ConsoleStoreCheck(0, 1, 1, "00") }
    assertThrows<IllegalArgumentException> { ConsoleStoreCheck(0, 1, null, "00") }
    for (hex in listOf("0", "gg", "00 00", "éé", "00".repeat(65537))) {
      assertThrows<IllegalArgumentException> { ConsoleStoreCheck(0, 1, 0, hex) }
    }
    assertEquals("", ConsoleStoreCheck(Long.MAX_VALUE, 1, Long.MAX_VALUE).bytes)
    for ((at, length) in listOf(-1L to 1, 1L to -1, 4L to 1, 0L to 5)) {
      assertThrows<IllegalArgumentException> { ConsoleReadCheck(0, 4, at, length) }
    }
    assertEquals(0, ConsoleReadCheck(0, 4, 4, 0).allowedLength)
    assertThrows<IllegalArgumentException> {
      case().copy(stores = List(65) { ConsoleStoreCheck(0, 1) })
    }
    assertThrows<IllegalArgumentException> {
      case().copy(stores = List(2) { ConsoleStoreCheck(0, 1, 0, "00".repeat(32769)) })
    }
    assertThrows<IllegalArgumentException> {
      case().copy(initialMemory = List(65) { ConsoleMemoryCheck(it.toLong(), "00") })
    }
    assertThrows<IllegalArgumentException> {
      case()
          .copy(
              initialMemory =
                  listOf(
                      ConsoleMemoryCheck(0, "00".repeat(524289)),
                      ConsoleMemoryCheck(524289, "00".repeat(524289)),
                  ),
          )
    }
  }
}
