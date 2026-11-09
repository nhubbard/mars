package edu.missouristate.mars.plugin.debug

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import edu.missouristate.mars.plugin.run.MarsRunConfiguration
import edu.missouristate.mars.plugin.run.MarsRunState

internal class MarsDebugRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "MarsMipsDebugger"

    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is MarsRunConfiguration

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment) =
        XDebuggerManager.getInstance(environment.project).newSessionBuilder(object : XDebugProcessStarter() {
            override fun start(session: XDebugSession) = MarsDebugProcess(session, state as MarsRunState)
        }).environment(environment).startSession().runContentDescriptor
}
