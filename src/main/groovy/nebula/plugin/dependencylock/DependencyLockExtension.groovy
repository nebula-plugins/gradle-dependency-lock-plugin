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

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

abstract class DependencyLockExtension {
    DependencyLockExtension() {
        lockFile.convention('dependencies.lock')
        globalLockFile.convention('global.lock')
        configurationNames.convention([])
        updateDependencies.convention([])
        includeTransitives.convention(false)
        lockAfterEvaluating.convention(true)
        updateDependenciesFailOnInvalidCoordinates.convention(true)
        updateDependenciesFailOnSimultaneousTaskUsage.convention(true)
        updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.convention(true)
        additionalConfigurationsToLock.convention([])
    }

    abstract Property<String> getLockFile()
    abstract Property<String> getGlobalLockFile()
    abstract SetProperty<String> getConfigurationNames()
    abstract SetProperty<String> getUpdateDependencies()
    abstract Property<Boolean> getIncludeTransitives()
    abstract Property<Boolean> getLockAfterEvaluating()
    abstract Property<Boolean> getUpdateDependenciesFailOnInvalidCoordinates()
    abstract Property<Boolean> getUpdateDependenciesFailOnSimultaneousTaskUsage()
    abstract Property<Boolean> getUpdateDependenciesFailOnNonSpecifiedDependenciesToUpdate()
    abstract SetProperty<String> getAdditionalConfigurationsToLock()

    Set<String> skippedDependencies = [] as Set

    Set<String> skippedConfigurationNamesPrefixes = [] as Set

    Closure dependencyFilter = { String group, String name, String version -> true }
}
