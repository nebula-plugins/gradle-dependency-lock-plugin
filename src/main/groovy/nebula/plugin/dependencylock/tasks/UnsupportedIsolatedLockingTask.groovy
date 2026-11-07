/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.utils.IsolatedProjectsSupport
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Retains excluded task names without configuring or executing the legacy engine. */
@DisableCachingByDefault
abstract class UnsupportedIsolatedLockingTask extends AbstractLockTask {
    @Input
    abstract Property<String> getUnsupportedWorkflow()

    @TaskAction
    void rejectWorkflow() {
        throw IsolatedProjectsSupport.unsupportedWorkflow(unsupportedWorkflow.get())
    }
}
