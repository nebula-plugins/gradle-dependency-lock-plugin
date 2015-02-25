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
import nebula.plugin.dependencylock.tasks.CommitLockTask
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.tasks.SaveLockTask
import nebula.plugin.dependencylock.tasks.UpdateLockTask
import nebula.plugin.scm.ScmPlugin
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
        DependencyLockCommitExtension commitExtension = project.rootProject.extensions.findByType(DependencyLockCommitExtension)
        if (!commitExtension) {
            commitExtension = project.rootProject.extensions.create('commitDependencyLock', DependencyLockCommitExtension)
        }

        Map overrides = loadOverrides()

        GenerateLockTask genLockTask = project.tasks.create('generateLock', GenerateLockTask)
        configureLockTask(genLockTask, clLockFileName, extension, overrides)
        if (project.hasProperty('dependencyLock.useGeneratedLock')) {
            clLockFileName = genLockTask.getDependenciesLock().path
            logger.lifecycle(clLockFileName)
        }

        UpdateLockTask updateLockTask = project.tasks.create('updateLock', UpdateLockTask)
        configureLockTask(updateLockTask, clLockFileName, extension, overrides)
        configureUpdateTask(updateLockTask, extension)

        SaveLockTask saveTask = configureSaveTask(clLockFileName, extension)
        saveTask.mustRunAfter genLockTask, updateLockTask

        configureCommitTask(clLockFileName, saveTask, extension, commitExtension)

        project.gradle.taskGraph.whenReady { taskGraph ->
            File dependenciesLock = new File(project.projectDir, clLockFileName ?: extension.lockFile)

            def hasLockingTask = taskGraph.hasTask(genLockTask) || taskGraph.hasTask(updateLockTask)

            if (hasLockingTask) {
                project.configurations.all {
                    resolutionStrategy {
                        cacheDynamicVersionsFor 0, 'seconds'
                    }
                }
            }

            if (!hasLockingTask && dependenciesLock.exists() && !shouldIgnoreDependencyLock()) {
                applyLock(dependenciesLock, overrides)
            } else if (!shouldIgnoreDependencyLock()) {
                applyOverrides(overrides)
            }
        }
    }

    private void configureCommitTask(String clLockFileName, SaveLockTask saveTask, DependencyLockExtension lockExtension,
            DependencyLockCommitExtension commitExtension) {
        project.plugins.withType(ScmPlugin) {
            if (!project.rootProject.tasks.findByName('commitLock')) {
                CommitLockTask commitTask = project.rootProject.tasks.create('commitLock', CommitLockTask)
                commitTask.mustRunAfter(saveTask)
                commitTask.conventionMapping.with {
                    scmFactory = { project.rootProject.scmFactory }
                    commitMessage = { project.hasProperty('commitDependencyLock.message') ? 
                            project['commitDependencyLock.message'] : commitExtension.message }
                    patternsToCommit = {
                        def lockFiles = []
                        def rootLock = new File(project.rootProject.projectDir, clLockFileName ?: lockExtension.lockFile)
                        if (rootLock.exists()) {
                            lockFiles << rootLock
                        }
                        project.rootProject.subprojects.each {
                            def potentialLock = new File(it.projectDir, clLockFileName ?: lockExtension.lockFile)
                            if (potentialLock.exists()) {
                                lockFiles << potentialLock
                            }
                        }
                        def patterns = lockFiles.collect { project.rootProject.projectDir.toURI().relativize(it.toURI()).path }
                        logger.info(patterns.toString())
                        patterns
                    }
                    shouldCreateTag = { project.hasProperty('commitDependencyLock.tag') ?: commitExtension.shouldCreateTag }
                    tag = { project.hasProperty('commitDependencyLock.tag') ? project['commitDependencyLock.tag'] : commitExtension.tag.call() }
                    remoteRetries = { commitExtension.remoteRetries }
                }
            }
        }
    }

    private SaveLockTask configureSaveTask(String clLockFileName, DependencyLockExtension extension) {
        SaveLockTask saveTask = project.tasks.create('saveLock', SaveLockTask)
        saveTask.conventionMapping.with {
            generatedLock = { new File(project.buildDir, clLockFileName ?: extension.lockFile) }
            outputLock = { new File(project.projectDir, extension.lockFile) }
        }
        saveTask.outputs.upToDateWhen {
            if (saveTask.generatedLock.exists() && saveTask.outputLock.exists()) {
                saveTask.generatedLock.text == saveTask.outputLock.text
            } else {
                false
            }
        }

        saveTask
    }

    private GenerateLockTask configureLockTask(GenerateLockTask lockTask, String clLockFileName, DependencyLockExtension extension, Map overrides) {
        lockTask.conventionMapping.with {
            dependenciesLock = {
                new File(project.buildDir, clLockFileName ?: extension.lockFile)
            }
            configurationNames = { extension.configurationNames }
            filter = { extension.dependencyFilter }
            skippedDependencies = { extension.skippedDependencies }
            includeTransitives = { project.hasProperty('dependencyLock.includeTransitives') ? Boolean.parseBoolean(project['dependencyLock.includeTransitives']) : extension.includeTransitives }
        }
        lockTask.overrides = overrides

        lockTask
    }

    private configureUpdateTask(UpdateLockTask lockTask, DependencyLockExtension extension) {
        // You can't read a property at the same time you define the convention mapping ∞
        def updatesFromOption = lockTask.dependencies
        lockTask.conventionMapping.dependencies = { updatesFromOption ?: extension.updateDependencies }

        lockTask
    }

    void applyOverrides(Map overrides) {
        if (project.hasProperty('dependencyLock.overrideFile')) {
            logger.info("Using override file ${project['dependencyLock.overrideFile']} to lock dependencies")
        }
        if (project.hasProperty('dependencyLock.override')) {
            logger.info("Using command line overrides ${project['dependencyLock.override']}")
        }

        def overrideForces = overrides.collect { "${it.key}:${it.value}" }
        logger.debug(overrideForces.toString())

        project.configurations.all {
            resolutionStrategy {
                overrideForces.each { dep -> force dep}
            }
        }
    }

    void applyLock(File dependenciesLock, Map overrides) {
        logger.info("Using ${dependenciesLock.name} to lock dependencies")
        def locks = loadLock(dependenciesLock)

        // Non-project locks are the top-level dependencies, and possibly transitive thereof, of this project which are
        // locked by the lock file. There may also be dependencies on other projects. These are not captured here.
        def nonProjectLocks = locks.findAll { it.value?.locked }

        // Override locks from the file with any of the user specified manual overrides.
        def lockForces = nonProjectLocks.collect {
            overrides.containsKey(it.key) ? "${it.key}:${overrides[it.key]}" : "${it.key}:${it.value.locked}"
        }

        // If the user specifies an override that does not exist in the lock file, force that dependency anyway.
        def unusedOverrides = overrides.findAll { !locks.containsKey(it.key) }.collect { "${it.key}:${it.value}" }
        lockForces << unusedOverrides
        logger.debug(lockForces.toString())

        // Pretty nice after all that work (:
        project.configurations.all {
            resolutionStrategy {
                lockForces.each { dep -> force dep}
            }
        }
    }

    boolean shouldIgnoreDependencyLock() {
        if (project.hasProperty('dependencyLock.ignore')) {
            def prop = project.property('dependencyLock.ignore')

            (prop instanceof String) ? prop.toBoolean() : prop.asBoolean()
        } else {
            false
        }
    }

    private Map loadOverrides() {
        // Overrides are dependencies that trump the lock file.
        Map overrides = [:]
        if (shouldIgnoreDependencyLock()) {
            return overrides
        }

        // Load overrides from a file if the user has specified one via a property.
        if (project.hasProperty('dependencyLock.overrideFile')) {
            File dependenciesLock = new File(project.rootDir, project['dependencyLock.overrideFile'])
            loadLock(dependenciesLock).each { overrides[it.key] = it.value.locked }
            logger.debug "Override file loaded: ${project['dependencyLock.overrideFile']}"
        }

        // Allow the user to specify overrides via a property as well.
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
