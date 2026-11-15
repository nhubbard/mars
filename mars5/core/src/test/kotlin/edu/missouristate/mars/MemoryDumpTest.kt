package edu.missouristate.mars

import edu.missouristate.mars.mips.dump.DumpFormat
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.mips.dump.MemoryDumpSnapshot
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects
import java.util.concurrent.CancellationException
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class MemoryDumpTest {
    @TempDir lateinit var directory: Path

    @Test
    fun intelHexUsesExtendedLinearAddressRecordsPastTheFirst64KiB() {
        TestSessions.create().use { session ->
            val start: Int = session.memory.dataBaseAddress
            var offset = 0
            while (offset <= 0x10000) {
                session.memory.setWord(start + offset, 0x41424344)
                offset += Integer.BYTES
            }
            val output = directory.resolve("data.hex")
            Objects.requireNonNull(DumpFormatLoader.findByCommandDescriptor("HEX"))!!
                .dumpMemoryRange(output.toFile(), start, start + 0x10000, session)
            val lines = Files.readAllLines(output)
            Assertions.assertThat(lines).hasSize(16387)
            Assertions.assertThat(lines.first()).isEqualTo(":0400000041424344F2")
            Assertions.assertThat(lines.subList(16383, lines.size))
                .containsExactly(
                    ":04FFFC0041424344F7",
                    ":020000040001F9",
                    ":0400000041424344F2",
                    ":00000001FF",
                )
        }
    }

    @Test
    fun segmentWindowWritesAPartialDataRowAndTextSource() {
        val source = directory.resolve("program.asm")
        Files.writeString(
            source,
            $$".data\n.word 1, 2, 3, 4, 5, 6, 7, 8, 9\n.text\nmain: addiu $t0, $zero, 7\n",
        )
        TestSessions.create().use { session ->
            session.assemble(listOf(source))
            val format =
                Objects.requireNonNull<DumpFormat>(
                    DumpFormatLoader.findByCommandDescriptor("SegmentWindow"),
                )
            val output = directory.resolve("segment.txt")
            val data: Int = session.memory.dataBaseAddress
            format.dumpMemoryRange(output.toFile(), data, data + 8 * Integer.BYTES, session)
            Assertions.assertThat(Files.readAllLines(output))
                .containsExactly(
                    "0x10010000    0x00000001 0x00000002 0x00000003 0x00000004 0x00000005 0x00000006 0x00000007 0x00000008 ",
                    "0x10010020    0x00000009 ",
                )
            val text: Int = session.memory.textBaseAddress
            format.dumpMemoryRange(output.toFile(), text, text + Integer.BYTES, session)
            val lines = Files.readAllLines(output)
            Assertions.assertThat(lines).hasSize(3)
            Assertions.assertThat(lines.last())
                .startsWith("0x00400000  0x24080007  ")
                .endsWith($$"main: addiu $t0, $zero, 7")
        }
    }

    @Test
    fun snapshotRetainsSourceAndMappedIoWithoutHoldingTheStoppedMachine() {
        val source = directory.resolve("program.s")
        Files.writeString(source, $$".text\nmain: addiu $t0, $zero, 7\n")
        val listingFormat =
            Objects.requireNonNull<DumpFormat>(
                DumpFormatLoader.findByCommandDescriptor("SegmentWindow"),
            )
        val hexFormat =
            Objects.requireNonNull<DumpFormat>(DumpFormatLoader.findByCommandDescriptor("HexText"))
        val listing: MemoryDumpSnapshot
        val mmio: MemoryDumpSnapshot
        TestSessions.create().use { session ->
            session.assemble(listOf(source))
            val text: Int = session.memory.textBaseAddress
            val mapped: Int = session.memory.memoryMapBaseAddress
            session.memory.setWord(mapped, 0x12345678)
            val notices = ArrayList<MemoryAccessNotice>()
            session.memory.addObserver(
                { e: MemoryAccessNotice -> notices.add(e) },
                text,
                text,
            )
            session.memory.addObserver(
                { e: MemoryAccessNotice -> notices.add(e) },
                mapped,
                mapped,
            )
            session.claimStoppedSession().use { `_` ->
                listing = listingFormat.capture(session, text, text, {})
                mmio = hexFormat.capture(session, mapped, mapped, {})
                Assertions.assertThatThrownBy {
                    hexFormat.capture(
                        session,
                        mapped,
                        mapped,
                        {
                            throw CancellationException()
                        },
                    )
                }
                    .isInstanceOf(CancellationException::class.java)
            }
            Assertions.assertThat(notices).isEmpty()
            val statement = requireNotNull(session.memory.getStatementNoNotify(text))
            statement.binaryStatement = 0
            statement.source = "changed"
            session.memory.setWord(mapped, 0)
        }
        val listingFile = directory.resolve("listing")
        listingFormat.write(listingFile.toFile(), listing)
        Assertions.assertThat(Files.readAllLines(listingFile).last())
            .startsWith("0x00400000  0x24080007  ")
            .endsWith($$"main: addiu $t0, $zero, 7")
        val mmioFile = directory.resolve("mmio")
        hexFormat.write(mmioFile.toFile(), mmio)
        Assertions.assertThat(Files.readAllLines(mmioFile)).containsExactly("12345678")
    }
}
