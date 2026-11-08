package edu.missouristate.mars;

import edu.missouristate.mars.api.SyscallEnvironment;

/** Minimal syscall fixture: individual tests override the operations that their programs use. */
abstract class TestEnvironment implements SyscallEnvironment {
  public void printString(String value) {
    throw new UnsupportedOperationException();
  }

  public void printInt(int value) {
    printString(Integer.toString(value));
  }

  public void printFloat(float value) {
    printString(Float.toString(value));
  }

  public void printDouble(double value) {
    printString(Double.toString(value));
  }

  public void printChar(char value) {
    printString(String.valueOf(value));
  }

  public String readString(int maximum) {
    throw new UnsupportedOperationException();
  }

  public int readInt() {
    throw new UnsupportedOperationException();
  }

  public float readFloat() {
    throw new UnsupportedOperationException();
  }

  public double readDouble() {
    throw new UnsupportedOperationException();
  }

  public char readChar() {
    throw new UnsupportedOperationException();
  }

  public void exit(int code) {}

  public int openFile(String path, int flags) {
    throw new UnsupportedOperationException();
  }

  public int readFromFile(int fd, byte[] buffer, int length) {
    throw new UnsupportedOperationException();
  }

  public int writeToFile(int fd, byte[] buffer, int length) {
    throw new UnsupportedOperationException();
  }

  public void closeFile(int fd) {
    throw new UnsupportedOperationException();
  }

  public int sbrk(int bytes) {
    throw new UnsupportedOperationException();
  }

  public void showMessageDialog(String message, String title, int type) {
    throw new UnsupportedOperationException();
  }

  public String showInputDialog(String message) {
    throw new UnsupportedOperationException();
  }

  public int showConfirmDialog(String message) {
    throw new UnsupportedOperationException();
  }

  public void clearConsole() {}
}
