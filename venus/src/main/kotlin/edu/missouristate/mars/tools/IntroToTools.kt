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

package edu.missouristate.mars.tools

import java.awt.Font
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

class IntroToTools(
    title: String = "$NAME, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val NAME = "Intro to Tools"
        private const val VERSION = "Version 1.0"
        private const val HEADING = "Introduction to MARS Tools"

        @JvmStatic
        fun main(args: Array<String>) = IntroToTools().go()
    }

    override val toolName: String = "Introduction to Tools"

    override fun buildMainDisplayArea(): JComponent = JScrollPane(JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font("Ariel", Font.PLAIN, 12)
        text = """
        Hello! This tool does not do anything, but you can use it's source code as a starting point to build your own
        MARS tool or application.
        
        A MARS tool is a program that's listed in the MARS tools menu. It is launched when you select its menu item, and
        typically interacts with running MIPS program to do something interesting, exciting, or informative.
        
        A MARS application is a standalone program for similarly interacting with executing MIPS programs. It uses the
        MARS assembler and simulator in the background to control execution of MIPS code.
        
        The basic requirements for building a MARS tool are:
          1. It must be a class file that implements the Kotlin MarsTool interface.
          2. It must be stored in the edu/missouristate/mars/tools folder.
          3. It must be successfully compiled in that package. Normally, this means the MARS program must be extracted
             from the JAR file before you can develop your tool.
              
        If these requirements are met, MARS will recognize and load your tool into the Tools menu the next time it runs.
        
        You can build a program that may be run as either a MARS tool or an application. The easiest way is to extend
        the abstract class edu.missouristate.mars.tools.AbstractMarsToolAndApplication. This abstract class does the 
        following tasks for you:
          1. It defines a suite of methods with default definitions for all but two items: the toolName variable, and
             the buildMainDisplayArea() method.
          2. buildMainDisplayArea() is an abstract function that returns a JComponent to be placed in the
             BorderLayout.CENTER region of the tool/app's user interface. The NORTH and SOUTH are defined to contain a
             header and a set of button controls, respectively.
          3. If defines a default static method `go()` that you can execute in a companion function to launch the app in
             standalone mode.
          4. Conventional usage is to define your application as a subclass, and launch it by invoking it's go method.
          
        The frame/dialog you are reading this text from right now is an example of an AbstractMarsToolAndApplication
        subclass. If you run it as an application, you will notice the set of controls at the bottom of the window
        will change as compared to running it from the Tools menu in MARS. It includes additional controls to load and
        control the execution of a pre-existing MIPS program.
        
        See the edu.missouristate.mars.tools.AbstractMarsToolAndApplication API or the source code of the existing tools
        and apps for more information.
        
        Have fun!
        """.trimIndent()
        caretPosition = 0
    })
}