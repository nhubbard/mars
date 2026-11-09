package edu.missouristate.mars.api;

import edu.missouristate.mars.api.InstructionSourceMap.Origin;

/** One emitted instruction and its provenance in the original assembled image. */
public record AssembledInstruction(int address, int encoding, String assembly, Origin origin) {}
