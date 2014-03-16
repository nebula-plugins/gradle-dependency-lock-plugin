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

import groovy.json.JsonSlurper
import nebula.test.IntegrationSpec

class TransitiveDependencyLockLauncherSpec extends IntegrationSpec {
    def 'create lock for dynamic transitive dependencies too'() {
        def repoDir = System.getProperty('user.dir') + "/fixture/trans-dyn-dep/repo"
        assert new File(repoDir).exists() : 'Failed to detect correct project dir and repo path'
        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'gradle-dependency-lock'
            repositories {
                maven { url '${repoDir}'}
                mavenCentral()
            }
            dependencies {
                compile 'trans-dyn-dep:trans-dyn-dep:1.0.0'
            }
        """.stripIndent()
        when:
        runTasksSuccessfully('generateLock')
        then:
        def actualLock = new File(projectDir, 'build/dependencies.lock').text
        def expectedLock = '''
            {
                "org.slf4j:slf4j-api":         {"locked":"1.6.6"},
                "trans-dyn-dep:trans-dyn-dep": {"locked":"1.0.0"}
            }
        '''
        new JsonSlurper().parseText(actualLock) == new JsonSlurper().parseText(expectedLock)
    }
}
