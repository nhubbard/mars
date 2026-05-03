package edu.missouristate.mars.api;

/**
 * Carries information about a single completed instruction step.
 *
 * @param pc the program counter value of the instruction that just executed
 * @param instruction the 32-bit machine-code word that executed
 * @param stepCount total number of instructions executed so far in this run
 */
public record StepEvent(int pc, int instruction, long stepCount) {}
