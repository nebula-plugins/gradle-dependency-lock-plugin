/*
 * Copyright 2015-2019 Netflix, Inc.
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

import nebula.plugin.dependencylock.utils.CoreLocking
import org.gradle.api.BuildCancelledException
import org.gradle.api.tasks.TaskAction

/**
 * The update task is a generate task, it simply reads in the old locked dependencies and then overwrites the desired
 * dependencies per user request.
 */
class UpdateLockTask extends GenerateLockTask {
    String description = 'Apply updates to a preexisting lock file and write to build/<specified name>'

    @TaskAction
    @Override
    void lock() {
        if (CoreLocking.isCoreLockingEnabled()) {
            throw new BuildCancelledException("updateLock is not supported with core locking. Please use `./gradlew dependencies --update-locks group1:module1,group2:module2`")
        }
        super.lock()
    }
}
