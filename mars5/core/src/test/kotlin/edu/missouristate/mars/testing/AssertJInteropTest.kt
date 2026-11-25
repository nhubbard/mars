package edu.missouristate.mars.testing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AssertJInteropTest {
  @Test
  fun `instance assertions retain native identity and subtype acceptance`() {
    val assertion = assertThat("MARS")
    assertSame(assertion, assertion.isInstanceOf<CharSequence>())
    assertThat(1).isInstanceOf<Int>()
    assertThrows<AssertionError> { assertion.isInstanceOf<Number>() }
  }

  @Test
  fun `throwable assertions retain fluent message checks and native failures`() {
    val assertion = assertThat(IllegalArgumentException("invalid"))
    assertSame(assertion, assertion.isInstanceOf<RuntimeException>())
    assertion.isInstanceOf<RuntimeException>().hasMessage("invalid")
    assertThatExceptionOfType<RuntimeException>()
        .isThrownBy { throw IllegalArgumentException("invalid") }
        .withMessage("invalid")
    assertThrows<AssertionError> {
      assertThatExceptionOfType<IllegalStateException>().isThrownBy {}
    }
  }
}
