package com.elpassion.intelijidea.task

import com.elpassion.intelijidea.task.ui.MFBeforeRunTaskDialog
import com.elpassion.intelijidea.util.showError
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.util.ExecutionErrorDialog
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.util.concurrency.Semaphore
import org.jetbrains.rpc.LOG
import javax.swing.SwingUtilities

class MFBeforeRunTaskProvider(private val project: Project) : BeforeRunTaskProvider<MFBeforeRunTask>() {

    override fun getDescription(task: MFBeforeRunTask): String = TASK_NAME

    override fun getName(): String = TASK_NAME

    override fun isConfigurable(): Boolean = true

    override fun configureTask(runConfiguration: RunConfiguration?, task: MFBeforeRunTask): Boolean {
        val dialog = MFBeforeRunTaskDialog(project)
        dialog.commandField.text = task.buildCommand
        dialog.mainframerScript.text = task.mainframerPath
        dialog.taskField.text = task.taskName
        if (dialog.showAndGet()) {
            task.mainframerPath = dialog.mainframerScript.text
            task.buildCommand = dialog.taskField.text
            task.taskName = dialog.taskField.text
        }
        return false
    }

    override fun getId(): Key<MFBeforeRunTask> = ID

    override fun canExecuteTask(configuration: RunConfiguration?, task: MFBeforeRunTask?): Boolean {
        //TODO: change
        return true
    }

    override fun executeTask(context: DataContext, configuration: RunConfiguration?, env: ExecutionEnvironment?, task: MFBeforeRunTask): Boolean {
        SwingUtilities.invokeAndWait {
            configuration?.let {
                showError(it.project, "Mainframer is executing task: ${it.name}")
            }
        }
        return executeSync(context, task)
    }

    fun executeSync(context: DataContext, task: MFBeforeRunTask): Boolean {
        val targetDone = Semaphore()
        val result = Ref(false)
        try {
            val waitingProcessAdapter = createWaitingProcessAdapter(result, targetDone)
            ApplicationManager.getApplication().invokeAndWait({ executeImpl(context, task, waitingProcessAdapter) }, ModalityState.NON_MODAL)
        } catch (e: Exception) {
            LOG.error(e)
            return false
        }

        targetDone.waitFor()
        return result.get()
    }

    private fun createWaitingProcessAdapter(result: Ref<Boolean>, targetDone: Semaphore) = object : ProcessAdapter() {
        override fun startNotified(event: ProcessEvent?) {
            targetDone.down()
        }

        override fun processTerminated(event: ProcessEvent?) {
            result.set(event!!.exitCode == 0)
            targetDone.up()
        }
    }

    private fun executeImpl(dataContext: DataContext, task: MFBeforeRunTask, waitingProcessAdapter: ProcessAdapter) {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return

        FileDocumentManager.getInstance().saveAllDocuments()
        try {
            val environment = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), MFBeforeRunTaskProfile(task)).build()
            environment.executionId = 0L
            environment.runner.execute(environment) { descriptor ->
                val processHandler = descriptor.processHandler
                if (processHandler != null) {
                    LOG.assertTrue(!processHandler.isStartNotified, "ProcessHandler is already startNotified, the listener won't be correctly notified")
                    processHandler.addProcessListener(waitingProcessAdapter)
                }
            }
        } catch (ex: ExecutionException) {
            ExecutionErrorDialog.show(ex, "Mainframer error title", project)
        }
    }

    override fun createTask(runConfiguration: RunConfiguration?): MFBeforeRunTask? {
        val task = MFBeforeRunTask("path", ".buildCommand", "taskName")
        task.isEnabled = true
        return task
    }

    companion object {
        val ID = Key.create<MFBeforeRunTask>("MainFrame.BeforeRunTask")
        val TASK_NAME = "MainframerBefore"
    }
}
