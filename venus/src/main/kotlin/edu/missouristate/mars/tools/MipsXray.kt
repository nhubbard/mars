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

@file:Suppress("MemberVisibilityCanBePrivate", "SameParameterValue", "DEPRECATION", "DuplicatedCode")

package edu.missouristate.mars.tools

import edu.missouristate.mars.Globals
import edu.missouristate.mars.UIGlobals
import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.vectorOf
import edu.missouristate.mars.venus.VenusUI
import edu.missouristate.mars.venus.actions.RunAssembleAction
import edu.missouristate.mars.venus.actions.RunBackstepAction
import edu.missouristate.mars.venus.actions.RunStepAction
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.awt.*
import java.awt.event.*
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.Serial
import java.util.*
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.Timer
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess

class MipsXray(
    title: String = "$NAME, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val NAME = "MIPS X-Ray"
        private const val VERSION = "Version 2.0"
        private const val HEADING = "Animation of MIPS Datapath"
    }

    private lateinit var g: Graphics
    private var lastAddress = -1
    private lateinit var label: JLabel

    private val contentPanel = contentPane

    private lateinit var gc: GraphicsConfiguration
    private lateinit var datapath: BufferedImage
    private lateinit var instructionBinary: String
    private lateinit var runAssembleAction: Action
    private lateinit var runStepAction: Action
    private lateinit var runBackstepAction: Action
    private lateinit var mainUI: VenusUI
    private lateinit var toolbar: JToolBar

    override val toolName: String = NAME

    override fun getHelpComponent(): JComponent {
        val helpContent = """
            This plugin is used to visualize the behavior of a MIPS processor using the default datapath. 
            It reads the source code instruction and generates an animation representing the inputs and 
            outputs of functional blocks and the interconnection between them. The basic signals 
            represented are, control signals, opcode bits and data of functional blocks.

            Besides the datapath representation, information for each instruction is displayed below
            the datapath. That display includes opcode value, with the correspondent colors used to
            represent the signals in datapath, mnemonic of the instruction processed at the moment, registers
            used in the instruction and a label that indicates the color code used to represent control signals.

            To see the datapath of register bank and control units click inside the functional unit.

            Version 2.0
            Developed by Marcio Roberto, Guilherme Sales, Fabricio Vivas, Flavio Cardeal and Fabio Lacio
            Contact Marcio Roberto at marcio.rdaraujo@gmail.com with questions or comments.
            """.trimIndent()
        val help = JButton("Help")
        help.addActionListener { JOptionPane.showMessageDialog(theWindow, helpContent) }
        return help
    }

    private fun buildAnimationSequence() = JPanel(GridBagLayout())

    override fun buildMainDisplayArea(): JComponent = buildMainDisplayArea("datapath.png")

    fun buildMainDisplayArea(figure: String): JComponent {
        mainUI = UIGlobals.gui
        createActionObjects()
        toolbar = setUpToolbar()

        val res = javaClass.getResource("${Globals.imagesPath}$figure")
            ?: throw IllegalStateException("Can't get ${Globals.imagesPath}$figure!")
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        gc = ge.defaultScreenDevice.defaultConfiguration
        try {
            val im = ImageIO.read(res)
            datapath = gc.createCompatibleImage(im.width, im.height, im.colorModel.transparency)
            val g2d = datapath.createGraphics()
            g2d.drawImage(im, 0, 0, null)
            g2d.dispose()
        } catch (e: IOException) {
            println("Image loading error for $res:\n$e")
            e.printStackTrace()
        }
        System.setProperty("sun.java2d.translaccel", "true")
        var icon = ImageIcon(res)
        val im = icon.image
        icon = ImageIcon(im)

        val label = JLabel(icon)
        contentPanel.add(label, BorderLayout.WEST)
        contentPanel.add(toolbar, BorderLayout.NORTH)
        isResizable = false
        return contentPanel as JComponent
    }

    override fun addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress)
    }

    override fun processMipsUpdate(resource: Observable, notice: AccessNotice) {
        if (!notice.accessIsFromMIPS || notice.accessType == AccessNotice.AccessType.READ || notice !is MemoryAccessNotice)
            return
        val currentAddress = notice.address
        if (currentAddress == lastAddress) return
        lastAddress = currentAddress
        try {
            val statement = Memory.instance.getStatement(currentAddress) ?: return
            instructionBinary = statement.getMachineStatement()!!

            contentPanel.removeAll()

            val datapathAnimation = DatapathAnimation(instructionBinary)
            createActionObjects()
            toolbar = setUpToolbar()
            contentPanel.add(toolbar, BorderLayout.NORTH)
            contentPanel.add(datapathAnimation, BorderLayout.WEST)
            datapathAnimation.startAnimation(instructionBinary)
        } catch (e: AddressErrorException) {
            e.printStackTrace()
        }
    }

    override fun updateDisplay() {
        repaint()
    }

    private fun setUpToolbar(): JToolBar {
        val toolBar = JToolBar()
        val assemble = JButton(runAssembleAction)
        assemble.text = ""
        val runBackStep = JButton(runBackstepAction)
        runBackStep.text = ""
        val step = JButton(runStepAction)
        step.text = ""
        toolBar.add(assemble)
        toolBar.add(step)
        return toolBar
    }

    private fun createActionObjects() {
        val tk = Toolkit.getDefaultToolkit()
        val cs = javaClass
        try {
            runAssembleAction = RunAssembleAction(
                "Assemble",
                ImageIcon(tk.getImage(cs.getResource("${Globals.imagesPath}Assemble22.png"))),
                "Assemble the current file and clear breakpoints",
                KeyEvent.VK_A,
                KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0),
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
        } catch (e: Exception) {
            println("Internal Error: images folder not found, or other null pointer exception while creating Action objects!")
            e.printStackTrace()
            exitProcess(1)
        }
    }

    inner class DatapathAnimation(instructionBinary: String) : JPanel(), ActionListener, MouseListener {
        @Serial
        private val serialVersionUID = -2681757800180958534L
        private val panelWidth = 1000
        private val panelHeight = 574

        private var gc: GraphicsConfiguration
        private var counter: Int = -1
        private var justStarted: Boolean = false
        private var indexX: Int = -1
        private var indexY: Int = -1
        private var xIsMoving: Boolean = false
        private var yIsMoving: Boolean = false
        private lateinit var outputGraph: Vector<Vector<Vertex>>
        private var vertexList: ArrayList<Vertex>
        private lateinit var vertexTraversed: ArrayList<Vertex>
        private var opcodeEquivalenceTable: HashMap<String, String>
        private var functionEquivalenceTable: HashMap<String, String>
        private var registerEquivalenceTable: HashMap<String, String>
        private var instructionCode: String

        private val green1 = Color(0, 153, 0)
        private val green2 = Color(0, 77, 0)
        private val yellow = Color(185, 182, 42)
        private val orange = Color(255, 102, 0)
        private val brown = Color(119, 34, 34)
        private val blue = Color(0, 153, 255)

        private val alu = FunctionUnitVisualization.FunctionalUnit.ALU
        private lateinit var currentUnit: FunctionUnitVisualization.FunctionalUnit
        private lateinit var g2d: Graphics2D
        private lateinit var datapath: BufferedImage

        override fun mousePressed(e: MouseEvent) {
            // Content removed; was a debug statement that was commented out
        }

        init {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            gc = ge.defaultScreenDevice.defaultConfiguration

            background = Color.white
            preferredSize = Dimension(panelWidth, panelHeight)

            initImages()

            vertexList = arrayListOf()
            counter = 0
            justStarted = true
            instructionCode = instructionBinary
            opcodeEquivalenceTable = hashMapOf()
            functionEquivalenceTable = hashMapOf()
            registerEquivalenceTable = hashMapOf()

            loadHashMapValues()
            addMouseListener(this)
        }

        fun loadHashMapValues() {
            importXmlStringData("/MipsXRayOpcode.xml", opcodeEquivalenceTable, "equivalence", "bits", "mnemonic")
            importXmlStringData(
                "/MipsXRayOpcode.xml",
                functionEquivalenceTable,
                "function_equivalence",
                "bits",
                "mnemonic"
            )
            importXmlStringData(
                "/MipsXRayOpcode.xml",
                registerEquivalenceTable,
                "register_equivalence",
                "bits",
                "mnemonic"
            )
            importXmlDatapathMap("/MipsXRayOpcode.xml", "datapath_map")
        }

        fun importXmlStringData(
            xmlName: String,
            table: HashMap<String, String>,
            elementTree: String,
            tagId: String,
            tagData: String
        ) {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            try {
                val docBuilder = dbf.newDocumentBuilder()
                val doc = docBuilder.parse(javaClass.getResource(xmlName)!!.toString())
                val root = doc.documentElement
                var equivalenceItem: Element
                var bitList: NodeList
                var mnemonic: NodeList
                val equivalenceList = root.getElementsByTagName(elementTree)
                for (i in 0..<equivalenceList.length) {
                    equivalenceItem = equivalenceList.item(i) as Element
                    bitList = equivalenceItem.getElementsByTagName(tagId)
                    mnemonic = equivalenceItem.getElementsByTagName(tagData)
                    for (j in 0..<bitList.length)
                        table[bitList.item(j).textContent] = mnemonic.item(j).textContent
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun importXmlDatapathMap(xmlName: String, elementTree: String) {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            try {
                val docBuilder = dbf.newDocumentBuilder()
                val doc = docBuilder.parse(javaClass.getResource(xmlName)!!.toString())
                val root = doc.documentElement
                var datapathMapItem: Element
                var indexVertex: NodeList
                var name: NodeList
                var init: NodeList
                var end: NodeList
                var color: NodeList
                var otherAxis: NodeList
                var isMovingXAxis: NodeList
                var targetVertex: NodeList
                var isText: NodeList
                val datapathMapList = root.getElementsByTagName(elementTree)
                for (i in 0..<datapathMapList.length) {
                    datapathMapItem = datapathMapList.item(i) as Element
                    indexVertex = datapathMapItem.getElementsByTagName("num_vertex")
                    name = datapathMapItem.getElementsByTagName("name")
                    init = datapathMapItem.getElementsByTagName("init")
                    end = datapathMapItem.getElementsByTagName("end")
                    color = if (instructionCode.startsWith("000000")) {
                        datapathMapItem.getElementsByTagName("color_Rtype")
                    } else if (instructionCode.substring(0, 6).matches("00001[0-1]".toRegex())) {
                        datapathMapItem.getElementsByTagName("color_Jtype")
                    } else if (instructionCode.substring(0, 6).matches("100[0-1][0-1][0-1]".toRegex())) {
                        datapathMapItem.getElementsByTagName("color_LOADtype")
                    } else if (instructionCode.substring(0, 6).matches("101[0-1][0-1][0-1]".toRegex())) {
                        datapathMapItem.getElementsByTagName("color_STOREtype")
                    } else if (instructionCode.substring(0, 6).matches("0001[0-1][0-1]".toRegex())) {
                        datapathMapItem.getElementsByTagName("color_BRANCHtype")
                    } else {
                        datapathMapItem.getElementsByTagName("color_Itype")
                    }
                    otherAxis = datapathMapItem.getElementsByTagName("otherAxis")
                    isMovingXAxis = datapathMapItem.getElementsByTagName("isMovingXaxis")
                    targetVertex = datapathMapItem.getElementsByTagName("target_vertex")
                    isText = datapathMapItem.getElementsByTagName("is_text")
                    for (j in 0..<indexVertex.length) {
                        vertexList.add(
                            Vertex(
                                indexVertex.item(j).textContent.toInt(),
                                init.item(j).textContent.toInt(),
                                end.item(j).textContent.toInt(),
                                name.item(j).textContent,
                                otherAxis.item(j).textContent.toInt(),
                                isMovingXAxis.item(j).textContent.toBoolean(),
                                color.item(j).textContent,
                                targetVertex.item(j).textContent,
                                isText.item(j).textContent.toBoolean()
                            )
                        )
                    }
                }
                outputGraph = vectorOf()
                vertexTraversed = arrayListOf()
                var vertex: Vertex
                var targetList: ArrayList<Int>
                for (value in vertexList) {
                    vertex = value
                    targetList = vertex.targetVertex
                    val vertexOfTargets = vectorOf<Vertex>()
                    for (integer in targetList) vertexOfTargets.add(vertexList[integer])
                    outputGraph.add(vertexOfTargets)
                }
                vertexList[0].isActive = true
                vertexTraversed.add(vertexList[0])
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun setUpInstructionInfo(g2d: Graphics2D) {
            val frc = g2d.fontRenderContext
            val font = Font("Digital-7", Font.PLAIN, 15)
            val fontTitle = Font("Verdana", Font.PLAIN, 10)
            var textVariable: TextLayout

            if (instructionCode.startsWith("000000")) {  //R-type instructions description on screen definition.
                textVariable = TextLayout("REGISTER TYPE INSTRUCTION", Font("Arial", Font.BOLD, 25), frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 280f, 30f)
                // opcode label
                textVariable = TextLayout("opcode", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 530f)

                // initialize of opcode
                textVariable = TextLayout(instructionCode.substring(0, 6), font, frc)
                g2d.color = Color.magenta
                textVariable.draw(g2d, 25f, 550f)

                // rs label
                textVariable = TextLayout("rs", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 90f, 530f)

                // initialize of rs
                textVariable = TextLayout(instructionCode.substring(6, 11), font, frc)
                g2d.color = Color.green
                textVariable.draw(g2d, 90f, 550f)

                // rt label
                textVariable = TextLayout("rt", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 150f, 530f)

                // initialize of rt
                textVariable = TextLayout(instructionCode.substring(11, 16), font, frc)
                g2d.color = Color.blue
                textVariable.draw(g2d, 150f, 550f)

                // rd label
                textVariable = TextLayout("rd", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 210f, 530f)

                // initialize of rd
                textVariable = TextLayout(instructionCode.substring(16, 21), font, frc)
                g2d.color = Color.cyan
                textVariable.draw(g2d, 210f, 550f)

                // shamt label
                textVariable = TextLayout("shamt", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 270f, 530f)

                // initialize of shamt
                textVariable = TextLayout(instructionCode.substring(21, 26), font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 270f, 550f)

                // function label
                textVariable = TextLayout("function", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 330f, 530f)

                // initialize of function
                textVariable = TextLayout(instructionCode.substring(26, 32), font, frc)
                g2d.color = orange
                textVariable.draw(g2d, 330f, 550f)

                // instruction mnemonic
                textVariable = TextLayout("Instruction", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 480f)

                // instruction name
                textVariable = TextLayout(functionEquivalenceTable[instructionCode.substring(26, 32)], font, frc)
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 25f, 500f)

                // register in RS
                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(6, 11)], font, frc)
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 65f, 500f)

                // register in RT
                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(16, 21)], font, frc)
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 105f, 500f)

                // register in RD
                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(11, 16)], font, frc)
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 145f, 500f)
            } else if (instructionCode.substring(0, 6).matches("00001[0-1]".toRegex())) { // jump instructions
                textVariable = TextLayout(
                    "JUMP TYPE INSTRUCTION",
                    Font("Verdana", Font.BOLD, 25),
                    frc
                ) // description of the instruction code type for jump.
                g2d.color = Color.black
                textVariable.draw(g2d, 280f, 30f)

                // label opcode
                textVariable = TextLayout("opcode", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 530f)

                // initialize of opcode
                textVariable = TextLayout(instructionCode.substring(0, 6), font, frc)
                g2d.color = Color.magenta
                textVariable.draw(g2d, 25f, 550f)

                // label address
                textVariable = TextLayout("address", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 95f, 530f)

                textVariable = TextLayout("Instruction", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 480f)

                // initialize of address
                textVariable = TextLayout(instructionCode.substring(6, 32), font, frc)
                g2d.color = Color.orange
                textVariable.draw(g2d, 95f, 550f)

                // instruction mnemonic
                textVariable = TextLayout(opcodeEquivalenceTable[instructionCode.substring(0, 6)], font, frc)
                g2d.color = Color.cyan
                textVariable.draw(g2d, 65f, 500f)

                // instruction immediate
                textVariable = TextLayout("LABEL", font, frc)
                g2d.color = Color.cyan
                textVariable.draw(g2d, 105f, 500f)
            } else if (instructionCode.substring(0, 6).matches("100[0-1][0-1][0-1]".toRegex())) { //load instruction
                textVariable = TextLayout(
                    "LOAD TYPE INSTRUCTION",
                    Font("Verdana", Font.BOLD, 25),
                    frc
                ) // description of the instruction code type for load.
                g2d.color = Color.black
                textVariable.draw(g2d, 280f, 30f)
                // opcode label
                textVariable = TextLayout("opcode", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 530f)

                // initialize of opcode
                textVariable = TextLayout(instructionCode.substring(0, 6), font, frc)
                g2d.color = Color.magenta
                textVariable.draw(g2d, 25f, 550f)

                // rs label
                textVariable = TextLayout("rs", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 90f, 530f)

                // initialize of rs
                textVariable = TextLayout(instructionCode.substring(6, 11), font, frc)
                g2d.color = Color.green
                textVariable.draw(g2d, 90f, 550f)

                // rt label
                textVariable = TextLayout("rt", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 145f, 530f)

                // initialize of rt
                textVariable = TextLayout(instructionCode.substring(11, 16), font, frc)
                g2d.color = Color.blue
                textVariable.draw(g2d, 145f, 550f)

                // rd label
                textVariable = TextLayout("Immediate", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 200f, 530f)

                // initialize of rd
                textVariable = TextLayout(instructionCode.substring(16, 32), font, frc)
                g2d.color = orange
                textVariable.draw(g2d, 200f, 550f)

                // instruction mnemonic
                textVariable = TextLayout("Instruction", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 480f)

                textVariable = TextLayout(opcodeEquivalenceTable[instructionCode.substring(0, 6)], font, frc)
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 25f, 500f)

                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(6, 11)], font, frc)
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 65f, 500f)

                textVariable = TextLayout(
                    buildString {
                        append("M[ ")
                        append(registerEquivalenceTable[instructionCode.substring(16, 21)])
                        append(" + ")
                        append(parseBinToInt(instructionCode.substring(6, 32)))
                        append(" ]")
                    }, font, frc
                )
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 105f, 500f)

                //implement co-processors instruction
            } else if (instructionCode.substring(0, 6).matches("101[0-1][0-1][0-1]".toRegex())) { //store instruction
                textVariable = TextLayout("STORE TYPE INSTRUCTION", Font("Verdana", Font.BOLD, 25), frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 280f, 30f)
                // opcode label
                textVariable = TextLayout("opcode", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 530f)

                // initialize of opcode
                textVariable = TextLayout(instructionCode.substring(0, 6), font, frc)
                g2d.color = Color.magenta
                textVariable.draw(g2d, 25f, 550f)

                // rs label
                textVariable = TextLayout("rs", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 90f, 530f)

                // initialize of rs
                textVariable = TextLayout(instructionCode.substring(6, 11), font, frc)
                g2d.color = Color.green
                textVariable.draw(g2d, 90f, 550f)

                // rt label
                textVariable = TextLayout("rt", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 145f, 530f)

                // initialize of rt
                textVariable = TextLayout(instructionCode.substring(11, 16), font, frc)
                g2d.color = Color.blue
                textVariable.draw(g2d, 145f, 550f)

                // rd label
                textVariable = TextLayout("Immediate", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 200f, 530f)

                // initialize of rd
                textVariable = TextLayout(instructionCode.substring(16, 32), font, frc)
                g2d.color = orange
                textVariable.draw(g2d, 200f, 550f)

                // instruction mnemonic
                textVariable = TextLayout("Instruction", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 480f)

                textVariable = TextLayout(opcodeEquivalenceTable[instructionCode.substring(0, 6)], font, frc)
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 25f, 500f)

                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(6, 11)], font, frc)
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 65f, 500f)

                textVariable = TextLayout(
                    buildString {
                        append("M[ ")
                        append(registerEquivalenceTable[instructionCode.substring(16, 21)])
                        append(" + ")
                        append(parseBinToInt(instructionCode.substring(6, 32)))
                        append(" ]")
                    }, font, frc
                )
                g2d.color = Color.BLACK
                textVariable.draw(g2d, 105f, 500f)
            } else if (instructionCode.substring(0, 6).matches("0100[0-1][0-1]".toRegex())) {
                // TODO: implement co-processors instruction
            } else if (instructionCode.substring(0, 6).matches("0001[0-1][0-1]".toRegex())) { // branch instructions
                textVariable = TextLayout("BRANCH TYPE INSTRUCTION", Font("Verdana", Font.BOLD, 25), frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 250f, 30f)

                // label opcode
                textVariable = TextLayout("opcode", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 440f)

                textVariable = TextLayout("opcode", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 530f)

                // initialize of opcode
                textVariable = TextLayout(instructionCode.substring(0, 6), font, frc)
                g2d.color = Color.magenta
                textVariable.draw(g2d, 25f, 550f)

                // rs label
                textVariable = TextLayout("rs", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 90f, 530f)

                // initialize of rs
                textVariable = TextLayout(instructionCode.substring(6, 11), font, frc)
                g2d.color = Color.green
                textVariable.draw(g2d, 90f, 550f)

                // rt label
                textVariable = TextLayout("rt", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 145f, 530f)

                // initialize of rt
                textVariable = TextLayout(instructionCode.substring(11, 16), font, frc)
                g2d.color = Color.blue
                textVariable.draw(g2d, 145f, 550f)

                // rd label
                textVariable = TextLayout("Immediate", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 200f, 530f)

                // initialize of immediate
                textVariable = TextLayout(instructionCode.substring(16, 32), font, frc)
                g2d.color = Color.cyan
                textVariable.draw(g2d, 200f, 550f)

                // instruction mnemonic
                textVariable = TextLayout("Instruction", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 480f)

                textVariable = TextLayout(opcodeEquivalenceTable[instructionCode.substring(0, 6)], font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 25f, 500f)

                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(6, 11)], font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 105f, 500f)

                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(11, 16)], font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 65f, 500f)

                textVariable = TextLayout(parseBinToInt(instructionCode.substring(16, 32)), font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 155f, 500f)
            } else { // immediate instructions
                textVariable = TextLayout("IMMEDIATE TYPE INSTRUCTION", Font("Verdana", Font.BOLD, 25), frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 250f, 30f)

                // label opcode
                textVariable = TextLayout("opcode", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 530f)

                // initialize of opcode
                textVariable = TextLayout(instructionCode.substring(0, 6), font, frc)
                g2d.color = Color.magenta
                textVariable.draw(g2d, 25f, 550f)

                // rs label
                textVariable = TextLayout("rs", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 90f, 530f)

                // initialize of rs
                textVariable = TextLayout(instructionCode.substring(6, 11), font, frc)
                g2d.color = Color.green
                textVariable.draw(g2d, 90f, 550f)

                // rt label
                textVariable = TextLayout("rt", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 145f, 530f)

                // initialize of rt
                textVariable = TextLayout(instructionCode.substring(11, 16), font, frc)
                g2d.color = Color.blue
                textVariable.draw(g2d, 145f, 550f)

                // rd label
                textVariable = TextLayout("Immediate", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 200f, 530f)

                // initialize of immediate
                textVariable = TextLayout(instructionCode.substring(16, 32), font, frc)
                g2d.color = Color.cyan
                textVariable.draw(g2d, 200f, 550f)

                // instruction mnemonic
                textVariable = TextLayout("Instruction", fontTitle, frc)
                g2d.color = Color.red
                textVariable.draw(g2d, 25f, 480f)
                textVariable = TextLayout(opcodeEquivalenceTable[instructionCode.substring(0, 6)], font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 25f, 500f)

                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(6, 11)], font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 105f, 500f)

                textVariable = TextLayout(registerEquivalenceTable[instructionCode.substring(11, 16)], font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 65f, 500f)

                textVariable = TextLayout(parseBinToInt(instructionCode.substring(16, 32)), font, frc)
                g2d.color = Color.black
                textVariable.draw(g2d, 155f, 500f)
            }

            // Type of control signal labels
            textVariable = TextLayout("Control Signals", fontTitle, frc)
            g2d.color = Color.red
            textVariable.draw(g2d, 25f, 440f)

            textVariable = TextLayout("Active", font, frc)
            g2d.color = Color.red
            textVariable.draw(g2d, 25f, 455f)

            textVariable = TextLayout("Inactive", font, frc)
            g2d.color = Color.gray
            textVariable.draw(g2d, 75f, 455f)

            textVariable = TextLayout(
                "To see details of control units and register bank click inside the functional block",
                font,
                frc
            )
            g2d.color = Color.black
            textVariable.draw(g2d, 400f, 550f)
        }

        fun startAnimation(codeInstruction: String) {
            instructionCode = codeInstruction
            val time = Timer(5, this)
            time.start()
        }

        private fun initImages() {
            val res = javaClass.getResource("${Globals.imagesPath}datapath.png")
            try {
                val im = ImageIO.read(res)
                val transparency = im.colorModel.transparency
                datapath = gc.createCompatibleImage(im.width, im.height, transparency)
                g2d = datapath.createGraphics()
                g2d.drawImage(im, 0, 0, null)
                g2d.dispose()
            } catch (e: IOException) {
                println("Failed to load image $res:\n$e")
            }
        }

        override fun actionPerformed(e: ActionEvent) {
            if (justStarted) justStarted = false
            if (xIsMoving) indexX++
            if (yIsMoving) indexY--
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g2d = g as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // FIXME: Is this reassignment necessary?
            g2d = g
            drawImage(g2d, datapath, 0, 0, null)
            executeAnimation(g)
            counter = (counter + 1) % 100
            g2d.dispose()
        }

        private fun drawImage(g2d: Graphics2D, im: BufferedImage?, x: Int, y: Int, c: Color?) {
            im?.let { g2d.drawImage(im, x, y, this) } ?: run {
                g2d.color = c
                g2d.fillOval(x, y, 20, 20)
                g2d.color = Color.black
                g2d.drawString("   ", x, y)
            }
        }

        fun printTrackLtoR(v: Vertex) {
            val size = v.end - v.init
            val track = IntArray(size) { v.init + it }
            if (v.isActive) {
                v.isFirstInteraction = false
                for (i in 0..<size) {
                    if (track[i] <= v.current) {
                        g2d.color = v.color
                        g2d.fillRect(track[i], v.oppositeAxis, 3, 3)
                    }
                }
                if (v.current == track[size - 1]) v.isActive = false
                v.current++
            } else if (!v.isFirstInteraction) {
                for (i in 0..<size) {
                    g2d.color = v.color
                    g2d.fillRect(track[i], v.oppositeAxis, 3, 3)
                }
            }
        }

        fun printTrackRtoL(v: Vertex) {
            val size = v.init - v.end
            val track = IntArray(size) { v.init - it }
            if (v.isActive) {
                v.isFirstInteraction = false
                for (i in 0..<size) {
                    if (track[i] >= v.current) {
                        g2d.color = v.color
                        g2d.fillRect(track[i], v.oppositeAxis, 3, 3)
                    }
                }
                if (v.current == track[size - 1]) v.isActive = false
                v.current--
            } else if (!v.isFirstInteraction) {
                for (i in 0..<size) {
                    g2d.color = v.color
                    g2d.fillRect(track[i], v.oppositeAxis, 3, 3)
                }
            }
        }

        fun printTrackDtoU(v: Vertex) {
            val size: Int
            val track: IntArray
            if (v.init > v.end) {
                size = v.init - v.end
                track = IntArray(size) { v.init - it }
            } else {
                size = v.end - v.init
                track = IntArray(size) { v.init + it }
            }
            if (v.isActive) {
                v.isFirstInteraction = false
                for (i in 0..<size) {
                    if (track[i] >= v.current) {
                        g2d.color = v.color
                        g2d.fillRect(v.oppositeAxis, track[i], 3, 3)
                    }
                }
                if (v.current == track[size - 1]) v.isActive = false
                v.current--
            } else if (!v.isFirstInteraction) {
                for (i in 0..<size) {
                    g2d.color = v.color
                    g2d.fillRect(v.oppositeAxis, track[i], 3, 3)
                }
            }
        }

        fun printTrackUtoD(v: Vertex) {
            val size = v.end - v.init
            val track = IntArray(size) { v.init + it }
            if (v.isActive) {
                v.isFirstInteraction = false
                for (i in 0..<size) {
                    if (track[i] <= v.current) {
                        g2d.color = v.color
                        g2d.fillRect(v.oppositeAxis, track[i], 3,3 )
                    }
                }
                if (v.current == track[size - 1]) v.isActive = false
                v.current++
            } else if (!v.isFirstInteraction) {
                for (i in 0..<size) {
                    g2d.color = v.color
                    g2d.fillRect(v.oppositeAxis, track[i], 3, 3)
                }
            }
        }

        fun printTextDtoU(v: Vertex) {
            val font = Font("Verdana", Font.BOLD, 13)
            val frc = g2d.fontRenderContext
            var actionInFunctionalBlock = TextLayout(v.name, font, frc)
            g2d.color = Color.RED
            if (instructionCode.substring(0, 6).matches("101[0-1][0-1][0-1]".toRegex()) &&
                !instructionCode.substring(0, 6).matches("0001[0-1][0-1]".toRegex()) &&
                !instructionCode.substring(0, 6).matches("00001[0-1]".toRegex())) {
                actionInFunctionalBlock = TextLayout(" ", font, frc)
            }
            if (v.name == "ALUVALUE") actionInFunctionalBlock = TextLayout(
                if (instructionCode.startsWith("000000"))
                    functionEquivalenceTable[instructionCode.substring(26, 32)]
                else opcodeEquivalenceTable[instructionCode.substring(0, 6)],
                font,
                frc
            )
            if (instructionCode.substring(0, 6).matches("0001[0-1][0-1]".toRegex()) && v.name == "CP+4")
                actionInFunctionalBlock = TextLayout("PC+OFFSET", font, frc)
            if (v.name == "WRITING")
                if (!instructionCode.substring(0, 6).matches("100[0-1][0-1][0-1]".toRegex()))
                    actionInFunctionalBlock = TextLayout(" ", font, frc)
            if (v.isActive) {
                v.isFirstInteraction = false
                actionInFunctionalBlock.draw(g2d, v.oppositeAxis.toFloat(), v.current.toFloat())
                if (v.current == v.end) v.isActive = false
                v.current--
            }
        }

        // TODO: Make sure this functions identically to the original code
        fun parseBinToInt(code: String): String =
            code.toInt(2).toString()

        private fun executeAnimation(g: Graphics) {
            g2d = g as Graphics2D
            setUpInstructionInfo(g2d)
            var vert: Vertex
            for (i in vertexTraversed.indices) {
                vert = vertexTraversed[i]
                if (vert.isMovingXAxis) {
                    if (vert.direction == Vertex.Direction.LEFT) {
                        printTrackLtoR(vert)
                        if (!vert.isActive) {
                            val j = vert.targetVertex.size
                            var tempVertex: Vertex
                            for (k in 0..<j) {
                                tempVertex = outputGraph[vert.numIndex][k]
                                var hasThisVertex = false
                                for (vertex in vertexTraversed) {
                                    if (tempVertex.numIndex == vertex.numIndex) {
                                        hasThisVertex = true
                                        break
                                    }
                                }
                                if (!hasThisVertex) {
                                    outputGraph[vert.numIndex][k].isActive = true
                                    vertexTraversed.add(outputGraph[vert.numIndex][k])
                                }
                            }
                        }
                    } else {
                        printTrackRtoL(vert)
                        if (!vert.isActive) {
                            val j = vert.targetVertex.size
                            var tempVertex: Vertex
                            for (k in 0..<j) {
                                tempVertex = outputGraph[vert.numIndex][k]
                                var hasThisVertex = false
                                for (vertex in vertexTraversed) {
                                    if (tempVertex.numIndex == vertex.numIndex) {
                                        hasThisVertex = true
                                        break
                                    }
                                }
                                if (!hasThisVertex) {
                                    outputGraph[vert.numIndex][k].isActive = true
                                    vertexTraversed.add(outputGraph[vert.numIndex][k])
                                }
                            }
                        }
                    }
                } else {
                    if (vert.direction == Vertex.Direction.DOWN) {
                        if (vert.isText) printTextDtoU(vert)
                        else printTrackDtoU(vert)

                        if (!vert.isActive) {
                            val j = vert.targetVertex.size
                            var tempVertex: Vertex
                            for (k in 0..<j) {
                                tempVertex = outputGraph[vert.numIndex][k]
                                var hasThisVertex = false
                                for (vertex in vertexTraversed) {
                                    if (tempVertex.numIndex == vertex.numIndex) {
                                        hasThisVertex = true
                                        break
                                    }
                                }
                                if (!hasThisVertex) {
                                    outputGraph[vert.numIndex][k].isActive = true
                                    vertexTraversed.add(outputGraph[vert.numIndex][k])
                                }
                            }
                        }
                    } else {
                        printTrackUtoD(vert)
                        if (!vert.isActive) {
                            val j = vert.targetVertex.size
                            var tempVertex: Vertex
                            for (k in 0..<j) {
                                tempVertex = outputGraph[vert.numIndex][k]
                                var hasThisVertex = false
                                for (vertex in vertexTraversed) {
                                    if (tempVertex.numIndex == vertex.numIndex) {
                                        hasThisVertex = true
                                        break
                                    }
                                }
                                if (!hasThisVertex) {
                                    outputGraph[vert.numIndex][k].isActive = true
                                    vertexTraversed.add(outputGraph[vert.numIndex][k])
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun mouseClicked(e: MouseEvent) {
            if (e.point.getX() > 425 && e.point.getX() < 520 && e.point.getY() > 300 && e.point.getY() < 425) {
                buildMainDisplayArea("register.png")
                val fu = FunctionUnitVisualization(instructionBinary, FunctionUnitVisualization.FunctionalUnit.REGISTER)
                fu.run()
            }
            if (e.point.getX() > 355 && e.point.getX() < 415 && e.point.getY() > 180 && e.point.getY() < 280) {
                buildMainDisplayArea("control.png")
                val fu = FunctionUnitVisualization(instructionBinary, FunctionUnitVisualization.FunctionalUnit.CONTROL)
                fu.run()
            }
            if (e.point.getX() > 560 && e.point.getX() < 620 && e.point.getY() > 450 && e.point.getY() < 520) {
                buildMainDisplayArea("ALUcontrol.png")
                val fu =
                    FunctionUnitVisualization(instructionBinary, FunctionUnitVisualization.FunctionalUnit.ALU_CONTROL)
                fu.run()
            }
        }

        override fun mouseEntered(e: MouseEvent) {}
        override fun mouseExited(e: MouseEvent) {}
        override fun mouseReleased(e: MouseEvent) {}
    }
}