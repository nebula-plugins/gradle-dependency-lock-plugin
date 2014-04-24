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
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder

class DependencyLockPluginSpec extends ProjectSpec {
    String pluginName = 'gradle-dependency-lock'

    def 'apply plugin'() {
        when:
        project.apply plugin: pluginName

        then:
        noExceptionThrown()
    }

    def 'read in dependencies.lock'() {
        stockTestSetup()

        when:
        project.apply plugin: pluginName
        triggerTaskGraphWhenReady()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def guava = resolved.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava.moduleVersion == '14.0'
    }

    def 'ignore dependencies.lock'() {
        stockTestSetup()
        project.ext.set('dependencyLock.ignore', true)

        when:
        project.apply plugin: pluginName
        triggerTaskGraphWhenReady()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def guava = resolved.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava.moduleVersion == '14.0.1'
    }

    def 'command line file override of dependencies'() {
        stockTestSetup()
        def override = new File(projectDir, 'override.lock')
        override.text = '''\
            {
              "com.google.guava:guava": { "locked": "16.0.1" }
            }    
        '''.stripIndent()

        project.ext.set('dependencyLock.overrideFile', 'override.lock')

        when:
        project.apply plugin: pluginName
        triggerTaskGraphWhenReady()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def guava = resolved.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava.moduleVersion == '16.0.1'
    }

    def 'command line override of a dependency'() {
        stockTestSetup()

        project.ext.set('dependencyLock.override', 'com.google.guava:guava:16.0.1')

        when:
        project.apply plugin: pluginName
        triggerTaskGraphWhenReady()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def guava = resolved.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava.moduleVersion == '16.0.1'
    }

    def 'command line overrides of multiple dependencies'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
              "com.google.guava:guava": { "locked": "14.0", "requested": "14.+" },
              "org.apache.commons:commons-lang3": { "locked": "3.3.1", "requested": "3.+" }
            }
        '''.stripIndent()

        project.apply plugin: 'java'
        project.repositories { mavenCentral() }
        project.dependencies {
            compile 'com.google.guava:guava:14.0.1'
            compile 'org.apache.commons:commons-lang3:3.+'
        }

        project.ext.set('dependencyLock.override', 'com.google.guava:guava:16.0.1,org.apache.commons:commons-lang3:3.2.1')

        when:
        project.apply plugin: pluginName
        triggerTaskGraphWhenReady()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def guava = resolved.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava.moduleVersion == '16.0.1'
        def commons = resolved.firstLevelModuleDependencies.find { it.moduleName == 'commons-lang3' }
        commons.moduleVersion == '3.2.1'
    }

    def 'multiproject dependencies.lock'() {
        def (Project sub1, Project sub2) = multiProjectSetup()

        when:
        project.subprojects {
            apply plugin: pluginName
        }
        triggerTaskGraphWhenReady()

        then:
        def resolved1 = sub1.configurations.compile.resolvedConfiguration
        def guava1 = resolved1.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava1.moduleVersion == '14.0'
        def resolved2 = sub2.configurations.compile.resolvedConfiguration
        def guava2 = resolved2.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava2.moduleVersion == '16.0'
    }

    def 'multiproject overrideFile'() {
        def (Project sub1, Project sub2) = multiProjectSetup()

        def override = new File(projectDir, 'override.lock')
        override.text = '''\
            {
              "com.google.guava:guava": { "locked": "16.0.1" }
            }
        '''.stripIndent()

        project.ext.set('dependencyLock.overrideFile', 'override.lock')

        when:
        project.subprojects {
            apply plugin: pluginName
        }
        triggerTaskGraphWhenReady()

        then:
        def resolved1 = sub1.configurations.compile.resolvedConfiguration
        def guava1 = resolved1.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava1.moduleVersion == '16.0.1'
        def resolved2 = sub2.configurations.compile.resolvedConfiguration
        def guava2 = resolved2.firstLevelModuleDependencies.find { it.moduleName == 'guava' }
        guava2.moduleVersion == '16.0.1'
    }

    private List multiProjectSetup() {
        def sub1Folder = new File(projectDir, 'sub1')
        sub1Folder.mkdir()
        def sub1 = ProjectBuilder.builder().withName('sub1').withProjectDir(sub1Folder).withParent(project).build()
        def sub1DependenciesLock = new File(sub1Folder, 'dependencies.lock')
        sub1DependenciesLock << '''\
            {
              "com.google.guava:guava": { "locked": "14.0", "requested": "14.+" }
            }
        '''.stripIndent()

        def sub2Folder = new File(projectDir, 'sub2')
        sub2Folder.mkdir()
        def sub2 = ProjectBuilder.builder().withName('sub2').withProjectDir(sub2Folder).withParent(project).build()
        def sub2DependenciesLock = new File(sub2Folder, 'dependencies.lock')
        sub2DependenciesLock << '''\
            {
              "com.google.guava:guava": { "locked": "16.0", "requested": "16.+" }
            }
        '''.stripIndent()

        project.subprojects {
            apply plugin: 'java'
            repositories { mavenCentral() }
        }

        sub1.dependencies {
            compile 'com.google.guava:guava:14.+'
        }

        sub2.dependencies {
            compile 'com.google.guava:guava:16.+'
        }

        [sub1, sub2]
    }

    private void triggerTaskGraphWhenReady() {
        Task placeholder = project.tasks.create('placeholder')
        project.gradle.taskGraph.addTasks([placeholder])
        project.gradle.taskGraph.execute()
    }

    private void stockTestSetup() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
              "com.google.guava:guava": { "locked": "14.0", "requested": "14.+" }
            }
        '''.stripIndent()

        project.apply plugin: 'java'
        project.repositories { mavenCentral() }
        project.dependencies {
            compile 'com.google.guava:guava:14.0.1'
        }
    }
}
