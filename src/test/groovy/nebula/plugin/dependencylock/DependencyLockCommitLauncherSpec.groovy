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

import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.test.IntegrationTestKitSpec
import org.ajoberstar.grgit.Grgit

import java.nio.file.Files

class DependencyLockCommitLauncherSpec extends IntegrationTestKitSpec {

    protected Grgit git
    protected Grgit originGit


    def setup() {
        def origin = new File(projectDir.parent, "${projectDir.name}.git")
        if (origin.exists()) {
            origin.deleteDir()
        }
        origin.mkdirs()

        ['build.gradle', 'settings.gradle'].each {
            def file = new File(projectDir, it)
            if(!file.exists()) {
                file.createNewFile()
            }
            Files.move(file.toPath(), new File(origin, it).toPath())
        }

        originGit = Grgit.init(dir: origin)
        originGit.add(patterns: ['build.gradle', 'settings.gradle', '.gitignore'] as Set)
        originGit.commit(message: 'Initial checkout')

        git = Grgit.clone(dir: projectDir, uri: origin.absolutePath) as Grgit

        new File(projectDir, '.gitignore') << '''.gradle-test-kit
.gradle
build/
gradle.properties'''.stripIndent()
        def gradleProperties = new File(projectDir, "gradle.properties")
        gradleProperties << "systemProp.nebula.features.coreLockingSupport=false"
        // Enable configuration cache :)
        gradleProperties << '''org.gradle.configuration-cache=true'''.stripIndent()
        git.add(patterns: ['build.gradle', 'settings.gradle', '.gitignore'])
        git.commit(message: 'Setup')
        git.push()
    }

    def cleanup() {
        if (git) git.close()
        if (originGit) originGit.close()
    }

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def 'commitLock git test'() {
        buildFile << DependencyLockLauncherSpec.BUILD_GRADLE

        when:
        runTasks('generateLock', 'saveLock', 'commitLock')

        then:
        def lockFile = new File(projectDir, 'dependencies.lock')
        lockFile.exists()
        lockFile.text == DependencyLockLauncherSpec.FOO_LOCK
    }

    def 'git integration test for multiproject'() {
        def sub1 = new File(projectDir, 'sub1')
        sub1.mkdirs()
        def sub2 = new File(projectDir, 'sub2')
        sub2.mkdirs()

        buildFile << """\
            plugins {
               id 'com.netflix.nebula.dependency-lock'
            }
            subprojects {
                apply plugin: 'java'
                apply plugin: 'com.netflix.nebula.dependency-lock'
                repositories { maven { url '${Fixture.repo}' } }
            }
        """.stripIndent()

        settingsFile << '''
            include 'sub1'
            include 'sub2'
        '''.stripIndent()

        new File(sub1, 'build.gradle') << '''\
            dependencies {
                implementation 'test.example:foo:2.+'
            }
        '''.stripIndent()

        new File(sub2, 'build.gradle') << '''\
            dependencies {
                implementation 'test.example:baz:1.+'
            }
        '''.stripIndent()


        when:
        runTasks('generateLock', 'saveLock', 'commitLock')

        then:
        new File(projectDir, 'sub1/dependencies.lock').exists()
        new File(projectDir, 'sub2/dependencies.lock').exists()
    }

    def 'git integration test for global lock on multiproject'() {
        def sub1 = new File(projectDir, 'sub1')
        sub1.mkdirs()
        def sub2 = new File(projectDir, 'sub2')
        sub2.mkdirs()

        buildFile << """\
            plugins {
               id 'com.netflix.nebula.dependency-lock'
            }
            subprojects {
                apply plugin: 'java'
                apply plugin: 'com.netflix.nebula.dependency-lock'
                repositories { maven { url '${Fixture.repo}' } }
            }
        """.stripIndent()

        settingsFile << '''
            include 'sub1'
            include 'sub2'
        '''.stripIndent()

        new File(sub1, 'build.gradle') << '''\
            dependencies {
                implementation 'test.example:foo:2.+'
            }
        '''.stripIndent()

        new File(sub2, 'build.gradle') << '''\
            dependencies {
                implementation 'test.example:baz:1.+'
            }
        '''.stripIndent()

        when:
        runTasks('generateGlobalLock', 'saveGlobalLock', 'commitLock', '--info')

        then:
        new File(projectDir, 'global.lock').exists()
    }
}
