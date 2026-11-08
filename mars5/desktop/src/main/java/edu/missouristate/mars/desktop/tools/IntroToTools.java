package edu.missouristate.mars.desktop.tools;

import java.awt.*;
import javax.swing.*;

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** The "hello world" of MarsTools! */
public class IntroToTools extends AbstractMarsToolAndApplication {

  private static final String heading = "Introduction to MARS Tools and Applications";
  private static final String version = " Version 1.0";

  /**
   * Simple constructor, likely used to run a stand-alone memory reference visualizer.
   *
   * @param title String containing title for title bar
   * @param heading String containing text for heading shown in upper part of window.
   */
  public IntroToTools(
      edu.missouristate.mars.desktop.runtime.DesktopSession session, String title, String heading) {
    super(session, title, heading);
  }

  /** Simple constructor, likely used by the MARS Tools menu mechanism */
  public IntroToTools(edu.missouristate.mars.desktop.runtime.DesktopSession session) {
    super(session, heading + ", " + version, heading);
  }

  /**
   * Main provided for pure stand-alone use. Recommended stand-alone use is to write a driver
   * program that instantiates a MemoryReferenceVisualization object then invokes its go() method.
   * "stand-alone" means it is not invoked from the MARS Tools menu. "Pure" means there is no driver
   * program to invoke the application.
   */
  public static void main(String[] args) {
    var session = edu.missouristate.mars.desktop.runtime.DesktopApplication.createSession();
    new IntroToTools(session, heading + ", " + version, heading).go();
  }

  /**
   * Required method to return Tool name.
   *
   * @return Tool name. MARS will display this in menu item.
   */
  public String getName() {
    return "Introduction to Tools";
  }

  /**
   * Implementation of the inherited abstract method to build the main display area of the GUI. It
   * will be placed in the CENTER area of a BorderLayout. The title is in the NORTH area, and the
   * controls are in the SOUTH area.
   */
  protected JComponent buildMainDisplayArea() {
    JTextArea message = new JTextArea();
    message.setEditable(false);
    message.setLineWrap(true);
    message.setWrapStyleWord(true);
    message.setFont(new Font("Ariel", Font.PLAIN, 12));
    message.setText(
        """
        This example provides a starting point for a MARS desktop tool or standalone application.

        Desktop tools appear in the Tools menu and interact with the session owned by that window.
        Implement MarsTool, provide getName() and action(), and accept a DesktopSession in your
        tool's constructor. Reuse that session for memory, registers, settings, and simulation.

        For the standard display and connection controls, extend
        edu.missouristate.mars.desktop.tools.AbstractMarsToolAndApplication.
        Pass the supplied session, title, and heading to its constructor, and implement getName()
        and buildMainDisplayArea(). Typed memory and register callbacks update your model;
        processMIPSUpdate(AccessNotice) receives accesses from instruction execution.

        To add a tool to the menu, implement MarsToolProvider. Its name() supplies the menu label;
        create(DesktopSession) constructs the tool only when selected. Register the provider's
        class name in META-INF/services/edu.missouristate.mars.desktop.tools.MarsToolProvider
        in your extension JAR. The extension JAR must be on the desktop application's classpath.
        Listing tools does not create their windows or simulation state.

        A standalone application's main method creates its own session with
        DesktopApplication.createSession(), constructs the tool with that session, and calls go().
        Close an independently owned session when its application is finished. A tool opened from
        a desktop menu shares the window's session and leaves its lifetime to that window.

        Use session.instructionContext() for writes that need undo recording, and use the owning
        session's hardware for reads and typed event subscriptions. Memory notices include an
        explicit origin so execution can be distinguished from editor and application accesses.

        The IntelliJ plugin has its own project-scoped integration in the plugin module. These
        desktop extension points continue to support the standalone application.
        """);
    message.setCaretPosition(0); // Assure first line is visible and at top of scroll pane.
    return new JScrollPane(message);
  }
}
