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

@file:Suppress("LeakingThis", "DEPRECATION")

package edu.missouristate.mars.tools

import edu.missouristate.mars.*
import edu.missouristate.mars.mips.hardware.*
import edu.missouristate.mars.simulator.Simulator
import edu.missouristate.mars.util.FilenameFinder
import edu.missouristate.mars.venus.panes.RunSpeedPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Insets
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.io.IOException
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/**
 * An abstract class that provides generic components to facilitate implementation of
 * a MarsTool and/or stand-alone Mars-based application.
 *
 * Provides default definitions of both the action() method required to implement MarsTool and the go() method
 * conventionally used to launch a Mars-based stand-alone application.
 *
 * It also provides generic definitions for interactively controlling the application.
 *
 * The generic controls for MarsTools are three buttons: connect/disconnect to MIPS resource (memory and/or registers),
 * reset, and close (exit).
 *
 * The generic controls for stand-alone Mars apps include: button that triggers a file open dialog, a text field to
 * display status messages, the run-speed slider to control execution rate when running a MIPS program, a button that
 * assembles and runs the current MIPS program, a button to interrupt the running MIPS program, a reset button, and an
 * exit button.
 */
abstract class AbstractMarsToolAndApplication protected constructor(
    private val title: String,
    private val heading: String
) : JFrame(), MarsTool, Observer {
    // Used to determine whether a tool is being invoked from the GUI, or standalone.
    protected var isBeingUsedAsAMarsTool = false
    protected val thisMarsApp: AbstractMarsToolAndApplication = this

    // The dialog that appears when a menu item is selected.
    private lateinit var dialog: JDialog

    // The highest-level GUI component (a JFrame for the app; a JDialog for MarsTool)
    protected lateinit var theWindow: Window

    private lateinit var headingLabel: JLabel

    // Some GUI settings
    private val emptyBorder = EmptyBorder(4, 4, 4, 4)
    private val backgroundColor = Color.WHITE

    private val lowMemoryAddress = Memory.dataSegmentBaseAddress
    private val highMemoryAddress = Memory.stackBaseAddress

    // For MarsTool, is set true when "Connect" clicked, false when "Disconnect" clicked.
    // For app, is set true when "Assemble and Run" clicked, false when program terminates.
    @Volatile private var observing = false

    // Several structures required for standalone use only
    private var mostRecentlyOpenedFile: File? = null
    private val interactiveGUIUpdater: Runnable = GUIUpdater()
    private lateinit var operationStatusMessages: AbstractMarsToolAndApplication.MessageField
    private lateinit var openFileButton: JButton
    private lateinit var assembleRunButton: JButton
    private lateinit var stopButton: JButton
    private var multiFileAssemble = false

    // Structure required for MarsTool use only. We want subclasses to have access.
    protected var connectButton: AbstractMarsToolAndApplication.ConnectButton? = null

    /**
     * Returns the tool name.
     *
     * @note Renamed to getToolName from getName. Would hide the JComponent.getName method otherwise.
     * @return The tool name that MARS will display in the menu item.
     */
    abstract override val toolName: String

    /**
     * Abstract method that must be instantiated by a subclass to build the main display area of the GUI.
     * It will be placed in the CENTER area of a BorderLayout. The title is in the NORTH area, and the controls are in
     * the SOUTH area.
     */
    protected abstract fun buildMainDisplayArea(): JComponent

    /**
     * Run the simulator as a stand-alone application.
     *
     * For this default implementation, the user-defined main display of the user interface is identical for both
     * stand-alone and MARS Tools menu use, but the control buttons are different because the stand-alone must include
     * a mechanism for controlling the opening, assembling, and executing of an underlying MIPS program.
     *
     * The generic controls include: a button that triggers a file open dialog, a text field to display status messages,
     * the run-speed slider to control execution rate when running a MIPS program, a button that assembles and runs the
     * current MIPS program, a reset button, and an exit button.
     *
     * This method calls 3 methods that can be defined/overridden in the subclass: initializePreGUI() for any special
     * initialization that must be completed before building the user interface (e.g. data structures whose properties
     * determine default GUI settings), initializePostGUI() for any special initialization that cannot be completed
     * until after the building the user interface (e.g. data structure whose properties are determined by default GUI
     * settings), and buildMainDisplayArea() to contain application-specific displays of parameters and results.
     */
    open fun go() {
        theWindow = this
        isBeingUsedAsAMarsTool = false
        thisMarsApp.setTitle(title)
        Globals.initialize()
        // Make sure the dialog does away if the user clicks the close button
        thisMarsApp.addWindowClosingListener {
            performAppClosingDuties()
        }
        initializePreGUI()

        // Create the content panel
        val contentPane = JPanel(BorderLayout(5, 5))
        contentPane.border = emptyBorder
        contentPane.isOpaque = true
        contentPane.add(buildHeadingArea(), BorderLayout.NORTH)
        contentPane.add(buildMainDisplayArea(), BorderLayout.CENTER)
        contentPane.add(buildButtonAreaStandAlone(), BorderLayout.SOUTH)

        thisMarsApp.contentPane = contentPane
        thisMarsApp.pack()
        // Center this on screen
        thisMarsApp.setLocationRelativeTo(null)
        thisMarsApp.isVisible = true
        initializePostGUI()
    }

    /**
     * Required MarsTool method to carry out Tool functions.  It is invoked when MARS
     * user selects this tool from the Tools menu.  This default implementation provides
     * generic definitions for interactively controlling the tool.  The generic controls
     * for MarsTools are three buttons: connect/disconnect to MIPS resource (memory and/or
     * registers), reset, and close (exit).  Like "go()" above, this default version
     * calls 3 methods that can be defined/overridden in the subclass: initializePreGUI()
     * for any special initialization that must be completed before building the user
     * interface (e.g. data structures whose properties determine default GUI settings),
     * initializePostGUI() for any special initialization that cannot be
     * completed until after the building the user interface (e.g. data structure whose
     * properties are determined by default GUI settings), and buildMainDisplayArea()
     * to contain application-specific displays of parameters and results.
     */
    override fun action() {
        isBeingUsedAsAMarsTool = true
        dialog = JDialog(UIGlobals.gui, title)
        // Make sure the dialog goes away if the user clicks the X.
        thisMarsApp.addWindowClosingListener {
            performToolClosingDuties()
        }
        theWindow = dialog
        initializePreGUI()
        val contentPane = JPanel(BorderLayout(5, 5))
        contentPane.border = emptyBorder
        contentPane.isOpaque = true
        contentPane.add(buildHeadingArea(), BorderLayout.NORTH)
        contentPane.add(buildMainDisplayArea(), BorderLayout.CENTER)
        contentPane.add(buildButtonAreaMarsTool(), BorderLayout.SOUTH)
        initializePostGUI()
        dialog.contentPane = contentPane
        dialog.pack()
        dialog.setLocationRelativeTo(UIGlobals.gui)
        dialog.isVisible = true
    }

    /**
     * Method that will be called once just before the GUI is constructed in the go() and action()
     * methods. Use it to initialize any data structures needed for the application whose values
     * will be needed to determine the initial state of GUI components. By default, it does nothing.
     */
    open fun initializePreGUI() {}

    /**
     * Method that will be called once just after the GUI is constructed in the go() and action()
     * methods. Use it to initialize data structures needed for the application whose values
     * may depend on the initial state of GUI components. By default, it does nothing.
     */
    open fun initializePostGUI() {}

    /**
     * Method that will be called each time the default Reset button is clicked.
     * Use it to reset any data structures and/or GUI components. By default, it does nothing.
     */
    open fun reset() {}

    /**
     * Constructs GUI header as label with default positioning and font. Can be overridden.
     */
    open fun buildHeadingArea(): JComponent {
        headingLabel = JLabel()
        val headingPanel = Box.createHorizontalBox()
        headingPanel.add(Box.createHorizontalGlue())
        headingPanel.add(headingLabel)
        headingPanel.add(Box.createHorizontalGlue())
        // Details for the heading area (top)
        headingLabel.text = heading
        headingLabel.horizontalTextPosition = JLabel.CENTER
        headingLabel.font = Font(headingLabel.font.fontName, Font.PLAIN, 18)
        return headingPanel
    }

    /**
     * The MarsTool default set of controls has one row of three buttons.  It includes a dual-purpose button to
     * attach or detach simulator to MIPS memory, a button to reset the cache, and one to close the tool.
     */
    open fun buildButtonAreaMarsTool(): JComponent {
        val buttonArea = Box.createHorizontalBox()
        val tc = TitledBorder("Tool Control")
        tc.titleJustification = TitledBorder.CENTER
        buttonArea.border = tc
        connectButton = ConnectButton()
        connectButton!!.toolTipText = "Control whether the tool will respond to the running MIPS program"
        connectButton!!.addActionListener {
            if (connectButton!!.isConnected) connectButton!!.disconnect()
            else connectButton!!.connect()
        }
        connectButton!!.addKeyListener(
            EnterKeyListener(
                connectButton!!
            )
        )

        val resetButton = JButton("Reset")
        resetButton.toolTipText = "Reset all counters and other structures"
        resetButton.addActionListener { reset() }
        resetButton.addKeyListener(
            EnterKeyListener(
                resetButton
            )
        )

        val closeButton = JButton("Close")
        closeButton.toolTipText = "Close (exit) this tool"
        closeButton.addActionListener { performToolClosingDuties() }
        closeButton.addKeyListener(
            EnterKeyListener(
                closeButton
            )
        )

        // Add all the buttons
        buttonArea.add(connectButton!!)
        buttonArea.add(Box.createHorizontalGlue())
        buttonArea.add(resetButton)
        buttonArea.add(Box.createHorizontalGlue())
        getHelpComponent()?.let {
            buttonArea.add(it)
            buttonArea.add(Box.createHorizontalGlue())
        }
        buttonArea.add(closeButton)
        return buttonArea
    }

    /**
     * The Mars stand-alone app default set of controls has two rows of controls.  It includes a text field for
     * displaying status messages, a button to trigger an open file dialog, the MARS run speed slider
     * to control timed execution, a button to assemble and run the program, a reset button
     * whose action is determined by the subclass reset() method, and an exit button.
     */
    open fun buildButtonAreaStandAlone(): JComponent {
        val operationArea = Box.createVerticalBox()
        val fileControlArea = Box.createHorizontalBox()
        val buttonArea = Box.createHorizontalBox()
        operationArea.add(fileControlArea)
        operationArea.add(Box.createVerticalStrut(5))
        operationArea.add(buttonArea)
        val ac = TitledBorder("Application Control")
        ac.titleJustification = TitledBorder.CENTER
        operationArea.border = ac

        // The top row of controls consists of a button to launch file open operation,
        // a text field to show the filename, and a run speed slider.
        openFileButton = JButton("Open MIPS program...")
        openFileButton.toolTipText = "Select MIPS program file to assemble and run"
        openFileButton.addActionListener {
            val fileChooser = JFileChooser()
            val multiFileAssembleChoice = JCheckBox("Assemble all files in selected file's directory", multiFileAssemble)
            multiFileAssembleChoice.toolTipText = "If checked, the selected file will be assembled first, and all other assembly files in the same directory will also be assembled."
            fileChooser.accessory = multiFileAssembleChoice
            if (mostRecentlyOpenedFile != null) fileChooser.selectedFile = mostRecentlyOpenedFile

            // Add file filter
            val defaultFileFilter = FilenameFinder.getFileFilter(Globals.fileExtensions, "Assembly files", true)
            fileChooser.addChoosableFileFilter(defaultFileFilter)
            fileChooser.addChoosableFileFilter(fileChooser.acceptAllFileFilter)
            fileChooser.fileFilter = defaultFileFilter

            if (fileChooser.showOpenDialog(thisMarsApp) == JFileChooser.APPROVE_OPTION) {
                multiFileAssemble = multiFileAssembleChoice.isSelected
                var theFile = fileChooser.selectedFile
                try {
                    theFile = theFile.canonicalFile
                } catch (ignored: IOException) {}
                val currentFilePath = theFile.path
                mostRecentlyOpenedFile = theFile
                operationStatusMessages.text = "File: $currentFilePath"
                operationStatusMessages.caretPosition = 0
                assembleRunButton.isEnabled = true
            }
        }
        openFileButton.addKeyListener(
            EnterKeyListener(
                openFileButton
            )
        )

        operationStatusMessages = MessageField("No file open.")
        operationStatusMessages.columns = 40
        operationStatusMessages.margin = Insets(0, 3, 0, 3)
        operationStatusMessages.background = backgroundColor
        operationStatusMessages.isFocusable = false
        operationStatusMessages.toolTipText = "Display operation status messages"

        val speed = RunSpeedPanel.getInstance()

        assembleRunButton = JButton("Assemble and Run")
        assembleRunButton.toolTipText = "Assemble and run the currently selected MIPS program"
        assembleRunButton.isEnabled = false
        assembleRunButton.addActionListener {
            assembleRunButton.isEnabled = false
            openFileButton.isEnabled = false
            stopButton.isEnabled = true
            Thread(CreateAssembleRunMIPSProgram()).start()
        }

        stopButton = JButton("Stop")
        stopButton.toolTipText = "Terminate MIPS program execution"
        stopButton.isEnabled = false
        stopButton.addActionListener { Simulator.getInstance().stopExecution(null) }
        stopButton.addKeyListener(
            EnterKeyListener(
                stopButton
            )
        )

        val resetButton = JButton("Reset")
        resetButton.toolTipText = "Reset all counters and other structures"
        resetButton.addActionListener { reset() }
        resetButton.addKeyListener(
            EnterKeyListener(
                resetButton
            )
        )

        val closeButton = JButton("Exit")
        closeButton.toolTipText = "Exit this application"
        closeButton.addActionListener { performAppClosingDuties() }
        closeButton.addKeyListener(
            EnterKeyListener(
                closeButton
            )
        )

        val fileDisplayBox = Box.createVerticalBox()
        fileDisplayBox.add(Box.createVerticalStrut(8))
        fileDisplayBox.add(operationStatusMessages)
        fileDisplayBox.add(Box.createVerticalStrut(8))
        fileControlArea.add(fileDisplayBox)

        fileControlArea.add(Box.createHorizontalGlue())
        fileControlArea.add(speed)

        buttonArea.add(openFileButton)
        buttonArea.add(Box.createHorizontalGlue())
        buttonArea.add(assembleRunButton)
        buttonArea.add(Box.createHorizontalGlue())
        buttonArea.add(stopButton)
        buttonArea.add(Box.createHorizontalGlue())
        buttonArea.add(resetButton)
        buttonArea.add(Box.createHorizontalGlue())
        getHelpComponent()?.let {
            buttonArea.add(it)
            buttonArea.add(Box.createHorizontalGlue())
        }
        buttonArea.add(closeButton)
        return operationArea
    }

    /**
     * Called when receiving notice of access to MIPS memory or registers.  Default
     * implementation of method required by Observer interface.  This method will filter out
     * notices originating from the MARS GUI or from direct user editing of memory or register
     * displays.  Only notices arising from MIPS program access are allowed in.
     * It then calls two methods to be overridden by the subclass (since they do
     * nothing by default): processMIPSUpdate() then updateDisplay().
     *
     * @param resource     the attached MIPS resource
     * @param accessNotice AccessNotice information provided by the resource
     */
    override fun update(resource: Observable, accessNotice: Any) {
        if (accessNotice !is AccessNotice) return
        if (accessNotice.accessIsFromMIPS) {
            processMipsUpdate(resource, accessNotice)
            updateDisplay()
        }
    }

    /**
     * Override this method to process a received notice from MIPS Observable (memory or register).
     * It will only be called if the notice was generated as the result of MIPS instruction execution.
     * By default, it does nothing.
     * After this method is complete, the updateDisplay() method will be invoked automatically.
     */
    open fun processMipsUpdate(resource: Observable, notice: AccessNotice) {}

    /**
     * This method is called when tool/app is exited either through the close/exit button or the window's X box.
     * Override it to perform any special housecleaning needed.
     * By default, it does nothing.
     */
    open fun performSpecialClosingDuties() {}

    /**
     * Add this app/tool as an Observer of desired MIPS Observables (memory and registers).
     * By default, will add as an Observer of the entire Data Segment in memory.
     * Override if you want something different.  Note that the Memory methods to add an
     * Observer to memory are flexible (you can register for a range of addresses) but
     * may throw an AddressErrorException that you need to catch.
     * This method is called whenever the default "Connect" button on a MarsTool or the
     * default "Assemble and run" on a stand-alone Mars app is selected.  The corresponding
     * NOTE: if you do not want to register as an Observer of the entire data segment
     * (starts at address 0x10000000) then override this to either do some alternative
     * or nothing at all.  This method is also overloaded to allow arbitrary memory
     * subrange.
     */
    open fun addAsObserver() = addAsObserver(lowMemoryAddress, highMemoryAddress)

    /**
     * Add this app/tool as an Observer of the specified MIPS memory subrange.
     * Note that this method is not invoked automatically like the no-argument version, but
     * if you use this method, you can still take advantage of the provided default deleteAsObserver()
     * since it will remove the app as a memory observer regardless of the sub-range
     * or number of sub-ranges it is registered for.
     *
     * @param lowEnd The low end of the memory address range.
     * @param highEnd The high end of the memory address range must be >= lowEnd.
     */
    protected fun addAsObserver(lowEnd: Int, highEnd: Int) {
        val errorMessage = "Error connecting to MARS memory"
        try {
            Globals.memory.addObserver(thisMarsApp, lowEnd, highEnd)
        } catch (aee: AddressErrorException) {
            if (isBeingUsedAsAMarsTool) {
                headingLabel.text = errorMessage
            } else {
                operationStatusMessages.displayMessage(errorMessage, true)
            }
        }
    }

    /**
     * Add this app/tool as an Observer of the specified MIPS register.
     */
    protected fun addAsObserver(reg: Register?) {
        reg?.addObserver(thisMarsApp)
    }

    /**
     * Delete this app/tool as an Observer of MIPS Observables (memory and registers).
     * By default, will delete as an Observer of memory.
     * Override if you want something different.
     * This method is called when the default "Disconnect" button on a MarsTool is selected or
     * when the MIPS program execution triggered by the default "Assemble and run" on a stand-alone
     * Mars app terminates (e.g., when the button is re-enabled).
     */
    protected open fun deleteAsObserver() {
        Globals.memory.deleteObserver(thisMarsApp)
    }

    /**
     * Delete this app/tool as an Observer of the specified MIPS register
     */
    protected fun deleteAsObserver(reg: Register?) {
        reg?.deleteObserver(thisMarsApp)
    }

    /**
     * Query method to let you know if the tool/app is (or could be) currently
     * "observing" any MIPS resources.  When running as a MarsTool, this
     * will be true by default after clicking the "Connect to MIPS" button until "Disconnect
     * from MIPS" is clicked.  When running as a stand-alone app, this will be
     * true by default after clicking the "Assemble and Run" button until
     * program execution has terminated either normally or by clicking the "Stop"
     * button.  The phrase "or could be" was added above because depending on how
     * the tool/app operates, it may be possible to run the MIPS program without
     * first registering as an Observer -- i.e., addAsObserver() is overridden and
     * takes no action.
     *
     * @return true if tool/app is (or could be) currently active as an Observer.
     */
    protected val isObserving: Boolean get() = observing

    /**
     * Override this method to implement updating of GUI after each MIPS instruction is executed,
     * while running in "timed" mode (user specifies execution speed on the slider control).
     * Does nothing by default.
     */
    open fun updateDisplay() {}

    /**
     * Override this method to provide a JComponent (probably a JButton) of your choice
     * to be placed just left of the Close/Exit button.  Its anticipated use is for a
     * "help" button that launches a help message or dialog.  But it can be any valid
     * JComponent that doesn't mind co-existing among a bunch of JButtons.
     */
    open fun getHelpComponent(): JComponent? = null

    private fun performToolClosingDuties() {
        performSpecialClosingDuties()
        if (connectButton?.isConnected == true) connectButton?.disconnect()
        dialog.isVisible = false
        dialog.dispose()
    }

    private fun performAppClosingDuties() {
        performSpecialClosingDuties()
        thisMarsApp.isVisible = false
        exitProcess(0)
    }

    /**
     * Little class for this dual-purpose button. It is only used by tools when they are run from the GUI, not in
     * standalone mode.
     */
    inner class ConnectButton: JButton() {
        private val connectText = "Connect to MARS"
        private val disconnectText = "Disconnect from MARS"

        init {
            disconnect()
        }

        fun connect() {
            observing = true
            Globals.memoryAndRegistersLock.withLock {
                addAsObserver()
            }
            text = disconnectText
        }

        fun disconnect() {
            Globals.memoryAndRegistersLock.withLock {
                deleteAsObserver()
            }
            observing = false
            text = connectText
        }

        val isConnected: Boolean get() = observing
    }

    /**
     * Every control button will get an instance of this listener.
     * This listener allows the user to press the Enter key to interact with a button when it's focused.
     * It will do nothing if no action listeners are attached to the button at the time of the call. Otherwise,
     * it will call actionPerformed for the first action listener in the button's list.
     */
    private class EnterKeyListener(val who: AbstractButton) : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (e.keyChar.digitToInt() == KeyEvent.VK_ENTER) {
                e.consume()
                who.actionListeners.firstOrNull()?.actionPerformed(ActionEvent(who, 0, who.text))
            }
        }
    }

    /**
     * Called when "Assemble and Run" is pressed. Used only in standalone app mode.
     */
    private inner class CreateAssembleRunMIPSProgram : Runnable {
        override fun run() {
            var exceptionHandler: String? = null
            if (Globals.config[CoreSpec.enableExceptionHandler] &&
                Globals.config[CoreSpec.exceptionHandlerFile].isNotEmpty()) {
                exceptionHandler = Globals.config[CoreSpec.exceptionHandlerFile]
            }

            Thread.currentThread().priority = Thread.NORM_PRIORITY - 1
            Thread.yield()
            val program = MIPSProgram()
            Globals.program = program
            val fileToAssemble = mostRecentlyOpenedFile?.path ?: run {
                operationStatusMessages.displayMessage("Most recently opened file is null!", true)
                return
            }
            val filesToAssemble = if (multiFileAssemble) {
                FilenameFinder.getFilenameList(File(fileToAssemble).parent, Globals.fileExtensions)
            } else {
                arrayListOf(fileToAssemble)
            }
            val programsToAssemble = try {
                operationStatusMessages.displayMessage("Assembling $fileToAssemble")
                program.prepareFilesForAssembly(filesToAssemble, fileToAssemble, exceptionHandler)
            } catch (pe: ProcessingException) {
                operationStatusMessages.displayMessage("Error reading file(s): $fileToAssemble", true)
                return
            }

            try {
                program.assemble(
                    programsToAssemble,
                    Globals.config[CoreSpec.enableExtendedAssembler],
                    Globals.config[CoreSpec.upgradeWarningsToErrors]
                )
            } catch (pe: ProcessingException) {
                operationStatusMessages.displayMessage("Assembly error: $fileToAssemble", true)
                return
            }

            // Reset registers for simulation
            RegisterFile.resetRegisters()
            Coprocessor1.resetRegisters()
            Coprocessor0.resetRegisters()

            addAsObserver()
            observing = true
            var terminatingMessage = "Normal termination: "
            try {
                operationStatusMessages.displayMessage("Running $fileToAssemble")
                program.simulate(-1)
            } catch (npe: NullPointerException) {
                // Occurs if the stop button interrupts program execution.
                terminatingMessage = "User interrupt: "
            } catch (pe: ProcessingException) {
                terminatingMessage = "Runtime error: "
            } finally {
                deleteAsObserver()
                observing = false
                operationStatusMessages.displayMessage("$terminatingMessage$fileToAssemble", true)
            }
        }
    }

    /**
     * Class for text message field used to update operation status when assembling and running MIPS programs.
     */
    private inner class MessageField(text: String): JTextField(text) {
        @JvmOverloads
        fun displayMessage(text: String?, terminating: Boolean = false) {
            SwingUtilities.invokeLater(MessageWriter(text, terminating))
        }

        private inner class MessageWriter(
            private val text: String?,
            private val terminatingMessage: Boolean
        ) : Runnable {
            override fun run() {
                if (text != null) {
                    operationStatusMessages.text = text
                    operationStatusMessages.caretPosition = 0
                }
                if (terminatingMessage) {
                    assembleRunButton.isEnabled = true
                    openFileButton.isEnabled = true
                    stopButton.isEnabled = false
                }
            }
        }
    }

    /**
     * Schedule GUI update on timed runs. Only used in standalone app mode.
     */
    private inner class GUIUpdater : Runnable {
        override fun run() { updateDisplay() }
    }
}