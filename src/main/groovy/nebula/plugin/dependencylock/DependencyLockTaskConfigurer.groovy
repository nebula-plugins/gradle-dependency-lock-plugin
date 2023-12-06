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
import nebula.plugin.dependencylock.tasks.DiffLockTask
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.tasks.MigrateLockedDepsToCoreLocksTask
import nebula.plugin.dependencylock.tasks.MigrateToCoreLocksTask
import nebula.plugin.dependencylock.tasks.SaveLockTask
import nebula.plugin.dependencylock.tasks.UpdateLockTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider

import static nebula.plugin.dependencylock.tasks.GenerateLockTask.filterNonLockableConfigurationsAndProvideWarningsForGlobalLockSubproject
import static nebula.plugin.dependencylock.tasks.GenerateLockTask.lockableConfigurations

class DependencyLockTaskConfigurer {
    private static final Logger LOGGER = Logging.getLogger(DependencyLockTaskConfigurer)

    public static final String OVERRIDE_FILE = 'dependencyLock.overrideFile'
    public static final String GLOBAL_LOCK_CONFIG = '_global_'

    public static final String GENERATE_GLOBAL_LOCK_TASK_NAME = 'generateGlobalLock'
    public static final String UPDATE_GLOBAL_LOCK_TASK_NAME = 'updateGlobalLock'
    public static final String UPDATE_LOCK_TASK_NAME = 'updateLock'
    public static final String GENERATE_LOCK_TASK_NAME = 'generateLock'
    public static final String MIGRATE_LOCKED_DEPS_TO_CORE_LOCKS_TASK_NAME = "migrateLockeDepsToCoreLocks"
    public static final String MIGRATE_TO_CORE_LOCKS_TASK_NAME = "migrateToCoreLocks"
    public static final String DIFF_LOCK_TASK_NAME = 'diffLock'
    public static final String COMMIT_LOCK_TASK_NAME = 'commitLock'
    public static final String SAVE_LOCK_TASK_NAME = 'saveLock'
    public static final String SAVE_GLOBAL_LOCK_TASK_NAME = 'saveGlobalLock'

    // these get skipped for subproject's configurations
    public static final Set<String> configurationsToSkipForGlobalLockPrefixes = ['checkstyle', 'findbugs', 'findbugsPlugins', 'jacocoAgent', 'jacocoAnt', 'spotbugs', 'spotbugsPlugins', 'zinc', 'pmd', 'resolutionRules', 'spotless']

    Project project

    DependencyLockTaskConfigurer(Project project) {
        this.project = project
    }

    String configureTasks(String globalLockFilename, String lockFilename, DependencyLockExtension extension, DependencyLockCommitExtension commitExtension, Map overrides) {
        TaskProvider<GenerateLockTask> genLockTask = project.tasks.register(GENERATE_LOCK_TASK_NAME, GenerateLockTask)
        configureGenerateLockTask(genLockTask, lockFilename, extension, overrides)

        TaskProvider<UpdateLockTask> updateLockTask = project.tasks.register(UPDATE_LOCK_TASK_NAME, UpdateLockTask)
        configureGenerateLockTask(updateLockTask, lockFilename, extension, overrides)

        TaskProvider<SaveLockTask> saveTask = configureSaveTask(lockFilename, genLockTask, updateLockTask, extension)
        createDeleteLock(saveTask)

        configureMigrateToCoreLocksTask(extension)

        TaskProvider<DiffLockTask> diffLockTask = configureDiffLockTask(lockFilename, extension)

        // configure global lock only on rootProject
        TaskProvider<SaveLockTask> globalSave = null
        TaskProvider<GenerateLockTask> globalLockTask
        TaskProvider<UpdateLockTask> globalUpdateLock
        if (project == project.rootProject) {
            globalLockTask = project.tasks.register(GENERATE_GLOBAL_LOCK_TASK_NAME, GenerateLockTask)
            configureGlobalLockTask(globalLockTask, globalLockFilename, extension, overrides)

            globalUpdateLock = project.tasks.register(UPDATE_GLOBAL_LOCK_TASK_NAME, UpdateLockTask)
            configureGlobalLockTask(globalUpdateLock, globalLockFilename, extension, overrides)

            globalSave = configureGlobalSaveTask(globalLockFilename, globalLockTask, globalUpdateLock, extension)

            createDeleteGlobalLock(globalSave)
        }

        configureCommitTask(lockFilename, globalLockFilename, saveTask, extension, commitExtension, globalSave)

        lockFilename
    }

