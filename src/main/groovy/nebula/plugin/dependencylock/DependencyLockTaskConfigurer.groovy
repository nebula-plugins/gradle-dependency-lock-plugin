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
import org.gradle.internal.deprecation.DeprecationLogger

import java.util.concurrent.atomic.AtomicInteger

import static nebula.plugin.dependencylock.tasks.GenerateLockTask.filterNonLockableConfigurationsAndProvideWarningsForGlobalLockSubproject
import static nebula.plugin.dependencylock.tasks.GenerateLockTask.lockableConfigurations

class DependencyLockTaskConfigurer {
    private static final Logger LOGGER = Logging.getLogger(DependencyLockTaskConfigurer)

    /** Counter for unique config names (globalLockConfig, aggregateConfiguration_) so names are deterministic within a build. */
    private static final AtomicInteger nextUniqueConfigSuffix = new AtomicInteger(0)

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
    // TODO: Remove this property when generateGlobalLock and saveGlobalLock tasks are removed
    // This property allows users to disable global lock functionality during migration to individual locks
    public static final String DISABLE_GLOBAL_LOCK = 'dependencyLock.disableGlobalLock'

    // these get skipped for subproject's configurations
    public static final Set<String> configurationsToSkipForGlobalLockPrefixes = ['checkstyle', 'findbugs', 'findbugsPlugins', 'jacocoAgent', 'jacocoAnt', 'spotbugs', 'spotbugsPlugins', 'zinc', 'pmd', 'resolutionRules', 'spotless', 'scalaToolchain']

    Project project

    DependencyLockTaskConfigurer(Project project) {
        this.project = project
    }

