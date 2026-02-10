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

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class SaveLockTask extends AbstractLockTask {
    @Internal
    String description = 'Move the generated lock file into the project directory'

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getGeneratedLock()

    @OutputFile
    abstract RegularFileProperty getOutputLock()

    // Global lock file to check for conflicts (configuration cache compatible)
    @Internal
    abstract RegularFileProperty getGlobalLockFile()

    @TaskAction
    void saveLock() {
        // Check for global lock conflict (without accessing project at execution time)
        // Only check if globalLockFile property was configured
        if (getGlobalLockFile().isPresent()) {
            def globalLock = getGlobalLockFile().get().asFile
            if (globalLock.exists()) {
                throw new GradleException('Cannot save individual locks when global lock is in place, run deleteGlobalLock task')
            }
        }
        
        getOutputLock().asFile.get().text = getGeneratedLock().asFile.get().text
    }
}
