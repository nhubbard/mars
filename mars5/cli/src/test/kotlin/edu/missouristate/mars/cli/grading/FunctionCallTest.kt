package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class FunctionCallTest {
  @Test
  fun `repeated calls keep full addresses memory observations and an external undo baseline`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("calls.asm").also {
          it.writeText(
              $$"""
                  .data 0x100000000
              value: .word 0
                  .text
              done:
                  li $v0, 10
                  syscall
              return_a:
                  nop
              return_b:
                  nop
              worker:
                  lw $t0, 0($a0)
                  addu $t0, $t0, $a1
                  sw $t0, 0($a0)
                  move $v0, $t0
                  jr $ra
                  nop
              """
                  .trimIndent(),
          )
        }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val program = machine.assemble(listOf(source))
          AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
            val counts = case.traceExecution()
            val data = case.traceMemoryAccesses("value", 4)
            case.setRegisterToDataAddress("a0", "value")
            case.setRegister("s0", 0x2345678900000011L)
            for ((index, delta) in listOf(2, 5, -1).withIndex()) {
              case.setRegister("a1", delta)
              case.callFunction("worker", if (index % 2 == 0) "return_a" else "return_b")
            }
            case.expectWord("value", 6)
            case.expectRegister("v0", 6)
            case.expectRegister("s0", 0x2345678900000011L)
            assertEquals(18, counts.snapshot().instructions)
            assertEquals(List(6) { 0x100000000L }, data.snapshot().map { it.address })
            assertEquals(3, data.snapshot().count { it.write })
            val ra = machine.registerFile.getLongValueNoNotify(31)
            repeat(6) { machine.simulator().backstepAsync().join() }
            case.expectWord("value", 7)
            assertFalse(machine.backStepper.canBackstep())
            assertEquals(ra, machine.registerFile.getLongValueNoNotify(31))
            assertEquals(18, counts.snapshot().instructions)
            assertEquals(3, data.snapshot().count { it.write })
          }
        }
  }

  @Test
  fun `pending delayed and compact forbidden slots are rejected without losing their state`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("slots.asm").also {
          it.writeText(
              $$"""
                  .text
              delayed:
                  beq $zero, $zero, compact
                  nop
              compact:
                  li $t0, 1
                  beqzc $t0, done
                  nop
              ready:
                  nop
              worker:
                  addiu $s0, $s0, 1
                  jr $ra
                  nop
              done:
                  li $v0, 10
                  syscall
              """
                  .trimIndent(),
          )
        }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val program = machine.assemble(listOf(source))
          AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
            case.startAt("delayed")
            case.step()
            assertTrue(machine.delayedBranch.isTriggered)
            val delayed = machine.delayedBranch.snapshot()
            val pc = machine.registerFile.programCounterBits
            val ra = machine.registerFile.getLongValueNoNotify(31)
            assertThrows<CaseFailure> { case.callFunction("worker", "done") }
            assertEquals(delayed, machine.delayedBranch.snapshot())
            assertEquals(pc, machine.registerFile.programCounterBits)
            assertEquals(ra, machine.registerFile.getLongValueNoNotify(31))
            case.step()
            case.step(2)
            val forbidden = machine.delayedBranch.snapshot()
            assertTrue(forbidden.forbiddenSlot)
            assertThrows<CaseFailure> { case.callFunction("worker", "done") }
            assertEquals(forbidden, machine.delayedBranch.snapshot())
            case.step()
            case.callFunction("worker", "done")
            case.expectRegister("s0", 1)
            case.runToExit()
          }
        }
  }

  @Test
  fun `invalid entries and terminal stops cannot resurrect a session or change its caller`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("validation.asm").also {
          it.writeText(
              $$"""
                  .data
              value: .word 0
                  .text
              main:
                  li $v0, 10
                  syscall
              worker:
                  jr $ra
                  nop
              """
                  .trimIndent(),
          )
        }
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val program = machine.assemble(listOf(source))
          AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
            case.setRegister("ra", 1234)
            val pc = machine.registerFile.programCounterBits
            for ((entry, continuation) in
                listOf(
                    "missing" to "main",
                    "worker" to "missing",
                    "value" to "main",
                    "worker" to "value",
                    "worker" to "worker",
                )) {
              assertThrows<CaseFailure> { case.callFunction(entry, continuation) }
              case.expectRegister("ra", 1234)
              assertEquals(pc, machine.registerFile.programCounterBits)
            }
            case.runToExit()
            assertThrows<CaseFailure> { case.callFunction("worker", "main") }
            case.expectRegister("ra", 1234)
          }
        }
  }
}
