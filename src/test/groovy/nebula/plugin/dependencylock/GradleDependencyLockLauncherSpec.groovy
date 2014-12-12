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

import nebula.test.IntegrationSpec

class GradleDependencyLockLauncherSpec extends IntegrationSpec {
    def 'warning is displayed on use of gradle-dependency-lock'() {
        buildFile << """\
            ${applyPlugin(GradleDependencyLockPlugin)}
        """.stripIndent()

        when:
        def result = runTasksSuccessfully("tasks")

        then:
        result.standardOutput.contains 'Please begin using `apply plugin: \'nebula.dependency-lock\'` instead of `apply plugin: \'nebula.gradle-dependency-lock\'`'
    }
}
