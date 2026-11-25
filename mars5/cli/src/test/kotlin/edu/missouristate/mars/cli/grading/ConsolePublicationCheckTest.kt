package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.DaggerMarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ConsolePublicationCheckTest {
  @TempDir lateinit var directory: Path
  private val source =
      $$"""
      .data 0x100100000
      .align 3
      counter: .word 0
      limit: .word 2
      pointer: .dword 0
      payload: .byte 7,8,9,10
      end: .word 0
      .text
      .globl main
      main:
          dla $t0,payload
          sd $t0,pointer
          lw $t1,limit
          beqzc $t1,done
          nop
          move $t5,$ra
      loop:
          lw $t2,counter
          addiu $t2,$t2,1
          sw $t2,counter
      published:
          nop
          bnec $t5,$ra,broken
          nop
          bltc $t2,$t1,loop
          nop
      done:
          li $v0,10
          syscall
      broken:
          break
      """
          .trimIndent()

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private fun image(text: String = source, little: Boolean = true) =
      session().use { machine ->
        machine.memory.byteOrder = little
        val path = directory.resolve("case.asm")
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to text)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun word(value: Int, little: Boolean) =
      (if (little) 0..3 else 3 downTo 0).joinToString("") {
        "%02x".format(value ushr (it * 8) and 255)
      }

  private fun policy(vararg writable: FunctionDataWindow) =
      FunctionAccessPolicyCheck(
          FunctionDataAddress("counter"),
          FunctionDataAddress("end", 4),
          writable = writable.toList(),
          checkReads = false,
      )

  private fun window(label: String, length: Int) =
      FunctionDataWindow(FunctionDataAddress(label), length)

  private fun state(value: Int, little: Boolean, vararg writable: FunctionDataWindow) =
      ConsolePublicationCheck.State(
          listOf(FunctionMemoryCheck("counter", word(value, little))),
          listOf(
              FunctionAddressMemoryCheck(
                  FunctionDataAddress("pointer"),
                  FunctionDataAddress("payload"),
              ),
          ),
          listOf(policy(*writable)),
      )

  private fun check(little: Boolean = true, count: Int = 2): ConsolePublicationCheck {
    val snapshots =
        (1..count).map { value ->
          if (value == 1) state(value, little, window("counter", 4), window("pointer", 8))
          else state(value, little, window("counter", 4))
        }
    return ConsolePublicationCheck(
        "published",
        listOf(FunctionMemoryCheck("limit", word(count, little))),
        snapshots,
        if (count == 0) state(0, little, window("pointer", 8)) else state(count, little),
    )
  }

  private fun evaluate(
      text: String = source,
      check: ConsolePublicationCheck = check(),
      little: Boolean = true,
  ) =
      PreparedConsoleChecks.evaluate(
              image(text, little),
              listOf(ConsoleCheckCase("publications", "", "", 2000, publication = check)),
              ::session,
          )
          .single()

  @Test
  fun `continuous markers preserve the return link and validate relocated full pointers in either byte order`() {
    for (little in listOf(true, false)) for (count in listOf(0, 1, 2, 3)) {
      val result = evaluate(check = check(little, count), little = little)
      assertTrue(result.passed, result.failure)
    }
    session().use { machine ->
      val path = directory.resolve("direct.asm")
      val program =
          machine.assembleSnapshot(
              AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
              null,
              extended = true,
              warnings = false,
          )
      AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { execution ->
        assertEquals(image().byteOrder, execution.byteOrder)
        check().verify(execution)
      }
    }
  }

  @Test
  fun `policies reject restored writes during the next interval and after the final marker`() {
    val restored = $$"li $t0,7\nsb $t0,payload\n"
    val badBetween = source.replace("bnec $" + "t5", restored + "bnec $" + "t5")
    val result = evaluate(badBetween)
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("outside its permitted caller windows"), result.failure)
    val late = evaluate(source.replace("done:\n", restored + "done:\n"))
    assertFalse(late.passed)
    assertTrue(late.failure!!.contains("outside its permitted caller windows"), late.failure)
    assertTrue(evaluate().passed)
  }

  @Test
  fun `wrong snapshots missing visits extra visits and invalid labels fail without resetting the machine`() {
    val wrong = check().copy(snapshots = listOf(state(9, true), state(2, true)))
    val mismatch = evaluate(check = wrong)
    assertFalse(mismatch.passed)
    assertTrue(mismatch.failure!!.startsWith("Publication 1 at published:"), mismatch.failure)
    assertFalse(evaluate(source.replace("bltc $" + "t2,$" + "t1,loop", "nop")).passed)
    val extra =
        source.replace(
            "done:\n",
            $$"done:\n bnezc $t3,exit\n nop\n li $t3,1\n bc published\n nop\nexit:\n",
        )
    val count = evaluate(extra)
    assertFalse(count.passed)
    assertTrue(count.failure!!.contains("exactly 2 completed visits"), count.failure)
    assertFalse(evaluate(check = check().copy(label = "missing")).passed)
    assertFalse(evaluate(check = check().copy(label = "counter")).passed)
    assertFalse(evaluate(source.replace("published:\n", "published:\n break\n")).passed)
  }

  @Test
  fun `nested caller collections are captured before any session is acquired`() {
    val memory = mutableListOf(FunctionMemoryCheck("counter", "09000000"))
    val first = state(1, true, window("counter", 4), window("pointer", 8)).copy(memory = memory)
    val check = check().copy(snapshots = listOf(first, state(2, true, window("counter", 4))))
    val result =
        PreparedConsoleChecks.evaluate(
                image(),
                listOf(ConsoleCheckCase("capture", "", "", 2000, publication = check)),
                {
                  memory.clear()
                  session()
                },
            )
            .single()
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("= 9, got 1"), result.failure)
  }

  @Test
  fun `cancellation between markers closes every subscription and allows an independent retry`() {
    session().use { machine ->
      val path = directory.resolve("cancel.asm")
      val program =
          machine.assembleSnapshot(
              AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
              null,
              extended = true,
              warnings = false,
          )
      var calls = 0
      assertThrows<CancellationException> {
        AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { execution ->
          check().run(execution) {
            if (++calls == 2) throw CancellationException("between markers")
          }
        }
      }
      assertEquals(0, machine.memory.countObservers())
    }
    assertTrue(evaluate().passed)
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `constructor bounds prevent oversized downloaded observation schedules and mixed protocols`() {
    assertThrows<IllegalArgumentException> { check().copy(label = "not a label") }
    assertThrows<IllegalArgumentException> {
      check().copy(snapshots = List(257) { ConsolePublicationCheck.State() })
    }
    assertThrows<IllegalArgumentException> {
      ConsolePublicationCheck.State(List(65) { FunctionMemoryCheck("counter", "00") })
    }
    assertThrows<IllegalArgumentException> {
      ConsolePublicationCheck.State(
          addressMemory =
              List(65) {
                FunctionAddressMemoryCheck(
                    FunctionDataAddress("pointer"),
                    FunctionDataAddress("payload"),
                )
              },
      )
    }
    val megabyte = FunctionMemoryCheck("payload", "00", repeat = 1024 * 1024)
    assertThrows<IllegalArgumentException> { check().copy(initialMemory = listOf(megabyte)) }
    assertThrows<IllegalArgumentException> {
      ConsoleCheckCase("mixed", "", "", maze = ConsoleMazeCheck.Renderer(0), publication = check())
    }
  }
}
