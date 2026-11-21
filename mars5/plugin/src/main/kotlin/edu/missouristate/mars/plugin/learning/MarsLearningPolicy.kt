package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shareable course settings, separate from personal progress and private grading authority. */
@Service(Service.Level.PROJECT)
@State(name = "MarsLearningPolicy", storages = [Storage("mars-learning.xml")])
internal class MarsLearningPolicy(private val project: Project) :
    SerializablePersistentStateComponent<MarsLearningPolicy.Policy>(Policy()) {
    data class Policy(
        @JvmField var releasedHints: Int = 4,
        @JvmField var requirePredictions: Boolean = false,
        @JvmField var allowFreeWriting: Boolean = true,
        @JvmField var requiredGuidedChecks: Set<String> = emptySet(),
    )

    val requiredGuidedChecks: Set<String> get() = getState().requiredGuidedChecks.toSet()
    val releasedHints: Int get() = getState().releasedHints
    val requirePredictions: Boolean get() = getState().requirePredictions
    val allowFreeWriting: Boolean get() = getState().allowFreeWriting

    fun update(releasedHints: Int = this.releasedHints, requirePredictions: Boolean = this.requirePredictions, allowFreeWriting: Boolean = this.allowFreeWriting) {
        require(releasedHints in 0..4)
        updateState { it.copy(releasedHints = releasedHints, requirePredictions = requirePredictions, allowFreeWriting = allowFreeWriting) }
    }

    override fun loadState(state: Policy) {
        super.loadState(state.copy(releasedHints = state.releasedHints.coerceIn(0, 4)))
    }

    fun setGuidedRequirement(check: MarsGuidedRequirement, required: Boolean) {
        updateState { it.copy(requiredGuidedChecks = if (required) it.requiredGuidedChecks + check.id else it.requiredGuidedChecks - check.id) }
    }

    private fun predictionsReady(policy: Policy): Boolean = !policy.requirePredictions ||
        (MarsPredictions.hasCorrectAnswer(project, MarsPredictions.doubling) &&
            MarsPredictions.hasCorrectAnswer(project, MarsPredictions.negative))

    private fun supportedRequirements(policy: Policy): Boolean = policy.requiredGuidedChecks.all { id -> MarsGuidedRequirement.entries.any { it.id == id } }
    private fun missingObservations(policy: Policy): List<MarsGuidedRequirement> = MarsGuidedRequirement.entries.filter {
        it.id in policy.requiredGuidedChecks && !it.passed(project)
    }

    fun canStartIndependent(refreshedRequirements: Set<String>? = null): Boolean {
        val policy = getState()
        if (refreshedRequirements != null && policy.requiredGuidedChecks != refreshedRequirements) return false
        return policy.allowFreeWriting && predictionsReady(policy) && supportedRequirements(policy) && missingObservations(policy).isEmpty()
    }

    /** Refresh outside read actions before both starting and accepting an assessment. */
    suspend fun verifyIndependentPrerequisites(): Boolean {
        return canStartIndependent(refreshGuidedSources())
    }

    suspend fun refreshGuidedSources(): Set<String> {
        val required = requiredGuidedChecks
        val paths = MarsGuidedRequirement.entries.filter { it.id in required }.mapNotNull { it.source(project) }.distinct()
        if (paths.isNotEmpty()) withContext(Dispatchers.IO) {
            for (path in paths) VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)?.refresh(false, false)
        }
        return required
    }

    fun independentRestriction(): String {
        val policy = getState()
        return when {
            !policy.allowFreeWriting -> learningMessages.getMessage("policy.free.required")
            !predictionsReady(policy) -> learningMessages.getMessage("policy.predictions.required")
            !supportedRequirements(policy) -> learningMessages.getMessage("policy.observations.unsupported")
            else -> {
                val missing = missingObservations(policy)
                if (missing.isEmpty()) learningMessages.getMessage("policy.observations.ready")
                else learningMessages.getMessage("policy.observations.required", missing.joinToString(", ") { it.title })
            }
        }
    }
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
        row { label(learningMessages.getMessage("policy.observations")) }
        for (check in MarsGuidedRequirement.entries) {
            row {
                checkBox(check.title).bindSelected({ check.id in policy.requiredGuidedChecks }) { policy.setGuidedRequirement(check, it) }
            }
        }
        row { comment(learningMessages.getMessage("policy.scope")) }
    }
}
