package edu.missouristate.mars.plugin;

import edu.missouristate.mars.api.MarsGuiProxy;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Session-scoped frontend routing; a desktop window attaches after its component is built. */
@Singleton
public final class VenusMarsGuiProxy implements MarsGuiProxy {
  private volatile MarsGuiProxy delegate;

  @Inject
  public VenusMarsGuiProxy() {}

  public MarsGuiProxy frontend() {
    return delegate;
  }

  public void attach(MarsGuiProxy delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public boolean isGuiMode() {
    return delegate != null && delegate.isGuiMode();
  }

  public void postMessage(String message) {
    if (delegate == null) System.err.print(message);
    else delegate.postMessage(message);
  }

  public void postRunMessage(String message) {
    if (delegate == null) System.out.print(message);
    else delegate.postRunMessage(message);
  }

  public String readInput(int length) {
    if (delegate == null) throw new IllegalStateException("No desktop input is attached");
    return delegate.readInput(length);
  }

  public String readInputWithPrompt(String prompt) {
    if (delegate == null) throw new IllegalStateException("No desktop input is attached");
    return delegate.readInputWithPrompt(prompt);
  }

  public void clearRunConsole() {
    if (delegate != null) delegate.clearRunConsole();
  }
}
