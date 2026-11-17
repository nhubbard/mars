package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.RegisterAccessNotice
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Predicate
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class AccessOriginTest {
    @TempDir lateinit var directory: Path

    @Test
    fun synchronousExecutionMarksInstructionFetchesAndRestoresApplicationOrigin() {
        TestSessions.create().use { session ->
            val source = directory.resolve("origin.s")
            Files.writeString(source, $$".text\nmain: addiu $t0,$zero,42\n")
            val program: MIPSprogram = session.assemble(listOf(source))
            val notices = ArrayList<MemoryAccessNotice>()
            session.memory.addObserver(
                { notices.add(it) },
                session.memory.textBaseAddress,
                session.memory.textBaseAddress + 4,
            )
            Assertions.assertThat(program.simulate(1)).isFalse()
            Assertions.assertThat(notices)
                .isNotEmpty()
                .allMatch(
                    Predicate { n: MemoryAccessNotice ->
                        n.origin() == AccessOrigin.EXECUTION && n.isInstructionFetch
                    },
                )
            Assertions.assertThat<AccessOrigin?>(AccessOrigin.current())
                .isEqualTo(AccessOrigin.APPLICATION)
            session.memory.getStatement(session.memory.textBaseAddress)
            Assertions.assertThat<AccessOrigin?>(notices.last().origin())
                .isEqualTo(AccessOrigin.APPLICATION)
            Assertions.assertThat(notices.last().isInstructionFetch).isFalse()
            Assertions.assertThat<AccessOrigin?>(notices.first().origin())
                .isEqualTo(AccessOrigin.EXECUTION)
        }
    }

    @Test
    fun fetchFromDataIsDistinctFromReadingTheSameWord() {
        TestSessions.create().use { session ->
            val memory: MemoryState = session.memory
            val address = memory.dataSegmentBaseAddress
            session.settings.setSelfModifyingCodeEnabled(true)
            memory.setWord(address, 0)
            val notices = ArrayList<MemoryAccessNotice>()
            memory.addObserver({ notices.add(it) }, address)
            AccessOrigin.duringExecution<Any?, AddressErrorException> {
                memory.fetchInstruction(address)
                memory.getWord(address)
                memory.getStatementNoNotify(address)
                null
            }
            Assertions.assertThat(notices)
                .extracting<Boolean?, RuntimeException>(MemoryAccessNotice::isInstructionFetch)
                .containsExactly(true, false)
            Assertions.assertThat(notices)
                .allMatch(
                    Predicate { n: MemoryAccessNotice -> n.origin() == AccessOrigin.EXECUTION },
                )
        }
    }

    @Test
    fun addressObserversCanExcludeFetchesWithoutChangingDeliveryOrder() {
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            session.settings.setSelfModifyingCodeEnabled(true)
            val memory = session.memory
            val address = checkNotNull(memory.wideDataRange).first
            val events = mutableListOf<Pair<String, MemoryAccessNotice>>()
            val data: (MemoryAccessNotice) -> Unit = { events.add("data" to it) }
            val all: (MemoryAccessNotice) -> Unit = { events.add("all" to it) }
            val last: (MemoryAccessNotice) -> Unit = {
                events.add("last" to it)
                memory.deleteAddressObserver(all)
            }

            memory.addAddressObserver(data, includeInstructionFetch = false)
            memory.addAddressObserver(data)
            memory.addAddressObserver(last)
            memory.addAddressObserver(all)
            AccessOrigin.duringExecution<Unit, AddressErrorException> {
                memory.fetchInstruction(address)
                memory.writeData(address, MemoryState.WORD_LENGTH_BYTES, 1)
            }
            Assertions.assertThat(events.map { (observer, notice) -> observer to notice.isInstructionFetch })
                .containsExactly("last" to true, "all" to true, "data" to false, "last" to false)
            Assertions.assertThat(events.map { it.second.addressBits }).containsOnly(address)
            Assertions.assertThat(events.map { it.second.origin() }).containsOnly(AccessOrigin.EXECUTION)
            Assertions.assertThat(events[0].second).isSameAs(events[1].second)
            memory.deleteAddressObserver(last)
            memory.deleteAddressObserver(data)
            memory.readData(address, MemoryState.WORD_LENGTH_BYTES)
            Assertions.assertThat(events).hasSize(4)
        }
    }

    @Test
    fun threadNamesDoNotTurnApplicationWritesIntoInstructionWrites() {
        TestSessions.create().use { session ->
            val notices = LinkedBlockingQueue<RegisterAccessNotice>()
            session.registerFile.registers[8].addObserver { notices.add(it) }
            val operation = FutureTask<Int?> { session.registerFile.updateRegister(8, 42) }
            Thread.ofPlatform().name("MIPS-spoof").start(operation)
            operation.get(2, TimeUnit.SECONDS)
            val notice =
                checkNotNull(notices.poll(2, TimeUnit.SECONDS)) { "Expected a memory access notice" }
            Assertions.assertThat<AccessOrigin?>(notice.origin())
                .isEqualTo(AccessOrigin.APPLICATION)
        }
    }

    @Test
    fun nestedBindingsAndExceptionalExitsRestoreTheOuterOrigin() {
        Assertions.assertThatThrownBy {
            AccessOrigin.duringExecution<Any?, RuntimeException> {
                Assertions.assertThat<AccessOrigin?>(AccessOrigin.current())
                    .isEqualTo(AccessOrigin.EXECUTION)
                AccessOrigin.duringExecution<Any?, RuntimeException> {
                    Assertions.assertThat<AccessOrigin?>(AccessOrigin.current())
                        .isEqualTo(AccessOrigin.EXECUTION)
                    null
                }
                Assertions.assertThat<AccessOrigin?>(AccessOrigin.current())
                    .isEqualTo(AccessOrigin.EXECUTION)
                throw IllegalArgumentException("failed invocation")
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        Assertions.assertThat<AccessOrigin?>(AccessOrigin.current())
            .isEqualTo(AccessOrigin.APPLICATION)
    }
}
