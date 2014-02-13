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

import nebula.test.ProjectSpec

class DependencyLockPluginSpec extends ProjectSpec {
    String pluginName = 'gradle-dependency-lock'

    def 'apply plugin'() {
        when:
        project.apply plugin: pluginName

        then:
        noExceptionThrown()
    }

    def 'read in dependencies.lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
              "direct": [
                { "group": "com.google.guava", "artifact": "guava", "locked": "14.0", "requested": "14.+" }
              ]
            }
        '''.stripIndent()

        project.apply plugin: 'java'
        project.repositories { mavenCentral() }
        project.dependencies {
            compile 'com.google.guava:guava:14.+'
        }

        when:
        project.apply plugin: pluginName
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def guava = resolved.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava.moduleVersion == '14.0'
    }

}
