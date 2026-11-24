package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.dsl.builder.bindIntText
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
  // XMLb map values need concrete state beans, as in the existing lesson-progress records.
  data class DownloadedRequirements(@JvmField var activities: Set<String> = emptySet())

  data class Policy(
      @JvmField var releasedHints: Int = 4,
      @JvmField var requirePredictions: Boolean = false,
      @JvmField var allowFreeWriting: Boolean = true,
      @JvmField var requiredGuidedChecks: Set<String> = emptySet(),
      @JvmField var maxIndependentAssessments: Int = 0,
      @JvmField var downloadedPrerequisites: Map<String, DownloadedRequirements> = emptyMap(),
  )

  val requiredGuidedChecks: Set<String>
    get() = getState().requiredGuidedChecks.toSet()

  val releasedHints: Int
    get() = getState().releasedHints

  val maxIndependentAssessments: Int
    get() = getState().maxIndependentAssessments

  val requirePredictions: Boolean
    get() = getState().requirePredictions

  val allowFreeWriting: Boolean
    get() = getState().allowFreeWriting

  fun update(
      releasedHints: Int = this.releasedHints,
      requirePredictions: Boolean = this.requirePredictions,
      allowFreeWriting: Boolean = this.allowFreeWriting,
      maxIndependentAssessments: Int = this.maxIndependentAssessments,
  ) {
    require(releasedHints in 0..4)
    require(maxIndependentAssessments in 0..1000)
    updateState {
      it.copy(
          releasedHints = releasedHints,
          requirePredictions = requirePredictions,
          allowFreeWriting = allowFreeWriting,
          maxIndependentAssessments = maxIndependentAssessments,
      )
    }
  }

  override fun loadState(state: Policy) {
    super.loadState(
        state.copy(
            releasedHints = state.releasedHints.coerceIn(0, 4),
            maxIndependentAssessments = state.maxIndependentAssessments.coerceIn(0, 1000),
            downloadedPrerequisites =
                state.downloadedPrerequisites.mapValues {
                  it.value.copy(activities = it.value.activities.toSet())
                },
        ),
    )
  }

  fun downloadedRequirements(identity: MarsLessonIdentity): Set<String> =
      getState().downloadedPrerequisites[identity.key]?.activities.orEmpty().toSet()

  fun setDownloadedRequirements(
      content: MarsDownloadedLessonContent,
      required: Map<String, Set<String>>,
  ) {
    val assessments =
        content.plan.steps.filterIsInstance<MarsNativeAssessmentStep>().associateBy { it.id }
    require(required.keys.all { it in assessments }) {
      "Choose an independent activity in this content edition"
    }
    val changes =
        required
            .map { (id, ids) ->
              val step = assessments.getValue(id)
              val allowed = content.preceding(step).map { it.id }.toSet()
              require(ids.all { it in allowed }) {
                "Only earlier activities in this content edition can be prerequisites"
              }
              content.assessmentIdentity(step).key to ids.toSet()
            }
            .toMap()
    updateState {
      it.copy(
          downloadedPrerequisites =
              (it.downloadedPrerequisites - changes.keys) +
                  changes
                      .filterValues { ids -> ids.isNotEmpty() }
                      .mapValues { entry -> DownloadedRequirements(entry.value) },
      )
    }
  }

  fun setGuidedRequirement(check: MarsGuidedRequirement, required: Boolean) {
    updateState {
      it.copy(
          requiredGuidedChecks =
              if (required) it.requiredGuidedChecks + check.id
              else it.requiredGuidedChecks - check.id,
      )
    }
  }

  private fun predictionsReady(policy: Policy): Boolean =
      !policy.requirePredictions ||
          (MarsPredictions.hasCorrectAnswer(project, MarsPredictions.doubling) &&
              MarsPredictions.hasCorrectAnswer(project, MarsPredictions.negative))

  private fun supportedRequirements(policy: Policy): Boolean =
      policy.requiredGuidedChecks.all { id -> MarsGuidedRequirement.entries.any { it.id == id } }

  private fun missingObservations(policy: Policy): List<MarsGuidedRequirement> =
      MarsGuidedRequirement.entries.filter {
        it.id in policy.requiredGuidedChecks && !it.passed(project)
      }

  fun canStartIndependent(refreshedRequirements: Set<String>? = null): Boolean {
    val policy = getState()
    if (refreshedRequirements != null && policy.requiredGuidedChecks != refreshedRequirements)
        return false
    return policy.allowFreeWriting &&
        predictionsReady(policy) &&
        supportedRequirements(policy) &&
        missingObservations(policy).isEmpty()
  }

  fun completedIndependentAssessments(): Int =
      project
          .service<MarsLearningProgress>()
          .snapshot(MarsIndependentCheckpoint.identity)
          ?.completedAssessments ?: 0

  fun canAssessIndependent(limit: Int = maxIndependentAssessments): Boolean {
    return limit == 0 || completedIndependentAssessments() < limit
  }

  /** Refresh outside read actions before both starting and accepting an assessment. */
  suspend fun verifyIndependentPrerequisites(): Boolean {
    return canStartIndependent(refreshGuidedSources())
  }

  suspend fun refreshGuidedSources(): Set<String> {
    val required = requiredGuidedChecks
    val paths =
        MarsGuidedRequirement.entries
            .filter { it.id in required }
            .mapNotNull { it.source(project) }
            .distinct()
    if (paths.isNotEmpty())
        withContext(Dispatchers.IO) {
          for (path in paths) VirtualFileManager.getInstance()
              .refreshAndFindFileByNioPath(path)
              ?.refresh(false, false)
        }
    return required
  }

  fun independentRestriction(): String {
    val policy = getState()
    return when {
      !policy.allowFreeWriting -> learningMessages.getMessage("policy.free.required")
      !predictionsReady(policy) -> learningMessages.getMessage("policy.predictions.required")
      !supportedRequirements(policy) ->
          learningMessages.getMessage("policy.observations.unsupported")
      else -> {
        val missing = missingObservations(policy)
        if (missing.isEmpty()) learningMessages.getMessage("policy.observations.ready")
        else
            learningMessages.getMessage(
                "policy.observations.required",
                missing.joinToString(", ") { it.title },
            )
      }
    }
  }
}

