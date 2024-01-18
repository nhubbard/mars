/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package edu.missouristate.mars.venus.actions;

import edu.missouristate.mars.*;
import edu.missouristate.mars.venus.VenusUI;

import java.awt.event.*;
import javax.swing.*;
	
	/**
 * Action  for the Help -> About menu item
 */
public class HelpAboutAction extends GuiAction {
    public HelpAboutAction(String name, Icon icon, String descrip,
                           Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        JOptionPane.showMessageDialog(mainUI,
                "MARS " + Globals.version + "    Copyright " + Globals.getCopyrightYears() + "\n" +
                        Globals.getCopyrightHolders() + "\n" +
                        "MARS is the Mips Assembler and Runtime Simulator.\n\n" +
                        "Mars image courtesy of NASA/JPL.\n" +
                        "Toolbar and menu icons are from:\n" +
                        "  *  Tango Desktop Project (tango.freedesktop.org),\n" +
                        "  *  glyFX (www.glyfx.com) Common Toolbar Set,\n" +
                        "  *  KDE-Look (www.kde-look.org) crystalline-blue-0.1,\n" +
                        "  *  Icon-King (www.icon-king.com) Nuvola 1.0.\n" +
                        "Print feature adapted from HardcopyWriter class in David Flanagan's\n" +
                        "Java Examples in a Nutshell 3rd Edition, O'Reilly, ISBN 0-596-00620-9.",
                "About Mars",
                JOptionPane.INFORMATION_MESSAGE,
                new ImageIcon("images/RedMars50.gif"));
    }
}