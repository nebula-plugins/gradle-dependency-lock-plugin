/*
 * Copyright 2026 Netflix, Inc.
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
package nebula.plugin.dependencylock.utils

import nebula.plugin.dependencylock.DependencyLockExtension
import nebula.plugin.dependencylock.FreshProjectSpec
import org.gradle.api.BuildCancelledException
import org.gradle.api.artifacts.Configuration

/**
 * The guard in {@link CoreLockingHelper#lockSelectedConfigurations} is unreachable through the plugin,
 * because only items from {@code project.configurations} are ever handed to it.
 * The spec overrides {@code findConfigurationsToLock} to hand it something else.
 */
class CoreLockingHelperSpec extends FreshProjectSpec {
    private static final String NOT_A_CONFIGURATION = 'not-a-configuration'

    def 'lockSelectedConfigurations rejects an item that is not a Configuration'() {
        given:
        project.plugins.apply('java-base')
        def helper = helperFinding([NOT_A_CONFIGURATION] as Set)

        when:
        helper.lockSelectedConfigurations(['compileClasspath'] as Set)

        then:
        def e = thrown(BuildCancelledException)
        e.message.contains(NOT_A_CONFIGURATION)
    }

    private CoreLockingHelper helperFinding(Set found) {
        project.extensions.create('dependencyLock', DependencyLockExtension)
        new CoreLockingHelper(project) {
            @Override
            protected Set<Configuration> findConfigurationsToLock(Set<String> names, Collection<String> additional) {
                found
            }
        }
    }
}
