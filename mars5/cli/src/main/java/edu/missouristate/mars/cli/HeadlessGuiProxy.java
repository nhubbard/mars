package edu.missouristate.mars.cli;

import edu.missouristate.mars.api.MarsGuiProxy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import org.jetbrains.annotations.NotNull;

/** MarsGuiProxy implementation for headless / command-line execution. */
public final class HeadlessGuiProxy implements MarsGuiProxy {

  private final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

  @Override
  public boolean isGuiMode() {
    return false;
  }

  @Override
  public void postMessage(@NotNull String message) {
    System.err.print(message);
  }

  @Override
  public void postRunMessage(@NotNull String message) {
    System.out.print(message);
  }

  @Override
  public @NotNull String readInput(int maxLength) {
    try {
      String line = stdin.readLine();
      if (line == null) {
        return "";
      }
      return maxLength < 0 ? line : line.substring(0, Math.min(line.length(), maxLength));
    } catch (IOException e) {
      return "";
    }
  }

  @Override
  public @NotNull String readInputWithPrompt(@NotNull String prompt) {
    System.err.print(prompt);
    return readInput(-1);
  }

  @Override
  public void clearRunConsole() {
    // no-op in headless mode
  }
}
