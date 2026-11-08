package edu.missouristate.mars.plugin.venus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EditorStateTest {
  @Test
  void editorAndMenuStateBelongToEachWindow() {
    var firstMenu = new AtomicInteger();
    var secondMenu = new AtomicInteger();
    var first = new EditorState(firstMenu::set);
    var second = new EditorState(secondMenu::set);
    first.reset();
    second.reset();
    first.adopt(new FileStatus(FileStatus.EDITED, "/tmp/first.s"));
    first.set(FileStatus.RUNNABLE);
    first.setAssembled(true);
    assertEquals(FileStatus.RUNNABLE, firstMenu.get());
    assertEquals(FileStatus.NO_FILE, secondMenu.get());
    assertEquals(FileStatus.NO_FILE, second.get());
    assertFalse(second.isAssembled());
    assertNull(second.getFile());
    assertEquals("/tmp/first.s", first.getName());
    assertEquals("first.s", first.getFile().getName());
    second.reset();
    assertTrue(first.isAssembled());
    assertEquals(FileStatus.RUNNABLE, first.get());
  }
}
