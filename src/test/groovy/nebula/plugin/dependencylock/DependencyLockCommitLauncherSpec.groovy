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

import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.test.IntegrationSpec
import org.ajoberstar.grgit.Grgit
import org.ajoberstar.grgit.operation.ResetOp
import org.gradle.BuildResult
import org.gradle.execution.TaskSelectionException

class DependencyLockCommitLauncherSpec extends IntegrationSpec {
    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def 'commitLock does not exist if no gradle-scm plugin exists'() {
        buildFile << DependencyLockLauncherSpec.BUILD_GRADLE

        when:
        BuildResult result = runTasksWithFailure('commitLock')

        then:
        result.failure instanceof TaskSelectionException
    }

    def 'commitLock no-ops when no scm implementation is applied'() {
        buildFile << DependencyLockLauncherSpec.BUILD_GRADLE
        buildFile << 'apply plugin: \'gradle-scm\''

        when:
        runTasksSuccessfully('generateLock', 'saveLock', 'commitLock')

        then:
        noExceptionThrown()
    }

    def 'commitLock git test'() {
        def gitDir = new File(projectDir, 'project')
        gitDir.mkdirs()
        def git = Grgit.init(dir: gitDir)
        new File(gitDir, '.placeholder').text = ''
        git.add(patterns: ['.placeholder'])
        git.commit(message: 'initial test')

        def tempProject = Grgit.clone(dir: new File(projectDir, 'tempgit'), uri: "file://${gitDir.absolutePath}").close()

        new AntBuilder().copy(todir: new File(projectDir, '.git').absolutePath) {
            fileset(dir: new File(projectDir,'tempgit/.git').absolutePath)
        }

        buildFile << DependencyLockLauncherSpec.BUILD_GRADLE
        buildFile << 'apply plugin: \'gradle-git-scm\''

        def project = Grgit.open(projectDir.absolutePath)
        project.reset(commit: 'HEAD', mode: ResetOp.Mode.SOFT)
        project.add(patterns: ['build.gradle', 'settings.gradle'])
        project.commit(message: 'test setup')
        project.push()
        project.close()

        when:
        runTasksSuccessfully('generateLock', 'saveLock', 'commitLock')
        git.reset(commit: 'HEAD', mode: ResetOp.Mode.HARD)

        then:
        new File(gitDir, 'dependencies.lock').text == DependencyLockLauncherSpec.FOO_LOCK
    }


    def 'commitLock no-ops works on multiproject'() {
        def sub1 = new File(projectDir, 'sub1')
        sub1.mkdirs()
        def sub2 = new File(projectDir, 'sub2')
        sub2.mkdirs()

        buildFile << """\
            subprojects {
                apply plugin: 'java'
                apply plugin: 'gradle-dependency-lock'
                apply plugin: 'gradle-scm'
                repositories { maven { url '${Fixture.repo}' } }
            }
        """.stripIndent()

        settingsFile << '''
            include 'sub1'
            include 'sub2'
        '''.stripIndent()

        new File(sub1, 'build.gradle') << '''\
            dependencies {
                compile 'test.example:foo:2.+'
            }
        '''.stripIndent()

        new File(sub2, 'build.gradle') << '''\
            dependencies {
                compile 'test.example:baz:1.+'
            }
        '''.stripIndent()

        when:
        runTasksSuccessfully('generateLock', 'saveLock', 'commitLock')

        then:
        noExceptionThrown()
    }

    def 'git integration test for multiproject'() {
        def sub1 = new File(projectDir, 'sub1')
        sub1.mkdirs()
        def sub2 = new File(projectDir, 'sub2')
        sub2.mkdirs()

        buildFile << """\
            subprojects {
                apply plugin: 'java'
                apply plugin: 'gradle-dependency-lock'
                apply plugin: 'gradle-scm'
                repositories { maven { url '${Fixture.repo}' } }
            }
        """.stripIndent()

        settingsFile << '''
            include 'sub1'
            include 'sub2'
        '''.stripIndent()

        new File(sub1, 'build.gradle') << '''\
            dependencies {
                compile 'test.example:foo:2.+'
            }
        '''.stripIndent()

        new File(sub2, 'build.gradle') << '''\
            dependencies {
                compile 'test.example:baz:1.+'
            }
        '''.stripIndent()

        when:
        runTasksSuccessfully('generateLock', 'saveLock', 'commitLock')

        then:
        noExceptionThrown()
    }
}
