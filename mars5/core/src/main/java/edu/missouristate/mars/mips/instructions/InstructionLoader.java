package edu.missouristate.mars.mips.instructions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads the basic instruction catalog and pseudo templates without terminating the host process.
 */
public final class InstructionLoader {
  private static final String BASIC =
      "/edu/missouristate/mars/instructions/basic-instructions.list";

  private static BufferedReader resource(String path) {
    var input = InstructionLoader.class.getResourceAsStream(path);
    if (input == null) {
      throw new IllegalStateException("Missing instruction resource: " + path);
    }
    return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
  }

  public List<BasicInstruction> loadBasicInstructions() {
    var instructions = new ArrayList<BasicInstruction>();
    try (var input = resource(BASIC)) {
      String line;
      while ((line = input.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        var type =
            Class.forName("edu.missouristate.mars.mips.instructions.basic." + line.trim())
                .asSubclass(BasicInstruction.class);
        instructions.add(type.getDeclaredConstructor().newInstance());
      }
      return List.copyOf(instructions);
    } catch (IOException | ReflectiveOperationException failure) {
      throw new IllegalStateException("Unable to load basic instruction catalog", failure);
    }
  }

  public List<ExtendedInstruction> loadExtendedInstructions(InstructionSet owner) {
    var instructions = new ArrayList<ExtendedInstruction>();
    try (var input = resource("/PseudoOps.txt")) {
      String line;
      while ((line = input.readLine()) != null) {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(" ")) {
          continue;
        }
        var tokens = new StringTokenizer(line, "\t");
        String example = tokens.nextToken(), description = "", first = null;
        var template = new StringBuilder();
        while (tokens.hasMoreTokens()) {
          String token = tokens.nextToken();
          if (token.startsWith("#")) {
            description = token.substring(1);
            break;
          }
          if (token.startsWith("COMPACT")) {
            first = template.toString();
            template = new StringBuilder();
            continue;
          }
          template.append(token);
          if (tokens.hasMoreTokens()) {
            template.append('\n');
          }
        }
        instructions.add(
            first == null
                ? new ExtendedInstruction(example, template.toString(), null, description, owner)
                : new ExtendedInstruction(example, first, template.toString(), description, owner));
      }
      return List.copyOf(instructions);
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("Unable to load pseudo-instruction templates", failure);
    }
  }
}
