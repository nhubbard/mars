package edu.missouristate.mars.plugin.venus.editors.jeditsyntax;

import static org.junit.jupiter.api.Assertions.*;

import edu.missouristate.mars.plugin.venus.editors.jeditsyntax.tokenmarker.Token;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;
import org.junit.jupiter.api.Test;

class EditorRenderingTest {
  @Test
  void styledMetricsFollowTheComponentFont() {
    var component = new JPanel();
    var base = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    var style = new SyntaxStyle(Color.BLUE, true, true);
    var styled = style.getStyledFont(base);
    assertEquals(Font.BOLD | Font.ITALIC, styled.getStyle());
    assertEquals(
        component.getFontMetrics(styled).stringWidth("label"),
        style.getFontMetrics(base, component).stringWidth("label"));
    var larger = base.deriveFont(22f);
    assertEquals(
        component.getFontMetrics(style.getStyledFont(larger)).getHeight(),
        style.getFontMetrics(larger, component).getHeight());
  }

  @Test
  void drawingAndMeasurementAgreeAcrossTabs() {
    var image = new BufferedImage(400, 60, BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    try {
      graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
      graphics.setColor(Color.BLACK);
      var chars = "ab\tcd".toCharArray();
      var segment = new Segment(chars, 0, chars.length);
      var token = new Token(chars.length, Token.NULL);
      token.next = new Token(0, Token.END);
      TabExpander tabs = (x, offset) -> ((int) x / 64 + 1) * 64;
      int measured =
          Math.round(Utilities.getTabbedTextWidth(segment, graphics.getFontMetrics(), 0f, tabs, 0));
      int painted =
          SyntaxUtilities.paintSyntaxLine(
              segment, token, new SyntaxStyle[0], tabs, graphics, 0, 24);
      assertEquals(measured, painted);
      assertTrue(painted > 64);
    } finally {
      graphics.dispose();
    }
  }

  @Test
  void caretCoordinatesRoundTripWithTabs() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var styles = SyntaxUtilities.getDefaultSyntaxStyles();
          var colors = new String[styles.length];
          var bold = new boolean[styles.length];
          var italic = new boolean[styles.length];
          for (int i = 0; i < styles.length; i++) {
            colors[i] = styles[i].getColorAsHexString();
            bold[i] = styles[i].isBold();
            italic[i] = styles[i].isItalic();
          }
          edu.missouristate.mars.legacy.LegacyApplication.services()
              .getSettings()
              .initializeSyntaxStyleDefaults(colors, bold, italic);
          var area = new JEditTextArea(new JPanel());
          area.getPainter().setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
          area.setText("ab\tcd");
          for (int offset = 0; offset <= 5; offset++) {
            assertEquals(offset, area.xToOffset(0, area._offsetToX(0, offset)));
          }
        });
  }
}
