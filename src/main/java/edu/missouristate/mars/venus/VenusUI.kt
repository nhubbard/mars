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

@file:Suppress("DuplicatedCode", "MemberVisibilityCanBePrivate")

package edu.missouristate.mars.venus

import edu.missouristate.mars.Globals
import edu.missouristate.mars.Globals.settings
import edu.missouristate.mars.Settings
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.venus.actions.*
import edu.missouristate.mars.venus.panes.MainPane
import edu.missouristate.mars.venus.panes.MessagesPane
import edu.missouristate.mars.venus.panes.RegistersPane
import edu.missouristate.mars.venus.panes.RunSpeedPanel
import edu.missouristate.mars.venus.windows.Coprocessor0Window
import edu.missouristate.mars.venus.windows.Coprocessor1Window
import edu.missouristate.mars.venus.windows.RegistersWindow
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import kotlin.math.roundToInt
import kotlin.system.exitProcess

class VenusUI(windowName: String) : JFrame(windowName) {
    companion object {
        @JvmStatic var menuState = FileStatus.NO_FILE
            private set
        @JvmStatic var reset = true
        @JvmStatic var started = false
    }

    var mainUI: VenusUI private set
    var menu: JMenuBar private set
    var toolbar: JToolBar private set
    var mainPane: MainPane private set
    var registersPane: RegistersPane private set
    var registersTab: RegistersWindow private set
    var coprocessor1Tab: Coprocessor1Window private set
    var coprocessor0Tab: Coprocessor0Window private set
    var messagesPane: MessagesPane private set
    var splitter: JSplitPane private set
    var horizonSplitter: JSplitPane private set
    lateinit var north: JPanel private set
    var editor: Editor private set

    private var frameState: Int = -1
    private lateinit var window: JMenu
    private lateinit var valueDisplayBaseMenuItem: JCheckBoxMenuItem
    private lateinit var addressDisplayBaseMenuItem: JCheckBoxMenuItem
    private lateinit var saveAll: JButton

    // The "action" objects, which include action listeners. One of each will be created then
    // shared between a menu item and its corresponding toolbar button. This is a very cool
    // technique because it relates the button and menu item so closely.
    private lateinit var fileNewAction: Action
    private lateinit var fileOpenAction: Action
    private lateinit var fileCloseAction: Action
    private lateinit var fileCloseAllAction: Action
    private lateinit var fileSaveAction: Action
    private lateinit var fileSaveAsAction: Action
    private lateinit var fileSaveAllAction: Action
    private lateinit var fileDumpMemoryAction: Action
    private lateinit var filePrintAction: Action
    private lateinit var fileExitAction: Action
    lateinit var editUndoAction: EditUndoAction private set
    lateinit var editRedoAction: EditRedoAction private set
    private lateinit var editCutAction: Action
    private lateinit var editCopyAction: Action
    private lateinit var editPasteAction: Action
    private lateinit var editFindReplaceAction: Action
    private lateinit var editSelectAllAction: Action
    lateinit var runAssembleAction: Action private set
    private lateinit var runGoAction: Action
    private lateinit var runStepAction: Action
    private lateinit var runBackstepAction: Action
    private lateinit var runResetAction: Action
    private lateinit var runStopAction: Action
    private lateinit var runPauseAction: Action
    private lateinit var runClearBreakpointsAction: Action
    private lateinit var runToggleBreakpointsAction: Action
    private lateinit var settingsLabelAction: Action
    private lateinit var settingsPopupInputAction: Action
    private lateinit var settingsValueDisplayBaseAction: Action
    private lateinit var settingsAddressDisplayBaseAction: Action
    private lateinit var settingsExtendedAction: Action
    private lateinit var settingsAssembleOnOpenAction: Action
    private lateinit var settingsAssembleAllAction: Action
    private lateinit var settingsWarningsAreErrorsAction: Action
    private lateinit var settingsStartAtMainAction: Action
    private lateinit var settingsProgramArgumentsAction: Action
    private lateinit var settingsDelayedBranchingAction: Action
    private lateinit var settingsExceptionHandlerAction: Action
    private lateinit var settingsEditorAction: Action
    private lateinit var settingsHighlightingAction: Action
    private lateinit var settingsMemoryConfigurationAction: Action
    private lateinit var settingsSelfModifyingCodeAction: Action
    private lateinit var helpHelpAction: Action
    private lateinit var helpAboutAction: Action

