package edu.missouristate.mars.api;

/**
 * Minimal GUI abstraction visible to mars-core. Decouples the simulator core from VenusUI so
 * Globals can live in mars-core without a compile-time dependency on mars-plugin.
 *
 * <p>Two implementations are provided:
 *
 * <ul>
 *   <li>{@code VenusUI} (mars-plugin) — routes to {@code MessagesPane}
 *   <li>{@code HeadlessGuiProxy} (mars-cli) — routes to {@code System.in} / {@code System.out}
 * </ul>
 *
 * <p>All methods are safe to call from any thread.
 */
public interface MarsGuiProxy {

  /** Returns {@code true} when running with a Swing GUI; {@code false} in headless/CLI mode. */
  boolean isGuiMode();

  /**
   * Posts a message to the "MARS Messages" console (the non-program output area). In headless
   * mode, writes to {@code System.err}.
   */
  void postMessage(String message);

  /**
   * Posts a string to the "Run I/O" console (program stdout). In headless mode, writes to {@code
   * System.out}.
   */
  void postRunMessage(String message);

  /**
   * Reads a string from the "Run I/O" console (program stdin).
   *
   * @param maxLength Maximum characters to read; -1 means read until newline.
   */
  String readInput(int maxLength);

  /**
   * Reads a string, showing {@code prompt} to the user first. In GUI mode this may display a
   * pop-up dialog (when the POPUP_SYSCALL_INPUT setting is enabled). In headless mode the prompt
   * is written to {@code System.err} and input is read from {@code System.in}.
   */
  String readInputWithPrompt(String prompt);

  /** Clears the "Run I/O" console. No-op in headless mode. */
  void clearRunConsole();
}
