package edu.missouristate.mars.plugin

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Selects the classic target for tests that characterize existing classroom programs. */
internal fun classicMipsProjectFixture() =
    testFixture("classicMipsProject") {
      val project = codeInsightProjectFixture().init()
      withContext(Dispatchers.EDT) {
        project.service<MarsAssemblySettings>().changeTarget(MarsTarget.MARS32)
      }
      initialized(project) {}
    }
