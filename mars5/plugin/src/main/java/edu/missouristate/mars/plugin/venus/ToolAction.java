package edu.missouristate.mars.plugin.venus;

import edu.missouristate.mars.plugin.runtime.DesktopSession;
import edu.missouristate.mars.plugin.tools.MarsToolProvider;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;

/** Opens one tool on the session that owns its menu. */
public final class ToolAction extends AbstractAction {
  private final MarsToolProvider provider;
  private final DesktopSession session;

  public ToolAction(MarsToolProvider provider, DesktopSession session) {
    super(provider.name());
    this.provider = provider;
    this.session = session;
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    provider.create(session).action();
  }
}
