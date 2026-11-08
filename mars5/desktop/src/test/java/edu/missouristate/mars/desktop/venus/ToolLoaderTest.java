package edu.missouristate.mars.desktop.venus;

import static org.junit.jupiter.api.Assertions.*;

import edu.missouristate.mars.desktop.di.DaggerDesktopComponent;
import edu.missouristate.mars.desktop.runtime.DesktopSession;
import edu.missouristate.mars.desktop.tools.*;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ToolLoaderTest {
  @Test
  void builtinToolsCanBeListedWithoutCreatingWindows() throws Exception {
    try (var session = DaggerDesktopComponent.create().session()) {
      SwingUtilities.invokeAndWait(
          () -> {
            var menu = new ToolLoader(session).buildToolsMenu();
            assertEquals(14, menu.getItemCount());
            assertEquals("BHT Simulator", menu.getItem(0).getText());
            assertEquals("Screen Magnifier", menu.getItem(13).getText());
          });
    }
  }

  @Test
  void toolCreationIsLazyAndReceivesTheOwningSession() throws Exception {
    try (var session = DaggerDesktopComponent.create().session()) {
      var created = new AtomicInteger();
      var opened = new AtomicInteger();
      var provider =
          new MarsToolProvider() {
            public String name() {
              return "Example";
            }

            public MarsTool create(DesktopSession owner) {
              assertSame(session, owner);
              created.incrementAndGet();
              return new MarsTool() {
                public String getName() {
                  return "Example";
                }

                public void action() {
                  opened.incrementAndGet();
                }
              };
            }
          };
      SwingUtilities.invokeAndWait(
          () -> {
            var menu = new ToolLoader(session, List.of(provider)).buildToolsMenu();
            assertEquals(0, created.get());
            menu.getItem(0).doClick();
            assertEquals(1, created.get());
            assertEquals(1, opened.get());
          });
    }
  }

  @Test
  void toolHardwareAndRegisterWindowsHaveNoMutableStaticFields() {
    for (var type :
        List.of(
            DigitalLabSim.class,
            KeyboardAndDisplaySimulator.class,
            ScavengerHunt.class,
            CacheSimulator.class,
            RegistersWindow.class,
            Coprocessor0Window.class,
            Coprocessor1Window.class,
            DataSegmentWindow.class)) {
      for (var field : type.getDeclaredFields()) {
        assertFalse(
            Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()),
            () -> type.getSimpleName() + "." + field.getName() + " must belong to an instance");
      }
    }
  }
}
