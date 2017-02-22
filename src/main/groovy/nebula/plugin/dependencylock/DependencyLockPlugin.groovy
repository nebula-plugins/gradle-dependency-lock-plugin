/*
 * Copyright 2014-2016 Netflix, Inc.
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
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Delete
import org.gradle.util.NameMatcher

import static nebula.plugin.dependencylock.tasks.GenerateLockTask.getConfigurationsFromConfigurationNames

class DependencyLockPlugin implements Plugin<Project> {
    private static final Logger LOGGER = Logging.getLogger(DependencyLockPlugin)

    private static final String EXTENSION_NAME = 'dependencyLock'
    private static final String COMMIT_EXTENSION_NAME = 'commitDependencyLock'
    private static final String GLOBAL_LOCK_FILE = 'dependencyLock.globalLockFile'
    private static final String LOCK_FILE = 'dependencyLock.lockFile'
    private static final String LOCK_AFTER_EVALUATING = 'dependencyLock.lockAfterEvaluating'
    private static final String USE_GENERATED_LOCK = 'dependencyLock.useGeneratedLock'
    private static final String USE_GENERATED_GLOBAL_LOCK = 'dependencyLock.useGeneratedGlobalLock'
    private static final String UPDATE_DEPENDENCIES = 'dependencyLock.updateDependencies'
    private static final String OVERRIDE_FILE = 'dependencyLock.overrideFile'
    private static final String OVERRIDE = 'dependencyLock.override'

    private static final List<String> GENERATION_TASK_NAMES = [GENERATE_LOCK_TASK_NAME, GENERATE_GLOBAL_LOCK_TASK_NAME,
                                                               UPDATE_LOCK_TASK_NAME, UPDATE_GLOBAL_LOCK_TASK_NAME]
    private static final List<String> UPDATE_TASK_NAMES = [UPDATE_LOCK_TASK_NAME, UPDATE_GLOBAL_LOCK_TASK_NAME]

    public static final String GLOBAL_LOCK_CONFIG = '_global_'
    public static final String GENERATE_GLOBAL_LOCK_TASK_NAME = 'generateGlobalLock'
    public static final String UPDATE_GLOBAL_LOCK_TASK_NAME = 'updateGlobalLock'
    public static final String GENERATE_LOCK_TASK_NAME = 'generateLock'
    public static final String UPDATE_LOCK_TASK_NAME = 'updateLock'
    public static final String SAVE_LOCK_TASK_NAME = 'saveLock'
    public static final String SAVE_GLOBAL_LOCK_TASK_NAME = 'saveGlobalLock'

    Project project

    @Override
    void apply(Project project) {
        this.project = project

        DependencyLockExtension extension = project.extensions.create(EXTENSION_NAME, DependencyLockExtension)
        DependencyLockCommitExtension commitExtension = project.rootProject.extensions.findByType(DependencyLockCommitExtension)
        if (!commitExtension) {
            commitExtension = project.rootProject.extensions.create(COMMIT_EXTENSION_NAME, DependencyLockCommitExtension)
        }

        Map overrides = loadOverrides()
        configureTasks(extension, commitExtension, overrides)

        def lockAfterEvaluating = project.hasProperty(LOCK_AFTER_EVALUATING) ? Boolean.parseBoolean(project[LOCK_AFTER_EVALUATING] as String) : extension.lockAfterEvaluating
        if (lockAfterEvaluating) {
            LOGGER.info("Delaying dependency lock apply until beforeResolve ($LOCK_AFTER_EVALUATING set to true)")
        } else {
            LOGGER.info("Applying dependency lock during plugin apply ($LOCK_AFTER_EVALUATING set to false)")
        }

        // We do this twice to catch resolves that happen during build evaluation, and ensure that we clobber configurations made during evaluation
        disableCachingForGenerateLock()
        project.gradle.taskGraph.whenReady { TaskExecutionGraph taskGraph ->
            disableCachingForGenerateLock()
        }

        project.configurations.all({ conf ->
            if (lockAfterEvaluating) {
                conf.incoming.beforeResolve {
                    maybeApplyLock(conf, extension, overrides)
                }
            } else {
                maybeApplyLock(conf, extension, overrides)
            }
        })
    }

    private void disableCachingForGenerateLock() {
        if (hasGenerationTask(project.gradle.startParameter.taskNames)) {
            project.configurations.all({ configuration ->
                if (configuration.state == Configuration.State.UNRESOLVED) {
                    configuration.resolutionStrategy {
                        cacheDynamicVersionsFor 0, 'seconds'
                        cacheChangingModulesFor 0, 'seconds'
                    }
                }
            })
        }
    }

    private void configureTasks(DependencyLockExtension extension, DependencyLockCommitExtension commitExtension, Map overrides) {
        File lockFile = project.hasProperty(LOCK_FILE) ? new File(project.projectDir, project[LOCK_FILE]) : null

        GenerateLockTask genLockTask = project.tasks.create(GENERATE_LOCK_TASK_NAME, GenerateLockTask)
        configureLockTask(genLockTask, lockFile, extension, overrides)
        if (project.hasProperty(USE_GENERATED_LOCK)) {
            lockFile = genLockTask.getDependenciesLock()
        }

        UpdateLockTask updateLockTask = project.tasks.create(UPDATE_LOCK_TASK_NAME, UpdateLockTask)
        configureLockTask(updateLockTask, lockFile, extension, overrides)

        SaveLockTask saveTask = configureSaveTask(lockFile, genLockTask, updateLockTask, extension)
        createDeleteLock(saveTask)

        // configure global lock only on rootProject
        SaveLockTask globalSave = null
        GenerateLockTask globalLockTask
        UpdateLockTask globalUpdateLock
        if (project == project.rootProject) {
            File globalLockFile = project.hasProperty(GLOBAL_LOCK_FILE) ? new File(project.rootProject.projectDir, project[GLOBAL_LOCK_FILE]) : null

            globalLockTask = project.tasks.create(GENERATE_GLOBAL_LOCK_TASK_NAME, GenerateLockTask)
            configureGlobalLockTask(globalLockTask, globalLockFile, extension, overrides)

            if (project.hasProperty(USE_GENERATED_GLOBAL_LOCK)) {
                globalLockFile = globalLockTask.getDependenciesLock()
            }
            globalUpdateLock = project.tasks.create(UPDATE_GLOBAL_LOCK_TASK_NAME, UpdateLockTask)
            configureGlobalLockTask(globalUpdateLock, globalLockFile, extension, overrides)
            globalSave = configureGlobalSaveTask(globalLockFile, globalLockTask, globalUpdateLock, extension)
            createDeleteGlobalLock(globalSave)
        }

        configureCommitTask(saveTask, commitExtension, globalSave)
    }

    private void configureCommitTask(SaveLockTask saveTask, DependencyLockCommitExtension commitExtension, SaveLockTask globalSaveTask) {
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
                        List<String> patterns = project.rootProject.allprojects.collect { pr ->
                            pr.tasks.withType(SaveLockTask).collect { slt ->
                                project.rootProject.relativePath(slt.outputLock)
                            }
                        }.flatten()

                        LOGGER.info(patterns.toString())
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

    private SaveLockTask configureSaveTask(File lockFile, GenerateLockTask lockTask, UpdateLockTask updateTask, DependencyLockExtension extension) {
        SaveLockTask saveTask = project.tasks.create(SAVE_LOCK_TASK_NAME, SaveLockTask)
        saveTask.doFirst {
            SaveLockTask globalSave = project.rootProject.tasks.findByName(SAVE_GLOBAL_LOCK_TASK_NAME) as SaveLockTask
            if (globalSave?.outputLock?.exists()) {
                throw new GradleException('Cannot save individual locks when global lock is in place, run deleteGlobalLock task')
            }
        }
        saveTask.conventionMapping.with {
            generatedLock = { lockTask.dependenciesLock }
            outputLock = { lockFile ?: new File(project.projectDir, extension.lockFile) }
        }
        configureCommonSaveTask(saveTask, lockTask, updateTask)

        saveTask
    }

    private static void configureCommonSaveTask(SaveLockTask saveTask, GenerateLockTask lockTask,
                                                UpdateLockTask updateTask) {
        saveTask.mustRunAfter lockTask, updateTask
        saveTask.outputs.upToDateWhen {
            if (saveTask.generatedLock.exists() && saveTask.outputLock.exists()) {
                saveTask.generatedLock.text == saveTask.outputLock.text
            } else {
                false
            }
        }
    }

    private SaveLockTask configureGlobalSaveTask(File globalLockFile, GenerateLockTask globalLockTask,
                                                 UpdateLockTask globalUpdateLockTask, DependencyLockExtension extension) {
        SaveLockTask globalSaveTask = project.tasks.create(SAVE_GLOBAL_LOCK_TASK_NAME, SaveLockTask)
        globalSaveTask.doFirst {
            project.subprojects.each { Project sub ->
                SaveLockTask save = sub.tasks.findByName(SAVE_LOCK_TASK_NAME) as SaveLockTask
                if (save && save.outputLock?.exists()) {
                    throw new GradleException('Cannot save global lock, one or more individual locks are in place, run deleteLock task')
                }
            }
        }
        globalSaveTask.conventionMapping.with {
            generatedLock = { globalLockTask.dependenciesLock }
            outputLock = { globalLockFile ?: new File(project.projectDir, extension.globalLockFile) }
        }
        configureCommonSaveTask(globalSaveTask, globalLockTask, globalUpdateLockTask)

        globalSaveTask
    }

    private GenerateLockTask configureLockTask(GenerateLockTask lockTask, File clLockFile, DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(lockTask, extension, overrides)
        lockTask.conventionMapping.with {
            dependenciesLock = {
                clLockFile ?: new File(project.buildDir, extension.lockFile)
            }
            configurationNames = { extension.configurationNames }
        }

        lockTask
    }

    private void setupLockConventionMapping(GenerateLockTask task, DependencyLockExtension extension, Map overrideMap) {
        task.conventionMapping.with {
            skippedDependencies = { extension.skippedDependencies }
            includeTransitives = {
                project.hasProperty('dependencyLock.includeTransitives') ? Boolean.parseBoolean(project['dependencyLock.includeTransitives'] as String) : extension.includeTransitives
            }
            filter = { extension.dependencyFilter }
            overrides = { overrideMap }
        }
    }

    private GenerateLockTask configureGlobalLockTask(GenerateLockTask globalLockTask, File globalLockFile, DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(globalLockTask, extension, overrides)
        globalLockTask.doFirst {
            project.subprojects.each { sub -> sub.repositories.each { repo -> project.repositories.add(repo) } }
        }
        globalLockTask.conventionMapping.with {
            dependenciesLock = {
                globalLockFile ?: new File(project.buildDir, extension.globalLockFile)
            }
            configurations = {
                def subprojects = project.subprojects.collect { subproject ->
                    def ext = subproject.getExtensions().findByType(DependencyLockExtension)
                    if (ext != null) {
                        def configurations = getConfigurationsFromConfigurationNames(project, subproject, ext.configurationNames)
                        configurations.collect { configuration ->
                            project.dependencies.create(project.dependencies.project(path: subproject.path, configuration: configuration.name))
                        }
                    } else {
                        [project.dependencies.create(subproject)]
                    }
                }.flatten()
                def subprojectsArray = subprojects.toArray(new Dependency[subprojects.size()])
                def conf = project.configurations.detachedConfiguration(subprojectsArray)
                project.allprojects.each { it.configurations.add(conf) }

                [conf] + getConfigurationsFromConfigurationNames(project, project, extension.configurationNames)
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

    private void maybeApplyLock(Configuration conf, DependencyLockExtension extension, Map overrides) {

        SaveLockTask save = project.tasks.findByName(SAVE_LOCK_TASK_NAME) as SaveLockTask
        SaveLockTask saveGlobal = project.rootProject.tasks.findByName(SAVE_GLOBAL_LOCK_TASK_NAME) as SaveLockTask

        File dependenciesLock

        File globalLock = saveGlobal?.outputLock
        if (globalLock && globalLock.exists()) {
            dependenciesLock = globalLock
        } else {
            dependenciesLock = save.outputLock
        }

        boolean appliedLock = false
        if (!shouldIgnoreDependencyLock()) {
            def taskNames = project.gradle.startParameter.taskNames
            boolean hasGenerateTask = hasGenerationTask(taskNames)
            if (dependenciesLock.exists()) {
                if (!hasGenerateTask) {
                    applyLock(conf, dependenciesLock, overrides)
                    appliedLock = true
                } else if (hasUpdateTask(taskNames)) {
                    def updates = project.hasProperty(UPDATE_DEPENDENCIES) ? parseUpdates(project.property(UPDATE_DEPENDENCIES) as String) : extension.updateDependencies
                    applyLock(conf, dependenciesLock, overrides, updates)
                    appliedLock = true
                }
            }
            if (!appliedLock) {
                applyOverrides(conf, overrides)
            }
        }
    }

    private boolean hasGenerationTask(Collection<String> cliTasks) {
        hasTask(cliTasks, GENERATION_TASK_NAMES)
    }

    private boolean hasUpdateTask(Collection<String> cliTasks) {
        hasTask(cliTasks, UPDATE_TASK_NAMES)
    }

    private boolean hasTask(Collection<String> cliTasks, Collection<String> taskNames) {
        def matcher = new NameMatcher()
        def found = cliTasks.find { cliTaskName ->
            def tokens = cliTaskName.split(':')
            def taskName = tokens.last()
            def generatesPresent = matcher.find(taskName, taskNames)
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

    private static Set<String> parseUpdates(String updates) {
        updates.tokenize(',') as Set
    }

    void applyLock(Configuration conf, File dependenciesLock, Map overrides, Collection<String> updates = []) {
        LOGGER.info("Using ${project.relativePath(dependenciesLock)} to lock dependencies in $conf")
        def locks = loadLock(dependenciesLock)

        if (updates) {
            locks = locks.collectEntries { configurationName, deps -> [(configurationName): deps.findAll { coord, info -> (info.transitive == null) && !updates.contains(coord) }] }
        }

        // in the old format, all first level props were groupId:artifactId
        def isDeprecatedFormat = !locks.isEmpty() && locks.every { it.key ==~ /[^:]+:.+/ }
        // in the old format, all first level props were groupId:artifactId
        if (isDeprecatedFormat) {
            LOGGER.warn("${dependenciesLock.name} is using a deprecated lock format. Support for this format may be removed in future versions.")
        }

        // In the old format of the lock file, there was only one locked setting. In that case, apply it on all configurations.
        // In the new format, apply _global_ to all configurations or use the config name
        def notations = isDeprecatedFormat ? locks : locks[GLOBAL_LOCK_CONFIG] ?: locks[conf.name]
        if (notations) {
            // Non-project locks are the top-level dependencies, and possibly transitive thereof, of this project which are
            // locked by the lock file. There may also be dependencies on other projects. These are not captured here.
            def nonProjectLocks = notations.findAll { it.value?.locked }

            // Override locks from the file with any of the user specified manual overrides.
            def locked = nonProjectLocks.collect {
                overrides.containsKey(it.key) ? "${it.key}:${overrides[it.key]}" : "${it.key}:${it.value.locked}"
            }

            // If the user specifies an override that does not exist in the lock file, force that dependency anyway.
            def unusedOverrides = overrides.findAll { !locks.containsKey(it.key) }.collect {
                "${it.key}:${it.value}"
            }

            locked.addAll(unusedOverrides)
            LOGGER.debug('locked: {}', locked)

            lockConfiguration(conf, locked)
        }
    }

    void applyOverrides(Configuration conf, Map overrides) {
        if (project.hasProperty(OVERRIDE_FILE)) {
            LOGGER.info("Using override file ${project[OVERRIDE_FILE]} to lock dependencies")
        }
        if (project.hasProperty(OVERRIDE)) {
            LOGGER.info("Using command line overrides ${project[OVERRIDE]}")
        }

        def overrideDeps = overrides.collect { "${it.key}:${it.value}" }
        LOGGER.debug('overrides: {}', overrideDeps)
        lockConfiguration(conf, overrideDeps)
    }

    private void lockConfiguration(Configuration conf, List<String> dependencyNotations) {
        def dependencies = dependencyNotations.collect { project.dependencies.create(it) }
        conf.resolutionStrategy.eachDependency { details ->
            dependencies.each { dep ->
                if (details.requested.group == dep.group && details.requested.name == dep.name) {
                    details.useTarget group: details.requested.group, name: details.requested.name, version: dep.version
                }
            }
        }
    }

    private boolean shouldIgnoreDependencyLock() {
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

        // Load overrides from a file if the user has specified one via a property.
        if (project.hasProperty(OVERRIDE_FILE)) {
            File dependenciesLock = new File(project.rootDir, project[OVERRIDE_FILE] as String)
            def lockOverride = loadLock(dependenciesLock)
            def isDeprecatedFormat = lockOverride.any { it.value.getClass() != String && it.value.locked }
            // the old lock override files specified the version to override under the "locked" property
            if (isDeprecatedFormat) {
                LOGGER.warn("The override file ${dependenciesLock.name} is using a deprecated format. Support for this format may be removed in future versions.")
            }
            lockOverride.each { overrides[it.key] = isDeprecatedFormat ? it.value.locked : it.value }
            LOGGER.debug "Override file loaded: ${project[OVERRIDE_FILE]}"
        }

        // Allow the user to specify overrides via a property as well.
        if (project.hasProperty('dependencyLock.override')) {
            project['dependencyLock.override'].tokenize(',').each {
                def (group, artifact, version) = it.tokenize(':')
                overrides["${group}:${artifact}".toString()] = version
                LOGGER.debug "Override added for: ${it}"
            }
        }

        return overrides
    }

    private static loadLock(File lock) {
        try {
            return new JsonSlurper().parseText(lock.text)
        } catch (ex) {
            LOGGER.debug('Unreadable json file: ' + lock.text)
            LOGGER.error('JSON unreadable')
            throw new GradleException("${lock.name} is unreadable or invalid json, terminating run", ex)
        }
    }
}