    private boolean isGlobalLockDisabled() {
        return project.providers.gradleProperty(DISABLE_GLOBAL_LOCK)
            .map { it.toBoolean() }
            .getOrElse(false)
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

        // configure global lock only on rootProject (unless disabled via property)
        TaskProvider<SaveLockTask> globalSave = null
        TaskProvider<GenerateLockTask> globalLockTask
        TaskProvider<UpdateLockTask> globalUpdateLock
        if (project == project.rootProject && !isGlobalLockDisabled()) {
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
        new File(project.projectDir, lockFilename ?: extension.lockFile.get())
    }

    private File getBuildDirLockFile(String lockFilename, DependencyLockExtension extension) {
        new File(project.layout.buildDirectory.getAsFile().get(), lockFilename ?: extension.lockFile.get())
    }

    private File getProjectDirGlobalLockFile(String lockFilename, DependencyLockExtension extension) {
        new File(project.projectDir, lockFilename ?: extension.globalLockFile.get())
    }

    private File getBuildDirGlobalLockFile(String lockFilename, DependencyLockExtension extension) {
        new File(project.layout.buildDirectory.getAsFile().get(), lockFilename ?: extension.globalLockFile.get())
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
                // Resolve at config time so the task holds plain values (no provider chains for config cache)
                String msg = project.findProperty('commitDependencyLock.message')?.toString()
                if (msg == null) {
                    msg = commitExtension.message.getOrElse('Committing dependency lock files')
                }
                commitMessage.set(msg)
                patternsToCommit.set(getPatternsToCommit(clLockFileName, globalLockFileName, lockExtension))
                remoteRetries.set(commitExtension.remoteRetries.get())

                boolean createTag = project.findProperty('commitDependencyLock.tag') != null
                if (!createTag) {
                    createTag = commitExtension.shouldCreateTag.getOrElse(false)
                }
                shouldCreateTag.set(createTag)
                String tagVal = project.findProperty('commitDependencyLock.tag')?.toString()
                if (tagVal == null) {
                    tagVal = commitExtension.tag.getOrElse("LockCommit-${new Date().format('yyyyMMddHHmmss')}")
                }
                tag.set(tagVal)
                rootDirPath.set(project.rootProject.projectDir.absolutePath)
            }
        }
    }

    private List<String> getPatternsToCommit(String clLockFileName, String globalLockFileName, DependencyLockExtension lockExtension ) {
        List<String> patterns = []
        patterns.add(clLockFileName ?: lockExtension.lockFile.get())
        patterns.add(globalLockFileName ?: lockExtension.globalLockFile.get())
        return patterns
    }

    private TaskProvider<SaveLockTask> configureSaveTask(String lockFilename, TaskProvider<GenerateLockTask> lockTask,
                                                         TaskProvider<UpdateLockTask> updateTask, DependencyLockExtension extension) {
        TaskProvider<SaveLockTask> saveLockTask = project.tasks.register(SAVE_LOCK_TASK_NAME, SaveLockTask)

        saveLockTask.configure { saveTask ->
            // Set input and output files using Property API
            saveTask.generatedLock.set(project.layout.buildDirectory.file(lockFilename ?: extension.lockFile.get()))
            saveTask.outputLock.set(project.layout.projectDirectory.file(lockFilename ?: extension.lockFile.get()))
            
            // Configuration cache compatible: Capture global lock file path to check at execution time
            if (!isGlobalLockDisabled()) {
                saveTask.globalLockFile.set(
                    project.rootProject.layout.projectDirectory.file(extension.globalLockFile)
                )
            }
        }
        configureCommonSaveTask(saveLockTask, lockTask, updateTask)

        saveLockTask
    }

    private static void configureCommonSaveTask(TaskProvider<SaveLockTask> saveLockTask, TaskProvider<GenerateLockTask> lockTask,
                                                TaskProvider<UpdateLockTask> updateTask) {
        saveLockTask.configure { saveTask ->
            // Configuration cache compatible: SaveLockTask uses only Property API
            saveTask.mustRunAfter lockTask, updateTask
            saveTask.outputs.upToDateWhen {
                def generated = saveTask.generatedLock.get().asFile
                def output = saveTask.outputLock.get().asFile
                if (generated.exists() && output.exists()) {
                    generated.text == output.text
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
                DeprecationLogger.whileDisabled {
                    project.subprojects.each { Project sub ->
                        SaveLockTask save = sub.tasks.findByName(SAVE_LOCK_TASK_NAME) as SaveLockTask
                        if (save && save.outputLock.isPresent() && save.outputLock.get().asFile.exists()) {
                            throw new GradleException('Cannot save global lock, one or more individual locks are in place, run deleteLock task')
                        }
                    }
                }
            }
            // Set input and output files using Property API
            globalSaveTask.generatedLock.set(project.layout.buildDirectory.file(lockFilename ?: extension.globalLockFile.get()))
            globalSaveTask.outputLock.set(project.layout.projectDirectory.file(lockFilename ?: extension.globalLockFile.get()))
        }
        configureCommonSaveTask(globalSaveLockTask, globalLockTask, globalUpdateLockTask)

        globalSaveLockTask
    }

    private TaskProvider<GenerateLockTask> configureGenerateLockTask(TaskProvider<GenerateLockTask> lockTask, String lockFilename, DependencyLockExtension extension, Map overrides) {
        setupLockProperties(lockTask, extension, overrides)
        lockTask.configure {
            // Set output file
            it.dependenciesLock.set(project.layout.buildDirectory.file(lockFilename ?: extension.lockFile.get()))
            // Set configuration names
            it.configurationNames.set(extension.configurationNames)
            it.skippedConfigurationNames.set(extension.skippedConfigurationNamesPrefixes)
            
            // Always regenerate lock files - dependency changes in build.gradle aren't tracked as task inputs
            it.outputs.upToDateWhen { false }
        }

        lockTask
    }

    private void setupLockProperties(TaskProvider<GenerateLockTask> task, DependencyLockExtension extension, Map overrideMap) {
        task.configure { generateTask ->
            // Set skipped dependencies
            generateTask.skippedDependencies.set(extension.skippedDependencies)
            
            // Set includeTransitives: gradle property overrides extension. Resolve at config time so the
            // task holds a plain value (no provider chain with non-serializable lambdas for config cache).
            Boolean includeTransitives = project.findProperty('dependencyLock.includeTransitives')?.toString()?.toBoolean()
            if (includeTransitives == null) {
                includeTransitives = extension.includeTransitives.getOrElse(false)
            }
            generateTask.includeTransitives.set(includeTransitives)

            // Set filter (kept as Closure for backward compatibility)
            generateTask.filter = extension.dependencyFilter

            // Set overrides
            generateTask.overrides.set(overrideMap)

            // Wire properties for configuration cache compatibility (resolve at config time, no project-capturing providers)
            generateTask.projectDirectory.set(project.layout.projectDirectory)
            generateTask.globalLockFileName.set(extension.globalLockFile)
            generateTask.dependencyLockIgnored.set(shouldIgnoreDependencyLock(project))

            // Build resolution map at config time so the task does not hold provider chains that capture project.
            // Intentionally resolve configurationNames and skippedConfigurationNamesPrefixes here (not lazy) for config-cache compatibility.
            def configNames = extension.configurationNames.get()
            def skippedNames = extension.skippedConfigurationNamesPrefixes.get()
            def lockableConfs = GenerateLockTask.lockableConfigurations(project, project, configNames, skippedNames)
            def resolutionMap = lockableConfs.collectEntries { conf ->
                [(conf.name): conf.incoming.resolutionResult.rootComponent]
            }
            generateTask.resolutionResults.set(resolutionMap)

            def peerCoords = project.rootProject.allprojects.collect { p ->
                String group = p.group?.toString() ?: ''
                String name = p.name
                "${group}:${name}".toString()
            }
            generateTask.peerProjectCoordinates.set(peerCoords)
        }
    }

    private TaskProvider<GenerateLockTask> configureGlobalLockTask(TaskProvider<GenerateLockTask> globalLockTask, String lockFilename,
                                                                   DependencyLockExtension extension, Map overrides) {
        // Global lock uses the OLD API (not configuration cache compatible)
        // It relies on conventionMapping to set configurations dynamically at execution time
        // Do NOT call setupLockProperties for global lock - it interferes with conventionMapping
        globalLockTask.configure { globalGenerateTask ->
            globalGenerateTask.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
            globalGenerateTask.doFirst {
                DeprecationLogger.whileDisabled {
                    project.subprojects.each { sub -> sub.repositories.each { repo -> project.repositories.add(repo) } }
                }
            }
            
            // Set output file
            globalGenerateTask.dependenciesLock.set(project.layout.buildDirectory.file(lockFilename ?: extension.globalLockFile.get()))
            
            // Wire minimal properties needed for basic checks in lock() method
            globalGenerateTask.projectDirectory.set(project.layout.projectDirectory)
            globalGenerateTask.globalLockFileName.set(lockFilename ?: extension.globalLockFile.get())
            globalGenerateTask.dependencyLockIgnored.set(project.provider { shouldIgnoreDependencyLock(project) })
            globalGenerateTask.skippedDependencies.set(extension.skippedDependencies)
            globalGenerateTask.overrides.set(overrides)
            globalGenerateTask.filter = extension.dependencyFilter
            
            // Capture peer coordinates to avoid accessing project at execution time
            globalGenerateTask.peerProjectCoordinates.set(project.provider {
                project.rootProject.allprojects.collect { p ->
                    String group = p.group?.toString() ?: ''
                    String name = p.name
                    "${group}:${name}".toString()
                }
            })
            
            // TODO: Refactor this to not use conventionMapping. The global lock's configuration logic is complex
            // because it creates aggregate configurations at execution time. This needs a proper Property-based solution.
            // For now, keeping conventionMapping for this specific case to maintain functionality.
            globalGenerateTask.conventionMapping.with {
                includeTransitives = { extension.includeTransitives.get() }
                configurations = {
                    def subprojects = project.subprojects.collect { subproject ->
                        def ext = subproject.getExtensions().findByType(DependencyLockExtension)
                        if (ext != null) {
                            Collection<Configuration> lockableConfigurations = lockableConfigurations(project, subproject, ext.configurationNames.get(), extension.skippedConfigurationNamesPrefixes.get())
                            Collection<Configuration> configurations = filterNonLockableConfigurationsAndProvideWarningsForGlobalLockSubproject(subproject, ext.configurationNames.get(), lockableConfigurations)
                            // Use unique name to avoid conflicts if evaluated multiple times
                            Configuration aggregate = subproject.configurations.create("aggregateConfiguration_${nextUniqueConfigSuffix.getAndIncrement()}_${subproject.path.replace(':', '_')}")
                            aggregate.setCanBeConsumed(true)
                            aggregate.setCanBeResolved(true)
                            configurations
                                    .findAll { configuration ->
                                        !configurationsToSkipForGlobalLockPrefixes.any { String prefix -> configuration.name.startsWith(prefix) }
                                                && !extension.skippedConfigurationNamesPrefixes.get().any { String prefix -> configuration.name.startsWith(prefix) }
                                    }
                                    .each { configuration ->
                                        aggregate.extendsFrom(configuration)
                                    }
                            [project.dependencies.create(project.dependencies.project(path: subproject.path, configuration: aggregate.name))]
                        } else {
                            [project.dependencies.create(subproject)]
                        }
                    }.flatten()


                    // Create a regular configuration instead of a detached one for Gradle 9.x compatibility
                    // Use a unique name that doesn't conflict with Gradle's reserved names
                    def subprojectsArray = subprojects.toArray(new Dependency[subprojects.size()])

                    List<Configuration> configurations = []
                    // Sort by project path so global lock merge order is deterministic by path
                    project.allprojects.sort({ a, b -> a.path.compareTo(b.path) }).each { p ->
                        def conf = p.configurations.create("globalLockConfig${nextUniqueConfigSuffix.getAndIncrement()}") {
                            canBeConsumed = true
                            canBeResolved = true
                            transitive = true
                        }
                        conf.dependencies.addAll(subprojectsArray)
                        configurations.add(conf)
                    }

                    configurations + lockableConfigurations(project, project, extension.configurationNames.get(), extension.skippedConfigurationNamesPrefixes.get())
                }
            }
        }

        globalLockTask
    }

    private TaskProvider<MigrateToCoreLocksTask> configureMigrateToCoreLocksTask(DependencyLockExtension extension) {
        def migrateLockedDepsToCoreLocksTask = project.tasks.register(MIGRATE_LOCKED_DEPS_TO_CORE_LOCKS_TASK_NAME, MigrateLockedDepsToCoreLocksTask)
        def migrateToCoreLocksTask = project.tasks.register(MIGRATE_TO_CORE_LOCKS_TASK_NAME, MigrateToCoreLocksTask)

        migrateLockedDepsToCoreLocksTask.configure {
            it.configurationNames.set(extension.configurationNames)
            it.inputLockFile.set(project.layout.projectDirectory.file(extension.lockFile))
            it.outputLock.set(project.layout.projectDirectory.file("gradle.lockfile"))
            it.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")

        }

        migrateToCoreLocksTask.configure {
            it.configurationNames.set(extension.configurationNames)
            it.outputLock.set(project.layout.projectDirectory.file("gradle.lockfile"))
            it.dependsOn project.tasks.named(MIGRATE_LOCKED_DEPS_TO_CORE_LOCKS_TASK_NAME)
            it.notCompatibleWithConfigurationCache("Dependency locking plugin tasks require project access. Please consider using Gradle's dependency locking mechanism")
        }

        migrateToCoreLocksTask
    }

    private void createDeleteLock(TaskProvider<SaveLockTask> saveLock) {
        TaskProvider<Delete> deleteLockTask = project.tasks.register('deleteLock', Delete)

        deleteLockTask.configure { it ->
            it.delete saveLock.map { it.outputLock }
        }

    }

    private void createDeleteGlobalLock(TaskProvider<SaveLockTask> saveGlobalLock) {
        TaskProvider<Delete> deleteGlobalLockTask = project.tasks.register('deleteGlobalLock', Delete)

        deleteGlobalLockTask.configure { it ->
            it.delete saveGlobalLock.map { it.outputLock }
        }
    }

    public static boolean shouldIgnoreDependencyLock(Project project) {
        // Use provider that checks both gradle properties (-P) and project extras (ext)
        // This maintains backward compatibility with existing usage
        return project.providers.provider {
            def prop = project.findProperty('dependencyLock.ignore')
            if (prop != null) {
                // Handle both String and other types (Boolean, Integer, etc.)
                return (prop instanceof String) ? prop.toBoolean() : prop.asBoolean()
            }
            return false
        }.get()
    }

    private TaskProvider<DiffLockTask> configureDiffLockTask(String lockFileName, DependencyLockExtension extension) {
        TaskProvider<DiffLockTask> diffLockTask = project.tasks.register(DIFF_LOCK_TASK_NAME, DiffLockTask)

        diffLockTask.configure { diffTask ->
            diffTask.mustRunAfter(project.tasks.named(GENERATE_LOCK_TASK_NAME), project.tasks.named(UPDATE_LOCK_TASK_NAME))
            
            // Set file properties
            def lockFileProvider = extension.lockFile.map { lockFileName ?: it }
            def existing = new File(project.projectDir, lockFileName ?: extension.lockFile.get())
            if (existing.exists()) {
                diffTask.existingLockFile.set(project.layout.projectDirectory.file(lockFileProvider))
            }
            diffTask.updatedLockFile.set(project.layout.buildDirectory.file(lockFileProvider))
            
            // Set output properties
            diffTask.outputDir.set(project.layout.buildDirectory.dir("dependency-lock"))
            diffTask.diffFile.set(diffTask.outputDir.file("lockdiff.${diffTask.diffFileExtension()}"))
            
            // Wire resolution results for path-aware diff (configuration cache compatible!)
            diffTask.resolutionResults.set(
                extension.configurationNames.zip(extension.skippedConfigurationNamesPrefixes) { configNames, skippedNames ->
                    def lockableConfs = GenerateLockTask.lockableConfigurations(project, project, configNames, skippedNames)
                    
                    lockableConfs.collectEntries { conf ->
                        [(conf.name): conf.incoming.resolutionResult.rootComponent]
                    }
                }
            )
        }

        project.tasks.named(SAVE_LOCK_TASK_NAME).configure { save ->
            save.mustRunAfter(diffLockTask)
        }

        diffLockTask
    }

}
