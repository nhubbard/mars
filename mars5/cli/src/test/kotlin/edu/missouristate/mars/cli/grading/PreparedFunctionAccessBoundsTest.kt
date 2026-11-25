package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionAccessBoundsTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  private fun image(body: String, padding: Int = 0, littleEndian: Boolean = true): AssemblyImage =
      session().use { machine ->
        machine.memory.byteOrder = littleEndian
        val path = directory.resolve("bounded.asm")
        val source =
            ".data\n.space $padding\n.align 3\nbefore: .space 8\nrecord: .space 8\nguard: .space 8\ncounter: .byte 0\n" +
                $$".text\nmain: li $v0,10\nsyscall\nreturned: bc main\nother_return: bc main\nfunction:\n$$body\n"
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
          "function",
          "returned",
          emptyMap(),
          mapOf("v0" to 0),
          argumentAddresses = mapOf("a0" to FunctionDataAddress("record")),
      )

  private fun contract() =
      FunctionCheckCase(
          "active array",
          listOf(call()),
          initialMemory = listOf(FunctionMemoryCheck("record", "1112131415161718")),
          accessBounds = listOf(FunctionAccessCheck("record", 16, 0, 4, -4)),
          protectCallerStack = true,
      )

  private fun evaluate(body: String, case: FunctionCheckCase = contract()) =
      PreparedFunctionChecks.evaluate(image(body), listOf(case), ::session).single()

  @Test
  fun `permitted reads and stores work across byte orders relocation repeat calls and fresh machines`() {
    val body = $$"lw $t0,0($a0)\nlbu $t1,1($a0)\nsb $t1,1($a0)\njrc $ra"
    val case = contract().copy(calls = listOf(call(), call().copy(returnTo = "other_return")))
    for (padding in listOf(0, 264)) for (endian in listOf(false, true)) {
      val results =
          PreparedFunctionChecks.evaluate(
              image(body, padding, endian),
              listOf(case, case.copy(name = "fresh")),
              ::session,
          )
      assertTrue(results.all { it.passed }, results.toString())
    }
    assertTrue(
        evaluate(
                $$"jrc $ra",
                contract().copy(accessBounds = listOf(FunctionAccessCheck("record", 16, 4, 0, -4))),
            )
            .passed,
    )
  }

  @Test
  fun `inactive and crossing accesses fail including reads and restored stores`() {
    val bodies =
        listOf(
            $$"lw $t0,-4($a0)",
            $$"lw $t0,4($a0)",
            $$"ld $t0,0($a0)",
            $$"ld $t0,-8($a0)",
            $$"sb $zero,4($a0)",
            $$"lw $t0,4($a0)\nsw $zero,4($a0)\nsw $t0,4($a0)",
        )
    for (body in bodies) {
      val result = evaluate($$"$$body\njrc $ra")
      assertFalse(result.passed, body)
      assertTrue(result.failure!!.contains("Expected accesses within"), result.failure)
    }
    val empty =
        evaluate(
            $$"lbu $t0,0($a0)\njrc $ra",
            contract().copy(accessBounds = listOf(FunctionAccessCheck("record", 16, 0, 0, -4))),
        )
    assertFalse(empty.passed)
    assertTrue(empty.failure!!.contains("within 0 bytes"), empty.failure)
  }

  @Test
  fun `caller stack protection catches restored and same value stores while private frames remain allowed`() {
    val saved = $$"ld $t0,0($sp)\ndli $t1,0x1122334455667788\nsd $t1,0($sp)\nsd $t0,0($sp)\njrc $ra"
    val same = $$"ld $t0,0($sp)\nsd $t0,0($sp)\njrc $ra"
    for (body in listOf(saved, same)) {
      assertTrue(evaluate(body, contract().copy(protectCallerStack = false)).passed)
      val result = evaluate(body)
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains("Expected no stores"), result.failure)
    }
    val privateFrame =
        $$"daddiu $sp,$sp,-16\nsd $ra,0($sp)\nld $ra,0($sp)\ndaddiu $sp,$sp,16\njrc $ra"
    assertTrue(evaluate(privateFrame).passed)
    assertTrue(evaluate(same, contract().copy(callerStackBytes = 0)).passed)
  }

  @Test
  fun `captured access contracts survive caller mutation and invalid labels close owned sessions`() {
    val bounds = contract().accessBounds.toMutableList()
    val case = contract().copy(accessBounds = bounds)
    val result =
        PreparedFunctionChecks.evaluate(
                image($$"lw $t0,4($a0)\njrc $ra"),
                listOf(case),
                {
                  bounds.clear()
                  session()
                },
            )
            .single()
    assertFalse(result.passed)
    val changed = mutableListOf(FunctionAccessCheck("record", 4, 0, 4))
    val invalid = contract().copy(accessBounds = changed)
    repeat(64) { changed += FunctionAccessCheck("record", 4, 0, 4) }
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image($$"jrc $ra"),
          listOf(invalid),
          { error("Validate before opening") },
      )
    }
    for (label in listOf("missing", "function")) {
      val machines = mutableListOf<MarsContext>()
      val failed =
          PreparedFunctionChecks.evaluate(
                  image($$"jrc $ra"),
                  listOf(
                      contract().copy(accessBounds = listOf(FunctionAccessCheck(label, 4, 0, 4))),
                  ),
                  { session().also(machines::add) },
              )
              .single()
      assertFalse(failed.passed)
      assertTrue(
          failed.failure!!.contains(
              if (label == "missing") "Missing label" else "Expected a data label",
          ),
          failed.failure,
      )
      assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
    }
  }

  @Test
  fun `access ranges and permitted subwindows reject invalid or excessive definitions`() {
    for (invalid in
        listOf(
            { FunctionAccessCheck("../file", 4, 0, 4) },
            { FunctionAccessCheck("record", 0, 0, 0) },
            { FunctionAccessCheck("record", -1, 0, 0) },
            { FunctionAccessCheck("record", 1048577, 0, 0) },
            { FunctionAccessCheck("record", 4, 0, -1) },
            { FunctionAccessCheck("record", 4, 0, 1048577) },
            { FunctionAccessCheck("record", 4, -1, 1) },
            { FunctionAccessCheck("record", 4, 1, 4) },
            { FunctionAccessCheck("record", 4, 1048577, 0) },
            { FunctionAccessCheck("record", 4, 0, 0, -1048577) },
            { contract().copy(accessBounds = List(65) { FunctionAccessCheck("record", 1, 0, 0) }) },
            {
              contract()
                  .copy(accessBounds = List(2) { FunctionAccessCheck("record", 600000, 0, 0) })
            },
        )) assertThrows<IllegalArgumentException> { invalid() }
    FunctionAccessCheck("record", 4, 4, 0)
    FunctionAccessCheck("record", 8, -4, 4, -8)
  }

  @Test
  fun `a later call cannot evade the window while each case owns a fresh counter`() {
    val body =
        $$"lbu $t2,counter\naddiu $t2,$t2,1\nsb $t2,counter\nli $t3,2\n" +
            $$"bnec $t2,$t3,read_only\nnop\nlw $t0,4($a0)\nread_only:\njrc $ra"
    val assembly = image(body)
    assertTrue(
        PreparedFunctionChecks.evaluate(
                assembly,
                listOf(contract(), contract().copy(name = "fresh counter")),
                ::session,
            )
            .all { it.passed },
    )
    val result =
        PreparedFunctionChecks.evaluate(
                assembly,
                listOf(
                    contract().copy(calls = listOf(call(), call().copy(returnTo = "other_return"))),
                ),
                ::session,
            )
            .single()
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("Expected accesses within"), result.failure)
  }

  @Test
  fun `overflowing access history fails rather than accepting a truncated trace`() {
    val body =
        $$"li $t2,65537\nrepeat_read:\nlbu $t0,0($a0)\naddiu $t2,$t2,-1\nbnezc $t2,repeat_read\nnop\njrc $ra"
    val result = evaluate(body, contract().copy(instructionLimit = 400000))
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("Memory-access trace exceeds 65536"), result.failure)
  }
}
