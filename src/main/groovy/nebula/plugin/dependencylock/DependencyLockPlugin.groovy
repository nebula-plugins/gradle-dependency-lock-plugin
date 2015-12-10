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
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Delete
import org.gradle.util.NameMatcher

import static nebula.plugin.dependencylock.tasks.GenerateLockTask.getConfigurationsFromConfigurationNames

class DependencyLockPlugin implements Plugin<Project> {
    public static final String GLOBAL_LOCK_CONFIG = '_global_'

    private static Logger logger = Logging.getLogger(DependencyLockPlugin)
    public static final String GENERATE_GLOBAL_LOCK_TASK_NAME = 'generateGlobalLock'
    public static final String UPDATE_GLOBAL_LOCK_TASK_NAME = 'updateGlobalLock'
    public static final String GENERATE_LOCK_TASK_NAME = 'generateLock'
    public static final String UPDATE_LOCK_TASK_NAME = 'updateLock'
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

        GenerateLockTask genLockTask = project.tasks.create(GENERATE_LOCK_TASK_NAME, GenerateLockTask)
        configureLockTask(genLockTask, clLockFileName, extension, overrides)
        if (project.hasProperty('dependencyLock.useGeneratedLock')) {
            clLockFileName = genLockTask.getDependenciesLock().path
            logger.lifecycle(clLockFileName)
        }

        UpdateLockTask updateLockTask = project.tasks.create(UPDATE_LOCK_TASK_NAME, UpdateLockTask)
        configureLockTask(updateLockTask, clLockFileName, extension, overrides)
        configureUpdateTask(updateLockTask, extension)

        //DiffLockTask diffLockTask = project.tasks.create('diffLock', DiffLockTask)
        //configureDiffTask(diffLockTask, genLockTask, clLockFileName)

        SaveLockTask saveTask = configureSaveTask(clLockFileName, genLockTask, updateLockTask, extension)
        createDeleteLock(saveTask)

        // configure global lock only on rootProject
        SaveLockTask globalSave
        String globalLockFileName = project.hasProperty('dependencyLock.globalLockFile') ? project['dependencyLock.globalLockFile'] : null
        GenerateLockTask globalLockTask
        UpdateLockTask globalUpdateLock
        if (project == project.rootProject) {
            globalLockTask = project.tasks.create(GENERATE_GLOBAL_LOCK_TASK_NAME, GenerateLockTask)
            if (project.hasProperty('dependencyLock.useGeneratedGlobalLock')) {
                globalLockFileName = globalLockTask.getDependenciesLock().path
            }
            configureGlobalLockTask(globalLockTask, globalLockFileName, extension, overrides)
            globalUpdateLock = project.tasks.create(UPDATE_GLOBAL_LOCK_TASK_NAME, UpdateLockTask)
            configureGlobalLockTask(globalUpdateLock, globalLockFileName, extension, overrides)
            configureUpdateTask(globalUpdateLock, extension)
            globalSave = configureGlobalSaveTask(globalLockFileName, globalLockTask, globalUpdateLock, extension)
            createDeleteGlobalLock(globalSave)
        }

        configureCommitTask(clLockFileName, globalLockFileName, saveTask, extension, commitExtension, globalSave)

        def lockAfterEvaluating = project.hasProperty('dependencyLock.lockAfterEvaluating') ? Boolean.parseBoolean(project['dependencyLock.lockAfterEvaluating']) : extension.lockAfterEvaluating
        if (lockAfterEvaluating) {
            logger.info('Applying dependency lock in afterEvaluate block')
            project.afterEvaluate { applyLockToResolutionStrategy(extension, overrides, globalLockFileName, clLockFileName) }
        } else {
            logger.info('Applying dependency lock as is (outside afterEvaluate block)')
            applyLockToResolutionStrategy(extension, overrides, globalLockFileName, clLockFileName)
        }

