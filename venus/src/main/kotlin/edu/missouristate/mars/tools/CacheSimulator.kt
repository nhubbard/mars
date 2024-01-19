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

@file:Suppress("DEPRECATION")

package edu.missouristate.mars.tools

import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.util.Binary
import java.awt.*
import java.awt.event.ItemEvent
import java.util.*
import javax.swing.*
import javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
import javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder
import kotlin.math.roundToInt

class CacheSimulator @JvmOverloads constructor(
    title: String = "$MARS_NAME, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private var debug = false
        private const val VERSION = "Version 1.2"
        private const val HEADING = "Simulate and illustrate data cache performance"
        private const val MARS_NAME = "Data Cache Simulation Tool"
        private const val STANDALONE_NAME = "Data Cache Simulator Stand-Alone"

        /**
         * Main provided for pure stand-alone use.  Recommended stand-alone use is to write a
         * driver program that instantiates a CacheSimulator object then invokes its go() method.
         * "stand-alone" means it is not invoked from the MARS Tools menu.  "Pure" means there
         * is no driver program to invoke the Cache Simulator.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            CacheSimulator("$STANDALONE_NAME, $VERSION", HEADING).go()
        }
    }

    private lateinit var cacheBlockSizeSelector: JComboBox<String>
    private lateinit var cacheBlockCountSelector: JComboBox<String>
    private lateinit var cachePlacementSelector: JComboBox<String>
    private lateinit var cacheReplacementSelector: JComboBox<String>
    private lateinit var cacheSetSizeSelector: JComboBox<String>

    private lateinit var memoryAccessCountDisplay: JTextField
    private lateinit var cacheHitCountDisplay: JTextField
    private lateinit var cacheMissCountDisplay: JTextField
    private lateinit var replacementPolicyDisplay: JTextField
    private lateinit var cacheableAddressesDisplay: JTextField
    private lateinit var cacheSizeDisplay: JTextField

    private lateinit var cacheHitRateDisplay: JProgressBar
    private lateinit var animations: Animation

    private lateinit var logPanel: JPanel
    private lateinit var logText: JTextArea

    private val emptyBorder = EmptyBorder(4, 4, 4, 4)
    private val countFonts = Font("Times", Font.BOLD, 12)
    private val backgroundColor = Color.WHITE

    private lateinit var cacheBlockSizeChoicesInt: IntArray
    private lateinit var cacheBlockCountChoicesInt: IntArray
    private val cacheBlockSizeChoices = arrayOf("1", "2", "4", "8", "16", "32", "64", "128", "256", "512", "1024", "2048")
    private val cacheBlockCountChoices = arrayOf("1", "2", "4", "8", "16", "32", "64", "128", "256", "512", "1024", "2048")
    private val placementPolicyChoices = arrayOf("Direct Mapping", "Fully Associative", "N-way Set Associative")

    private enum class PlacementPolicy(val rawValue: Int) {
        DIRECT(0), FULL(1), SET(2);

        companion object {
            @JvmStatic
            fun fromInt(rawValue: Int) = entries.firstOrNull { it.rawValue == rawValue } ?: FULL
        }
    }

    private val replacementPolicyChoices = arrayOf("LRU", "Random")

    private enum class ReplacementPolicy(val rawValue: Int) {
        LRU(0), RANDOM(1);

        companion object {
            @JvmStatic
            fun fromInt(rawValue: Int) = entries.firstOrNull { it.rawValue == rawValue } ?: LRU
        }
    }

    private lateinit var cacheSetSizeChoices: Array<String>
    private val defaultCacheBlockCountIndex = 3
    private val defaultPlacementPolicyIndex = PlacementPolicy.DIRECT

    private lateinit var theCache: AbstractCache

    private var memoryAccessCount = -1
    private var cacheHitCount = -1
    private var cacheMissCount = -1

    private var cacheHitRate = 0.0

    private val random = Random(0)

    /**
     * Required MarsTool method to return Tool name.
     *
     * @return Tool name.  MARS will display this in menu item.
     */
    override val toolName = "Data Cache Simulator"

    /**
     * Method that constructs the main cache simulator display area.  It is organized vertically
     * into three major components: the cache configuration which can be modified
     * using combo boxes, the cache performance which is updated as the
     * attached MIPS program executes, and the runtime log which is optionally used
     * to display log of each cache access.
     *
     * @return the GUI component containing these three areas
     */
    override fun buildMainDisplayArea(): JComponent {
        val results = Box.createVerticalBox()
        results.add(buildOrganizationArea())
        results.add(buildPerformanceArea())
        results.add(buildLogArea())
        return results
    }

    private fun buildLogArea(): JComponent {
        logPanel = JPanel()

        val ltb = TitledBorder("Runtime Log")
        ltb.titleJustification = TitledBorder.CENTER
        logPanel.border = ltb

        val logShow = JCheckBox("Enabled", debug)
        logShow.addItemListener {
            debug = it.stateChange == ItemEvent.SELECTED
            resetLogDisplay()
            logText.isEnabled = debug
            logText.background = if (debug) Color.WHITE else logPanel.background
        }
        logPanel.add(logShow)

        logText = JTextArea(5, 70)
        logText.isEnabled = debug
        logText.background = if (debug) Color.WHITE else logPanel.background
        logText.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        logText.toolTipText = "Displays cache activity log if enabled"

        val logScroll = JScrollPane(logText, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED)
        logPanel.add(logScroll)

        return logPanel
    }

    private fun buildOrganizationArea(): JComponent {
        val organization = JPanel(GridLayout(3, 2))

        val otb = TitledBorder("Cache Organization")
        otb.titleJustification = TitledBorder.CENTER
        organization.border = otb

        cachePlacementSelector = JComboBox(placementPolicyChoices).apply {
            isEditable = false
            background = backgroundColor
            selectedIndex = defaultPlacementPolicyIndex.rawValue
            addActionListener {
                updateCacheSetSizeSelector()
                reset()
            }
        }

        cacheReplacementSelector = JComboBox(replacementPolicyChoices).apply {
            isEditable = false
            background = backgroundColor
            selectedIndex = ReplacementPolicy.LRU.rawValue
        }

        cacheBlockSizeSelector = JComboBox(cacheBlockSizeChoices).apply {
            isEditable = false
            background = backgroundColor
            selectedIndex = 2
            addActionListener {
                updateCacheSizeDisplay()
                reset()
            }
        }

        cacheBlockCountSelector = JComboBox(cacheBlockCountChoices).apply {
            isEditable = false
            background = backgroundColor
            selectedIndex = defaultCacheBlockCountIndex
            addActionListener {
                updateCacheSetSizeSelector()
                theCache = createNewCache()
                resetCounts()
                updateDisplay()
                updateCacheSizeDisplay()
                animations.fillAnimationBoxWithCacheBlocks()
            }
        }

        cacheSetSizeSelector = JComboBox(cacheSetSizeChoices).apply {
            isEditable = false
            background = backgroundColor
            selectedIndex = 0
            addActionListener { reset() }
        }

        // ALL COMPONENTS FOR "CACHE ORGANIZATION" SECTION
        val placementPolicyRow = getPanelWithBorderLayout().apply {
            border = emptyBorder
            add(JLabel("Placement Policy "), BorderLayout.WEST)
            add(cachePlacementSelector, BorderLayout.EAST)
        }

        val replacementPolicyRow = getPanelWithBorderLayout().apply {
            border = emptyBorder
            add(JLabel("Block Replacement Policy "), BorderLayout.WEST)
            add(cacheReplacementSelector, BorderLayout.EAST)
        }

        val cacheSetSizeRow = getPanelWithBorderLayout().apply {
            border = emptyBorder
            add(JLabel("Set size (blocks) "), BorderLayout.WEST)
            add(cacheSetSizeSelector, BorderLayout.EAST)
        }

        val cacheNumberBlocksRow = getPanelWithBorderLayout().apply {
            border = emptyBorder
            add(JLabel("Number of blocks "), BorderLayout.WEST)
            add(cacheBlockCountSelector, BorderLayout.EAST)
        }

        val cacheBlockSizeRow = getPanelWithBorderLayout().apply {
            border = emptyBorder
            add(JLabel("Cache block size (words) "), BorderLayout.WEST)
            add(cacheBlockSizeSelector, BorderLayout.EAST)
        }

        cacheSizeDisplay = JTextField(8).apply {
            horizontalAlignment = JTextField.RIGHT
            isEditable = false
            background = backgroundColor
            font = countFonts
        }
        val cacheTotalSizeRow = getPanelWithBorderLayout().apply {
            border = emptyBorder
            add(JLabel("Cache size (bytes) "), BorderLayout.WEST)
            add(cacheSizeDisplay, BorderLayout.EAST)
        }
        updateCacheSizeDisplay()

        // Lay 'em out in the grid...
        organization.apply {
            add(placementPolicyRow)
            add(cacheNumberBlocksRow)
            add(replacementPolicyRow)
            add(cacheBlockSizeRow)
            add(cacheSetSizeRow)
            add(cacheTotalSizeRow)
        }

        return organization
    }

    private fun buildPerformanceArea(): JComponent {
        val performance = JPanel(GridLayout(1, 2))

        val ptb = TitledBorder("Cache Performance")
        ptb.titleJustification = TitledBorder.CENTER
        performance.border = ptb

        memoryAccessCountDisplay = createTextField()
        val memoryAccessCountRow = createLabelRow("Memory Access Count ", memoryAccessCountDisplay)

        cacheHitCountDisplay = createTextField()
        val cacheHitCountRow = createLabelRow("Cache Hit Count ", cacheHitCountDisplay)

        cacheMissCountDisplay = createTextField()
        val cacheMissCountRow = createLabelRow("Cache Miss Count ", cacheMissCountDisplay)

        cacheHitRateDisplay = JProgressBar(JProgressBar.HORIZONTAL, 0, 100).apply {
            isStringPainted = true
            foreground = Color.BLUE
            background = backgroundColor
            font = countFonts
        }
        val cacheHitRateRow = createLabelRow("Cache Hit Rate ", cacheHitRateDisplay)

        resetCounts()
        updateDisplay()

        val performanceMeasures = JPanel(GridLayout(4, 1))
        performanceMeasures.add(memoryAccessCountRow)
        performanceMeasures.add(cacheHitCountRow)
        performanceMeasures.add(cacheMissCountRow)
        performanceMeasures.add(cacheHitRateRow)

        performance.add(performanceMeasures)

        // Create animation
        animations = Animation()
        animations.fillAnimationBoxWithCacheBlocks()

        val animationsPanel = JPanel(GridLayout(1, 2))

        val animationLabel = Box.createVerticalBox()

        val tableTitle1 = JPanel(FlowLayout(FlowLayout.LEFT))
        tableTitle1.add(JLabel("Cache Block Table"))

        val tableTitle2 = JPanel(FlowLayout(FlowLayout.LEFT))
        tableTitle2.add(JLabel("(block 0 at top)"))

        animationLabel.add(tableTitle1)
        animationLabel.add(tableTitle2)

        val colorKeyBoxSize = Dimension(8, 8)

        val emptyBox = JPanel().apply {
            size = colorKeyBoxSize
            background = animations.defaultColor
            border = BorderFactory.createLineBorder(Color.BLACK)
        }
        val emptyKey = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(emptyBox)
            add(JLabel(" = empty"))
        }

        val missBox = JPanel().apply {
            size = colorKeyBoxSize
            background = animations.missColor
            border = BorderFactory.createLineBorder(Color.BLACK)
        }
        val missKey = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(missBox)
            add(JLabel(" = miss"))
        }

        val hitBox = JPanel().apply {
            size = colorKeyBoxSize
            background = animations.hitColor
            border = BorderFactory.createLineBorder(Color.BLACK)
        }
        val hitKey = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(hitBox)
            add(JLabel(" = hit"))
        }

        animationLabel.apply {
            add(emptyKey)
            add(hitKey)
            add(missKey)
            add(Box.createVerticalGlue())
        }
        animationsPanel.apply {
            add(animationLabel)
            add(animations.animationBox)
        }

        performance.add(animationsPanel)

        return performance
    }

    private fun createTextField(): JTextField =
        JTextField(10).apply {
            horizontalAlignment = JTextField.RIGHT
            isEditable = false
            background = backgroundColor
            font = countFonts
        }

    private fun createLabelRow(label: String, textField: JComponent): JPanel {
        val row = getPanelWithBorderLayout()
        row.border = emptyBorder
        row.add(JLabel(label), BorderLayout.WEST)
        row.add(textField, BorderLayout.EAST)
        return row
    }

    /**
     * Apply caching policies and update display when connected MIPS program accesses (data) memory.
     *
     * @param resource       the attached memory
     * @param notice information provided by memory in MemoryAccessNotice object
     */
    override fun processMipsUpdate(resource: Observable, notice: AccessNotice) {
        if (notice !is MemoryAccessNotice) return
        memoryAccessCount++
        val cacheAccessResult = theCache.isItAHitThenReadOnMiss(notice.address)
        if (cacheAccessResult.isHit) {
            cacheHitCount++
            animations.showHit(cacheAccessResult.block)
        } else {
            cacheMissCount++
            animations.showMiss(cacheAccessResult.block)
        }
        cacheHitRate = cacheHitCount / memoryAccessCount.toDouble()
    }

    /**
     * Initialize all JComboBox choice structures not already initialized at declaration.
     * Also creates the initial default cache object. Overrides inherited method that does nothing.
     */
    override fun initializePreGUI() {
        cacheBlockSizeChoicesInt = IntArray(cacheBlockSizeChoices.size) {
            cacheBlockSizeChoices[it].toIntOrNull() ?: 1
        }
        cacheBlockCountChoicesInt = IntArray(cacheBlockCountChoices.size) {
            cacheBlockCountChoices[it].toIntOrNull() ?: 1
        }
        cacheSetSizeChoices = determineSetSizeChoices(defaultCacheBlockCountIndex, defaultPlacementPolicyIndex.rawValue)
    }

    /**
     * The only post-GUI initialization is to create the initial cache object based on the default settings
     * of the various combo boxes. Overrides inherited method that does nothing.
     */
    override fun initializePostGUI() {
        theCache = createNewCache()
    }

    /**
     * Method to reset cache, counters and display when the Reset button selected.
     * Overrides inherited method that does nothing.
     */
    override fun reset() {
        theCache = createNewCache()
        resetCounts()
        updateDisplay()
        animations.reset()
        resetLogDisplay()
    }

    /**
     * Updates display immediately after each update (AccessNotice) is processed, after
     * cache configuration changes as needed, and after each execution step when Mars
     * is running in timed mode.  Overrides inherited method that does nothing.
     */
    override fun updateDisplay() {
        updateMemoryAccessCountDisplay()
        updateCacheHitCountDisplay()
        updateCacheMissCountDisplay()
        updateCacheHitRateDisplay()
    }

    private fun determineSetSizeChoices(cacheBlockCountIndex: Int, placementPolicyIndex: Int): Array<String> {
        val choices: Array<String>
        val firstBlockCountIndex = 0

        when (PlacementPolicy.fromInt(placementPolicyIndex)) {
            PlacementPolicy.DIRECT -> choices = arrayOf(cacheBlockCountChoices[firstBlockCountIndex])
            PlacementPolicy.SET -> {
                choices = Array(cacheBlockCountIndex - firstBlockCountIndex + 1) { "" }
                System.arraycopy(cacheBlockCountIndex, firstBlockCountIndex, choices, 0, choices.size)
            }
            PlacementPolicy.FULL -> choices = arrayOf(cacheBlockCountChoices[cacheBlockCountIndex])
        }
        return choices
    }

    private fun updateCacheSetSizeSelector() {
        cacheSetSizeSelector.model = DefaultComboBoxModel(determineSetSizeChoices(
            cacheBlockCountSelector.selectedIndex,
            cachePlacementSelector.selectedIndex
        ))
    }

    private fun createNewCache(): AbstractCache = AnyCache(
        cacheBlockCountChoicesInt[cacheBlockCountSelector.selectedIndex],
        cacheBlockSizeChoicesInt[cacheBlockSizeSelector.selectedIndex],
        cacheSetSizeSelector.selectedItem?.toString()?.toIntOrNull() ?: 1
    )

    private fun resetCounts() {
        memoryAccessCount = 0
        cacheHitCount = 0
        cacheMissCount = 0
        cacheHitRate = 0.0
    }

    private fun updateMemoryAccessCountDisplay() {
        memoryAccessCountDisplay.text = memoryAccessCount.toString()
    }

    private fun updateCacheHitCountDisplay() {
        cacheHitCountDisplay.text = cacheHitCount.toString()
    }

    private fun updateCacheMissCountDisplay() {
        cacheMissCountDisplay.text = cacheMissCount.toString()
    }

    private fun updateCacheHitRateDisplay() {
        cacheHitRateDisplay.value = (cacheHitRate * 100).roundToInt()
    }

    private fun updateCacheSizeDisplay() {
        val cacheSize = cacheBlockSizeChoicesInt[cacheBlockSizeSelector.selectedIndex] * cacheBlockCountChoicesInt[cacheBlockCountSelector.selectedIndex] * Memory.WORD_LENGTH_BYTES
        cacheSizeDisplay.text = cacheSize.toString()
    }

    private fun getPanelWithBorderLayout() = JPanel(BorderLayout(2, 2))

    private fun resetLogDisplay() {
        logText.text = ""
    }

    private fun writeLog(text: String) {
        logText.append(text)
        logText.caretPosition = logText.document.length
    }

    /**
     * Represents a block in the cache.
     * Since we are only simulating cache performance, there's no need to actually store memory contents.
     */
    @Suppress("UNUSED_PARAMETER")
    private class CacheBlock(sizeInWords: Int) {
        var valid: Boolean = false
        var tag: Int = 0
        var mostRecentAccessTime: Int = -1
    }

    /**
     * Represents the outcome of accessing the cache. There are two parts:
     * whether it was a hit or not, and in which block is the value stored.
     * In the case of a hit, the block associated with address. In the case of
     * a miss, the block where new association is made.
     */
    private data class CacheAccessResult(val isHit: Boolean, val block: Int)

    /** Abstract cache class. Subclasses will implement specific policies. */
    private abstract class AbstractCache protected constructor(
        val numberOfBlocks: Int,
        val blockSizeInWords: Int,
        val setSizeInBlocks: Int
    ) {
        val numberOfSets: Int = numberOfBlocks / setSizeInBlocks
        val blocks: Array<CacheBlock> = Array(numberOfBlocks) { CacheBlock(blockSizeInWords) }

        init {
            reset()
        }

        val cacheSizeInWords: Int get() = numberOfBlocks * blockSizeInWords
        val cacheSizeInBytes: Int get() = numberOfBlocks * blockSizeInWords * Memory.WORD_LENGTH_BYTES

        fun getSetNumber(address: Int): Int =
            address / Memory.WORD_LENGTH_BYTES / blockSizeInWords % numberOfSets

        fun getTag(address: Int): Int =
            address / Memory.WORD_LENGTH_BYTES / blockSizeInWords / numberOfSets

        fun getFirstBlockToSearch(address: Int) = getSetNumber(address) * setSizeInBlocks

        fun getLastBlockToSearch(address: Int) = getFirstBlockToSearch(address) * setSizeInBlocks - 1

        fun reset() {
            for (i in 0..<numberOfBlocks) blocks[i] = CacheBlock(blockSizeInWords)
            System.gc()
        }

        // FIXME: This is an abomination of a name!
        abstract fun isItAHitThenReadOnMiss(address: Int): CacheAccessResult
    }

    /**
     * Implements any of the well-known cache organizations.  Physical memory
     * address is partitioned depending on organization:
     *    Direct Mapping:    [ tag | block | word | byte ]
     *    Fully Associative: [ tag | word | byte ]
     *    Set Associative:   [ tag | set | word | byte ]
     *
     * Bit lengths of each part are determined as follows:
     * Direct Mapping:
     *   byte  = log2 of #bytes in a word (typically 4)
     *   word  = log2 of #words in a block
     *   block = log2 of #blocks in the cache
     *   tag   = #bytes in address - (byte+word+block)
     * Fully Associative:
     *   byte  = log2 of #bytes in a word (typically 4)
     *   word  = log2 of #words in a block
     *   tag   = #bytes in address - (byte+word)
     * Set Associative:
     *   byte  = log2 of #bytes in a word (typically 4)
     *   word  = log2 of #words in a block
     *   set   = log2 of #sets in the cache
     *   tag   = #bytes in address - (byte+word+set)
     *
     * Direct Mapping (one way set associative):
     * The block value for a given address identifies its block index into the cache.
     * That is why it is called "direct mapped." This is the only cache block it can
     * occupy. If that cache block is empty or if it is occupied by a different tag,
     * this is a MISS. If the same tag occupies that cache block, this is a HIT.
     * There is no replacement policy; upon a cache miss of an occupied block, the old
     * block is written out (unless write-through is enabled), and the new one is read in.
     * Those actions are not simulated here.
     *
     * Fully Associative:
     * There is one set, and every tag has to be searched before determining hit or miss.
     * If the tag is matched, it is a hit. If the tag isn't matched, and there is at least one
     * empty block, it is a miss and the new tag will occupy it. If the tag isn't matched
     * and every block is occupied, it is a miss, and one of the occupied blocks will be
     * selected for removal, and the new tag will replace it.
     *
     * n-way Set Associative:
     * Each set consists of n blocks, and the number of sets in the cache is total number
     * of blocks divided by n. The set bits in the address will identify which set to
     * search, and every tag in that set has to be searched before determining hit or miss.
     * If the tag is matched, it is a hit. If the tag is not matched and there is at least one
     * empty block, it is a miss and the new tag will occupy it. If the tag is not matched
     * and every block is occupied, it is a miss, and one of the occupied blocks will be
     * selected for removal, and the new tag will replace it.
     */
    private inner class AnyCache(
        numberOfBlocks: Int,
        blockSizeInWords: Int,
        setSizeInBlocks: Int
    ) : AbstractCache(numberOfBlocks, blockSizeInWords, setSizeInBlocks) {
        override fun isItAHitThenReadOnMiss(address: Int): CacheAccessResult {
            var result = 0 // Set full
            val firstBlock = getFirstBlockToSearch(address)
            val lastBlock = getLastBlockToSearch(address)
            if (debug) writeLog("($memoryAccessCount) address: ${Binary.intToHexString(address)} (tag " +
                "${Binary.intToHexString(getTag(address))}) block range: $firstBlock-$lastBlock\n")
            var block: CacheBlock
            var blockNumber = firstBlock
            while (blockNumber in firstBlock..lastBlock) {
                block = blocks[blockNumber]
                if (debug) writeLog("   Trying block $blockNumber ${if (block.valid) " tag ${Binary.intToHexString(block.tag)}" else " empty"}")
                if (block.valid && block.tag == getTag(address)) {
                    if (debug) writeLog(" -- HIT\n")
                    result = 1 // Hit
                    block.mostRecentAccessTime = memoryAccessCount
                    break
                }
                // Miss
                if (!block.valid) {
                    if (debug) writeLog(" -- MISS\n")
                    result = 2 // Miss
                    block.valid = true
                    block.tag = getTag(address)
                    block.mostRecentAccessTime = memoryAccessCount
                    break
                }
                if (debug) writeLog(" -- OCCUPIED\n")
                blockNumber++
            }
            // If the result is set full...
            if (result == 0) {
                if (debug) writeLog("   MISS due to FULL SET")
                val blockToReplace = selectBlockToReplace(firstBlock, lastBlock)
                block = blocks[blockToReplace]
                block.tag = getTag(address)
                block.mostRecentAccessTime = memoryAccessCount
                blockNumber = blockToReplace
            }
            return CacheAccessResult(result == 1, blockNumber)
        }

        private fun selectBlockToReplace(first: Int, last: Int): Int {
            var replaceBlock = first
            if (first != last) {
                when (ReplacementPolicy.fromInt(cacheReplacementSelector.selectedIndex)) {
                    ReplacementPolicy.RANDOM -> {
                        replaceBlock = first + random.nextInt(last - first + 1)
                        if (debug) writeLog(" -- Random replace block $replaceBlock\n")
                    }
                    ReplacementPolicy.LRU -> {
                        var leastRecentAccessTime = memoryAccessCount
                        var block = first
                        while (block <= last) {
                            if (blocks[block].mostRecentAccessTime < leastRecentAccessTime) {
                                leastRecentAccessTime = blocks[block].mostRecentAccessTime
                                replaceBlock = block
                            }
                            block++
                        }
                        if (debug)
                            writeLog(" -- LRU replace block $replaceBlock; unused since $leastRecentAccessTime\n")
                    }
                }
            }
            return replaceBlock
        }
    }

    /** Display animated cache. */
    private inner class Animation {
        val animationBox: Box = Box.createVerticalBox()
        var blocks: Array<JTextField>? = null

        val hitColor: Color = Color.GREEN
        val missColor: Color = Color.RED
        val defaultColor: Color = Color.WHITE

        val numberOfBlocks: Int get() = blocks?.size ?: 0

        fun showHit(blockNumber: Int) {
            blocks?.get(blockNumber)?.background = hitColor
        }

        fun showMiss(blockNumber: Int) {
            blocks?.get(blockNumber)?.background = missColor
        }

        fun reset() {
            blocks?.forEach { it.background = defaultColor }
        }

        fun fillAnimationBoxWithCacheBlocks() {
            animationBox.isVisible = false
            animationBox.removeAll()
            val numberOfBlocks = cacheBlockCountChoicesInt[cacheBlockCountSelector.selectedIndex]
            val totalVerticalPixels = 128
            val blockPixelHeight = if (numberOfBlocks > totalVerticalPixels) 1 else totalVerticalPixels / numberOfBlocks
            val blockPixelWidth = 40
            val blockDimension = Dimension(blockPixelWidth, blockPixelHeight)
            blocks = Array(numberOfBlocks) {
                JTextField().apply {
                    isEditable = false
                    background = defaultColor
                    size = blockDimension
                    preferredSize = blockDimension
                }
            }
            for (block in blocks!!) animationBox.add(block)
            animationBox.repaint()
            animationBox.isVisible = true
        }
    }
}