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

package edu.missouristate.mars.tools

/**
 * Represents a single entry of the Branch History Table.
 *
 * The entry holds the information about former branch predictions and outcomes.
 * The number of past branch outcomes can be configured and is called the history.
 * The semantics of the history (size *i*) is as follows.
 * The entry will change its prediction if it mispredicts the branch *n* times in series.
 * The prediction of the entry can be obtained by the [predictionRatio] method.
 * Feedback of taken or not taken branches is provided to the entry via the [updatePrediction] method.
 * This causes the history and the prediction to be updated.
 *
 * Additionally, the entry keeps track about how many times the prediction was correct or incorrect.
 * The statistics can be obtained by the methods [correctPredictions], [incorrectPredictions] and
 * [predictionRatio].
 *
 * @param historySize The number of past branch outcomes to remember.
 * @param initVal The initial value of the entry (take or don't take).
 */
class BHTEntry(historySize: Int, initVal: Boolean) {
    /**
     * The history of the BHT entry. The boolean values signal if the branch was taken or not.
     * The value at index `n - 1` represents the most recent branch outcome.
     */
    private val history = BooleanArray(historySize) { initVal }

    /** The current prediction. */
    var prediction: Boolean = initVal
        private set

    /** Absolute number of incorrect predictions. */
    var incorrectPredictions: Int = 0
        private set

    /** Absolute number of correct predictions. */
    var correctPredictions: Int = 0
        private set

    /**
     * Updates the entry's history and prediction.
     * This method provides feedback for a prediction.
     * The history and statistics are updated accordingly.
     * Based on the updated history, a new prediction is calculated.
     *
     * @param branchTaken Signals if the branch was taken (true) or not (false)
     */
    fun updatePrediction(branchTaken: Boolean) {
        // Update history
        for (i in 0..<(history.size - 1))
            history[i] = history[i + 1]
        history[history.size - 1] = branchTaken
        // If the prediction was correct, update stats and keep prediction
        if (branchTaken == prediction) correctPredictions++ else {
            incorrectPredictions++
            // Check if the prediction should change
            var changePrediction = true
            for (b in history) {
                if (b != branchTaken) {
                    changePrediction = false
                    break
                }
            }
            if (changePrediction) prediction = !prediction
        }
    }

    /**
     * Get the number of incorrect predictions.
     */
    @Deprecated(
        "Use incorrectPredictions instead.",
        ReplaceWith("incorrectPredictions"),
        DeprecationLevel.ERROR
    )
    fun getStatsPredIncorrect() = incorrectPredictions

    /**
     * Get the number of correct predictions.
     */
    @Deprecated(
        "Use correctPredictions instead.",
        ReplaceWith("correctPredictions"),
        DeprecationLevel.ERROR
    )
    fun getStatsPredCorrect() = correctPredictions

    val predictionRatio: Double
        get() {
            val sum = incorrectPredictions + correctPredictions
            return if (sum == 0) 0.0 else correctPredictions * 100.0 / sum
        }

    @Deprecated(
        "Use predictionRatio instead.",
        ReplaceWith("predictionRatio"),
        DeprecationLevel.ERROR
    )
    fun getStatsPredPrecision(): Double = predictionRatio

    val predictionHistory: String get() = buildString {
        for (i in history.indices) {
            if (i > 0) append(", ")
            append(if (history[i]) "T" else "NT")
        }
    }

    @Deprecated(
        "Use predictionHistory instead.",
        ReplaceWith("predictionHistory"),
        DeprecationLevel.ERROR
    )
    fun getHistoryAsStr() = predictionHistory

    val currentPrediction: String get() = if (prediction) "Taken" else "Not Taken"

    @Deprecated(
        "Use currentPrediction instead.",
        ReplaceWith("currentPrediction"),
        DeprecationLevel.ERROR
    )
    fun getPredictionAsStr(): String = toString()

    override fun toString() = currentPrediction
}