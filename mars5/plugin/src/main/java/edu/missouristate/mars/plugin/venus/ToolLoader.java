package edu.missouristate.mars.plugin.venus;

import edu.missouristate.mars.plugin.runtime.DesktopSession;
import edu.missouristate.mars.plugin.tools.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.function.Function;
import javax.swing.JMenu;

/**
 * Discovers tool metadata without constructing windows; each action receives its owning session.
 */
public final class ToolLoader {
  private final DesktopSession session;
  private final List<MarsToolProvider> tools;

  public ToolLoader(DesktopSession session) {
    this(session, availableTools());
  }

  public ToolLoader(DesktopSession session, List<MarsToolProvider> tools) {
    this.session = session;
    this.tools = List.copyOf(tools);
  }

  public JMenu buildToolsMenu() {
    var menu = new JMenu("Tools");
    menu.setMnemonic(KeyEvent.VK_T);
    tools.stream()
        .sorted(Comparator.comparing(MarsToolProvider::name))
        .forEach(tool -> menu.add(new ToolAction(tool, session)));
    return menu;
  }

  private static MarsToolProvider tool(String name, Function<DesktopSession, MarsTool> factory) {
    return new MarsToolProvider() {
      public String name() {
        return name;
      }

      public MarsTool create(DesktopSession session) {
        return factory.apply(session);
      }
    };
  }

  private static List<MarsToolProvider> availableTools() {
    var tools =
        new ArrayList<>(
            List.of(
                tool("Digital Lab Sim", DigitalLabSim::new),
                tool("Introduction to Tools", IntroToTools::new),
                tool("Floating Point Representation", FloatRepresentation::new),
                tool("BHT Simulator", BHTSimulator::new),
                tool("Keyboard and Display MMIO Simulator", KeyboardAndDisplaySimulator::new),
                tool("Instruction Statistics", InstructionStatistics::new),
                tool("Instruction Counter", InstructionCounter::new),
                tool("Data Cache Simulator", CacheSimulator::new),
                tool("Mars Bot", MarsBot::new),
                tool("Screen Magnifier", ScreenMagnifier::new),
                tool("Memory Reference Visualization", MemoryReferenceVisualization::new),
                tool("ScavengerHunt", ScavengerHunt::new),
                tool("MIPS X-Ray", MipsXray::new),
                tool("Bitmap Display", BitmapDisplay::new)));
    ServiceLoader.load(MarsToolProvider.class).forEach(tools::add);
    return List.copyOf(tools);
  }
}
