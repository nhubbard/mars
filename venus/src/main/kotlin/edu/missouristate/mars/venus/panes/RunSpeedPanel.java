/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.venus.panes;

import edu.missouristate.mars.*;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;

import static edu.missouristate.mars.simulator.RunSpeedManager.*;

/**
 * Class for the Run speed slider control.  One is created and can be obtained using
 * getInstance().
 *
 * @author Pete Sanderson
 * @version August 2005
 */

public class RunSpeedPanel extends JPanel {
    private JLabel sliderLabel = null;
    private static RunSpeedPanel runSpeedPanel = null;
    private volatile int runSpeedIndex = SPEED_INDEX_MAX;

    /**
     * Retrieve the run speed panel object
     *
     * @return the run speed panel
     */

    public static RunSpeedPanel getInstance() {
        if (runSpeedPanel == null) {
            runSpeedPanel = new RunSpeedPanel();
            Globals.setRunSpeedPanelExists(true); // DPS 24 July 2008 (needed for standalone tools)
        }
        return runSpeedPanel;
    }

    /*
     * private constructor (this is a singleton class)
     */
    private RunSpeedPanel() {
        super(new BorderLayout());
        JSlider runSpeedSlider = new JSlider(JSlider.HORIZONTAL, SPEED_INDEX_MIN, SPEED_INDEX_MAX, SPEED_INDEX_INIT);
        runSpeedSlider.setSize(new Dimension(100, (int) runSpeedSlider.getSize().getHeight()));
        runSpeedSlider.setMaximumSize(runSpeedSlider.getSize());
        runSpeedSlider.setMajorTickSpacing(5);
        runSpeedSlider.setPaintTicks(true); //Create the label table
        runSpeedSlider.addChangeListener(new RunSpeedListener());
        sliderLabel = new JLabel(setLabel(runSpeedIndex));
        sliderLabel.setHorizontalAlignment(JLabel.CENTER);
        sliderLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.add(sliderLabel, BorderLayout.NORTH);
        this.add(runSpeedSlider, BorderLayout.CENTER);
        this.setToolTipText("Simulation speed for \"Go\".  At " +
                ((int) speedTable[SPEED_INDEX_INTERACTION_LIMIT]) + " inst/sec or less, tables updated " +
                "after each instruction.");
    }

    /*
     * set label wording depending on current speed setting
     */
    private String setLabel(int index) {
        String result = "Run speed ";
        if (index <= SPEED_INDEX_INTERACTION_LIMIT) {
            if (speedTable[index] < 1) {
                result += speedTable[index];
            } else {
                result += ((int) speedTable[index]);
            }
            result += " inst/sec";
        } else {
            result += ("at max (no interaction)");
        }
        return result;
    }


    /*
     *  Both revises label as user slides and updates current index when sliding stops.
     */
    private class RunSpeedListener implements ChangeListener {
        public void stateChanged(ChangeEvent e) {
            JSlider source = (JSlider) e.getSource();
            if (!source.getValueIsAdjusting()) {
                runSpeedIndex = source.getValue();
            } else {
                sliderLabel.setText(setLabel(source.getValue()));
            }
        }
    }
}