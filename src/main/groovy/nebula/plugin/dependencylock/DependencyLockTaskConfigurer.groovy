/*
 * Copyright 2014-2019 Netflix, Inc.
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

import nebula.plugin.dependencylock.tasks.CommitLockTask
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.tasks.MigrateToCoreLocksTask
import nebula.plugin.dependencylock.tasks.SaveLockTask
import nebula.plugin.dependencylock.tasks.UpdateLockTask
import nebula.plugin.dependencylock.wayback.WaybackProvider
import nebula.plugin.dependencylock.wayback.WaybackProviderFactory
import nebula.plugin.scm.ScmPlugin
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Delete

import static nebula.plugin.dependencylock.tasks.GenerateLockTask.lockableConfigurations

class DependencyLockTaskConfigurer {
    private static final Logger LOGGER = Logging.getLogger(DependencyLockTaskConfigurer)

    private static final String LOCK_FILE = 'dependencyLock.lockFile'
    private static final String USE_GENERATED_LOCK = 'dependencyLock.useGeneratedLock'
    private static final String USE_GENERATED_GLOBAL_LOCK = 'dependencyLock.useGeneratedGlobalLock'

    public static final String OVERRIDE_FILE = 'dependencyLock.overrideFile'
    public static final String GLOBAL_LOCK_CONFIG = '_global_'
    public static final String GENERATE_GLOBAL_LOCK_TASK_NAME = 'generateGlobalLock'
    public static final String UPDATE_GLOBAL_LOCK_TASK_NAME = 'updateGlobalLock'
    public static final String UPDATE_LOCK_TASK_NAME = 'updateLock'
    public static final String GENERATE_LOCK_TASK_NAME = 'generateLock'
    public static final String MIGRATE_TO_CORE_LOCK_TASK_NAME = "migrateToCoreLocks"

    final Set<String> configurationsToSkipForGlobalLock = ['checkstyle', 'findbugs', 'findbugsPlugins', 'jacocoAgent', 'jacocoAnt']

    Project project

    DependencyLockTaskConfigurer(Project project) {
        this.project = project
    }

    String configureTasks(String globalLockFilename, DependencyLockExtension extension, DependencyLockCommitExtension commitExtension, Map overrides) {
        String lockFilename = project.hasProperty(LOCK_FILE) ? project[LOCK_FILE] : null

        GenerateLockTask genLockTask = project.tasks.create(GENERATE_LOCK_TASK_NAME, GenerateLockTask)
        configureGenerateLockTask(genLockTask, lockFilename, extension, overrides)
        if (project.hasProperty(USE_GENERATED_LOCK)) {
            lockFilename = genLockTask.getDependenciesLock().path
        }

        UpdateLockTask updateLockTask = project.tasks.create(UPDATE_LOCK_TASK_NAME, UpdateLockTask)
        configureGenerateLockTask(updateLockTask, lockFilename, extension, overrides)

        SaveLockTask saveTask = configureSaveTask(lockFilename, genLockTask, updateLockTask, extension)
        createDeleteLock(saveTask)

        configureMigrateToCoreLocksTask(extension)

        // configure global lock only on rootProject
        SaveLockTask globalSave = null
        GenerateLockTask globalLockTask
        UpdateLockTask globalUpdateLock
        if (project == project.rootProject) {
            globalLockTask = project.tasks.create(GENERATE_GLOBAL_LOCK_TASK_NAME, GenerateLockTask)
            if (project.hasProperty(USE_GENERATED_GLOBAL_LOCK)) {
                globalLockFilename = globalLockTask.getDependenciesLock().path
            }
            configureGlobalLockTask(globalLockTask, globalLockFilename, extension, overrides)
            globalUpdateLock = project.tasks.create(UPDATE_GLOBAL_LOCK_TASK_NAME, UpdateLockTask)
            configureGlobalLockTask(globalUpdateLock, globalLockFilename, extension, overrides)
            globalSave = configureGlobalSaveTask(globalLockFilename, globalLockTask, globalUpdateLock, extension)
            createDeleteGlobalLock(globalSave)
        }

        configureCommitTask(lockFilename, globalLockFilename, saveTask, extension, commitExtension, globalSave)

        lockFilename
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
                        List<File> lockFiles = []
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

    private SaveLockTask configureSaveTask(String lockFileName, GenerateLockTask lockTask, UpdateLockTask updateTask, DependencyLockExtension extension) {
        SaveLockTask saveTask = project.tasks.create('saveLock', SaveLockTask)
        saveTask.doFirst {
            SaveLockTask globalSave = project.rootProject.tasks.findByName('saveGlobalLock') as SaveLockTask
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

    private SaveLockTask configureGlobalSaveTask(String globalLockFileName, GenerateLockTask globalLockTask,
                                                 UpdateLockTask globalUpdateLockTask, DependencyLockExtension extension) {
        SaveLockTask globalSaveTask = project.tasks.create('saveGlobalLock', SaveLockTask)
        globalSaveTask.doFirst {
            project.subprojects.each { Project sub ->
                SaveLockTask save = sub.tasks.findByName('saveLock') as SaveLockTask
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

    private GenerateLockTask configureGenerateLockTask(GenerateLockTask lockTask, String clLockFileName, DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(lockTask, extension, overrides)
        lockTask.conventionMapping.with {
            waybackProvider = {
                def impl = null
                switch (extension.waybackProvider) {
                    case WaybackProvider:
                        impl = extension.waybackProvider
                        break
                    case String:
                        impl = new WaybackProviderFactory(project, getClass().classLoader).build(extension.waybackProvider as String)
                        break
                }
                impl
            }

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
                project.hasProperty('dependencyLock.includeTransitives') ? Boolean.parseBoolean(project['dependencyLock.includeTransitives'] as String) : extension.includeTransitives
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
                        def configurations = lockableConfigurations(project, subproject, ext.configurationNames)
                        configurations
                                .findAll { configuration ->
                            !configurationsToSkipForGlobalLock.contains(configuration.name)
                        }
                        .collect { configuration ->
                            project.dependencies.create(project.dependencies.project(path: subproject.path, configuration: configuration.name))
                        }
                    } else {
                        [project.dependencies.create(subproject)]
                    }
                }.flatten()
                def subprojectsArray = subprojects.toArray(new Dependency[subprojects.size()])
                def conf = project.configurations.detachedConfiguration(subprojectsArray)
                project.allprojects.each { it.configurations.add(conf) }

                [conf] + lockableConfigurations(project, project, extension.configurationNames)
            }
        }

        globalLockTask
    }

    private MigrateToCoreLocksTask configureMigrateToCoreLocksTask(DependencyLockExtension extension) {
        def migrateToCoreLocksTask = project.tasks.create(MIGRATE_TO_CORE_LOCK_TASK_NAME, MigrateToCoreLocksTask)
        def lockFile = new File(project.projectDir, extension.lockFile)
        def dependencyLockDirectory = new File(project.projectDir, "/gradle/dependency-locks")

        migrateToCoreLocksTask.conventionMapping.with {
            inputLockFile = { lockFile }
            outputLocksDirectory = { dependencyLockDirectory }
        }
        migrateToCoreLocksTask
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

    public static boolean shouldIgnoreDependencyLock(Project project) {
        if (project.hasProperty('dependencyLock.ignore')) {
            def prop = project.property('dependencyLock.ignore')
            (prop instanceof String) ? prop.toBoolean() : prop.asBoolean()
        } else {
            false
        }
    }
}
