package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/** Content revisions keep separate histories; opening a new edition never migrates student work. */
internal data class MarsLessonIdentity(
    val course: String,
    val lesson: String,
    val version: String,
    val contentSha256: String,
) {
  init {
    require(listOf(course, lesson, version).all { ID.matches(it) })
    require(SHA256.matches(contentSha256))
  }

  val key: String
    get() = "$course/$lesson/$version/$contentSha256"

  companion object {
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val SHA256 = Regex("[0-9a-f]{64}")
  }
}

/**
 * MARS evidence, independent of Features Trainer's whole-lesson passed flag. Only source-dependent
 * checks are invalidated by an edit; predictions and revealed hints survive.
 */
@Service(Service.Level.PROJECT)
@State(name = "MarsLearningProgress", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class MarsLearningProgress :
    SerializablePersistentStateComponent<MarsLearningProgress.Progress>(Progress()) {

  data class Progress(
      @JvmField var lessons: Map<String, LessonRecord> = emptyMap(),
      @JvmField var recovery: Map<String, LessonRecord> = emptyMap(),
  )

  data class LessonRecord(
      @JvmField var sourceRevision: String = "",
      @JvmField var successfulChecks: Set<String> = emptySet(),
      @JvmField var quizAttempts: List<QuizAttempt> = emptyList(),
      @JvmField var revealedHints: Set<String> = emptySet(),
      @JvmField var completedAssessments: Int = 0,
  )

  data class QuizAttempt(
      @JvmField var quiz: String = "",
      @JvmField var choice: String = "",
  )

  /**
   * Opaque, transient capability: a result belongs to this exact request in this service instance.
   */
  class CheckTicket
  internal constructor(
      val identity: MarsLessonIdentity,
      val step: String,
      val sourceRevision: String,
  )

  private val pendingChecks = mutableMapOf<Pair<String, String>, CheckTicket>()

  /** The revision fingerprints all source and execution options used by the eventual check. */
  @Synchronized
  fun open(identity: MarsLessonIdentity, sourceRevision: String) {
    require(sourceRevision.isNotBlank())
    val previous = getState().lessons[identity.key]
    if (previous?.sourceRevision == sourceRevision) return
    pendingChecks.keys.removeAll { it.first == identity.key }
    val record =
        (previous ?: LessonRecord()).copy(
            sourceRevision = sourceRevision,
            successfulChecks = emptySet(),
        )
    put(identity, record)
  }

  @Synchronized
  fun snapshot(identity: MarsLessonIdentity): LessonRecord? =
      getState().lessons[identity.key]?.let {
        it.copy(
            successfulChecks = it.successfulChecks.toSet(),
            quizAttempts = it.quizAttempts.map { attempt -> attempt.copy() },
            revealedHints = it.revealedHints.toSet(),
        )
      }

  @Synchronized
  fun recordChoice(identity: MarsLessonIdentity, quiz: String, choice: String) {
    require(quiz.isNotBlank())
    require(choice.isNotBlank())
    val record = record(identity)
    put(identity, record.copy(quizAttempts = record.quizAttempts + QuizAttempt(quiz, choice)))
  }

  @Synchronized
  fun revealHint(identity: MarsLessonIdentity, hint: String) {
    require(hint.isNotBlank())
    val record = record(identity)
    put(identity, record.copy(revealedHints = record.revealedHints + hint))
  }

  @Synchronized
  fun beginCheck(identity: MarsLessonIdentity, step: String, sourceRevision: String): CheckTicket {
    require(step.isNotBlank())
    open(identity, sourceRevision)
    // A retry invalidates an earlier pass even when the source has not changed.
    val record = record(identity)
    put(identity, record.copy(successfulChecks = record.successfulChecks - step))
    return CheckTicket(identity, step, sourceRevision).also {
      pendingChecks[identity.key to step] = it
    }
  }

  /** Read-only capability test for an observation with several intermediate pauses. */
  @Synchronized
  fun isCurrent(ticket: CheckTicket): Boolean =
      pendingChecks[ticket.identity.key to ticket.step] === ticket

  /** Returns whether the result is current, independently of whether the student's check passed. */
  @Synchronized
  fun finishCheck(ticket: CheckTicket, passed: Boolean): Boolean {
    val key = ticket.identity.key to ticket.step
    if (pendingChecks[key] !== ticket) return false
    pendingChecks.remove(key)
    val record = record(ticket.identity)
    if (passed)
        put(ticket.identity, record.copy(successfulChecks = record.successfulChecks + ticket.step))
    return true
  }

  /**
   * Only a current, completed assessment consumes an attempt; interrupted checks use finishCheck.
   */
  @Synchronized
  fun finishAssessment(ticket: CheckTicket, passed: Boolean, limit: Int): Boolean {
    require(limit >= 0)
    if (!isCurrent(ticket)) return false
    val record = record(ticket.identity)
    if (limit != 0 && record.completedAssessments >= limit) {
      pendingChecks.remove(ticket.identity.key to ticket.step)
      return false
    }
    pendingChecks.remove(ticket.identity.key to ticket.step)
    put(
        ticket.identity,
        record.copy(
            successfulChecks =
                if (passed) record.successfulChecks + ticket.step else record.successfulChecks,
            completedAssessments =
                (record.completedAssessments.toLong() + 1)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
        ),
    )
    return true
  }

  /** A deliberate reset forgets this content edition only, including its outstanding results. */
  @Synchronized
  fun reset(identity: MarsLessonIdentity) {
    pendingChecks.keys.removeAll { it.first == identity.key }
    updateState { it.copy(lessons = it.lessons - identity.key) }
  }

  @Synchronized
  fun canReset(identities: Set<MarsLessonIdentity>): Boolean = identities.any {
    val record = getState().lessons[it.key] ?: return@any false
    record.successfulChecks.isNotEmpty() ||
        record.quizAttempts.isNotEmpty() ||
        record.revealedHints.isNotEmpty() ||
        record.completedAssessments > 0
  }

  @Synchronized
  fun canRecover(identities: Set<MarsLessonIdentity>): Boolean = identities.any {
    it.key in getState().recovery
  }

  /** One persisted recovery copy for this scope; an empty reset never replaces it. */
  @Synchronized
  fun resetRecoverably(identities: Set<MarsLessonIdentity>): Boolean {
    if (!canReset(identities)) return false
    val keys = identities.map { it.key }.toSet()
    val records = getState().lessons.filterKeys { it in keys }
    pendingChecks.keys.removeAll { it.first in keys }
    updateState { it.copy(lessons = it.lessons - keys, recovery = it.recovery - keys + records) }
    return true
  }

  /** Restore formative history, keeping newer attempts; execution must be checked afresh. */
  @Synchronized
  fun recover(identities: Set<MarsLessonIdentity>): Boolean {
    val keys = identities.map { it.key }.toSet()
    val records = getState().recovery.filterKeys { it in keys }
    if (records.isEmpty()) return false
    pendingChecks.keys.removeAll { it.first in keys }
    val restored = records.mapValues { (key, backup) ->
      val current = getState().lessons[key] ?: LessonRecord(sourceRevision = backup.sourceRevision)
      current.copy(
          successfulChecks = emptySet(),
          quizAttempts = (backup.quizAttempts + current.quizAttempts).map { it.copy() },
          revealedHints = backup.revealedHints + current.revealedHints,
          completedAssessments =
              (backup.completedAssessments.toLong() + current.completedAssessments)
                  .coerceAtMost(Int.MAX_VALUE.toLong())
                  .toInt(),
      )
    }
    updateState {
      val recheck =
          it.lessons
              .filterKeys { key -> key in keys }
              .mapValues { (_, record) ->
                record.copy(successfulChecks = emptySet())
              }
      it.copy(lessons = it.lessons + recheck + restored, recovery = it.recovery - keys)
    }
    return true
  }

  @Synchronized
  override fun loadState(state: Progress) {
    pendingChecks.clear()
    super.loadState(
        state.copy(
            lessons =
                state.lessons.mapValues { (_, record) ->
                  record.copy(completedAssessments = record.completedAssessments.coerceAtLeast(0))
                },
            recovery =
                state.recovery.mapValues { (_, record) ->
                  record.copy(completedAssessments = record.completedAssessments.coerceAtLeast(0))
                },
        ),
    )
  }

  private fun record(identity: MarsLessonIdentity): LessonRecord =
      checkNotNull(getState().lessons[identity.key]) { "Open the lesson before recording progress" }

  private fun put(identity: MarsLessonIdentity, record: LessonRecord) {
    updateState { it.copy(lessons = it.lessons + (identity.key to record)) }
  }
}
