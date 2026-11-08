package edu.missouristate.mars.api;

import edu.missouristate.mars.legacy.LegacyApplication;
import edu.missouristate.mars.mips.instructions.syscalls.ToneGenerator;
import edu.missouristate.mars.util.SystemIO;

/**
 * Legacy compatibility environment. New sessions use {@link SessionSyscallEnvironment}. Provides
 * default implementations of all non-dialog {@link SyscallEnvironment} methods by delegating to
 * {@link SystemIO} and {@link edu.missouristate.mars.legacy.LegacyServices#memory}. Subclasses
 * supply the dialog and console-control methods that differ between headless (CLI) and GUI
 * environments.
 */
public abstract class BaseSyscallEnvironment implements SyscallEnvironment {
  private final ToneGenerator tones = new ToneGenerator();

  @Override
  public void playTone(int pitch, int duration, int instrument, int volume, boolean synchronous) {
    if (synchronous) {
      tones.generateToneSynchronously((byte) pitch, duration, (byte) instrument, (byte) volume);
    } else {
      tones.generateTone((byte) pitch, duration, (byte) instrument, (byte) volume);
    }
  }

  @Override
  public void printString(String s) {
    SystemIO.printString(s);
  }

  @Override
  public void printInt(int value) {
    SystemIO.printString(Integer.toString(value));
  }

  @Override
  public void printFloat(float value) {
    SystemIO.printString(Float.toString(value));
  }

  @Override
  public void printDouble(double value) {
    SystemIO.printString(Double.toString(value));
  }

  @Override
  public void printChar(char c) {
    SystemIO.printString(String.valueOf(c));
  }

  @Override
  public String readString(int maxLength) {
    return SystemIO.readString(8, maxLength);
  }

  @Override
  public int readInt() {
    return SystemIO.readInteger(5);
  }

  @Override
  public float readFloat() {
    return SystemIO.readFloat(6);
  }

  @Override
  public double readDouble() {
    return SystemIO.readDouble(7);
  }

  @Override
  public char readChar() {
    return (char) SystemIO.readChar(12);
  }

  @Override
  public void exit(int code) {
    // Exit is signalled by the caller throwing ProcessingException; nothing to do here.
  }

  @Override
  public int openFile(String path, int flags) {
    return SystemIO.openFile(path, flags);
  }

  @Override
  public int readFromFile(int fd, byte[] buffer, int lengthRequested) {
    return SystemIO.readFromFile(fd, buffer, lengthRequested);
  }

  @Override
  public int writeToFile(int fd, byte[] buffer, int lengthRequested) {
    return SystemIO.writeToFile(fd, buffer, lengthRequested);
  }

  @Override
  public void closeFile(int fd) {
    SystemIO.closeFile(fd);
  }

  @Override
  public int sbrk(int bytes) {
    return LegacyApplication.services().memory.allocateBytesFromHeap(bytes);
  }
}
