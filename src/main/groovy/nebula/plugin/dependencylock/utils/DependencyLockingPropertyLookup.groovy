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

import org.gradle.api.Project

/** Property lookup for internal wiring; legacy callers retain parent lookup. */
class DependencyLockingPropertyLookup {
    static Object findProperty(Project project, String name, boolean isolatedProjectsSupportEnabled) {
        if (!isolatedProjectsSupportEnabled) {
            return project.findProperty(name)
        }

        // Track external Gradle properties even when a script supplies a local override.
        def gradleProperty = project.providers.gradleProperty(name).orNull
        def extras = project.extensions.extraProperties
        return extras.has(name) ? extras.get(name) : gradleProperty
    }
}