    private File getProjectDirLockFile(String lockFilename, DependencyLockExtension extension) {
        new File(project.projectDir, lockFilename ?: extension.lockFile)
    }

    private File getBuildDirLockFile(String lockFilename, DependencyLockExtension extension) {
        new File(project.layout.buildDirectory.getAsFile().get(), lockFilename ?: extension.lockFile)
    }

    private File getProjectDirGlobalLockFile(String lockFilename, DependencyLockExtension extension) {
        new File(project.projectDir, lockFilename ?: extension.globalLockFile)
    }

    private File getBuildDirGlobalLockFile(String lockFilename, DependencyLockExtension extension) {
        new File(project.layout.buildDirectory.getAsFile().get(), lockFilename ?: extension.globalLockFile)
    }

    private void configureCommitTask(String clLockFileName, String globalLockFileName, TaskProvider<SaveLockTask> saveTask, DependencyLockExtension lockExtension,
                                     DependencyLockCommitExtension commitExtension, TaskProvider<SaveLockTask> globalSaveTask = null) {
        def hasCommitLockTask = false
        try {
            project.rootProject.tasks.named(COMMIT_LOCK_TASK_NAME)
            hasCommitLockTask = true // if there is not an exception, then the task exists
        } catch (UnknownTaskException ute) {
            LOGGER.debug("Task $COMMIT_LOCK_TASK_NAME is not in the root project.", ute.getMessage())
        }
        if (!hasCommitLockTask) {
            TaskProvider<CommitLockTask> commitTask = project.rootProject.tasks.register(COMMIT_LOCK_TASK_NAME, CommitLockTask)
            commitTask.configure {
                it.mustRunAfter(saveTask)
                if (globalSaveTask) {
                    it.mustRunAfter(globalSaveTask)
                }
                String commitMessageValue = project.hasProperty('commitDependencyLock.message') ?
                        project['commitDependencyLock.message'] : commitExtension.message
                commitMessage.set(commitMessageValue)
                patternsToCommit.set(getPatternsToCommit(clLockFileName, globalLockFileName, lockExtension))
                remoteRetries.set(commitExtension.remoteRetries)
                shouldCreateTag.set(project.hasProperty('commitDependencyLock.tag') ?: commitExtension.shouldCreateTag)
                String tagValue = project.hasProperty('commitDependencyLock.tag') ? project['commitDependencyLock.tag'] : commitExtension.tag.call()
                tag.set(tagValue)
                rootDirPath.set(project.rootProject.projectDir.absolutePath)
            }
        }
    }

    private List<String> getPatternsToCommit(String clLockFileName, String globalLockFileName, DependencyLockExtension lockExtension ) {
        List<String> patterns = []
        patterns.add(clLockFileName ?: lockExtension.lockFile)
        patterns.add(globalLockFileName ?: lockExtension.globalLockFile)
        return patterns
    }

    private TaskProvider<SaveLockTask> configureSaveTask(String lockFilename, TaskProvider<GenerateLockTask> lockTask,
                                                         TaskProvider<UpdateLockTask> updateTask, DependencyLockExtension extension) {
        TaskProvider<SaveLockTask> saveLockTask = project.tasks.register(SAVE_LOCK_TASK_NAME, SaveLockTask)

        saveLockTask.configure { saveTask ->
            saveTask.doFirst {
                SaveLockTask globalSave = project.rootProject.tasks.findByName(SAVE_GLOBAL_LOCK_TASK_NAME) as SaveLockTask
                if (globalSave?.outputLock?.exists()) {
                    throw new GradleException('Cannot save individual locks when global lock is in place, run deleteGlobalLock task')
                }
            }
            saveTask.conventionMapping.with {
                generatedLock = { getBuildDirLockFile(lockFilename, extension) }
                outputLock = { getProjectDirLockFile(lockFilename, extension) }
            }
        }
        configureCommonSaveTask(saveLockTask, lockTask, updateTask)

        saveLockTask
    }

