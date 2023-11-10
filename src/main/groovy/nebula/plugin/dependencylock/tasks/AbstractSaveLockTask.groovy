/*
 * Copyright 2014-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock.tasks

import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class AbstractSaveLockTask extends AbstractLockTask {
    @Internal
    String description = 'Move the generated lock file into the project directory'

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract Property<File> getGeneratedLock()

    @OutputFile
    abstract Property<File> getOutputLock()

    AbstractSaveLockTask() {
        outputs.upToDateWhen(new Spec<AbstractSaveLockTask>() {
            @Override
            boolean isSatisfiedBy(AbstractSaveLockTask task) {
                if (task.generatedLock.isPresent() && task.generatedLock.get().exists() && task.outputLock.isPresent() && task.outputLock.get().exists()) {
                    task.generatedLock.get().text == task.outputLock.get().text
                } else {
                    false
                }
            }
        })
    }

    abstract void verifyIfCanSaveLock()

    @TaskAction
    void saveLock() {
        verifyIfCanSaveLock()
        getOutputLock().get().text =  generatedLock.get().text
    }

}
