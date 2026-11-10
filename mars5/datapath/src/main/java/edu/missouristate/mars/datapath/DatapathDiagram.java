package edu.missouristate.mars.datapath;

import java.util.List;
import java.util.Map;

/** The original MARS X-Ray diagram tables, shared by the two frontends. */
public record DatapathDiagram(
    List<Wire> wires,
    Map<String, String> opcodes,
    Map<String, String> functions,
    Map<String, String> registers) {
  public DatapathDiagram {
    wires = List.copyOf(wires);
    opcodes = Map.copyOf(opcodes);
    functions = Map.copyOf(functions);
    registers = Map.copyOf(registers);
  }

  public enum Kind {
    MAIN,
    REGISTERS,
    CONTROL,
    ALU_CONTROL
  }

  /** Palette order in each wire's RGB table. ALU palettes occur only in ALU control. */
  public enum Palette {
    REGISTER,
    IMMEDIATE,
    JUMP,
    LOAD,
    STORE,
    BRANCH,
    ALU_ADD,
    ALU_SUBTRACT,
    ALU_AND,
    ALU_OR,
    ALU_OTHER
  }

  /** Target IDs are indices in the diagram's wire list. Coordinates are in image pixels. */
  public record Wire(
      String name,
      boolean horizontal,
      int axis,
      int start,
      int end,
      boolean label,
      List<Integer> targets,
      List<Integer> colors) {
    public Wire {
      targets = List.copyOf(targets);
      colors = List.copyOf(colors);
    }

    public int rgb(Palette palette) {
      return colors.get(palette.ordinal());
    }
  }

  public static DatapathDiagram forKind(Kind kind) {
    return switch (kind) {
      case MAIN -> MainDatapath.DATA;
      case REGISTERS -> RegisterDatapath.DATA;
      case CONTROL -> ControlDatapath.DATA;
      case ALU_CONTROL -> AluControlDatapath.DATA;
    };
  }
}