internal class MarsLearningConfigurable(project: Project) :
    BoundConfigurable(learningMessages.getMessage("policy.title")) {
  private val policy = project.service<MarsLearningPolicy>()

  override fun createPanel(): DialogPanel = panel {
    row(learningMessages.getMessage("policy.hints")) {
      spinner(0..4).bindIntValue(policy::releasedHints) { policy.update(releasedHints = it) }
    }
    row {
      checkBox(learningMessages.getMessage("policy.predictions")).bindSelected(
          policy::requirePredictions,
      ) {
        policy.update(requirePredictions = it)
      }
    }
    row {
      checkBox(learningMessages.getMessage("policy.free")).bindSelected(policy::allowFreeWriting) {
        policy.update(allowFreeWriting = it)
      }
    }
    row(learningMessages.getMessage("policy.assessments")) {
      intTextField(0..1000)
          .bindIntText(policy::maxIndependentAssessments) {
            policy.update(maxIndependentAssessments = it)
          }
          .applyToComponent {
            accessibleContext.accessibleName = learningMessages.getMessage("policy.assessments")
          }
    }
    row { label(learningMessages.getMessage("policy.observations")) }
    for (check in MarsGuidedRequirement.entries) {
      row {
        checkBox(check.title).bindSelected({ check.id in policy.requiredGuidedChecks }) {
          policy.setGuidedRequirement(check, it)
        }
      }
    }
    row { comment(learningMessages.getMessage("policy.scope")) }
  }
}
