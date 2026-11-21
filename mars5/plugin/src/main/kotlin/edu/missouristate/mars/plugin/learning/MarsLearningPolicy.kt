package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Shareable course settings, separate from personal progress and private grading authority. */
@Service(Service.Level.PROJECT)
@State(name = "MarsLearningPolicy", storages = [Storage("mars-learning.xml")])
internal class MarsLearningPolicy(private val project: Project) :
    SerializablePersistentStateComponent<MarsLearningPolicy.Policy>(Policy()) {
    data class Policy(
        @JvmField var releasedHints: Int = 4,
        @JvmField var requirePredictions: Boolean = false,
        @JvmField var allowFreeWriting: Boolean = true,
    )

    val releasedHints: Int get() = getState().releasedHints
    val requirePredictions: Boolean get() = getState().requirePredictions
    val allowFreeWriting: Boolean get() = getState().allowFreeWriting

    fun update(releasedHints: Int = this.releasedHints, requirePredictions: Boolean = this.requirePredictions, allowFreeWriting: Boolean = this.allowFreeWriting) {
        require(releasedHints in 0..4)
        updateState { Policy(releasedHints, requirePredictions, allowFreeWriting) }
    }

    override fun loadState(state: Policy) {
        super.loadState(state.copy(releasedHints = state.releasedHints.coerceIn(0, 4)))
    }

    fun canStartIndependent(): Boolean = allowFreeWriting && (!requirePredictions ||
        (MarsPredictions.hasCorrectAnswer(project, MarsPredictions.doubling) &&
            MarsPredictions.hasCorrectAnswer(project, MarsPredictions.negative)))

    fun independentRestriction(): String = learningMessages.getMessage(
        if (allowFreeWriting) "policy.predictions.required" else "policy.free.required",
    )
}

internal class MarsLearningConfigurable(project: Project) : BoundConfigurable(learningMessages.getMessage("policy.title")) {
    private val policy = project.service<MarsLearningPolicy>()
    override fun createPanel(): DialogPanel = panel {
        row(learningMessages.getMessage("policy.hints")) {
            spinner(0..4).bindIntValue(policy::releasedHints) { policy.update(releasedHints = it) }
        }
        row {
            checkBox(learningMessages.getMessage("policy.predictions"))
                .bindSelected(policy::requirePredictions) { policy.update(requirePredictions = it) }
        }
        row {
            checkBox(learningMessages.getMessage("policy.free"))
                .bindSelected(policy::allowFreeWriting) { policy.update(allowFreeWriting = it) }
        }
        row { comment(learningMessages.getMessage("policy.scope")) }
    }
}
