/**
 *
 *  Copyright 2014-2019 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.DependencyLockReader
import nebula.plugin.dependencylock.utils.DependencyLockingFeatureFlags
import nebula.plugin.dependencylock.utils.CoreLockingHelper
import org.gradle.api.BuildCancelledException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.deprecation.DeprecationLogger
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class MigrateLockedDepsToCoreLocksTask extends AbstractMigrateToCoreLocksTask {

    @Internal
    String description = "Migrates Nebula-locked dependencies to use core Gradle locks"

    private static final Logger LOGGER = Logging.getLogger(MigrateLockedDepsToCoreLocksTask)

    @Internal
    abstract RegularFileProperty getInputLockFile()

    @TaskAction
    void migrateLockedDependencies() {
        //TODO: address Invocation of Task.project at execution time has been deprecated.
        DeprecationLogger.whileDisabled {
            if (DependencyLockingFeatureFlags.isCoreLockingEnabled()) {
                def coreLockingHelper = new CoreLockingHelper(project)
                coreLockingHelper.lockSelectedConfigurations(getConfigurationNames().get())

                if (getInputLockFile().asFile.get().exists()) {
                    LOGGER.warn("Migrating legacy locks to core Gradle locking. This will remove legacy locks.\n" +
                            " - Legacy lock: ${getInputLockFile().asFile.get().absolutePath}\n" +
                            " - Core Gradle locks: ${project.projectDir.absoluteFile}/gradle.lockfile")

                    def lockReader = new DependencyLockReader(project)

                    Map<String, List<String>> dependenciesInConfigs = new HashMap<>()
                    List<String> emptyLockedConfigs = new ArrayList<>()

                    def migrateConfigurationClosure = {
                        def locks = lockReader.readLocks(it, getInputLockFile().asFile.get(), new HashMap<>())

                        if (locks != null) {
                            for (Map.Entry<String, ArrayList<String>> entry : locks.entrySet()) {
                                def groupAndName = entry.key as String
                                def entryLockedValue = (entry.value as Map<String, String>)["locked"]
                                if (entryLockedValue != null) {
                                    def lockedVersion = entryLockedValue as String
                                    def lockedDependency = "$groupAndName:$lockedVersion".toString()
                                    if (dependenciesInConfigs.containsKey(lockedDependency)) {
                                        if (!dependenciesInConfigs[lockedDependency].contains(it.name)) {
                                            dependenciesInConfigs[lockedDependency].add(it.name)
                                        }
                                    } else {
                                        dependenciesInConfigs.put(lockedDependency, [it.name])
                                    }
                                } else {
                                    LOGGER.info("No locked version for '$groupAndName' to migrate in $it")
                                }
                            }
                        } else {
                            if (!emptyLockedConfigs.contains(it.name))
                                emptyLockedConfigs.add(it.name)
                        }
                    }
                    coreLockingHelper.migrateLockedConfigurations(getConfigurationNames().get(), migrateConfigurationClosure)

                    def configLockFile = getOutputLock().asFile.get()
                    if (!configLockFile.exists()) {
                        configLockFile.createNewFile()
                        configLockFile.write("# This is a file for dependency locking, migrated from Nebula locks.\n" +
                                "# Manual edits can break the build and are not advised.\n" +
                                "# This file is expected to be part of source control.\n")

                        dependenciesInConfigs.sort { it.key }.each {
                            configLockFile.append("${it.key}=${it.value.sort().join(",")}\n")
                        }
                        configLockFile.append("empty=${emptyLockedConfigs.join(',')}")
                    }

                    def failureToDeleteInputLockFileMessage = "Failed to delete legacy locks.\n" +
                            "Please remove the legacy lockfile manually.\n" +
                            " - Legacy lock: ${getInputLockFile().asFile.get().absolutePath}"
                    try {
                        if (!getInputLockFile().asFile.get().delete()) {
                            throw new BuildCancelledException(failureToDeleteInputLockFileMessage)
                        }
                    } catch (Exception e) {
                        throw new BuildCancelledException(failureToDeleteInputLockFileMessage, e)
                    }
                } else {
                    throw new BuildCancelledException("Stopping migration. There is no lockfile at expected location:\n" +
                            "${getInputLockFile().asFile.get().path}")
                }
            }
        }
    }
}
