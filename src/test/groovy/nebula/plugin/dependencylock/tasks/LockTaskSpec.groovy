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

import nebula.plugin.dependencylock.FreshProjectSpec

/**
 * Base class for lock task tests that provides common helper methods.
 * Specifically helps with wiring task properties for configuration cache compatibility.
 */
abstract class LockTaskSpec extends FreshProjectSpec {
    
    /**
     * Helper to wire GenerateLockTask properties for configuration cache compatibility.
     * Tests create tasks directly, so we need to wire the new properties manually.
     * 
     * This helper ensures:
     * - Basic properties (projectDirectory, globalLockFileName, dependencyLockIgnored) are set
     * - Resolution API properties (resolutionResults, peerProjectCoordinates) are wired immediately
     * - Properties are evaluated at wire time to avoid test pollution in ProjectSpec (which reuses project instances)
     */
    void wireTaskProperties(GenerateLockTask task) {
        // Wire basic properties
        task.projectDirectory.set(task.project.layout.projectDirectory)
        task.globalLockFileName.set('global.lock')
        task.dependencyLockIgnored.set(false)

        // Eagerly capture the current state at wire time to avoid test pollution
        // (ProjectSpec reuses the same project instance across tests, so lazy evaluation would see accumulated dependencies)
        def configNames = task.configurationNames.getOrElse([] as Set)
        def skippedNames = task.skippedConfigurationNames.getOrElse([] as Set)
        
        // Use the actual lockableConfigurations static method to get proper filtering
        // (includes resolution alternative checks, CompileOnly exclusions, etc.)
        def lockableConfs = GenerateLockTask.lockableConfigurations(
            task.project, 
            task.project, 
            configNames, 
            skippedNames
        )
        
        // Build resolution map and set immediately (not via provider)
        def resolutionMap = lockableConfs.collectEntries { conf ->
            [(conf.name): conf.incoming.resolutionResult.rootComponent]
        }
        task.resolutionResults.set(resolutionMap)
        
        // Capture peer coordinates immediately
        def peers = task.project.rootProject.allprojects.collect { p ->
            String group = p.group?.toString() ?: ''
            String name = p.name
            "${group}:${name}".toString()
        }
        task.peerProjectCoordinates.set(peers)
    }
}

