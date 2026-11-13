package edu.missouristate.mars;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.missouristate.mars.mips.dump.DumpFormatLoader;
import edu.missouristate.mars.mips.dump.MemoryDumpSnapshot;
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryDumpTest {
  @TempDir Path directory;

  @Test
  void intelHexUsesExtendedLinearAddressRecordsPastTheFirst64KiB() throws Exception {
    try (var session = TestSessions.create()) {
      int start = session.memory.dataBaseAddress;
      for (int offset = 0; offset <= 0x10000; offset += Integer.BYTES) {
        session.memory.setWord(start + offset, 0x41424344);
      }
      var output = directory.resolve("data.hex");
      requireNonNull(DumpFormatLoader.findByCommandDescriptor("HEX"))
          .dumpMemoryRange(output.toFile(), start, start + 0x10000, session);
      var lines = Files.readAllLines(output);
      assertThat(lines).hasSize(16387);
      assertThat(lines.getFirst()).isEqualTo(":0400000041424344F2");
      assertThat(lines.subList(16383, lines.size()))
          .containsExactly(":04FFFC0041424344F7", ":020000040001F9",
              ":0400000041424344F2", ":00000001FF");
    }
  }

  @Test
  void segmentWindowWritesAPartialDataRowAndTextSource() throws Exception {
    var source = directory.resolve("program.asm");
    Files.writeString(source, ".data\n.word 1, 2, 3, 4, 5, 6, 7, 8, 9\n.text\nmain: addiu $t0, $zero, 7\n");
    try (var session = TestSessions.create()) {
      session.assemble(List.of(source));
      var format = requireNonNull(DumpFormatLoader.findByCommandDescriptor("SegmentWindow"));
      var output = directory.resolve("segment.txt");
      int data = session.memory.dataBaseAddress;
      format.dumpMemoryRange(output.toFile(), data, data + 8 * Integer.BYTES, session);
      assertThat(Files.readAllLines(output)).containsExactly(
          "0x10010000    0x00000001 0x00000002 0x00000003 0x00000004 0x00000005 0x00000006 0x00000007 0x00000008 ",
          "0x10010020    0x00000009 ");
      int text = session.memory.textBaseAddress;
      format.dumpMemoryRange(output.toFile(), text, text + Integer.BYTES, session);
      var lines = Files.readAllLines(output);
      assertThat(lines).hasSize(3);
      assertThat(lines.getLast()).startsWith("0x00400000  0x24080007  ")
          .endsWith("main: addiu $t0, $zero, 7");
    }
  }

  @Test
  void snapshotRetainsSourceAndMappedIoWithoutHoldingTheStoppedMachine() throws Exception {
    var source = directory.resolve("program.s");
    Files.writeString(source, ".text\nmain: addiu $t0, $zero, 7\n");
    var listingFormat = requireNonNull(DumpFormatLoader.findByCommandDescriptor("SegmentWindow"));
    var hexFormat = requireNonNull(DumpFormatLoader.findByCommandDescriptor("HexText"));
    MemoryDumpSnapshot listing;
    MemoryDumpSnapshot mmio;
    try (var session = TestSessions.create()) {
      session.assemble(List.of(source));
      int text = session.memory.textBaseAddress;
      int mapped = session.memory.memoryMapBaseAddress;
      session.memory.setWord(mapped, 0x12345678);
      var notices = new ArrayList<MemoryAccessNotice>();
      session.memory.addObserver(notices::add, text, text);
      session.memory.addObserver(notices::add, mapped, mapped);
      // Hold the stopped-session lease for all snapshot reads.
      try (var _ = session.claimStoppedSession()) {
        listing = listingFormat.capture(session, text, text, () -> {});
        mmio = hexFormat.capture(session, mapped, mapped, () -> {});
        assertThatThrownBy(() -> hexFormat.capture(session, mapped, mapped, () -> {
          throw new CancellationException();
        })).isInstanceOf(CancellationException.class);
      }
      assertThat(notices).isEmpty();
      var statement = requireNonNull(session.memory.getStatementNoNotify(text));
      statement.setBinaryStatement(0);
      statement.setSource("changed");
      session.memory.setWord(mapped, 0);
    }
    var listingFile = directory.resolve("listing");
    listingFormat.write(listingFile.toFile(), listing);
    assertThat(Files.readAllLines(listingFile).getLast())
        .startsWith("0x00400000  0x24080007  ").endsWith("main: addiu $t0, $zero, 7");
    var mmioFile = directory.resolve("mmio");
    hexFormat.write(mmioFile.toFile(), mmio);
    assertThat(Files.readAllLines(mmioFile)).containsExactly("12345678");
  }
}