    init {
        mainUI = this
        Globals.gui = this
        editor = Editor(this)
        // Adjust for screen size if resolution is low (800x600)
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val (screenWidth, screenHeight) = screenSize.width to screenSize.height
        val messageWidthScale = if (screenWidth < 1000.0) 0.67 else 0.73
        val messageHeightScale = if (screenWidth < 1000.0) 0.12 else 0.15
        val mainWidthScale = if (screenWidth < 1000.0) 0.67 else 0.73
        val mainHeightScale = if (screenWidth < 1000.0) 0.60 else 0.65
        val registersWidthScale = if (screenWidth < 1000.0) 0.18 else 0.22
        val registersHeightScale = if (screenWidth < 1000.0) 0.72 else 0.80
        val messagesPanePreferredSize = Dimension(
            (screenWidth * messageWidthScale).roundToInt(),
            (screenHeight * messageHeightScale).roundToInt()
        )
        val mainPanePreferredSize = Dimension(
            (screenWidth * mainWidthScale).roundToInt(),
            (screenHeight * mainHeightScale).roundToInt()
        )
        val registersPanePreferredSize = Dimension(
            (screenWidth * registersWidthScale).roundToInt(),
            (screenHeight * registersHeightScale).roundToInt()
        )

        Globals.initialize()

        val im = javaClass.getResource("${Globals.imagesPath}RedMars16.gif") ?: run {
            println("Internal Error: cannot find /images/RedMars16.gif (images folder or file not found)")
            exitProcess(1)
        }
        val mars = Toolkit.getDefaultToolkit().getImage(im)
        iconImage = mars

        this.iconImage = mars

        // Everything in frame will be arranged on JPanel "center", which is only a frame component.
        // "center" has BorderLayout and 2 major components:
        //   -- panel (jp) on North with 2 components
        //      1. toolbar
        //      2. run speed slider.
        //   -- split pane (horizonSplitter) in the center with 2 components side-by-side
        //      1. split pane (splitter) with 2 components stacked
        //         a. main pane, with 2 tabs (edit, execute)
        //         b. messages pane with 2 tabs (MARS, run I/O)
        //      2. registers pane with 3 tabs (register file, coproc 0, coproc 1)
        // I should probably run this breakdown out to full detail.  The components are created
        // roughly in bottom-up order; some are created in component constructors and thus are
        // not visible here.
        registersTab = RegistersWindow()
        coprocessor1Tab = Coprocessor1Window()
        coprocessor0Tab = Coprocessor0Window()
        registersPane = RegistersPane(mainUI, registersTab, coprocessor1Tab, coprocessor0Tab)
        registersPane.preferredSize = registersPanePreferredSize

        mainPane = MainPane(mainUI, editor, registersTab, coprocessor1Tab, coprocessor0Tab)
        mainPane.preferredSize = mainPanePreferredSize

        messagesPane = MessagesPane()
        messagesPane.preferredSize = messagesPanePreferredSize

        splitter = JSplitPane(JSplitPane.VERTICAL_SPLIT, mainPane, messagesPane)
        splitter.isOneTouchExpandable = true
        splitter.resetToPreferredSizes()

        horizonSplitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, splitter, registersPane)
        horizonSplitter.isOneTouchExpandable = true
        horizonSplitter.resetToPreferredSizes()

        createActionObjects()
        menu = setUpMenuBar()
        jMenuBar = menu
        toolbar = setUpToolbar()

        val jp = JPanel(FlowLayout(FlowLayout.LEFT))
        jp.add(toolbar)
        jp.add(RunSpeedPanel.getInstance())

        val center = JPanel(BorderLayout())
        center.add(jp, BorderLayout.NORTH)
        center.add(horizonSplitter)

        contentPane.add(center)

