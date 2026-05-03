package edu.missouristate.mars.plugin;

import edu.missouristate.mars.api.MarsGuiProxy;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * IntelliJ plugin implementation of {@link MarsGuiProxy}. Routes all I/O to the MARS tool window
 * (Messages pane and Run I/O pane) built by {@code VenusUI}.
 *
 * <p>This class is registered as an IntelliJ application-level service in {@code plugin.xml}:
 *
 * <pre>{@code
 * <applicationService
 *   serviceInterface="edu.missouristate.mars.api.MarsGuiProxy"
 *   serviceImplementation="edu.missouristate.mars.plugin.VenusMarsGuiProxy"/>
 * }</pre>
 *
 * <p>When used with Dagger (e.g. in tests or the standalone Swing launcher), inject it via
 * {@link edu.missouristate.mars.plugin.di.PluginModule} which binds it to {@link MarsGuiProxy}.
 *
 * <p><b>Stub:</b> message routing to the actual Venus toolwindow panels is wired in a later
 * migration phase; for now all output goes to stderr/stdout so the class is safe to instantiate.
 */
@Singleton
public final class VenusMarsGuiProxy implements MarsGuiProxy {

  @Inject
  public VenusMarsGuiProxy() {}

  @Override
  public boolean isGuiMode() {
    return true;
  }

  @Override
  public void postMessage(String message) {
    // TODO(plugin): route to MessagesPane via SwingUtilities.invokeLater
    System.err.print(message);
  }

  @Override
  public void postRunMessage(String message) {
    // TODO(plugin): route to RunIOPane via SwingUtilities.invokeLater
    System.out.print(message);
  }

  @Override
  public String readInput(int maxLength) {
    // TODO(plugin): block on RunIOPane input field
    return "";
  }

  @Override
  public String readInputWithPrompt(String prompt) {
    // TODO(plugin): show dialog or use RunIOPane prompt
    return "";
  }

  @Override
  public void clearRunConsole() {
    // TODO(plugin): clear RunIOPane text area via EDT
  }
}
