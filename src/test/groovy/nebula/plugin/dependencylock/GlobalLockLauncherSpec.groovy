/*
 * Copyright 2014-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License atpre5*4nu
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock

import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder

class GlobalLockLauncherSpec extends BaseIntegrationTestKitSpec {

    def setup() {
        definePluginOutsideOfPluginBlock = true
        keepFiles = true
        disableConfigurationCache()
    }

    def 'global lock selective dependency updates'() {
        def graph = new DependencyGraphBuilder()
                .addModule('apricot:a:1.0.0')
                .addModule(new ModuleBuilder('beetroot:b:1.0.0').addDependency('apricot:a:1.0.0').build())
                .addModule('cherry:c:1.0.0')
                .addModule('dandelion:d:1.0.0')
                .addModule(new ModuleBuilder('eggplant:e:1.0.0').addDependency('dandelion:d:1.0.0').build())
                .build()
        def mavenrepo = new GradleDependencyGenerator(graph, "$projectDir/testrepogen")
        mavenrepo.generateTestMavenRepo()

        addSubproject('sub1', """\
            dependencies {
                implementation 'beetroot:b:1.+'
                implementation 'cherry:c:1.+'
                implementation 'eggplant:e:1.+'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                implementation 'beetroot:b:1.+'
                implementation 'cherry:c:1.+'
                implementation 'eggplant:e:1.+'
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
                dependencyLock {
                    includeTransitives = true
                }
            }
            subprojects {
                apply plugin: 'java'
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                }
            }
        """.stripIndent()

        when:
        def generateLockResults = runTasks("generateGlobalLock", "saveGlobalLock")
        def results = runTasks(":sub1:dependencies", "--configuration", "compileClasspath")

        then:
        results.output.contains 'apricot:a:1.0.0'
        results.output.contains 'beetroot:b:1.+ -> 1.0.0'
        results.output.contains 'cherry:c:1.+ -> 1.0.0'
        results.output.contains 'dandelion:d:1.0.0'
        results.output.contains 'eggplant:e:1.+ -> 1.0.0'

        def globalLockfileText = new File(projectDir, 'global.lock').text
        globalLockfileText.contains '_global_'
        globalLockfileText.contains 'apricot:a'
        globalLockfileText.contains 'beetroot:b'
        globalLockfileText.contains 'cherry:c'
        globalLockfileText.contains 'dandelion:d'
        globalLockfileText.contains 'eggplant:e'

        when:
        graph = new DependencyGraphBuilder()
                .addModule('apricot:a:1.1.0')
                .addModule(new ModuleBuilder('beetroot:b:1.1.0').addDependency('apricot:a:1.1.0').build())
                .addModule('cherry:c:1.1.0')
                .addModule('dandelion:d:1.1.0')
                .addModule(new ModuleBuilder('eggplant:e:1.1.0').addDependency('dandelion:d:1.1.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "$projectDir/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def selectiveUpdateGlobalLockResults = runTasks("updateGlobalLock", "-PdependencyLock.updateDependencies=beetroot:b", "saveGlobalLock")
        def updatedResults = runTasks(":sub1:dependencies", "--configuration", "compileClasspath")

        then:
        updatedResults.output.contains 'apricot:a:1.1.0'
        updatedResults.output.contains 'beetroot:b:1.+ -> 1.1.0'
        updatedResults.output.contains 'cherry:c:1.+ -> 1.0.0'
        updatedResults.output.contains 'dandelion:d:1.0.0'
        updatedResults.output.contains 'eggplant:e:1.+ -> 1.0.0'

        def updatedGlobalLockfileText = new File(projectDir, 'global.lock').text
        updatedGlobalLockfileText.contains 'apricot:a'
        updatedGlobalLockfileText.contains 'beetroot:b'
        updatedGlobalLockfileText.contains 'cherry:c'
        updatedGlobalLockfileText.contains 'dandelion:d'
        updatedGlobalLockfileText.contains 'eggplant:e'
    }
}
