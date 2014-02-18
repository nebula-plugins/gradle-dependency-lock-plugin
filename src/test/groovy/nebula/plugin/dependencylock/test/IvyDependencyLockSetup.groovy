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
package nebula.plugin.dependencylock.test

import org.gradle.api.Project

class IvyDependencyLockSetup implements DependencyLockSetup {
    private static final String REPO_PATTERN = '[organisation]/[module]/[revision]'
    private static final String ARTIFACT_PATTERN = "${REPO_PATTERN}/[artifact]-[revision](-[classifier]).[ext]"
    private static final String IVY_PATTERN = "${REPO_PATTERN}/[module]-[revision]-ivy.[ext]'"
    private static final String URL = 'testrepos/ivyrepo'

    @Override
    void setupSpec(Project project) {
        project.repositories {
            ivy {
                url URL
                layout 'pattern', {
                    artifact ARTIFACT_PATTERN
                    ivy IVY_PATTERN
                    m2compatible = true
                }
            }
        }
    }

    @Override
    void setupLauncher(buildFile) {
        buildFile << """\
            repositories {
                ivy {
                    url '${URL}'
                    layout 'pattern', {
                        artifact '${ARTIFACT_PATTERN}'
                        ivy '${IVY_PATTERN}'
                        m2compatible = true
                    }
                }    
            }
        """.stripIndent()
    }
}
