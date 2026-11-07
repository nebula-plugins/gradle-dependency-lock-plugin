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

/** Immutable project identities and command-line intent; never reads foreign tasks or extensions. */
class LockingTaskSelector {
    private final String projectPath
    private final String invocationPath
    private final Set<String> projectPaths
    private final List<String> requestedTasks

    LockingTaskSelector(Project project) {
        projectPath = project.path
        requestedTasks = new ArrayList<>(project.gradle.startParameter.taskNames).asImmutable()
        def directories = project.rootProject.allprojects.collectEntries { [(it.path): it.projectDir] }
        projectPaths = new LinkedHashSet<>(directories.keySet()).asImmutable()
        def invocationDirectory = project.gradle.startParameter.currentDir.canonicalFile.toPath()
        invocationPath = directories.findAll { path, directory ->
            invocationDirectory.startsWith(directory.canonicalFile.toPath())
        }.max { it.value.canonicalFile.toPath().nameCount }?.key ?: ':'
    }

    boolean hasTask(Collection<String> taskNames, Collection<String> localTaskNames) {
        requestedTasks.any { requested ->
            def tokens = requested.split(':', -1) as List<String>
            String taskName = new NameMatcher().find(tokens.last(), localTaskNames)
            if (taskName == null || !taskNames.contains(taskName)) return false
            if (tokens.size() == 1) {
                return invocationPath == ':' || projectPath == invocationPath || projectPath.startsWith(invocationPath + ':')
            }
            String selected = requested.startsWith(':') ? ':' : invocationPath
            for (String pattern : tokens.subList(requested.startsWith(':') ? 1 : 0, tokens.size() - 1)) {
                String prefix = selected == ':' ? ':' : selected + ':'
                def children = projectPaths.findAll { it.startsWith(prefix) }
                    .collect { it.substring(prefix.length()) }.findAll { !it.empty && !it.contains(':') }
                String matched = new NameMatcher().find(pattern, children)
                if (matched == null) return false
                selected = prefix + matched
            }
            selected == projectPath
        }
    }
}
