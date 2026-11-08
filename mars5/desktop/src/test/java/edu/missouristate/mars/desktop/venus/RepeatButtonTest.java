package edu.missouristate.mars.desktop.venus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import org.junit.jupiter.api.Test;

class RepeatButtonTest {
  @Test
  void repeatedActionsPreserveKeyboardModifiersWithoutMouseButtonBits() {
    int keyboard =
        InputEvent.SHIFT_DOWN_MASK
            | InputEvent.CTRL_DOWN_MASK
            | InputEvent.META_DOWN_MASK
            | InputEvent.ALT_DOWN_MASK;
    var press =
        new MouseEvent(
            new JButton(),
            MouseEvent.MOUSE_PRESSED,
            1,
            keyboard | InputEvent.BUTTON1_DOWN_MASK,
            0,
            0,
            1,
            false,
            MouseEvent.BUTTON1);
    assertEquals(
        ActionEvent.SHIFT_MASK
            | ActionEvent.CTRL_MASK
            | ActionEvent.META_MASK
            | ActionEvent.ALT_MASK,
        RepeatButton.actionModifiers(press));
    var plain =
        new MouseEvent(
            new JButton(),
            MouseEvent.MOUSE_PRESSED,
            1,
            InputEvent.BUTTON1_DOWN_MASK,
            0,
            0,
            1,
            false,
            MouseEvent.BUTTON1);
    assertEquals(0, RepeatButton.actionModifiers(plain));
  }
}
