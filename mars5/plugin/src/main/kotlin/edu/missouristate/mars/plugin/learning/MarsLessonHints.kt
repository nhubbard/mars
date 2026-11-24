package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import training.ui.LearningUiManager

/** Shared formative hint controls; callers open the correct progress revision first. */
internal object MarsLessonHints {
  fun text(
      project: Project,
      identity: MarsLessonIdentity,
      step: String,
      hints: List<MarsLessonHint>,
      active: () -> Boolean,
      feedback: (String) -> Unit,
  ): String {
    if (hints.isEmpty()) return ""
    val progress = project.service<MarsLearningProgress>()
    val revealed = progress.snapshot(identity)!!.revealedHints
    val lines =
        hints.filter { key(step, it.id) in revealed }.map { escapeXmlEntities(it.text) + "&#10;" }
    val callback = LearningUiManager.addCallback {
      if (!project.isDisposed && active()) {
        val history = progress.snapshot(identity)!!.revealedHints
        val next =
            hints.take(project.service<MarsLearningPolicy>().releasedHints).firstOrNull {
              key(step, it.id) !in history
            }
        if (next == null)
            feedback(escapeXmlEntities(downloadedLessonMessages.getMessage("hint.none")))
        else {
          progress.revealHint(identity, key(step, next.id))
          feedback(escapeXmlEntities(next.text))
        }
      }
    }
    return lines.joinToString("") +
        "<callback id=\"$callback\">${escapeXmlEntities(downloadedLessonMessages.getMessage("hint.open"))}</callback>"
  }

  private fun key(step: String, hint: String) = "hints/$step/$hint"
}
