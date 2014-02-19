/*
 * Copyright 2014 Netflix, Inc.
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
package nebula.plugin.dependencylock

import groovy.json.JsonSlurper
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.tasks.SaveLockTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class DependencyLockPlugin implements Plugin<Project> {
    private static Logger logger = Logging.getLogger(DependencyLockPlugin)

    @Override
    void apply(Project project) {
        String overrideFileName = project.hasProperty('dependencyLock.lockFile') ? project['dependencyLock.lockFile'] : null
        DependencyLockExtension extension = project.extensions.create('dependencyLock', DependencyLockExtension)

        GenerateLockTask lockTask = project.tasks.create('generateLock', GenerateLockTask)
        lockTask.conventionMapping.with {
            dependenciesLock = {
                new File(project.buildDir, overrideFileName ?: extension.lockFile)
            }
            configurationNames = { extension.configurationNames }
        }

        SaveLockTask saveTask = project.tasks.create('saveLock', SaveLockTask)
        saveTask.conventionMapping.with {
            generatedLock = { lockTask.dependenciesLock }
            outputLock = { new File(project.projectDir, extension.lockFile) }
        }
        saveTask.dependsOn lockTask

        project.gradle.taskGraph.whenReady { taskGraph ->
            File dependenciesLock = new File(project.projectDir, overrideFileName ?: extension.lockFile)

            if (!taskGraph.hasTask(lockTask) && dependenciesLock.exists() &&
                    !project.hasProperty('dependencyLock.ignore')) {
                logger.info("Using ${dependenciesLock.name} to lock dependencies")
                def locks
                try {
                    locks = new JsonSlurper().parseText(dependenciesLock.text)
                } catch (ex) {
                    logger.debug('Unreadable json file: ' + dependenciesLock.text)
                    logger.error('JSON unreadable')
                    throw new GradleException("${extension.lockFile} is unreadable or invalid json, terminating run", ex)
                }
                def forcedModules = locks.collect { "${it.key}:${it.value.locked}" }
                logger.debug(forcedModules.toString())

                project.configurations.all {
                    resolutionStrategy.forcedModules = forcedModules
                }
            }
        }
    }
}
