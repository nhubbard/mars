package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MarsUnderscoreAnchorTest {
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun lesson(mnemonic: String) =
      """{"formatVersion":7,"introduction":"Read lanes.","conclusion":"Explain the result.","steps":[{"kind":"prediction","id":"lanes","explanation":"Read the source.","question":"Which count?","choices":[{"id":"a","text":"One","feedback":"Explain it."},{"id":"b","text":"Two","feedback":"Read it again."}],"correctChoice":"a","anchor":{"label":"counts","mnemonic":"$mnemonic","occurrence":0}}]}"""
          .toByteArray()

  @Test
  fun `native instruction anchors accept MSA underscore spellings and reject executable punctuation`() {
    for (mnemonic in listOf("copy_s.d", "adds_s.h", "subs_s.h", "andi", "vshf.b")) {
      assertEquals(
          mnemonic,
          MarsNativeLessonPlan.parse(lesson(mnemonic)).steps.single().anchor!!.mnemonic,
      )
    }
    for (mnemonic in
        listOf(
            "_copy_s.d",
            "copy_s.d()",
            "copy_s.d;exit",
            "copy_s.d/next",
            "copy_s.d ",
            "copy-s.d",
        )) {
      assertThrows<IllegalArgumentException> { MarsNativeLessonPlan.parse(lesson(mnemonic)) }
    }
  }

  @Test
  fun `native PSI resolves the actual underscored operation within its label block`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val source =
              ".text\ncounts:\nCOPY_S.D \$t0, \$w5[0]\ncopy_s.d \$t1, \$w5[1]\nnext:\ncopy_s.d \$t2, \$w5[0]\n"
          fixture.configureByText("population.asm", source)
          val anchor = MarsLessonAnchor("counts", "copy_s.d", 0)
          val first = MarsLessonAnchors.range(fixture.editor, anchor)!!
          val second = MarsLessonAnchors.range(fixture.editor, anchor.copy(occurrence = 1))!!
          assertEquals(
              "COPY_S.D \$t0, \$w5[0]",
              source.substring(first.startOffset, first.endOffset),
          )
          assertEquals(
              "copy_s.d \$t1, \$w5[1]",
              source.substring(second.startOffset, second.endOffset),
          )
          assertNull(MarsLessonAnchors.range(fixture.editor, anchor.copy(occurrence = 2)))
        }
      }
}
