package nebula.plugin.dependencylock.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

class SaveLockTask extends DefaultTask {
    @InputFile
    File generatedLock

    @OutputFile
    File outputLock

    @TaskAction
    void saveLock() {
        getOutputLock().text = getGeneratedLock().text
    }
}
