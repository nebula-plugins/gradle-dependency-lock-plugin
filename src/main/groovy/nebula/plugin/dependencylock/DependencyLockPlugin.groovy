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
    Project project

    @Override
    void apply(Project project) {
        this.project = project

        String clLockFileName = project.hasProperty('dependencyLock.lockFile') ? project['dependencyLock.lockFile'] : null
        DependencyLockExtension extension = project.extensions.create('dependencyLock', DependencyLockExtension)

        Map overrides = loadOverrides()
        GenerateLockTask lockTask = configureLockTask(clLockFileName, extension, overrides)
        configureSaveTask(lockTask, extension)

        project.gradle.taskGraph.whenReady { taskGraph ->
            File dependenciesLock = new File(project.projectDir, clLockFileName ?: extension.lockFile)

            if (!taskGraph.hasTask(lockTask) && dependenciesLock.exists() &&
                    !project.hasProperty('dependencyLock.ignore')) {
                applyLock(dependenciesLock, overrides)
            } else if (taskGraph.hasTask(lockTask) && !project.hasProperty('dependencyLock.ignore')) {
                applyOverrides(overrides)
            }
        }
    }

    private void configureSaveTask(GenerateLockTask lockTask, DependencyLockExtension extension) {
        SaveLockTask saveTask = project.tasks.create('saveLock', SaveLockTask)
        saveTask.conventionMapping.with {
            generatedLock = { lockTask.dependenciesLock }
            outputLock = { new File(project.projectDir, extension.lockFile) }
        }
        saveTask.dependsOn lockTask
        saveTask.outputs.upToDateWhen {
            if (saveTask.generatedLock.exists() && saveTask.outputLock.exists()) {
                saveTask.generatedLock.text == saveTask.outputLock.text
            } else {
                false
            }
        }
    }

    private GenerateLockTask configureLockTask(String clLockFileName, DependencyLockExtension extension, Map overrides) {
        GenerateLockTask lockTask = project.tasks.create('generateLock', GenerateLockTask)
        lockTask.conventionMapping.with {
            dependenciesLock = {
                new File(project.buildDir, clLockFileName ?: extension.lockFile)
            }
            configurationNames = { extension.configurationNames }
        }
        lockTask.overrides = overrides

        lockTask
    }

    void applyOverrides(Map overrides) {
        if (project.hasProperty('dependencyLock.overrideFile')) {
            logger.info("Using override file ${project['dependencyLock.overrideFile']} to lock dependencies")
        }
        if (project.hasProperty('dependencyLock.override')) {
            logger.info("Using command line overrides ${project['dependencyLock.override']}")
        }

        def overrideModules = overrides.collect { "${it.key}:${it.value}" }

        project.configurations.all {
            resolutionStrategy.forcedModules = overrideModules
        }
    }

    void applyLock(File dependenciesLock, Map overrides) {
        logger.info("Using ${dependenciesLock.name} to lock dependencies")
        def locks = loadLock(dependenciesLock)
        def forcedModules = locks.collect {
            overrides.containsKey(it.key) ? "${it.key}:${overrides[it.key]}" : "${it.key}:${it.value.locked}"
        }
        logger.debug(forcedModules.toString())

        project.configurations.all {
            resolutionStrategy.forcedModules = forcedModules
        }
    }

    private Map loadOverrides() {
        Map overrides = [:]
        if (project.hasProperty('dependencyLock.ignore')) {
            return overrides
        }

        if (project.hasProperty('dependencyLock.overrideFile')) {
            File dependenciesLock = new File(project.rootDir, project['dependencyLock.overrideFile'])
            loadLock(dependenciesLock).each { overrides[it.key] = it.value.locked }
            logger.debug "Override file loaded: ${project['dependencyLock.overrideFile']}"
        }

        if (project.hasProperty('dependencyLock.override')) {
            project['dependencyLock.override'].tokenize(',').each {
                def (group, artifact, version) = it.tokenize(':')
                overrides["${group}:${artifact}".toString()] = version
                logger.debug "Override added for: ${it}"
            }
        }

        overrides
    }

    private loadLock(File lock) {
        try {
            return new JsonSlurper().parseText(lock.text)
        } catch (ex) {
            logger.debug('Unreadable json file: ' + lock.text)
            logger.error('JSON unreadable')
            throw new GradleException("${lock.name} is unreadable or invalid json, terminating run", ex)
        }
    }
}
