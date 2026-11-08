package edu.missouristate.mars.plugin.venus;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.plugin.runtime.DesktopApplication;
import java.awt.event.*;
import javax.swing.*;

/*
Copyright (c) 2003-2009,  Pete Sanderson and Kenneth Vollmar

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

/** Action for the Run -&gt; Backstep menu item */
public class RunBackstepAction extends GuiAction {

  String name;
  ExecutePane executePane;

  public RunBackstepAction(
      String name, Icon icon, String descrip, Integer mnemonic, KeyStroke accel, VenusUI gui) {
    super(name, icon, descrip, mnemonic, accel, gui);
  }

  /** perform next simulated instruction step. */
  public void actionPerformed(ActionEvent e) {
    name = this.getValue(Action.NAME).toString();
    executePane = mainUI.getMainPane().getExecutePane();
    boolean done = false;
    if (!FileStatus.isAssembled()) {
      // note: this should never occur since backstepping is only enabled after successful assembly.
      JOptionPane.showMessageDialog(mainUI, "The program must be assembled before it can be run.");
      return;
    }
    VenusUI.setStarted(true);
    mainUI.messagesPane.setSelectedComponent(mainUI.messagesPane.runTab);
    executePane.getTextSegmentWindow().setCodeHighlighting(true);

    if (DesktopApplication.services().getSettings().getBackSteppingEnabled()) {
      boolean inDelaySlot =
          DesktopApplication.services()
              .getProgram()
              .getBackStepper()
              .inDelaySlot(); // Added 25 June 2007
      DesktopApplication.services()
          .memory
          .addObserver(executePane.getDataSegmentWindow().getMemoryListener());
      DesktopApplication.services()
          .context()
          .registerFile
          .addRegistersObserver(executePane.getRegistersWindow().getRegistersListener());
      DesktopApplication.services()
          .context()
          .coprocessor0
          .addRegistersObserver(executePane.getCoprocessor0Window().getRegistersListener());
      DesktopApplication.services()
          .context()
          .coprocessor1
          .addRegistersObserver(executePane.getCoprocessor1Window().getRegistersListener());
      DesktopApplication.services().getProgram().getBackStepper().backStep();
      DesktopApplication.services()
          .memory
          .deleteObserver(executePane.getDataSegmentWindow().getMemoryListener());
      DesktopApplication.services()
          .context()
          .registerFile
          .deleteRegistersObserver(executePane.getRegistersWindow().getRegistersListener());
      executePane.getRegistersWindow().updateRegisters();
      executePane.getCoprocessor1Window().updateRegisters();
      executePane.getCoprocessor0Window().updateRegisters();
      executePane.getDataSegmentWindow().updateValues();
      executePane
          .getTextSegmentWindow()
          .highlightStepAtPC(inDelaySlot); // Argument aded 25 June 2007
      FileStatus.set(FileStatus.RUNNABLE);
      VenusUI.setReset(false);
    }
  }
}
