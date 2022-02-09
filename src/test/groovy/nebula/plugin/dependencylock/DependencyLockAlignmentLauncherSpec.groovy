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

import nebula.plugin.dependencyverifier.DependencyResolutionVerifierKt
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import spock.lang.Subject
import spock.lang.Unroll

import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

@Subject(DependencyResolutionVerifierKt)
class DependencyLockAlignmentLauncherSpec extends IntegrationTestKitSpec {
    def setup() {
        definePluginOutsideOfPluginBlock = true
        keepFiles = true
    }

    @Unroll
    def 'dependency-lock when applied before wins out over new locked alignment rules - core alignment #coreAlignment'() {
        def (GradleDependencyGenerator mavenrepo, File mavenForRules, File jsonRuleFile) = dependencyLockAlignInteractionSetupWithLockedResolutionRulesConfiguration()

        buildFile << """\
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath 'com.netflix.nebula:gradle-resolution-rules-plugin:latest.release'
                }
            }

            apply plugin: 'nebula.dependency-lock'
            apply plugin: 'nebula.resolution-rules'
            apply plugin: 'java'

            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                maven { url '${mavenForRules.absolutePath}' }
            }

            dependencies {
                resolutionRules 'test.rules:resolution-rules:1.+'
                implementation 'test.nebula:a:1.41.5'
                implementation 'test.nebula:b:1.42.2'
            }
        """.stripIndent()

        when:
        def results = runTasks('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def resultsForRules = runTasks('dependencyInsight', '--dependency', 'test.rules', '--configuration', 'resolutionRules', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        results.output.contains 'test.nebula:a locked to 1.41.5'
        results.output.contains 'test.nebula:b locked to 1.42.2'
        resultsForRules.output.contains 'Selected by rule : test.rules:resolution-rules locked to 1.0.0'

        // final results where locks win over new alignment rules
        results.output.contains 'test.nebula:a:1.41.5\n'
        results.output.contains 'test.nebula:b:1.42.2\n'
        resultsForRules.output.contains 'test.rules:resolution-rules:1.+ -> 1.0.0\n'

        !results.output.contains('belongs to platform')
        !results.output.contains('- Forced')
        !resultsForRules.output.contains('belongs to platform')
        !resultsForRules.output.contains('- Forced')

        when:
        def resultsIgnoringLocks = runTasks('-PdependencyLock.ignore=true', 'dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def resultsForRulesIgnoringLocks = runTasks('-PdependencyLock.ignore=true', 'dependencyInsight', '--dependency', 'test.rules', '--configuration', 'resolutionRules', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        // final results if we ignore locks
        resultsIgnoringLocks.output.contains 'test.nebula:a:1.42.2\n'
        resultsIgnoringLocks.output.contains 'test.nebula:b:1.42.2\n'
        resultsForRulesIgnoringLocks.output.contains 'test.rules:resolution-rules:1.1.0\n'

        assert resultsIgnoringLocks.output.contains('- By constraint : belongs to platform aligned-platform:rules-0-for-test.nebula:1.42.2\n')
        assert resultsIgnoringLocks.output.contains('- By conflict resolution : between versions 1.42.2 and 1.41.5')

        !resultsIgnoringLocks.output.contains('- Forced')
        !resultsForRulesIgnoringLocks.output.contains('- Forced')
    }

    @Unroll
    def 'dependency-lock when applied after wins out over new locked alignment rules - coreAlignment #coreAlignment'() {
        def (GradleDependencyGenerator mavenrepo, File mavenForRules, File jsonRuleFile) = dependencyLockAlignInteractionSetupWithLockedResolutionRulesConfiguration()

        buildFile << """\
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath 'com.netflix.nebula:gradle-resolution-rules-plugin:latest.release'
                }
            }

            apply plugin: 'nebula.resolution-rules'
            apply plugin: 'nebula.dependency-lock'
            apply plugin: 'java'

            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                maven { url '${mavenForRules.absolutePath}' }
            }

            dependencies {
                resolutionRules 'test.rules:resolution-rules:1.+'
                implementation 'test.nebula:a:1.41.5'
                implementation 'test.nebula:b:1.42.2'
            }
        """.stripIndent()

        when:
        def results = runTasks('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def resultsForRules = runTasks('dependencyInsight', '--dependency', 'test.rules', '--configuration', 'resolutionRules', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        results.output.contains 'test.nebula:a locked to 1.41.5'
        results.output.contains 'test.nebula:b locked to 1.42.2'
        resultsForRules.output.contains 'Selected by rule : test.rules:resolution-rules locked to 1.0.0'

        // final results where locks win over new alignment rules
        results.output.contains 'test.nebula:a:1.41.5\n'
        results.output.contains 'test.nebula:b:1.42.2\n'
        resultsForRules.output.contains 'test.rules:resolution-rules:1.+ -> 1.0.0\n'

        !results.output.contains('belongs to platform')
        !results.output.contains('- Forced')
        !resultsForRules.output.contains('belongs to platform')
        !resultsForRules.output.contains('- Forced')

        when:
        def resultsIgnoringLocks = runTasks('-PdependencyLock.ignore=true', 'dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def resultsForRulesIgnoringLocks = runTasks('-PdependencyLock.ignore=true', 'dependencyInsight', '--dependency', 'test.rules', '--configuration', 'resolutionRules', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        // final results if we ignore locks
        resultsIgnoringLocks.output.contains 'test.nebula:a:1.42.2\n'
        resultsIgnoringLocks.output.contains 'test.nebula:b:1.42.2\n'
        resultsForRulesIgnoringLocks.output.contains 'test.rules:resolution-rules:1.1.0\n'

        if(coreAlignment) {
            assert resultsIgnoringLocks.output.contains('- By constraint : belongs to platform aligned-platform:rules-0-for-test.nebula:1.42.2\n')
            assert resultsIgnoringLocks.output.contains('- By conflict resolution : between versions 1.42.2 and 1.41.5')
        }

        !resultsIgnoringLocks.output.contains('- Forced')
        !resultsForRulesIgnoringLocks.output.contains('- Forced')

        where:
        coreAlignment << [true]
    }

    @Unroll
    def 'dependency-lock plugin applied after resolution-rules plugin with non-locked resolution rules - #description'() {
        // note: this is a more unusual case. Typically resolution rules are distributed like a library, version controlled, and locked like other dependencies
        def (GradleDependencyGenerator mavenrepo, File rulesJsonFile) = dependencyLockAlignInteractionSetupWithUnlockedResolutionRulesConfiguration()
        buildFile << """\
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath 'com.netflix.nebula:gradle-resolution-rules-plugin:latest.release'
                }
            }
            apply plugin: 'nebula.resolution-rules'
            apply plugin: 'nebula.dependency-lock'
            apply plugin: 'java'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                resolutionRules files('$rulesJsonFile')
                implementation 'test.nebula:a:1.41.5'
                implementation 'test.nebula:b:1.42.2'
            }
            """.stripIndent()

        when:
        def insightResults = runTasksAndFail('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def  results = runTasksAndFail('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        assert insightResults.output.contains('test.nebula:a:1.41.5 -> 1.42.2\n')
        assert insightResults.output.contains('test.nebula:a:1.42.2\n')

        assert insightResults.output.contains('FAILED')

        assert results.output.contains("Resolved 'test.nebula:a:1.42.2' instead of locked version '1.41.5'")
        assert results.output.contains("Please update your dependency locks or your build file constraints.")

        when:
        def ignoreLocksResults = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true", '-PdependencyLock.ignore=true')

        then:
        ignoreLocksResults.output.contains '+--- test.nebula:a:1.41.5 -> 1.42.2\n'
        ignoreLocksResults.output.contains '\\--- test.nebula:b:1.42.2\n'
        !ignoreLocksResults.output.contains('FAILED')

        when:
        runTasks('generateLock', 'saveLock', "-Dnebula.features.coreAlignmentSupport=true")
        def locksUpdatedInsightResults = runTasks('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def locksUpdatedResults = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        locksUpdatedResults.output.contains '+--- test.nebula:a:1.41.5 -> 1.42.2\n'
        locksUpdatedResults.output.contains '\\--- test.nebula:b:1.42.2\n'
        locksUpdatedInsightResults.output.contains('Selected by rule : test.nebula:a locked to 1.42.2')
        locksUpdatedInsightResults.output.contains('Selected by rule : test.nebula:b locked to 1.42.2')

    }

    @Unroll
    def 'dependency-lock plugin applied before resolution-rules plugin with non-locked resolution rules - #description'() {
        // note: this is a more unusual case. Typically resolution rules are distributed like a library, version controlled, and locked like other dependencies
        def (GradleDependencyGenerator mavenrepo, File rulesJsonFile) = dependencyLockAlignInteractionSetupWithUnlockedResolutionRulesConfiguration()
        buildFile << """\
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath 'com.netflix.nebula:gradle-resolution-rules-plugin:latest.release'
                }
            }
            apply plugin: 'nebula.dependency-lock'
            apply plugin: 'nebula.resolution-rules'
            apply plugin: 'java'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                resolutionRules files('$rulesJsonFile')
                implementation 'test.nebula:a:1.41.5'
                implementation 'test.nebula:b:1.42.2'
            }
            """.stripIndent()

        when:
        def insightResults = runTasksAndFail('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def results = runTasksAndFail('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true")



        then:
        // plugin ordering is important. Dependency lock plugin must be applied after the resolution rules plugin.
        // This test case is simply showcasing the current behavior.
        assert results.output.contains('+--- test.nebula:a:1.41.5 -> 1.42.2\n') // this does not honor the locked versions
        assert results.output.contains('\\--- test.nebula:b:1.42.2\n')
        assert results.output.contains("Resolved 'test.nebula:a:1.42.2' instead of locked version '1.41.5'")
        assert results.output.contains("Please update your dependency locks or your build file constraints.")

        when:
        def ignoreLocksResults = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true", '-PdependencyLock.ignore=true')

        then:
        ignoreLocksResults.output.contains '+--- test.nebula:a:1.41.5 -> 1.42.2\n'
        ignoreLocksResults.output.contains '\\--- test.nebula:b:1.42.2\n'
        !ignoreLocksResults.output.contains('FAILED')

        when:
        runTasks('generateLock', 'saveLock', "-Dnebula.features.coreAlignmentSupport=true")
        def locksUpdatedInsightResults = runTasks('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def locksUpdatedResults = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        locksUpdatedResults.output.contains '+--- test.nebula:a:1.41.5 -> 1.42.2\n'
        locksUpdatedResults.output.contains '\\--- test.nebula:b:1.42.2\n'
        locksUpdatedInsightResults.output.contains('Selected by rule : test.nebula:a locked to 1.42.2')
        locksUpdatedInsightResults.output.contains('Selected by rule : test.nebula:b locked to 1.42.2')

    }

    def 'dependency-lock and new unversioned core alignment rule in the project cause resolution failure until locks are updated'() {
        // note: this is a more unusual case. Typically resolution rules are distributed like a library, version controlled, and locked like other dependencies
        def (GradleDependencyGenerator mavenrepo, File rulesJsonFile) = dependencyLockAlignInteractionSetupWithUnlockedResolutionRulesConfiguration()
        buildFile << """\
            apply plugin: 'nebula.dependency-lock'
            apply plugin: 'java'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.41.5'
                implementation 'test.nebula:b:1.42.2'
            }
            """.stripIndent()

        when:
        def insightResults = runTasks('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def results = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        results.output.contains '+--- test.nebula:a:1.41.5\n'
        results.output.contains '\\--- test.nebula:b:1.42.2\n'

        when:
        buildFile << """
            logger.warn("--- Informational message: using an unversioned, local-to-project alignment rule ---")
            project.dependencies.components.all(AlignGroup.class)
            class AlignGroup implements ComponentMetadataRule {
                void execute(ComponentMetadataContext ctx) {
                    ctx.details.with { it ->
                        if (it.getId().getGroup().startsWith("test.nebula")) {
                            it.belongsTo("test.nebula:test.nebula:\${it.getId().getVersion()}")
                        }
                    }
                }
            }
            """.stripIndent()

        def newAlignmentRuleInsightResults = runTasksAndFail('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def newAlignmentRuleResults = runTasksAndFail('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        newAlignmentRuleResults.output.contains("Resolved 'test.nebula:a:1.42.2' instead of locked version '1.41.5'")
        newAlignmentRuleResults.output.contains("Please update your dependency locks or your build file constraints.")

        when:
        runTasks('generateLock', 'saveLock', "-Dnebula.features.coreAlignmentSupport=true")
        def locksUpdatedInsightResults = runTasks('dependencyInsight', '--dependency', 'test.nebula', "-Dnebula.features.coreAlignmentSupport=true")
        def locksUpdatedResults = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=true")

        then:
        locksUpdatedResults.output.contains '+--- test.nebula:a:1.41.5 -> 1.42.2\n'
        locksUpdatedResults.output.contains '\\--- test.nebula:b:1.42.2\n'
        locksUpdatedInsightResults.output.contains('Selected by rule : test.nebula:a locked to 1.42.2')
        locksUpdatedInsightResults.output.contains('Selected by rule : test.nebula:b locked to 1.42.2')

        locksUpdatedInsightResults.output.contains('belongs to platform test.nebula:test.nebula:1.42.2')

        where:
        coreAlignment << [true]
    }

    private List dependencyLockAlignInteractionSetupWithLockedResolutionRulesConfiguration() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.41.5')
                .addModule('test.nebula:a:1.42.2')
                .addModule('test.nebula:b:1.41.5')
                .addModule('test.nebula:b:1.42.2')
                .build()
        def mavenrepo = new GradleDependencyGenerator(graph, "$projectDir/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def rulesFolder = new File(projectDir, 'rules')
        rulesFolder.mkdirs()
        def rulesJsonFile = new File(rulesFolder, 'rules.json')

        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [], "align": []
            }
        '''.stripIndent()

        def mavenForRules = new File(projectDir, 'repo')
        mavenForRules.mkdirs()
        def locked = new File(mavenForRules, 'test/rules/resolution-rules/1.0.0')
        locked.mkdirs()
        createRulesJar([rulesFolder], projectDir, new File(locked, 'resolution-rules-1.0.0.jar'))
        createPom('test.rules', 'resolution-rules', '1.0.0', locked)

        rulesJsonFile.text = '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.nebula",
                        "reason": "Align test.nebula dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()
        def newer = new File(mavenForRules, 'test/rules/resolution-rules/1.1.0')
        newer.mkdirs()
        createRulesJar([rulesFolder], projectDir, new File(newer, 'resolution-rules-1.1.0.jar'))
        createPom('test.rules', 'resolution-rules', '1.1.0', newer)

        def mavenMetadataXml = new File(mavenForRules, 'test/rules/resolution-rules/maven-metadata.xml')
        mavenMetadataXml.createNewFile()
        mavenMetadataXml << '''<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>test.rules</groupId>
  <artifactId>resolution-rules</artifactId>
  <versioning>
    <latest>1.1.0</latest>
    <release>1.1.0</release>
    <versions>
      <version>1.0.0</version>
      <version>1.1.0</version>
    </versions>
    <lastUpdated>20200320014943</lastUpdated>
  </versioning>
</metadata>
'''

        def dependencyLock = new File(projectDir, 'dependencies.lock')
        dependencyLock << '''\
        {
            "compileClasspath": {
                "test.nebula:a": { "locked": "1.41.5" },
                "test.nebula:b": { "locked": "1.42.2" }
            },
            "resolutionRules": {
                "test.rules:resolution-rules": { "locked": "1.0.0" }
            }
        }
        '''.stripIndent()
        [mavenrepo, mavenForRules, rulesJsonFile]
    }

    private List dependencyLockAlignInteractionSetupWithUnlockedResolutionRulesConfiguration() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.41.5')
                .addModule('test.nebula:a:1.42.2')
                .addModule('test.nebula:b:1.41.5')
                .addModule('test.nebula:b:1.42.2')
                .build()
        def mavenrepo = new GradleDependencyGenerator(graph, "$projectDir/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def rulesJsonFile = new File(projectDir, 'rules.json')

        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.nebula",
                        "reason": "Align test.nebula dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        def dependencyLock = new File(projectDir, 'dependencies.lock')

        dependencyLock << '''\
        {
            "compileClasspath": {
                "test.nebula:a": { "locked": "1.41.5" },
                "test.nebula:b": { "locked": "1.42.2" }
            }
        }
        '''.stripIndent()
        [mavenrepo, rulesJsonFile]
    }

    private createRulesJar(Collection<File> files, File unneededRoot, File destination) {
        Manifest manifest = new Manifest()
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, '1.0')
        JarOutputStream target = new JarOutputStream(new FileOutputStream(destination), manifest)
        files.each { add(it, unneededRoot, target) }
        target.close()
    }

    private createPom(String group, String name, String version, File dir) {
        def pom = new File(dir, "${name}-${version}.pom")
        pom.text = """\
            <?xml version="1.0" encoding="UTF-8"?>
            <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <modelVersion>4.0.0</modelVersion>
              <groupId>${group}</groupId>
              <artifactId>${name}</artifactId>
              <version>${version}</version>
            </project>
        """.stripIndent()
    }

    private void add(File source, File unneededRoot, JarOutputStream target) throws IOException {
        def prefix = "${unneededRoot.path}/"
        if (source.isDirectory()) {
            String dirName = source.path - prefix
            if (!dirName.endsWith('/')) {
                dirName += '/'
            }
            def entry = new JarEntry(dirName)
            target.putNextEntry(entry)
            target.closeEntry()
            source.listFiles().each { nested ->
                add(nested, unneededRoot, target)
            }
        } else {
            def entry = new JarEntry(source.path - prefix)
            target.putNextEntry(entry)
            target << source.bytes
            target.closeEntry()
        }
    }
}
