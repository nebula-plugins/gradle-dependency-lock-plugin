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
package nebula.plugin.dependencylock.utils

import org.gradle.api.GradleException

/** Guards for workflows outside the opt-in per-project locking contract. */
class IsolatedProjectsSupport {
    static GradleException unsupportedCustomization(String option, String guidance) {
        new GradleException("$option is not supported when " +
            "${DependencyLockingFeatureFlags.ISOLATED_PROJECTS_SUPPORT}=true. $guidance")
    }

    static GradleException unsupportedWorkflow(String workflow) {
        new GradleException("$workflow is not supported when " +
            "${DependencyLockingFeatureFlags.ISOLATED_PROJECTS_SUPPORT}=true. " +
            'Use normal per-project locking, or disable this feature flag to use the legacy workflow.')
    }

    static void requireNoGlobalLock(File lockFile) {
        if (lockFile.exists()) {
            throw unsupportedWorkflow("Global lock consumption (${lockFile.absolutePath})")
        }
    }
}
