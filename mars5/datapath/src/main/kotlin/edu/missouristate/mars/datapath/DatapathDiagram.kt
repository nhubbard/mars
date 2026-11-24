package edu.missouristate.mars.datapath

import java.util.List.copyOf as immutableList
import java.util.Map.copyOf as immutableMap

/** The original MARS X-Ray diagram tables. */
class DatapathDiagram(
    wires: List<Wire>,
    opcodes: Map<String, String>,
    functions: Map<String, String>,
    registers: Map<String, String>,
) {
  @get:JvmName("wires") val wires: List<Wire> = immutableList(wires)

  @get:JvmName("opcodes") val opcodes: Map<String, String> = immutableMap(opcodes)

  @get:JvmName("functions") val functions: Map<String, String> = immutableMap(functions)

  @get:JvmName("registers") val registers: Map<String, String> = immutableMap(registers)

  override fun equals(other: Any?): Boolean =
      other is DatapathDiagram &&
          wires == other.wires &&
          opcodes == other.opcodes &&
          functions == other.functions &&
          registers == other.registers

  override fun hashCode(): Int =
      ((wires.hashCode() * 31 + opcodes.hashCode()) * 31 + functions.hashCode()) * 31 +
          registers.hashCode()

  override fun toString(): String =
      "DatapathDiagram[wires=$wires, opcodes=$opcodes, functions=$functions, registers=$registers]"

  enum class Kind {
    MAIN,
    REGISTERS,
    CONTROL,
    ALU_CONTROL,
  }

  /** Palette order in each wire's RGB table. ALU palettes occur only in ALU control. */
  enum class Palette {
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
    ALU_OTHER,
  }

  /** Target IDs are indices in the diagram's wire list. Coordinates are in image pixels. */
  class Wire(
      @get:JvmName("name") val name: String,
      @get:JvmName("horizontal") val horizontal: Boolean,
      @get:JvmName("axis") val axis: Int,
      @get:JvmName("start") val start: Int,
      @get:JvmName("end") val end: Int,
      @get:JvmName("label") val label: Boolean,
      targets: List<Int>,
      colors: List<Int>,
  ) {
    @get:JvmName("targets") val targets: List<Int> = immutableList(targets)

    @get:JvmName("colors") val colors: List<Int> = immutableList(colors)

    fun rgb(palette: Palette): Int = colors[palette.ordinal]

    override fun equals(other: Any?): Boolean =
        other is Wire &&
            name == other.name &&
            horizontal == other.horizontal &&
            axis == other.axis &&
            start == other.start &&
            end == other.end &&
            label == other.label &&
            targets == other.targets &&
            colors == other.colors

    override fun hashCode(): Int {
      var hash = name.hashCode()
      hash = 31 * hash + horizontal.hashCode()
      hash = 31 * hash + axis
      hash = 31 * hash + start
      hash = 31 * hash + end
      hash = 31 * hash + label.hashCode()
      hash = 31 * hash + targets.hashCode()
      return 31 * hash + colors.hashCode()
    }

    override fun toString(): String =
        "Wire[name=$name, horizontal=$horizontal, axis=$axis, start=$start, end=$end, " +
            "label=$label, targets=$targets, colors=$colors]"
  }

  companion object {
    @JvmStatic
    fun forKind(kind: Kind): DatapathDiagram =
        when (kind) {
          Kind.MAIN -> MainDatapath.diagram
          Kind.REGISTERS -> RegisterDatapath.diagram
          Kind.CONTROL -> ControlDatapath.diagram
          Kind.ALU_CONTROL -> AluControlDatapath.diagram
        }
  }
}
