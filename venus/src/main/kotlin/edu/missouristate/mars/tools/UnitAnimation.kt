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

@file:Suppress("SameParameterValue", "MemberVisibilityCanBePrivate", "DuplicatedCode")

package edu.missouristate.mars.tools

import edu.missouristate.mars.Globals
import edu.missouristate.mars.tools.FunctionUnitVisualization.FunctionalUnit
import edu.missouristate.mars.tools.FunctionUnitVisualization.FunctionalUnit.*
import edu.missouristate.mars.vectorOf
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.Serial
import java.util.*
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.Timer
import javax.xml.parsers.DocumentBuilderFactory

class UnitAnimation(
    private var instructionCode: String,
    private var datapathTypeUsed: FunctionalUnit
) : JPanel(), ActionListener {
    @Serial
    private val serialVersionUID = -2681757800180958534L

    private val panelWidth = 1000
    private val panelHeight = 574

    private var gc: GraphicsConfiguration =
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    private var counter: Int = 0
    private var justStarted: Boolean = true

    private var xIndex: Int = -1
    private var yIndex: Int = -1
    private var xIsMoving: Boolean = false
    private var yIsMoving: Boolean = false

    private lateinit var outputGraph: Vector<Vector<Vertex>>
    private var vertexList: ArrayList<Vertex> = arrayListOf()
    private lateinit var vertexTraversed: ArrayList<Vertex>

    private var registerEquivalenceTable: HashMap<String, String> = hashMapOf()

    private var cursorInReg: Boolean = false
    private lateinit var g2d: Graphics2D
    private lateinit var datapath: BufferedImage

    init {
        background = Color.white
        preferredSize = Dimension(panelWidth, panelHeight)

        initImages()
        loadHashMapValues()
    }

    fun loadHashMapValues() {
        val (filename, function) = when (datapathTypeUsed) {
            REGISTER -> "/registerDatapath.xml" to ::importXmlDatapathMap
            CONTROL -> "/controlDatapath.xml" to ::importXmlDatapathMap
            ALU_CONTROL -> "/ALUcontrolDatapath.xml" to ::importXmlDatapathMapAluControl
            ALU -> return
        }
        importXmlStringData(filename, registerEquivalenceTable, "register_equivalence", "bits", "mnemonic")
        function.invoke(filename, "datapath_map")
    }

    fun importXmlStringData(xmlName: String, table: HashMap<String, String>, elementTree: String, tagId: String, tagData: String) {
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
                with(datapathMapItem) {
                    indexVertex = getElementsByTagName("num_vertex")
                    name = getElementsByTagName("name")
                    init = getElementsByTagName("init")
                    end = getElementsByTagName("end")
                    color = getElementsByTagName(
                        when {
                            instructionCode.startsWith("000000") -> "color_Rtype"
                            instructionCode.substring(0, 6).matches("00001[0-1]".toRegex()) -> "color_Jtype"
                            instructionCode.substring(0, 6).matches("100[0-1][0-1][0-1]".toRegex()) -> "color_LOADtype"
                            instructionCode.substring(0, 6).matches("101[0-1][0-1][0-1]".toRegex()) -> "color_STOREtype"
                            instructionCode.substring(0, 6).matches("0001[0-1][0-1]".toRegex()) -> "color_BRANCHtype"
                            else -> "color_Itype"
                        }
                    )
                    otherAxis = getElementsByTagName("other_axis")
                    isMovingXAxis = getElementsByTagName("isMovingXaxis")
                    targetVertex = getElementsByTagName("target_vertex")
                    isText = getElementsByTagName("is_text")
                    for (j in 0..<indexVertex.length)
                        vertexList.add(Vertex(
                            indexVertex.item(j).textContent.toInt(),
                            init.item(j).textContent.toInt(),
                            end.item(j).textContent.toInt(),
                            name.item(j).textContent,
                            otherAxis.item(j).textContent.toInt(),
                            isMovingXAxis.item(j).textContent.toBoolean(),
                            color.item(j).textContent,
                            targetVertex.item(j).textContent,
                            isText.item(j).textContent.toBoolean()
                        ))
                }
            }
            outputGraph = vectorOf()
            vertexTraversed = arrayListOf()
            for (vertex in vertexList) {
                val vertexOfTargets = vectorOf<Vertex>()
                for (integer in vertex.targetVertex)
                    vertexOfTargets.add(vertexList[integer])
                outputGraph.add(vertexOfTargets)
            }
            vertexList[0].isActive = true
            vertexTraversed.add(vertexList[0])
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun importXmlDatapathMapAluControl(xmlName: String, elementTree: String) {
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
                with(datapathMapItem) {
                    indexVertex = getElementsByTagName("num_vertex")
                    name = getElementsByTagName("name")
                    init = datapathMapItem.getElementsByTagName("init")
                    end = datapathMapItem.getElementsByTagName("end")
                    color = getElementsByTagName(when {
                        instructionCode.startsWith("000000") -> when (instructionCode.substring(28, 32)) {
                            "0000" -> "ALU_out010"
                            "0010" -> "ALU_out110"
                            "0100" -> "ALU_out000"
                            "0101" -> "ALU_out001"
                            else -> "ALU_out111"
                        }
                        instructionCode.substring(0, 6).matches("00001[0-1]".toRegex()) -> "color_Jtype"
                        instructionCode.substring(0, 6).matches("100[0-1][0-1][0-1]".toRegex()) -> "color_LOADtype"
                        instructionCode.substring(0, 6).matches("101[0-1][0-1][0-1]".toRegex()) -> "color_STOREtype"
                        instructionCode.substring(0, 6).matches("0001[0-1][0-1]".toRegex()) -> "color_BRANCHtype"
                        else -> "color_Itype"
                    })
                    otherAxis = getElementsByTagName("otherAxis")
                    isMovingXAxis = getElementsByTagName("isMovingXaxis")
                    targetVertex = getElementsByTagName("target_vertex")
                    isText = getElementsByTagName("is_text")
                    for (j in 0..<indexVertex.length) vertexList.add(Vertex(
                        indexVertex.item(j).textContent.toInt(),
                        init.item(j).textContent.toInt(),
                        end.item(j).textContent.toInt(),
                        name.item(j).textContent,
                        otherAxis.item(j).textContent.toInt(),
                        isMovingXAxis.item(j).textContent.toBoolean(),
                        color.item(j).textContent,
                        targetVertex.item(j).textContent,
                        isText.item(j).textContent.toBoolean()
                    ))
                }
            }
            outputGraph = vectorOf()
            vertexTraversed = arrayListOf()
            for (vertex in vertexList) {
                val vertexOfTargets = vectorOf<Vertex>()
                for (integer in vertex.targetVertex)
                    vertexOfTargets.add(vertexList[integer])
                outputGraph.add(vertexOfTargets)
            }
            vertexList[0].isActive = true
            vertexTraversed.add(vertexList[0])
       } catch (e: Exception) {
           e.printStackTrace()
       }
    }

    fun startAnimation(codeInstruction: String) {
        instructionCode = codeInstruction
        Timer(8, this).start()
        repaint()
    }

    private fun initImages() {
        val name = when (datapathTypeUsed) {
            REGISTER -> "register.png"
            CONTROL -> "control.png"
            ALU_CONTROL -> "ALUcontrol.png"
            else -> "alu.png"
        }
        try {
            val im = ImageIO.read(javaClass.getResource("${Globals.imagesPath}$name"))
            val transparency = im.colorModel.transparency
            datapath = gc.createCompatibleImage(im.width, im.height, transparency)
            g2d = datapath.createGraphics()
            g2d.drawImage(im, 0, 0, null)
            g2d.dispose()
        } catch (e: IOException) {
            println("Image loading error for $name:\n$e")
        }
    }

    fun updateDisplay() = repaint()

    override fun actionPerformed(e: ActionEvent) {
        if (justStarted) justStarted = false
        if (xIsMoving) xIndex++
        if (yIsMoving) yIndex--
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        // TODO: uhh what is this?
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

    // TODO: Make sure this functions identically to the original code
    fun parseBinToInt(code: String): String =
        code.toInt(2).toString()

    private fun executeAnimation(g: Graphics) {
        g2d = g as Graphics2D
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
                    if (!vert.isText) printTrackDtoU(vert)
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
}