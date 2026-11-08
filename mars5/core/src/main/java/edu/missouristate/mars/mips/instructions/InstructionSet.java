package edu.missouristate.mars.mips.instructions;

import java.io.*;
import java.util.*;

/*
Copyright (c) 2003-2013,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * The list of Instruction objects, each of which represents a MIPS instruction. The instruction may
 * either be basic (translates into binary machine code) or extended (translates into sequence of
 * one or more basic instructions).
 *
 * @author Pete Sanderson and Ken Vollmar
 * @version August 2003-5
 */
public class InstructionSet {
  private final ArrayList<Instruction> instructionList;
  private ArrayList<MatchMap> opcodeMatchMaps;

  /** Creates a new InstructionSet object. */
  public InstructionSet() {
    instructionList = new ArrayList<>();
  }

  /** Retrieve the current instruction set. */
  public ArrayList<Instruction> getInstructionList() {
    return instructionList;
  }

  /**
   * Adds all instructions to the set. A given extended instruction may have more than one
   * Instruction object, depending on how many formats it can have.
   *
   * @see Instruction
   * @see BasicInstruction
   * @see ExtendedInstruction
   */
  public void populate() {
    instructionList.addAll(InstructionRegistry.getAll());

    // READ PSEUDO-INSTRUCTION SPECS FROM DATA FILE AND ADD //////////////////////
    addPseudoInstructions();

    // Initialization step.  Create token list for each instruction example.  This is
    // used by parser to determine user program correct syntax.
    for (Instruction inst : instructionList) {
      inst.createExampleTokenList(this);
    }

    HashMap<Integer, HashMap<Integer, BasicInstruction>> maskMap = new HashMap<>();
    ArrayList<MatchMap> matchMaps = new ArrayList<>();
    for (Object rawInstr : instructionList) {
      if (rawInstr instanceof BasicInstruction basic) {
        Integer mask = basic.getOpcodeMask();
        Integer match = basic.getOpcodeMatch();
        HashMap<Integer, BasicInstruction> matchMap = maskMap.get(mask);
        if (matchMap == null) {
          matchMap = new HashMap<>();
          maskMap.put(mask, matchMap);
          matchMaps.add(new MatchMap(mask, matchMap));
        }
        matchMap.put(match, basic);
      }
    }
    Collections.sort(matchMaps);
    this.opcodeMatchMaps = matchMaps;
  }

  public BasicInstruction findByBinaryCode(int binaryInstr) {
    ArrayList<MatchMap> matchMaps = this.opcodeMatchMaps;
    for (MatchMap map : matchMaps) {
      BasicInstruction ret = map.find(binaryInstr);
      if (ret != null) return ret;
    }
    return null;
  }

  /*  METHOD TO ADD PSEUDO-INSTRUCTIONS
   */

  private void addPseudoInstructions() {
    InputStream is;
    BufferedReader in = null;
    try {
      // leading "/" prevents package name being prepended to filepath.
      is = this.getClass().getResourceAsStream("/PseudoOps.txt");
      in = new BufferedReader(new InputStreamReader(is));
    } catch (NullPointerException e) {
      System.out.println("Error: MIPS pseudo-instruction file PseudoOps.txt not found.");
      System.exit(0);
    }
    try {
      String line;
      String pseudoOp;
      StringBuilder template;
      String firstTemplate;
      String token;
      String description;
      StringTokenizer tokenizer;
      while ((line = in.readLine()) != null) {
        // skip over: comment lines, empty lines, lines starting with blank.
        if (!line.startsWith("#") && !line.startsWith(" ") && !line.isEmpty()) {
          description = "";
          tokenizer = new StringTokenizer(line, "\t");
          pseudoOp = tokenizer.nextToken();
          template = new StringBuilder();
          firstTemplate = null;
          while (tokenizer.hasMoreTokens()) {
            token = tokenizer.nextToken();
            if (token.startsWith("#")) {
              // Optional description must be last token in the line.
              description = token.substring(1);
              break;
            }
            if (token.startsWith("COMPACT")) {
              // has second template for Compact (16-bit) memory config -- added DPS 3 Aug 2009
              firstTemplate = template.toString();
              template = new StringBuilder();
              continue;
            }
            template.append(token);
            if (tokenizer.hasMoreTokens()) {
              template.append("\n");
            }
          }
          ExtendedInstruction inst =
              (firstTemplate == null)
                  ? new ExtendedInstruction(pseudoOp, template.toString(), null, description, this)
                  : new ExtendedInstruction(
                      pseudoOp, firstTemplate, template.toString(), description, this);
          instructionList.add(inst);
          // if (firstTemplate != null) System.out.println("\npseudoOp: "+pseudoOp+"\ndefault
          // template:\n"+firstTemplate+"\ncompact template:\n"+template);
        }
      }
      in.close();
    } catch (IOException ioe) {
      System.out.println("Internal Error: MIPS pseudo-instructions could not be loaded.");
      System.exit(0);
    } catch (Exception ioe) {
      System.out.println("Error: Invalid MIPS pseudo-instruction specification.");
      System.exit(0);
    }
  }

  /**
   * Given an operator mnemonic, will return the corresponding Instruction object(s) from the
   * instruction set. Uses straight linear search technique.
   *
   * @param name operator mnemonic (e.g. addi, sw,...)
   * @return list of corresponding Instruction object(s), or null if not found.
   */
  public ArrayList<Instruction> matchOperator(String name) {
    ArrayList<Instruction> matchingInstructions = null;
    // Linear search for now...
    for (Instruction instruction : instructionList) {
      if (instruction.getName().equalsIgnoreCase(name)) {
        if (matchingInstructions == null) matchingInstructions = new ArrayList<>();
        matchingInstructions.add(instruction);
      }
    }
    return matchingInstructions;
  }

  /**
   * Given a string, will return the Instruction object(s) from the instruction set whose operator
   * mnemonic prefix matches it. Case-insensitive. For example "s" will match "sw", "sh", "sb", etc.
   * Uses straight linear search technique.
   *
   * @param name a string
   * @return list of matching Instruction object(s), or null if none match.
   */
  public ArrayList<Instruction> prefixMatchOperator(String name) {
    ArrayList<Instruction> matchingInstructions = null;
    // Linear search for now....
    if (name != null) {
      for (Instruction instruction : instructionList) {
        if (instruction.getName().toLowerCase().startsWith(name.toLowerCase())) {
          if (matchingInstructions == null) matchingInstructions = new ArrayList<>();
          matchingInstructions.add(instruction);
        }
      }
    }
    return matchingInstructions;
  }

  private static class MatchMap implements Comparable<MatchMap> {
    private final int mask;
    private final int maskLength; // number of 1 bits in mask
    private final HashMap<Integer, BasicInstruction> matchMap;

    public MatchMap(int mask, HashMap<Integer, BasicInstruction> matchMap) {
      this.mask = mask;
      this.matchMap = matchMap;

      int k = 0;
      int n = mask;
      while (n != 0) {
        k++;
        n &= n - 1;
      }
      this.maskLength = k;
    }

    public boolean equals(Object o) {
      return o instanceof MatchMap && mask == ((MatchMap) o).mask;
    }

    public int compareTo(MatchMap other) {
      int d = other.maskLength - this.maskLength;
      if (d == 0) d = this.mask - other.mask;
      return d;
    }

    public BasicInstruction find(int instr) {
      int match = instr & mask;
      return matchMap.get(match);
    }
  }
}
