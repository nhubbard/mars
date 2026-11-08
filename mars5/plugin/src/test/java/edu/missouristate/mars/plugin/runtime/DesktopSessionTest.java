package edu.missouristate.mars.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import edu.missouristate.mars.api.*;
import edu.missouristate.mars.plugin.di.DaggerPluginComponent;
import edu.missouristate.mars.plugin.venus.VenusSyscallEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSessionTest {
  @TempDir Path directory;

  @Test
  void componentsOwnIndependentHardwareFileTablesAndFrontendState() throws Exception {
    try (var first = DaggerPluginComponent.create().session();
        var second = DaggerPluginComponent.create().session()) {
      assertNotSame(first.memory, second.memory);
      assertNotSame(first.context().registerFile, second.context().registerFile);
      first.instructionContext().updateRegister(8, 42);
      assertEquals(0, second.context().registerFile.getValue(8));
      first.setExitCode(17);
      assertEquals(0, second.exitCode());
      int fd = first.context().syscallEnvironment.openFile(directory.resolve("one").toString(), 1);
      assertEquals(3, fd);
      assertEquals(-1, second.context().syscallEnvironment.writeToFile(fd, new byte[] {1}, 1));
      var gui = new Console();
      first.setGui(gui);
      assertTrue(first.context().gui.isGuiMode());
      assertFalse(second.context().gui.isGuiMode());
      var io = new VenusSyscallEnvironment(first);
      assertEquals(2, io.writeToFile(1, "hello".getBytes(), 2));
      assertEquals("he", gui.output.toString());
      assertEquals(1, io.writeToFile(fd, new byte[] {42}, 1));
      first.context().syscallEnvironment.closeFile(fd);
      assertArrayEquals(new byte[] {42}, Files.readAllBytes(directory.resolve("one")));
    }
  }

  @Test
  void desktopStepAndRunUseTheCanonicalEngineAndCompletionDispatcher() throws Exception {
    try (var session = DaggerPluginComponent.create().session()) {
      var source = directory.resolve("program.s");
      Files.writeString(source, ".text\nmain: addiu $t0,$zero,42\naddiu $v0,$zero,10\nsyscall\n");
      var program = session.context().assemble(List.of(source));
      session.setProgram(program);
      var dispatcher = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
      session.simulator().setCompletionDispatcher(dispatcher::add);
      var actor = new Actor("Step");
      session
          .simulator()
          .setRunSpeedSource(() -> 0.01); // Single-step must not wait for the next tick.
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(3),
          () -> {
            session
                .simulator()
                .simulate(program, session.context().registerFile.getPC(), 1, null, actor);
            var callback = dispatcher.poll(2, TimeUnit.SECONDS);
            assertNotNull(callback);
            assertNull(actor.result);
            callback.run();
          });
      assertEquals(DesktopSimulation.MAX_STEPS, actor.result);
      assertEquals(42, session.context().registerFile.getValue(8));
      session.simulator().setRunSpeedSource(() -> DesktopSimulation.UNLIMITED_SPEED);
      assertTrue(
          session
              .simulator()
              .simulate(program, session.context().registerFile.getPC(), -1, null, null));
      assertEquals(SimulatorState.STOPPED, session.context().simulator().state());
    }
  }

  @Test
  void pauseInterruptsTheSpeedDelayAtAnInstructionBoundary() throws Exception {
    try (var session = DaggerPluginComponent.create().session()) {
      var source = directory.resolve("loop.s");
      Files.writeString(source, ".text\nmain: addiu $t0,$t0,1\nj main\n");
      var program = session.context().assemble(List.of(source));
      var firstStep = new CountDownLatch(1);
      session
          .context()
          .simulator()
          .addListener(
              new SimulatorListener() {
                public void onStep(StepEvent event) {
                  firstStep.countDown();
                }

                public void onStop(StopEvent event) {}

                public void onError(ErrorEvent event) {
                  fail(event.cause());
                }
              });
      session.simulator().setRunSpeedSource(() -> 0.01);
      var completion = session.context().simulator().runAsync(-1, null);
      assertTrue(firstStep.await(2, TimeUnit.SECONDS));
      session.simulator().stopExecution(() -> "Pause");
      assertEquals(StopEvent.Reason.PAUSE, completion.get(2, TimeUnit.SECONDS).reason());
      assertEquals(1, session.context().registerFile.getValue(8));
    }
  }

  private static final class Actor
      implements SimulatorActionSource, DesktopSimulation.SimFinishCallback {
    private final String name;
    Integer result;

    Actor(String name) {
      this.name = name;
    }

    public String getActionName() {
      return name;
    }

    public void stepped(
        boolean done, int reason, edu.missouristate.mars.ProcessingException error) {
      result = reason;
    }

    public void stopped(edu.missouristate.mars.ProcessingException error, int reason) {
      result = reason;
    }

    public void paused(boolean done, int reason, edu.missouristate.mars.ProcessingException error) {
      result = reason;
    }
  }

  private static final class Console implements MarsGuiProxy {
    final StringBuilder output = new StringBuilder();

    public boolean isGuiMode() {
      return true;
    }

    public void postMessage(String text) {}

    public void postRunMessage(String text) {
      output.append(text);
    }

    public String readInput(int max) {
      return "42";
    }

    public String readInputWithPrompt(String prompt) {
      return "42";
    }

    public void clearRunConsole() {
      output.setLength(0);
    }
  }
}
