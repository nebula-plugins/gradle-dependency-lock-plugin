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
import org.gradle.api.tasks.Input
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class SaveLockTask extends AbstractSaveLockTask {

    @Input
    abstract Property<Boolean> getProjectHasGlobalLockFile()

    @Override
    void verifyIfCanSaveLock() {
        if (projectHasGlobalLockFile.isPresent() && projectHasGlobalLockFile.get()) {
            throw new IllegalStateException("Cannot save individual locks when global lock is in place, run deleteGlobalLock task.")
        }
    }

}
