package edu.missouristate.mars.tools;//.bhtsim;

/**
 * Represents a single entry of the Branch History Table.
 * <p>
 * The entry holds the information about former branch predictions and outcomes.
 * The number of past branch outcomes can be configured and is called the history.
 * The semantics of the history of size <i>n</i> is as follows.
 * The entry will change its prediction, if it mispredicts the branch <i>n</i> times in series.
 * The prediction of the entry can be obtained by the {@link BHTEntry#getPrediction()} method.
 * Feedback of taken or not taken branches is provided to the entry via the {@link BHTEntry#updatePrediction(boolean)} method.
 * This causes the history and the prediction to be updated.
 * <p>
 * Additionally the entry keeps track about how many times the prediction was correct or incorrect.
 * The statistics can be obtained by the methods {@link BHTEntry#getStatsPredCorrect()}, {@link BHTEntry#getStatsPredIncorrect()} and {@link BHTEntry#getStatsPredPrecision()}.
 *
 * @author ingo.kofler@itec.uni-klu.ac.at
 */
public class BHTEntry {

    /**
     * the history of the BHT entry. Each boolean value signals if the branch was taken or not. The value at index n-1 represents the most recent branch outcome.
     */
    private final boolean[] history;

    /**
     * the current prediction
     */
    private boolean prediction;

    /**
     * absolute number of incorrect predictions
     */
    private int incorrect;

    /**
     * absolute number of correct predictions
     */
    private int correct;

    /**
     * Constructs a BHT entry with a given history size.
     * <p>
     * The size of the history can only be set via the constructor and cannot be changed afterwards.
     *
     * @param historySize number of past branch outcomes to remember
     * @param initVal     the initial value of the entry (take or do not take)
     */
    public BHTEntry(int historySize, boolean initVal) {
        prediction = initVal;
        history = new boolean[historySize];

        for (int i = 0; i < historySize; i++) {
            history[i] = initVal;
        }
        correct = incorrect = 0;
    }

    /**
     * Returns the branch prediction based on the history.
     *
     * @return true if prediction is to take the branch, false otherwise
     */
    public boolean getPrediction() {
        return prediction;
    }

    /**
     * Updates the entry's history and prediction.
     * This method provides feedback for a prediction.
     * The history and the statistics are updated accordingly.
     * Based on the updated history a new prediction is calculated
     *
     * @param branchTaken signals if the branch was taken (true) or not (false)
     */
    public void updatePrediction(boolean branchTaken) {

        // update history
        for (int i = 0; i < history.length - 1; i++) {
            history[i] = history[i + 1];
        }
        history[history.length - 1] = branchTaken;

        // if the prediction was correct, update stats and keep prediction
        if (branchTaken == prediction) {
            correct++;
        } else {
            incorrect++;

            // check if the prediction should change
            boolean changePrediction = true;

            for (boolean b : history) {
                if (b != branchTaken) {
                    changePrediction = false;
                    break;
                }
            }

            if (changePrediction) prediction = !prediction;

        }
    }

    /**
     * Get the absolute number of mispredictions.
     *
     * @return number of incorrect predictions (mispredictions)
     */
    public int getStatsPredIncorrect() {
        return incorrect;
    }

    /**
     * Get the absolute number of correct predictions.
     *
     * @return number of correct predictions
     */
    public int getStatsPredCorrect() {
        return correct;
    }

    /**
     * Get the percentage of correct predictions.
     *
     * @return the percentage of correct predictions
     */
    public double getStatsPredPrecision() {
        int sum = incorrect + correct;
        return (sum == 0) ? 0 : correct * 100.0 / sum;
    }

    /***
     * Builds a string representation of the BHT entry's history.
     * The history is a sequence of flags that signal if the branch was taken (T) or not taken (NT).
     *
     * @return a string representation of the BHT entry's history
     */
    public String getHistoryAsStr() {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < history.length; i++) {
            if (i > 0) result.append(", ");
            result.append(history[i] ? "T" : "NT");
        }
        return result.toString();
    }

    /***
     * Returns a string representation of the BHT entry's current prediction.
     * The prediction can be either to TAKE or do NOT TAKE the branch.
     *
     * @return a string representation of the BHT entry's current prediction
     */
    public String getPredictionAsStr() {
        return prediction ? "TAKE" : "NOT TAKE";
    }
}