        FileStatus.reset()
        FileStatus.set(FileStatus.NO_FILE)

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                mainUI.extendedState = MAXIMIZED_BOTH
            }
        })
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (mainUI.editor.closeAll()) exitProcess(0)
            }
        })

        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        pack()
        isVisible = true
    }

    /**
     * Action objects are used instead of action listeners because one can be easily shared between
     * a menu item and a toolbar button.  Does nice things like disable both if the action is
     * disabled, etc.
     */
    private fun createActionObjects() {
        val tk = Toolkit.getDefaultToolkit()
        val cs = javaClass
        try {
            fileNewAction = FileNewAction(
                "New",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}New22.png"))),
                "Create a new file for editing",
                KeyEvent.VK_N,
                KeyStroke.getKeyStroke(KeyEvent.VK_N, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            fileOpenAction = FileOpenAction(
                "Open ...",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Open22.png"))),
                "Open a file for editing", KeyEvent.VK_O,
                KeyStroke.getKeyStroke(KeyEvent.VK_O, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            fileCloseAction = FileCloseAction(
                "Close",
                null,
                "Close the current file",
                KeyEvent.VK_C,
                KeyStroke.getKeyStroke(KeyEvent.VK_W, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            fileCloseAllAction = FileCloseAllAction(
                "Close All",
                null,
                "Close all open files",
                KeyEvent.VK_L,
                null,
                mainUI
            )
            fileSaveAction = FileSaveAction(
                "Save",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Save22.png"))),
                "Save the current file",
                KeyEvent.VK_S,
                KeyStroke.getKeyStroke(KeyEvent.VK_S, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            fileSaveAsAction = FileSaveAsAction(
                "Save as ...",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}SaveAs22.png"))),
                "Save current file with different name",
                KeyEvent.VK_A,
                null,
                mainUI
            )
            fileSaveAllAction = FileSaveAllAction(
                "Save All",
                null,
                "Save all open files",
                KeyEvent.VK_V,
                null,
                mainUI
            )
            fileDumpMemoryAction = FileDumpMemoryAction(
                "Dump Memory ...",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Dump22.png"))),
                "Dump machine code or data in an available format",
                KeyEvent.VK_D,
                KeyStroke.getKeyStroke(KeyEvent.VK_D, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            filePrintAction = FilePrintAction(
                "Print ...",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Print22.gif"))),
                "Print current file",
                KeyEvent.VK_P,
                null,
                mainUI
            )
            fileExitAction = FileExitAction(
                "Exit",
                null,
                "Exit Mars",
                KeyEvent.VK_X,
                null,
                mainUI
            )
            editUndoAction = EditUndoAction(
                "Undo",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Undo22.png"))),
                "Undo last edit",
                KeyEvent.VK_U,
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            editRedoAction = EditRedoAction(
                "Redo",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Redo22.png"))),
                "Redo last edit",
                KeyEvent.VK_R,
                KeyStroke.getKeyStroke(KeyEvent.VK_Y, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            editCutAction = EditCutAction(
                "Cut",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Cut22.gif"))),
                "Cut",
                KeyEvent.VK_C,
                KeyStroke.getKeyStroke(KeyEvent.VK_X, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            editCopyAction = EditCopyAction(
                "Copy",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Copy22.png"))),
                "Copy",
                KeyEvent.VK_O,
                KeyStroke.getKeyStroke(KeyEvent.VK_C, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            editPasteAction = EditPasteAction(
                "Paste",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Paste22.png"))),
                "Paste",
                KeyEvent.VK_P,
                KeyStroke.getKeyStroke(KeyEvent.VK_V, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            editFindReplaceAction = EditFindReplaceAction(
                "Find/Replace",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Find22.png"))),
                "Find/Replace",
                KeyEvent.VK_F,
                KeyStroke.getKeyStroke(KeyEvent.VK_F, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            editSelectAllAction = EditSelectAllAction(
                "Select All",
                null,  //new ImageIcon(tk.getImage(cs.getResource(Globals.imagesPath+"Find22.png"))),
                "Select All",
                KeyEvent.VK_A,
                KeyStroke.getKeyStroke(KeyEvent.VK_A, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            runAssembleAction = RunAssembleAction(
                "Assemble",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Assemble22.png"))),
                "Assemble the current file and clear breakpoints",
                KeyEvent.VK_A,
                KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0),
                mainUI
            )
            runGoAction = RunGoAction(
                "Go",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Play22.png"))),
                "Run the current program",
                KeyEvent.VK_G,
                KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
                mainUI
            )
            runStepAction = RunStepAction(
                "Step",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}StepForward22.png"))),
                "Run one step at a time",
                KeyEvent.VK_T,
                KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0),
                mainUI
            )
            runBackstepAction = RunBackstepAction(
                "Backstep",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}StepBack22.png"))),
                "Undo the last step",
                KeyEvent.VK_B,
                KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0),
                mainUI
            )
            runPauseAction = RunPauseAction(
                "Pause",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Pause22.png"))),
                "Pause the currently running program",
                KeyEvent.VK_P,
                KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0),
                mainUI
            )
            runStopAction = RunStopAction(
                "Stop",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Stop22.png"))),
                "Stop the currently running program",
                KeyEvent.VK_S,
                KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0),
                mainUI
            )
            runResetAction = RunResetAction(
                "Reset",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Reset22.png"))),
                "Reset MIPS memory and registers",
                KeyEvent.VK_R,
                KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0),
                mainUI
            )
            runClearBreakpointsAction = RunClearBreakpointsAction(
                "Clear all breakpoints",
                null,
                "Clears all execution breakpoints set since the last assemble.",
                KeyEvent.VK_K,
                KeyStroke.getKeyStroke(KeyEvent.VK_K, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            runToggleBreakpointsAction = RunToggleBreakpointsAction(
                "Toggle all breakpoints",
                null,
                "Disable/enable all breakpoints without clearing (can also click Breakpoint column header)",
                KeyEvent.VK_T,
                KeyStroke.getKeyStroke(KeyEvent.VK_T, tk.menuShortcutKeyMaskEx),
                mainUI
            )
            settingsLabelAction = SettingsLabelAction(
                "Show Labels Window (symbol table)",
                null,
                "Toggle visibility of Labels window (symbol table) in the Execute tab",
                null,
                null,
                mainUI
            )
            settingsPopupInputAction = SettingsPopupInputAction(
                "Popup dialog for input syscalls (5,6,7,8,12)",
                null,
                "If set, use popup dialog for input syscalls (5,6,7,8,12) instead of cursor in Run I/O window",
                null,
                null,
                mainUI
            )

            settingsValueDisplayBaseAction = SettingsValueDisplayBaseAction(
                "Values displayed in hexadecimal",
                null,
                "Toggle between hexadecimal and decimal display of memory/register values",
                null,
                null,
                mainUI
            )
            settingsAddressDisplayBaseAction = SettingsAddressDisplayBaseAction(
                "Addresses displayed in hexadecimal",
                null,
                "Toggle between hexadecimal and decimal display of memory addresses",
                null,
                null,
                mainUI
            )
            settingsExtendedAction = SettingsExtendedAction(
                "Permit extended (pseudo) instructions and formats",
                null,
                "If set, MIPS extended (pseudo) instructions are formats are permitted.",
                null,
                null,
                mainUI
            )
            settingsAssembleOnOpenAction = SettingsAssembleOnOpenAction(
                "Assemble file upon opening",
                null,
                "If set, a file will be automatically assembled as soon as it is opened.  File Open dialog will show most recently opened file.",
                null,
                null,
                mainUI
            )
            settingsAssembleAllAction = SettingsAssembleAllAction(
                "Assemble all files in directory",
                null,
                "If set, all files in current directory will be assembled when Assemble operation is selected.",
                null,
                null,
                mainUI
            )
            settingsWarningsAreErrorsAction = SettingsWarningsAreErrorsAction(
                "Assembler warnings are considered errors",
                null,
                "If set, assembler warnings will be interpreted as errors and prevent successful assembly.",
                null,
                null,
                mainUI
            )
            settingsStartAtMainAction = SettingsStartAtMainAction(
                "Initialize Program Counter to global 'main' if defined",
                null,
                "If set, assembler will initialize Program Counter to text address globally labeled 'main', if defined.",
                null,
                null,
                mainUI
            )
            settingsProgramArgumentsAction = SettingsProgramArgumentsAction(
                "Program arguments provided to MIPS program",
                null,
                "If set, program arguments for MIPS program can be entered in border of Text Segment window.",
                null,
                null,
                mainUI
            )
            settingsDelayedBranchingAction = SettingsDelayedBranchingAction(
                "Delayed branching",
                null,
                "If set, delayed branching will occur during MIPS execution.",
                null,
                null,
                mainUI
            )
            settingsSelfModifyingCodeAction = SettingsSelfModifyingCodeAction(
                "Self-modifying code",
                null,
                "If set, the MIPS program can write and branch to both text and data segments.",
                null,
                null,
                mainUI
            )
            settingsEditorAction = SettingsEditorAction(
                "Editor...",
                null,
                "View and modify text editor settings.",
                null,
                null,
                mainUI
            )
            settingsHighlightingAction = SettingsHighlightingAction(
                "Highlighting...",
                null,
                "View and modify Execute Tab highlighting colors",
                null,
                null,
                mainUI
            )
            settingsExceptionHandlerAction = SettingsExceptionHandlerAction(
                "Exception Handler...",
                null,
                "If set, the specified exception handler file will be included in all Assemble operations.",
                null,
                null,
                mainUI
            )
            settingsMemoryConfigurationAction = SettingsMemoryConfigurationAction(
                "Memory Configuration...",
                null,
                "View and modify memory segment base addresses for simulated MIPS.",
                null,
                null,
                mainUI
            )
            helpHelpAction = HelpHelpAction(
                "Help",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Help22.png"))),
                "Help",
                KeyEvent.VK_H,
                KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0),
                mainUI
            )
            helpAboutAction = HelpAboutAction(
                "About ...",
                null,
                "Information about Mars",
                null,
                null,
                mainUI
            )
        } catch (e: NullPointerException) {
            println("Internal Error: images folder not found, or other null pointer exception while creating Action objects!")
            e.printStackTrace()
            exitProcess(1)
        }
    }

    /**
     * Build the menus and connect them to action objects (which serve as action listeners
     * shared between menu item and corresponding toolbar icon).
     */
    private fun setUpMenuBar(): JMenuBar {
        val tk = Toolkit.getDefaultToolkit()
        val cs = javaClass
        val menuBar = JMenuBar()

        // Components of the menubar
        val file = JMenu("File")
        file.mnemonic = KeyEvent.VK_F
        val edit = JMenu("Edit")
        edit.mnemonic = KeyEvent.VK_E
        val run = JMenu("Run")
        run.mnemonic = KeyEvent.VK_R
        val settings = JMenu("Settings")
        settings.mnemonic = KeyEvent.VK_S
        val help = JMenu("Help")
        help.mnemonic = KeyEvent.VK_H

        // Minor bug: user typing alt-H activates help menu item directly, not the help menu
        val fileNew = JMenuItem(fileNewAction)
        fileNew.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}New16.png")))
        val fileOpen = JMenuItem(fileOpenAction)
        fileOpen.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Open16.png")))
        val fileClose = JMenuItem(fileCloseAction)
        fileClose.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}MyBlank16.gif")))
        val fileCloseAll = JMenuItem(fileCloseAllAction)
        fileCloseAll.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}MyBlank16.gif")))
        val fileSave = JMenuItem(fileSaveAction)
        fileSave.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Save16.png")))
        val fileSaveAs = JMenuItem(fileSaveAsAction)
        fileSaveAs.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}SaveAs16.png")))
        val fileSaveAll = JMenuItem(fileSaveAllAction)
        fileSaveAll.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}MyBlank16.gif")))
        val fileDumpMemory = JMenuItem(fileDumpMemoryAction)
        fileDumpMemory.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Dump16.png")))
        val filePrint = JMenuItem(filePrintAction)
        filePrint.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Print16.gif")))
        val fileExit = JMenuItem(fileExitAction)
        fileExit.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}MyBlank16.gif")))
        file.add(fileNew)
        file.add(fileOpen)
        file.add(fileClose)
        file.add(fileCloseAll)
        file.addSeparator()
        file.add(fileSave)
        file.add(fileSaveAs)
        file.add(fileSaveAll)
        if (DumpFormatLoader().loadDumpFormats().isNotEmpty()) {
            file.add(fileDumpMemory)
        }
        file.addSeparator()
        file.add(filePrint)
        file.addSeparator()
        file.add(fileExit)

        val editUndo = JMenuItem(editUndoAction)
        editUndo.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Undo16.png"))) //"Undo16.gif"))));
        val editRedo = JMenuItem(editRedoAction)
        editRedo.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Redo16.png"))) //"Redo16.gif"))));
        val editCut = JMenuItem(editCutAction)
        editCut.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Cut16.gif")))
        val editCopy = JMenuItem(editCopyAction)
        editCopy.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Copy16.png"))) //"Copy16.gif"))));
        val editPaste = JMenuItem(editPasteAction)
        editPaste.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Paste16.png"))) //"Paste16.gif"))));
        val editFindReplace = JMenuItem(editFindReplaceAction)
        editFindReplace.icon =
            ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Find16.png"))) //"Paste16.gif"))));
        val editSelectAll = JMenuItem(editSelectAllAction)
        editSelectAll.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}MyBlank16.gif")))
        edit.add(editUndo)
        edit.add(editRedo)
        edit.addSeparator()
        edit.add(editCut)
        edit.add(editCopy)
        edit.add(editPaste)
        edit.addSeparator()
        edit.add(editFindReplace)
        edit.add(editSelectAll)

        val runAssemble = JMenuItem(runAssembleAction)
        runAssemble.icon =
            ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Assemble16.png"))) //"MyAssemble16.gif"))));
        val runGo = JMenuItem(runGoAction)
        runGo.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Play16.png"))) //"Play16.gif"))));
        val runStep = JMenuItem(runStepAction)
        runStep.icon =
            ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}StepForward16.png"))) //"MyStepForward16.gif"))));
        val runBackstep = JMenuItem(runBackstepAction)
        runBackstep.icon =
            ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}StepBack16.png"))) //"MyStepBack16.gif"))));
        val runReset = JMenuItem(runResetAction)
        runReset.icon =
            ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Reset16.png"))) //"MyReset16.gif"))));
        val runStop = JMenuItem(runStopAction)
        runStop.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Stop16.png"))) //"Stop16.gif"))));
        val runPause = JMenuItem(runPauseAction)
        runPause.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Pause16.png"))) //"Pause16.gif"))));
        val runClearBreakpoints = JMenuItem(runClearBreakpointsAction)
        runClearBreakpoints.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}MyBlank16.gif")))
        val runToggleBreakpoints = JMenuItem(runToggleBreakpointsAction)
        runToggleBreakpoints.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}MyBlank16.gif")))

        run.add(runAssemble)
        run.add(runGo)
        run.add(runStep)
        run.add(runBackstep)
        run.add(runPause)
        run.add(runStop)
        run.add(runReset)
        run.addSeparator()
        run.add(runClearBreakpoints)
        run.add(runToggleBreakpoints)

        val settingsLabel = JCheckBoxMenuItem(settingsLabelAction)
        settingsLabel.isSelected = Globals.settings.getBooleanSetting(Settings.LABEL_WINDOW_VISIBILITY)
        val settingsPopupInput = JCheckBoxMenuItem(settingsPopupInputAction)
        settingsPopupInput.isSelected = Globals.settings.getBooleanSetting(Settings.POPUP_SYSCALL_INPUT)
        valueDisplayBaseMenuItem = JCheckBoxMenuItem(settingsValueDisplayBaseAction)
        valueDisplayBaseMenuItem.isSelected =
            Globals.settings.getBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX) //mainPane.getExecutePane().getValueDisplayBaseChooser().isSelected());

        // Tell the corresponding JCheckBox in the Execute Pane about me -- it has already been created.
        mainPane.executePane.valueDisplayBaseChooser.setSettingsMenuItem(valueDisplayBaseMenuItem)
        addressDisplayBaseMenuItem = JCheckBoxMenuItem(settingsAddressDisplayBaseAction)
        addressDisplayBaseMenuItem.isSelected =
            Globals.settings.getBooleanSetting(Settings.EXTENDED_ASSEMBLER_ENABLED) //mainPane.getExecutePane().getValueDisplayBaseChooser().isSelected());

        // Tell the corresponding JCheckBox in the Execute Pane about me -- it has already been created.
        mainPane.executePane.addressDisplayBaseChooser.setSettingsMenuItem(addressDisplayBaseMenuItem)
        val settingsExtended = JCheckBoxMenuItem(settingsExtendedAction)
        settingsExtended.isSelected = Globals.settings.getBooleanSetting(Settings.EXTENDED_ASSEMBLER_ENABLED)
        val settingsDelayedBranching = JCheckBoxMenuItem(settingsDelayedBranchingAction)
        settingsDelayedBranching.isSelected = Globals.settings.getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)
        val settingsSelfModifyingCode = JCheckBoxMenuItem(settingsSelfModifyingCodeAction)
        settingsSelfModifyingCode.isSelected = Globals.settings.getBooleanSetting(Settings.ENABLE_SELF_MODIFYING_CODE)
        val settingsAssembleOnOpen = JCheckBoxMenuItem(settingsAssembleOnOpenAction)
        settingsAssembleOnOpen.isSelected = Globals.settings.getBooleanSetting(Settings.ASSEMBLE_ON_OPEN_ENABLED)
        val settingsAssembleAll = JCheckBoxMenuItem(settingsAssembleAllAction)
        settingsAssembleAll.isSelected = Globals.settings.getBooleanSetting(Settings.ASSEMBLE_ALL_ENABLED)
        val settingsWarningsAreErrors = JCheckBoxMenuItem(settingsWarningsAreErrorsAction)
        settingsWarningsAreErrors.isSelected = Globals.settings.getBooleanSetting(Settings.WARNINGS_ARE_ERRORS)
        val settingsStartAtMain = JCheckBoxMenuItem(settingsStartAtMainAction)
        settingsStartAtMain.isSelected = Globals.settings.getBooleanSetting(Settings.START_AT_MAIN)
        val settingsProgramArguments = JCheckBoxMenuItem(settingsProgramArgumentsAction)
        settingsProgramArguments.isSelected = Globals.settings.getBooleanSetting(Settings.ENABLE_PROGRAM_ARGUMENTS)
        val settingsEditor = JMenuItem(settingsEditorAction)
        val settingsHighlighting = JMenuItem(settingsHighlightingAction)
        val settingsExceptionHandler = JMenuItem(settingsExceptionHandlerAction)
        val settingsMemoryConfiguration = JMenuItem(settingsMemoryConfigurationAction)

        settings.add(settingsLabel)
        settings.add(settingsProgramArguments)
        settings.add(settingsPopupInput)
        settings.add(addressDisplayBaseMenuItem)
        settings.add(valueDisplayBaseMenuItem)
        settings.addSeparator()
        settings.add(settingsAssembleOnOpen)
        settings.add(settingsAssembleAll)
        settings.add(settingsWarningsAreErrors)
        settings.add(settingsStartAtMain)
        settings.addSeparator()
        settings.add(settingsExtended)
        settings.add(settingsDelayedBranching)
        settings.add(settingsSelfModifyingCode)
        settings.addSeparator()
        settings.add(settingsEditor)
        settings.add(settingsHighlighting)
        settings.add(settingsExceptionHandler)
        settings.add(settingsMemoryConfiguration)

        val helpHelp = JMenuItem(helpHelpAction)
        helpHelp.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Help16.png"))) //"Help16.gif"))));
        val helpAbout = JMenuItem(helpAboutAction)
        helpAbout.icon = ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}MyBlank16.gif")))
        help.add(helpHelp)
        help.addSeparator()
        help.add(helpAbout)

        menuBar.add(file)
        menuBar.add(edit)
        menuBar.add(run)
        menuBar.add(settings)
        val toolMenu = ToolLoader().buildToolsMenu()
        if (toolMenu != null) menuBar.add(toolMenu)
        menuBar.add(help)

        // Experiment with a pop-up menu for settings. 3 Aug 2006 PS
        // setupPopupMenu();
        return menuBar
    }

    /**
     * Build the toolbar and connect items to action objects (which serve as action listeners
     * shared between toolbar icon and corresponding menu item).
     */
    fun setUpToolbar(): JToolBar {
        val toolBar = JToolBar()

        val aNew = JButton(fileNewAction)
        aNew.text = ""
        val open = JButton(fileOpenAction)
        open.text = ""
        val save = JButton(fileSaveAction)
        save.text = ""
        val saveAs = JButton(fileSaveAsAction)
        saveAs.text = ""
        val dumpMemory = JButton(fileDumpMemoryAction)
        dumpMemory.text = ""
        val print = JButton(filePrintAction)
        print.text = ""

        // components of the toolbar
        val undo = JButton(editUndoAction)
        undo.text = ""
        val redo = JButton(editRedoAction)
        redo.text = ""
        val cut = JButton(editCutAction)
        cut.text = ""
        val copy = JButton(editCopyAction)
        copy.text = ""
        val paste = JButton(editPasteAction)
        paste.text = ""
        val findReplace = JButton(editFindReplaceAction)
        findReplace.text = ""
        val selectAll = JButton(editSelectAllAction)
        selectAll.text = ""

        val run1 = JButton(runGoAction)
        run1.text = ""
        val assemble = JButton(runAssembleAction)
        assemble.text = ""
        val step = JButton(runStepAction)
        step.text = ""
        val backstep = JButton(runBackstepAction)
        backstep.text = ""
        val reset1 = JButton(runResetAction)
        reset1.text = ""
        val stop = JButton(runStopAction)
        stop.text = ""
        val pause = JButton(runPauseAction)
        pause.text = ""
        val help1 = JButton(helpHelpAction)
        help1.text = ""

        toolBar.add(aNew)
        toolBar.add(open)
        toolBar.add(save)
        toolBar.add(saveAs)
        if (DumpFormatLoader().loadDumpFormats().isNotEmpty()) {
            toolBar.add(dumpMemory)
        }
        toolBar.add(print)
        toolBar.add(JToolBar.Separator())
        toolBar.add(undo)
        toolBar.add(redo)
        toolBar.add(cut)
        toolBar.add(copy)
        toolBar.add(paste)
        toolBar.add(findReplace)
        toolBar.add(JToolBar.Separator())
        toolBar.add(assemble)
        toolBar.add(run1)
        toolBar.add(step)
        toolBar.add(backstep)
        toolBar.add(pause)
        toolBar.add(stop)
        toolBar.add(reset1)
        toolBar.add(JToolBar.Separator())
        toolBar.add(help1)
        toolBar.add(JToolBar.Separator())

        return toolBar
    }

    /**
     * Determine from FileStatus what the menu state (enabled/disabled) should
     * be then call the appropriate method to set it.  Current states are:
     *
     * setMenuStateInitial: set upon startup and after File->Close
     * setMenuStateEditingNew: set upon File->New
     * setMenuStateEditing: set upon File->Open or File->Save or erroneous Run->Assemble
     * setMenuStateRunnable: set upon successful Run->Assemble
     * setMenuStateRunning: set upon Run->Go
     * setMenuStateTerminated: set upon completion of simulated execution
     */
    fun setMenuState(status: Int) {
        menuState = status
        when (status) {
            FileStatus.NO_FILE -> setMenuStateInitial()
            FileStatus.NEW_NOT_EDITED, FileStatus.NEW_EDITED -> setMenuStateEditingNew()
            FileStatus.NOT_EDITED -> setMenuStateNotEdited()
            FileStatus.EDITED -> setMenuStateEditing()
            FileStatus.RUNNABLE -> setMenuStateRunnable()
            FileStatus.RUNNING -> setMenuStateRunning()
            FileStatus.TERMINATED -> setMenuStateTerminated()
            FileStatus.OPENING -> {}
            else -> println("Invalid file status: $status")
        }
    }

    fun setMenuStateInitial() {
        fileNewAction.isEnabled = true
        fileOpenAction.isEnabled = true
        fileCloseAction.isEnabled = false
        fileCloseAllAction.isEnabled = false
        fileSaveAction.isEnabled = false
        fileSaveAsAction.isEnabled = false
        fileSaveAllAction.isEnabled = false
        fileDumpMemoryAction.isEnabled = false
        filePrintAction.isEnabled = false
        fileExitAction.isEnabled = true
        editUndoAction.isEnabled = false
        editRedoAction.isEnabled = false
        editCutAction.isEnabled = false
        editCopyAction.isEnabled = false
        editPasteAction.isEnabled = false
        editFindReplaceAction.isEnabled = false
        editSelectAllAction.isEnabled = false
        settingsDelayedBranchingAction.isEnabled = true // added 25 June 2007
        settingsMemoryConfigurationAction.isEnabled = true // added 21 July 2009
        runAssembleAction.isEnabled = false
        runGoAction.isEnabled = false
        runStepAction.isEnabled = false
        runBackstepAction.isEnabled = false
        runResetAction.isEnabled = false
        runStopAction.isEnabled = false
        runPauseAction.isEnabled = false
        runClearBreakpointsAction.isEnabled = false
        runToggleBreakpointsAction.isEnabled = false
        helpHelpAction.isEnabled = true
        helpAboutAction.isEnabled = true
        editUndoAction.updateUndoState()
        editRedoAction.updateRedoState()
    }

    fun setMenuStateNotEdited() {
        /* Note: undo and redo are handled separately by the undo manager*/
        setCommonUpperMenuState()
        runAssembleAction.isEnabled = true
        // If assemble-all, allow previous Run menu settings to remain.
        // Otherwise, clear them out.  DPS 9-Aug-2011
        if (!settings.getBooleanSetting(Settings.ASSEMBLE_ALL_ENABLED)) {
            runGoAction.isEnabled = false
            runStepAction.isEnabled = false
            runBackstepAction.isEnabled = false
            runResetAction.isEnabled = false
            runStopAction.isEnabled = false
            runPauseAction.isEnabled = false
            runClearBreakpointsAction.isEnabled = false
            runToggleBreakpointsAction.isEnabled = false
        }
        helpHelpAction.isEnabled = true
        helpAboutAction.isEnabled = true
        editUndoAction.updateUndoState()
        editRedoAction.updateRedoState()
    }

    private fun setCommonLowerMenuState() {
        runGoAction.isEnabled = false
        runStepAction.isEnabled = false
        runBackstepAction.isEnabled = false
        runResetAction.isEnabled = false
        runStopAction.isEnabled = false
        runPauseAction.isEnabled = false
        runClearBreakpointsAction.isEnabled = false
        runToggleBreakpointsAction.isEnabled = false
        helpHelpAction.isEnabled = true
        helpAboutAction.isEnabled = true
        editUndoAction.updateUndoState()
        editRedoAction.updateRedoState()
    }

    fun setMenuStateEditing() {
        /* Note: undo and redo are handled separately by the undo manager*/
        setCommonUpperMenuState()
        runAssembleAction.isEnabled = true
        setCommonLowerMenuState()
    }

    private fun setMenuStateEditingNew() {
        /* Note: undo and redo are handled separately by the undo manager*/
        setCommonUpperMenuState()
        runAssembleAction.isEnabled = false
        setCommonLowerMenuState()
    }

    private fun setCommonUpperMenuState() {
        fileNewAction.isEnabled = true
        fileOpenAction.isEnabled = true
        fileCloseAction.isEnabled = true
        fileCloseAllAction.isEnabled = true
        fileSaveAction.isEnabled = true
        fileSaveAsAction.isEnabled = true
        fileSaveAllAction.isEnabled = true
        fileDumpMemoryAction.isEnabled = false
        filePrintAction.isEnabled = true
        fileExitAction.isEnabled = true
        editCutAction.isEnabled = true
        editCopyAction.isEnabled = true
        editPasteAction.isEnabled = true
        editFindReplaceAction.isEnabled = true
        editSelectAllAction.isEnabled = true
        settingsDelayedBranchingAction.isEnabled = true
        settingsMemoryConfigurationAction.isEnabled = true
    }

    fun setMenuStateRunnable() {
        /* Note: undo and redo are handled separately by the undo manager */
        fileNewAction.isEnabled = true
        fileOpenAction.isEnabled = true
        fileCloseAction.isEnabled = true
        fileCloseAllAction.isEnabled = true
        fileSaveAction.isEnabled = true
        fileSaveAsAction.isEnabled = true
        fileSaveAllAction.isEnabled = true
        fileDumpMemoryAction.isEnabled = true
        filePrintAction.isEnabled = true
        fileExitAction.isEnabled = true
        editCutAction.isEnabled = true
        editCopyAction.isEnabled = true
        editPasteAction.isEnabled = true
        editFindReplaceAction.isEnabled = true
        editSelectAllAction.isEnabled = true
        settingsDelayedBranchingAction.isEnabled = true // added 25 June 2007
        settingsMemoryConfigurationAction.isEnabled = true // added 21 July 2009
        runAssembleAction.isEnabled = true
        runGoAction.isEnabled = true
        runStepAction.isEnabled = true
        runBackstepAction.isEnabled = settings.getBackSteppingEnabled() && !Globals.program.getBackStepper()!!.isEmpty()
        runResetAction.isEnabled = true
        runStopAction.isEnabled = false
        runPauseAction.isEnabled = false
        runToggleBreakpointsAction.isEnabled = true
        helpHelpAction.isEnabled = true
        helpAboutAction.isEnabled = true
        editUndoAction.updateUndoState()
        editRedoAction.updateRedoState()
    }

    fun setMenuStateRunning() {
        /* Note: undo and redo are handled separately by the undo manager */
        fileNewAction.isEnabled = false
        fileOpenAction.isEnabled = false
        fileCloseAction.isEnabled = false
        fileCloseAllAction.isEnabled = false
        fileSaveAction.isEnabled = false
        fileSaveAsAction.isEnabled = false
        fileSaveAllAction.isEnabled = false
        fileDumpMemoryAction.isEnabled = false
        filePrintAction.isEnabled = false
        fileExitAction.isEnabled = false
        editCutAction.isEnabled = false
        editCopyAction.isEnabled = false
        editPasteAction.isEnabled = false
        editFindReplaceAction.isEnabled = false
        editSelectAllAction.isEnabled = false
        settingsDelayedBranchingAction.isEnabled = false // added 25 June 2007
        settingsMemoryConfigurationAction.isEnabled = false // added 21 July 2009
        runAssembleAction.isEnabled = false
        runGoAction.isEnabled = false
        runStepAction.isEnabled = false
        runBackstepAction.isEnabled = false
        runResetAction.isEnabled = false
        runStopAction.isEnabled = true
        runPauseAction.isEnabled = true
        runToggleBreakpointsAction.isEnabled = false
        helpHelpAction.isEnabled = true
        helpAboutAction.isEnabled = true
        editUndoAction.isEnabled = false //updateUndoState(); // DPS 10 Jan 2008
        editRedoAction.isEnabled = false //updateRedoState(); // DPS 10 Jan 2008
    }

    fun setMenuStateTerminated() {
        /* Note: undo and redo are handled separately by the undo manager */
        fileNewAction.isEnabled = true
        fileOpenAction.isEnabled = true
        fileCloseAction.isEnabled = true
        fileCloseAllAction.isEnabled = true
        fileSaveAction.isEnabled = true
        fileSaveAsAction.isEnabled = true
        fileSaveAllAction.isEnabled = true
        fileDumpMemoryAction.isEnabled = true
        filePrintAction.isEnabled = true
        fileExitAction.isEnabled = true
        editCutAction.isEnabled = true
        editCopyAction.isEnabled = true
        editPasteAction.isEnabled = true
        editFindReplaceAction.isEnabled = true
        editSelectAllAction.isEnabled = true
        settingsDelayedBranchingAction.isEnabled = true
        settingsMemoryConfigurationAction.isEnabled = true
        runAssembleAction.isEnabled = true
        runGoAction.isEnabled = false
        runStepAction.isEnabled = false
        runBackstepAction.isEnabled = settings.getBackSteppingEnabled() && !Globals.program.getBackStepper()!!.isEmpty()
        runResetAction.isEnabled = true
        runStopAction.isEnabled = false
        runPauseAction.isEnabled = false
        runToggleBreakpointsAction.isEnabled = true
        helpHelpAction.isEnabled = true
        helpAboutAction.isEnabled = true
        editUndoAction.updateUndoState()
        editRedoAction.updateRedoState()
    }

    fun requestMenuFocus() = menu.requestFocus()

    fun dispatchEventToMenu(event: KeyEvent) = menu.dispatchEvent(event)
}