    private static void configureCommonSaveTask(TaskProvider<SaveLockTask> saveLockTask, TaskProvider<GenerateLockTask> lockTask,
                                                TaskProvider<UpdateLockTask> updateTask) {
        saveLockTask.configure { saveTask ->
            saveTask.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
            saveTask.mustRunAfter lockTask, updateTask
            saveTask.outputs.upToDateWhen {
                if (saveTask.generatedLock.exists() && saveTask.outputLock.exists()) {
                    saveTask.generatedLock.text == saveTask.outputLock.text
                } else {
                    false
                }
            }
        }
    }

    private TaskProvider<SaveLockTask> configureGlobalSaveTask(String lockFilename, TaskProvider<GenerateLockTask> globalLockTask,
                                                               TaskProvider<UpdateLockTask> globalUpdateLockTask, DependencyLockExtension extension) {
        TaskProvider<SaveLockTask> globalSaveLockTask = project.tasks.register(SAVE_GLOBAL_LOCK_TASK_NAME, SaveLockTask)

        globalSaveLockTask.configure { globalSaveTask ->
            globalSaveTask.doFirst {
                project.subprojects.each { Project sub ->
                    SaveLockTask save = sub.tasks.findByName(SAVE_LOCK_TASK_NAME) as SaveLockTask
                    if (save && save.outputLock?.exists()) {
                        throw new GradleException('Cannot save global lock, one or more individual locks are in place, run deleteLock task')
                    }
                }
            }
            globalSaveTask.conventionMapping.with {
                generatedLock = { getBuildDirGlobalLockFile(lockFilename, extension) }
                outputLock = { getProjectDirGlobalLockFile(lockFilename, extension) }
            }
        }
        configureCommonSaveTask(globalSaveLockTask, globalLockTask, globalUpdateLockTask)

        globalSaveLockTask
    }

    private TaskProvider<GenerateLockTask> configureGenerateLockTask(TaskProvider<GenerateLockTask> lockTask, String lockFilename, DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(lockTask, extension, overrides)
        lockTask.configure {
            it.conventionMapping.with {
                dependenciesLock = { getBuildDirLockFile(lockFilename, extension) }
                configurationNames = { extension.configurationNames }
                skippedConfigurationNames = { extension.skippedConfigurationNamesPrefixes }
            }
        }

        lockTask
    }

    private void setupLockConventionMapping(TaskProvider<GenerateLockTask> task, DependencyLockExtension extension, Map overrideMap) {
        task.configure { generateTask ->
            generateTask.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
            generateTask.conventionMapping.with {
                skippedDependencies = { extension.skippedDependencies }
                includeTransitives = {
                    project.hasProperty('dependencyLock.includeTransitives') ? Boolean.parseBoolean(project['dependencyLock.includeTransitives'] as String) : extension.includeTransitives
                }
                filter = { extension.dependencyFilter }
                overrides = { overrideMap }
            }
        }
    }