        project.gradle.taskGraph.whenReady { taskGraph ->
            def hasLockingTask = taskGraph.hasTask(genLockTask) || taskGraph.hasTask(updateLockTask) || ((project == project.rootProject) && (taskGraph.hasTask(globalLockTask) || taskGraph.hasTask(globalUpdateLock)))
            if (hasLockingTask) {
                project.configurations.all {
                    resolutionStrategy {
                        cacheDynamicVersionsFor 0, 'seconds'
                        cacheChangingModulesFor 0, 'seconds'
                    }
                }
                if (!shouldIgnoreDependencyLock()) {
                    applyOverrides(overrides)
                }
            }
        }
    }

    private void applyLockToResolutionStrategy(DependencyLockExtension extension, Map overrides, String globalLockFileName, String clLockFileName) {
        if (extension.configurationNames.empty) {
            extension.configurationNames = project.configurations.collect { it.name }
        }

        File dependenciesLock
        File globalLock = new File(project.rootProject.projectDir, globalLockFileName ?: extension.globalLockFile)
        if (globalLock.exists()) {
            dependenciesLock = globalLock
        } else {
            dependenciesLock = new File(project.projectDir, clLockFileName ?: extension.lockFile)
        }

        def taskNames = project.gradle.startParameter.taskNames

        if (dependenciesLock.exists() && !shouldIgnoreDependencyLock() && !hasGenerationTask(taskNames)) {
            applyLock(dependenciesLock, overrides)
        } else if (!shouldIgnoreDependencyLock()) {
            applyOverrides(overrides)
        }
    }

    private boolean hasGenerationTask(Collection<String> cliTasks) {
        def matcher = new NameMatcher()
        def found = cliTasks.find { cliTaskName ->
            def tokens = cliTaskName.split(':')
            def taskName = tokens.last()
            def generatesPresent = matcher.find(taskName, [GENERATE_LOCK_TASK_NAME, GENERATE_GLOBAL_LOCK_TASK_NAME,
                    UPDATE_LOCK_TASK_NAME, UPDATE_GLOBAL_LOCK_TASK_NAME])

            generatesPresent && taskRunOnThisProject(tokens)
        }

        found != null
    }

    private boolean taskRunOnThisProject(String[] tokens) {
        if (tokens.size() == 1) { // task run globally
            return true
        } else if (tokens.size() == 2 && tokens[0] == '') { // running fully qualified on root project
            return project == project.rootProject
        } else { // the task is being run on a specific project
            return project.name == tokens[-2]
        }
    }

    private void configureCommitTask(String clLockFileName, String globalLockFileName, SaveLockTask saveTask, DependencyLockExtension lockExtension,
                                     DependencyLockCommitExtension commitExtension, SaveLockTask globalSaveTask = null) {
        project.plugins.withType(ScmPlugin) {
            if (!project.rootProject.tasks.findByName('commitLock')) {
                CommitLockTask commitTask = project.rootProject.tasks.create('commitLock', CommitLockTask)
                commitTask.mustRunAfter(saveTask)
                if (globalSaveTask) {
                    commitTask.mustRunAfter(globalSaveTask)
                }
                commitTask.conventionMapping.with {
                    scmFactory = { project.rootProject.scmFactory }
                    commitMessage = {
                        project.hasProperty('commitDependencyLock.message') ?
                                project['commitDependencyLock.message'] : commitExtension.message
                    }
                    patternsToCommit = {
                        def lockFiles = []
                        def rootLock = new File(project.rootProject.projectDir, clLockFileName ?: lockExtension.lockFile)
                        if (rootLock.exists()) {
                            lockFiles << rootLock
                        }
                        def globalLock = new File(project.rootProject.projectDir, globalLockFileName ?: lockExtension.globalLockFile)
                        if (globalLock.exists()) {
                            lockFiles << globalLock
                        }
                        project.rootProject.subprojects.each {
                            def potentialLock = new File(it.projectDir, clLockFileName ?: lockExtension.lockFile)
                            if (potentialLock.exists()) {
                                lockFiles << potentialLock
                            }
                        }
                        def patterns = lockFiles.collect {
                            project.rootProject.projectDir.toURI().relativize(it.toURI()).path
                        }
                        logger.info(patterns.toString())
                        patterns
                    }
                    shouldCreateTag = {
                        project.hasProperty('commitDependencyLock.tag') ?: commitExtension.shouldCreateTag
                    }
                    tag = {
                        project.hasProperty('commitDependencyLock.tag') ? project['commitDependencyLock.tag'] : commitExtension.tag.call()
                    }
                    remoteRetries = { commitExtension.remoteRetries }
                }
            }
        }
    }

    private SaveLockTask configureSaveTask(String lockFileName, GenerateLockTask lockTask, UpdateLockTask updateTask, DependencyLockExtension extension) {
        SaveLockTask saveTask = project.tasks.create('saveLock', SaveLockTask)
        saveTask.doFirst {
            SaveLockTask globalSave = project.rootProject.tasks.findByName('saveGlobalLock')
            if (globalSave?.outputLock?.exists()) {
                throw new GradleException('Cannot save individual locks when global lock is in place, run deleteGlobalLock task')
            }
        }
        saveTask.conventionMapping.with {
            generatedLock = { lockTask.dependenciesLock }
            outputLock = { new File(project.projectDir, lockFileName ?: extension.lockFile) }
        }
        configureCommonSaveTask(saveTask, lockTask, updateTask)

        saveTask
    }

    private void configureCommonSaveTask(SaveLockTask saveTask, GenerateLockTask lockTask, UpdateLockTask updateTask) {
        saveTask.mustRunAfter lockTask, updateTask
        saveTask.outputs.upToDateWhen {
            if (saveTask.generatedLock.exists() && saveTask.outputLock.exists()) {
                saveTask.generatedLock.text == saveTask.outputLock.text
            } else {
                false
            }
        }
    }

    private SaveLockTask configureGlobalSaveTask(String globalLockFileName, GenerateLockTask globalLockTask, UpdateLockTask globalUpdateLockTask, DependencyLockExtension extension) {
        SaveLockTask globalSaveTask = project.tasks.create('saveGlobalLock', SaveLockTask)
        globalSaveTask.doFirst {
            project.subprojects.each { Project sub ->
                SaveLockTask save = sub.tasks.findByName('saveLock')
                if (save && save.outputLock?.exists()) {
                    throw new GradleException('Cannot save global lock, one or more individual locks are in place, run deleteLock task')
                }
            }
        }
        globalSaveTask.conventionMapping.with {
            generatedLock = { globalLockTask.dependenciesLock }
            outputLock = { new File(project.projectDir, globalLockFileName ?: extension.globalLockFile) }
        }
        configureCommonSaveTask(globalSaveTask, globalLockTask, globalUpdateLockTask)

        globalSaveTask
    }

    private GenerateLockTask configureLockTask(GenerateLockTask lockTask, String clLockFileName, DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(lockTask, extension, overrides)
        lockTask.conventionMapping.with {
            dependenciesLock = {
                new File(project.buildDir, clLockFileName ?: extension.lockFile)
            }
            configurationNames = { extension.configurationNames }
        }

        lockTask
    }

    private void setupLockConventionMapping(GenerateLockTask task, DependencyLockExtension extension, Map overrideMap) {
        task.conventionMapping.with {
            skippedDependencies = { extension.skippedDependencies }
            includeTransitives = {
                project.hasProperty('dependencyLock.includeTransitives') ? Boolean.parseBoolean(project['dependencyLock.includeTransitives']) : extension.includeTransitives
            }
            filter = { extension.dependencyFilter }
            overrides = { overrideMap }
        }
    }

    private GenerateLockTask configureGlobalLockTask(GenerateLockTask globalLockTask, String globalLockFileName, DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(globalLockTask, extension, overrides)
        globalLockTask.doFirst {
            project.subprojects.each { sub -> sub.repositories.each { repo -> project.repositories.add(repo) } }
        }
        globalLockTask.conventionMapping.with {
            dependenciesLock = {
                new File(project.buildDir, globalLockFileName ?: extension.globalLockFile)
            }
            configurations = {
                def subprojects = project.subprojects.collect { subproject ->
                    def ext = subproject.getExtensions().findByType(DependencyLockExtension)
                    if (ext != null) {
                        ext.configurationNames.collect { subconf ->
                            project.dependencies.create(project.dependencies.project(path: subproject.path, configuration: subconf))
                        }
                    } else {
                        [project.dependencies.create(subproject)]
                    }
                }.flatten()
                def subprojectsArray = subprojects.toArray(new Dependency[subprojects.size()])
                def conf = project.configurations.detachedConfiguration(subprojectsArray)
                project.allprojects.each { it.configurations.add(conf) }

                [conf] + getConfigurationsFromConfigurationNames(project, extension.configurationNames)
            }
        }

        globalLockTask
    }

    private void createDeleteLock(SaveLockTask saveLock) {
        project.tasks.create('deleteLock', Delete) {
            delete saveLock.outputLock
        }
    }

    private void createDeleteGlobalLock(SaveLockTask saveGlobalLock) {
        project.tasks.create('deleteGlobalLock', Delete) {
            delete saveGlobalLock.outputLock
        }
    }

    private configureUpdateTask(UpdateLockTask lockTask, DependencyLockExtension extension) {
        // You can't read a property at the same time you define the convention mapping ∞
        def updatesFromOption = lockTask.dependencies
        lockTask.conventionMapping.dependencies = { updatesFromOption ?: extension.updateDependencies }

        lockTask
    }

    /*private configureDiffTask(DiffLockTask diffLockTask, GenerateLockTask generateLockTask, String lockFileName, DependencyLockExtension extension) {
        diffLockTask.conventionMapping.with {
            existingLock = { new File(project.projectDir, lockFileName ?: extension.lockFile) }
        }

        diffLockTask.newLock = generateLockTask.dependenciesLock
        diffLockTask.output = project.file('build/reports/dependencylock/lockdiff.txt')
    }*/

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
                overrideForces.each { dep -> force dep }
            }
        }
    }

    void applyLock(File dependenciesLock, Map overrides) {
        logger.info("Using ${dependenciesLock.name} to lock dependencies")
        def locks = loadLock(dependenciesLock)

        def isDeprecatedFormat = locks.every { it.key ==~ /[^:]+:.+/ } // in the old format, all first level props were groupId:artifactId
        if (isDeprecatedFormat) {
            logger.warn("${dependenciesLock.name} is using a deprecated lock format. Support for this format may be removed in future versions.")
        }

        project.configurations.all({ Configuration conf ->
            // In the old format of the lock file, there was only one locked setting. In that case, apply it on all configurations.
            // In the new format, apply _global_ to all configurations or use the config name
            def deps = isDeprecatedFormat ? locks : locks[GLOBAL_LOCK_CONFIG] ?: locks[conf.name]
            if (deps) {
                // Non-project locks are the top-level dependencies, and possibly transitive thereof, of this project which are
                // locked by the lock file. There may also be dependencies on other projects. These are not captured here.
                def nonProjectLocks = deps.findAll { it.value?.locked }

                // Override locks from the file with any of the user specified manual overrides.
                def lockForces = nonProjectLocks.collect {
                    overrides.containsKey(it.key) ? "${it.key}:${overrides[it.key]}" : "${it.key}:${it.value.locked}"
                }

                // If the user specifies an override that does not exist in the lock file, force that dependency anyway.
                def unusedOverrides = overrides.findAll { !locks.containsKey(it.key) }.collect {
                    "${it.key}:${it.value}"
                }

                lockForces.addAll(unusedOverrides)
                logger.debug('lockForces: {}', lockForces)

                // Create the dependencies explicitly to avoid doing that implicitly for every configuration
                lockForces = lockForces.collect { dep -> project.dependencies.create(dep) }
                resolutionStrategy {
                    force lockForces.toArray()
                }
            }
        })
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
            def lockOverride = loadLock(dependenciesLock)
            def isDeprecatedFormat = lockOverride.any { it.value.getClass() != String && it.value.locked } // the old lock override files specified the version to override under the "locked" property
            if (isDeprecatedFormat) {
                logger.warn("The override file ${dependenciesLock.name} is using a deprecated format. Support for this format may be removed in future versions.")
            }
            lockOverride.each { overrides[it.key] = isDeprecatedFormat ? it.value.locked : it.value }
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

        return overrides
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
