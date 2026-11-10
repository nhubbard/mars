package edu.missouristate.mars.datapath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.missouristate.mars.datapath.DatapathDiagram.Kind;
import edu.missouristate.mars.datapath.DatapathDiagram.Palette;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatapathDiagramTest {
  // The fingerprints cover every wire, target, color, and effective lookup from the old diagrams.
  @ParameterizedTest
  @CsvSource({
    "MAIN, 102, 6, 27d30f44ed520e8c6bdfbcaef3b077e191281ef971d57b35f7e827c429aaa596",
    "REGISTERS, 62, 6, bdacd05663ecc53b7ab53c4dc0c180e19445ef7d53055a01df017e74a5c576c7",
    "CONTROL, 81, 6, 3a8998d2f07c2810de16630e0444a098d36da07e9af15caf0d3eb88188121041",
    "ALU_CONTROL, 24, 11, 76e867b411b45e5525a4e684dc192d0c8620c2529734613178b87d8e573a3ef8"
  })
  void retainsEveryDiagramValue(Kind kind, int wireCount, int paletteCount, String expected)
      throws Exception {
    var diagram = DatapathDiagram.forKind(kind);
    assertEquals(wireCount, diagram.wires().size());
    var lines = new ArrayList<String>();
    for (var wire : diagram.wires()) {
      assertTrue(wire.targets().stream().allMatch(target -> target >= 0 && target < wireCount));
      assertEquals(paletteCount, wire.colors().size());
      for (int palette = 0; palette < paletteCount; palette++) {
        assertEquals(wire.colors().get(palette).intValue(), wire.rgb(Palette.values()[palette]));
      }
      lines.add(
          String.join(
              "\t",
              wire.name(),
              Boolean.toString(wire.horizontal()),
              Integer.toString(wire.axis()),
              Integer.toString(wire.start()),
              Integer.toString(wire.end()),
              Boolean.toString(wire.label()),
              commaSeparated(wire.targets()),
              commaSeparated(wire.colors())));
    }
    for (var lookup : List.of(diagram.opcodes(), diagram.functions(), diagram.registers())) {
      lines.add("");
      for (Map.Entry<String, String> entry : new TreeMap<>(lookup).entrySet()) {
        lines.add(entry.getKey() + "\t" + entry.getValue());
      }
    }
    var digest = MessageDigest.getInstance("SHA-256");
    byte[] canonical = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    assertEquals(expected, HexFormat.of().formatHex(digest.digest(canonical)));
  }

  private static String commaSeparated(List<Integer> values) {
    return values.stream().map(String::valueOf).collect(Collectors.joining(","));
  }
}