    private TaskProvider<GenerateLockTask> configureGlobalLockTask(TaskProvider<GenerateLockTask> globalLockTask, String lockFilename,
                                                                   DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(globalLockTask, extension, overrides)
        globalLockTask.configure { globalGenerateTask ->
            globalGenerateTask.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
            globalGenerateTask.doFirst {
                project.subprojects.each { sub -> sub.repositories.each { repo -> project.repositories.add(repo) } }
            }
            globalGenerateTask.conventionMapping.with {
                dependenciesLock = { getBuildDirGlobalLockFile(lockFilename, extension) }
                configurations = {
                    def subprojects = project.subprojects.collect { subproject ->
                        def ext = subproject.getExtensions().findByType(DependencyLockExtension)
                        if (ext != null) {
                            Collection<Configuration> lockableConfigurations = lockableConfigurations(project, subproject, ext.configurationNames, extension.skippedConfigurationNamesPrefixes)
                            Collection<Configuration> configurations = filterNonLockableConfigurationsAndProvideWarningsForGlobalLockSubproject(subproject, ext.configurationNames, lockableConfigurations)
                            Configuration aggregate = subproject.configurations.create("aggregateConfiguration")
                            aggregate.setCanBeConsumed(true)
                            aggregate.setCanBeResolved(true)
                            configurations
                                    .findAll { configuration ->
                                        !configurationsToSkipForGlobalLockPrefixes.any { String prefix -> configuration.name.startsWith(prefix) }
                                                && !extension.skippedConfigurationNamesPrefixes.any { String prefix -> configuration.name.startsWith(prefix) }
                                    }
                                    .each { configuration ->
                                        aggregate.extendsFrom(configuration)
                                    }
                            [project.dependencies.create(project.dependencies.project(path: subproject.path, configuration: aggregate.name))]
                        } else {
                            [project.dependencies.create(subproject)]
                        }
                    }.flatten()
                    def subprojectsArray = subprojects.toArray(new Dependency[subprojects.size()])
                    def conf = project.configurations.detachedConfiguration(subprojectsArray)
                    project.allprojects.each { it.configurations.add(conf) }

                    [conf] + lockableConfigurations(project, project, extension.configurationNames, extension.skippedConfigurationNamesPrefixes)
                }
            }
        }

        globalLockTask
    }

    private TaskProvider<MigrateToCoreLocksTask> configureMigrateToCoreLocksTask(DependencyLockExtension extension) {
        def migrateLockedDepsToCoreLocksTask = project.tasks.register(MIGRATE_LOCKED_DEPS_TO_CORE_LOCKS_TASK_NAME, MigrateLockedDepsToCoreLocksTask)
        def migrateToCoreLocksTask = project.tasks.register(MIGRATE_TO_CORE_LOCKS_TASK_NAME, MigrateToCoreLocksTask)
        def lockFile = new File(project.projectDir, extension.lockFile)
        def dependencyLock = new File(project.projectDir, "gradle.lockfile")

        migrateLockedDepsToCoreLocksTask.configure {
            it.conventionMapping.with {
                configurationNames = { extension.configurationNames }
                inputLockFile = { lockFile }
                outputLock = { dependencyLock }
            }
            it.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")

        }

        migrateToCoreLocksTask.configure {
            it.conventionMapping.with {
                configurationNames = { extension.configurationNames }
                outputLock = { dependencyLock }
            }
            it.dependsOn project.tasks.named(MIGRATE_LOCKED_DEPS_TO_CORE_LOCKS_TASK_NAME)
            it.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
        }

        migrateToCoreLocksTask
    }

    private void createDeleteLock(TaskProvider<SaveLockTask> saveLock) {
        TaskProvider<Delete> deleteLockTask = project.tasks.register('deleteLock', Delete)

        deleteLockTask.configure { it ->
            it.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
            it.delete saveLock.map { it.outputLock }
        }

    }

    private void createDeleteGlobalLock(TaskProvider<SaveLockTask> saveGlobalLock) {
        TaskProvider<Delete> deleteGlobalLockTask = project.tasks.register('deleteGlobalLock', Delete)

        deleteGlobalLockTask.configure { it ->
            it.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
            it.delete saveGlobalLock.map { it.outputLock }
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

    private TaskProvider<DiffLockTask> configureDiffLockTask(String lockFileName, DependencyLockExtension extension) {
        TaskProvider<DiffLockTask> diffLockTask = project.tasks.register(DIFF_LOCK_TASK_NAME, DiffLockTask)

        diffLockTask.configure { diffTask ->
            diffTask.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
            diffTask.mustRunAfter(project.tasks.named(GENERATE_LOCK_TASK_NAME), project.tasks.named(UPDATE_LOCK_TASK_NAME))
            def existing = new File(project.projectDir, lockFileName ?: extension.lockFile)
            if (existing.exists()) {
                diffTask.existingLockFile = existing
            }
            diffTask.updatedLockFile = new File(project.layout.buildDirectory.getAsFile().get(), lockFileName ?: extension.lockFile)
        }

        project.tasks.named(SAVE_LOCK_TASK_NAME).configure { save ->
            save.mustRunAfter(diffLockTask)
        }

        diffLockTask
    }

}
