/*
 * Copyright 2026 Netflix, Inc.
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

import nebula.test.ProjectSpec

/**
 * Base class for lock task tests that provides common helper methods.
 * Specifically helps with wiring task properties for configuration cache compatibility.
 */
abstract class LockTaskSpec extends ProjectSpec {
    
    /**
     * Helper to wire GenerateLockTask properties for configuration cache compatibility.
     * Tests create tasks directly, so we need to wire the new properties manually.
     * 
     * This helper ensures:
     * - Basic properties (projectDirectory, globalLockFileName, dependencyLockIgnored) are set
     * - Resolution API properties (resolutionResults, peerProjectCoordinates) are wired with lazy providers
     * - Properties are evaluated at execution time to avoid test pollution
     */
    void wireTaskProperties(GenerateLockTask task) {
        // Wire basic properties
        task.projectDirectory.set(task.project.layout.projectDirectory)
        task.globalLockFileName.set('global.lock')
        task.dependencyLockIgnored.set(false)

        // Wire resolutionResults and peerProjectCoordinates using lazy provider
        // This ensures they're evaluated at execution time, not configuration time
        task.resolutionResults.set(task.project.provider {
            def configNames = task.configurationNames.getOrElse([] as Set)
            def skippedNames = task.skippedConfigurationNames.getOrElse([] as Set)
            
            // Get lockable configurations fresh each time (avoids test pollution)
            def lockableConfs = task.project.configurations.matching { conf ->
                configNames.contains(conf.name) && 
                !skippedNames.any { prefix -> conf.name.startsWith(prefix) } &&
                conf.isCanBeResolved()
            }
            
            // Build resolution map
            lockableConfs.collectEntries { conf ->
                [(conf.name): conf.incoming.resolutionResult.rootComponent]
            }
        })
        
        task.peerProjectCoordinates.set(task.project.provider {
            task.project.rootProject.allprojects.collect { p ->
                String group = p.group?.toString() ?: ''
                String name = p.name
                "${group}:${name}".toString()
            }
        })
    }
}

