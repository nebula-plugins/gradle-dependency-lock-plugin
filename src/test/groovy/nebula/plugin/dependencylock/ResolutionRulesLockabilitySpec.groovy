/**
 *
 *  Copyright 2021 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package nebula.plugin.dependencylock

import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.plugin.dependencylock.util.LockGenerator
import org.gradle.testkit.runner.TaskOutcome

import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class ResolutionRulesLockabilitySpec extends BaseIntegrationTestKitSpec {
    def mavenForRules

    def setup() {
        keepFiles = true

        setupRules()

        buildFile << """\
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath 'com.netflix.nebula:gradle-resolution-rules-plugin:latest.release'
                }
            }
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'java'
            }
            apply plugin: 'com.netflix.nebula.resolution-rules'
            allprojects {
                repositories {
                    mavenCentral()
                    maven { url = '${mavenForRules.absolutePath}' }
                }
            }
            dependencies {
                resolutionRules 'test.rules:resolution-rules:1.+'
            }
        """.stripIndent()

        addSubproject('sub1', """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'com.netflix.nebula.resolution-rules'
                id 'java'
            }
            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.12.0'
            }
            """)

        addSubproject('sub2', """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'com.netflix.nebula.resolution-rules'
                id 'java'
            }
            dependencies {
                implementation project(':sub1')
                implementation 'commons-io:commons-io:2.11.0'
            }
            """)

        addSubproject('sub3', """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'com.netflix.nebula.resolution-rules'
                id 'java'
            }
            dependencies {
                implementation 'commons-logging:commons-logging:1.2'
            }
            """)
    }

    def 'global locking works'() {
        disableConfigurationCache()

        when:
        def result = runTasks('generateGlobalLock', 'saveGlobalLock')

        then:
        result.task(":generateGlobalLock").outcome == TaskOutcome.SUCCESS
        result.task(":saveGlobalLock").outcome == TaskOutcome.SUCCESS

        def globalLockFile = new File(projectDir, 'global.lock')
        def globalLockText = """\
{
    "_global_": {
        "commons-io:commons-io": {
            "firstLevelTransitive": [
                "$moduleName:sub2"
            ],
            "locked": "2.11.0"
        },
        "commons-logging:commons-logging": {
            "firstLevelTransitive": [
                "$moduleName:sub3"
            ],
            "locked": "1.2"
        },
        "$moduleName:sub1": {
            "firstLevelTransitive": [
                "$moduleName:sub2"
            ],
            "project": true
        },
        "$moduleName:sub2": {
            "project": true
        },
        "$moduleName:sub3": {
            "project": true
        },
        "org.apache.commons:commons-lang3": {
            "firstLevelTransitive": [
                "$moduleName:sub1"
            ],
            "locked": "3.12.0"
        }
    },
    "resolutionRules": {
        "test.rules:resolution-rules": {
            "locked": "1.0.0"
        }
    }
}""".stripIndent()

        globalLockFile.text == globalLockText
    }

    def 'project locking works'() {
        when:
        def result = runTasks('generateLock', 'saveLock')

        then:
        result.task(":generateLock").outcome == TaskOutcome.SUCCESS
        result.task(":saveLock").outcome == TaskOutcome.SUCCESS

        def rootLockFile = new File(projectDir, 'dependencies.lock')
        def sub1LockFile = new File(projectDir, 'sub1/dependencies.lock')
        def sub2LockFile = new File(projectDir, 'sub2/dependencies.lock')
        def sub3LockFile = new File(projectDir, 'sub3/dependencies.lock')

        def rootLockText = '''\
            {
                "resolutionRules": {
                    "test.rules:resolution-rules": {
                        "locked": "1.0.0"
                    }
                }
            }'''.stripIndent()

        def sub1LockText = LockGenerator.duplicateIntoConfigs('''\
            "org.apache.commons:commons-lang3": {
                "locked": "3.12.0"
            }
            '''.stripIndent(), ['compileClasspath', 'testCompileClasspath', 'runtimeClasspath', 'testRuntimeClasspath'], """\
            ":$moduleName": {
                "project": true
            },
            "test.rules:resolution-rules": {
                "firstLevelTransitive": [
                    ":$moduleName"
                ],
                "locked": "1.0.0"
            }""".stripIndent(), ['resolutionRules'])

        def sub2LockText = LockGenerator.duplicateIntoConfigs("""\
        "commons-io:commons-io": {
            "locked": "2.11.0"
        },
        "org.apache.commons:commons-lang3": {
            "firstLevelTransitive": [
                "$moduleName:sub1"
            ],
            "locked": "3.12.0"
        },
        "$moduleName:sub1": {
            "project": true
        }
        """.stripIndent(), ['runtimeClasspath', 'testRuntimeClasspath'], """\
        "commons-io:commons-io": {
            "locked": "2.11.0"
        },
        "$moduleName:sub1": {
            "project": true
        }
        """.stripIndent(), ['compileClasspath', 'testCompileClasspath'], """\
        ":$moduleName": {
            "project": true
        },
        "test.rules:resolution-rules": {
            "firstLevelTransitive": [
                ":$moduleName"
            ],
            "locked": "1.0.0"
        }""".stripIndent(), ['resolutionRules'])

        def sub3LockText = LockGenerator.duplicateIntoConfigs('''\
            "commons-logging:commons-logging": {
                "locked": "1.2"
            }
            '''.stripIndent(), ['compileClasspath', 'testCompileClasspath', 'runtimeClasspath', 'testRuntimeClasspath'], """\
            ":$moduleName": {
                "project": true
            },
            "test.rules:resolution-rules": {
                "firstLevelTransitive": [
                    ":$moduleName"
                ],
                "locked": "1.0.0"
            }""".stripIndent(), ['resolutionRules'])

        rootLockFile.text == rootLockText
        sub1LockFile.text == sub1LockText
        sub2LockFile.text == sub2LockText
        sub3LockFile.text == sub3LockText
    }

    def 'Gradle core locking works'() {
        given:
        new File("${projectDir}/gradle.properties").text = """
            systemProp.nebula.features.coreLockingSupport=true
            dependencyLock.additionalConfigurationsToLock=resolutionRules
            """.stripIndent()
        buildFile << '''
            allprojects {
                task dependenciesForAll(type: DependencyReportTask) {}
            }
            '''.stripIndent()

        when:
        def result = runTasks('dependenciesForAll', '--write-locks')

        then:
        def rootLockFile = coreLockContent(new File(projectDir, 'gradle.lockfile'))
        def sub1LockFile = coreLockContent(new File(projectDir, 'sub1/gradle.lockfile'))
        def sub2LockFile = coreLockContent(new File(projectDir, 'sub2/gradle.lockfile'))
        def sub3LockFile = coreLockContent(new File(projectDir, 'sub3/gradle.lockfile'))

        rootLockFile.get('test.rules:resolution-rules:1.0.0') == 'resolutionRules'
        rootLockFile.get('empty') == 'annotationProcessor,compileClasspath,runtimeClasspath,testAnnotationProcessor,testCompileClasspath,testRuntimeClasspath'

        sub1LockFile.get('org.apache.commons:commons-lang3:3.12.0') == 'compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath'
        sub1LockFile.get('test.rules:resolution-rules:1.0.0') == 'resolutionRules'
        sub1LockFile.get('empty') == 'annotationProcessor,testAnnotationProcessor'

        sub2LockFile.get('commons-io:commons-io:2.11.0') == 'compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath'
        sub2LockFile.get('org.apache.commons:commons-lang3:3.12.0') == 'runtimeClasspath,testRuntimeClasspath'
        sub2LockFile.get('test.rules:resolution-rules:1.0.0') == 'resolutionRules'
        sub2LockFile.get('empty') == 'annotationProcessor,testAnnotationProcessor'

        sub3LockFile.get('commons-logging:commons-logging:1.2') == 'compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath'
        sub3LockFile.get('test.rules:resolution-rules:1.0.0') == 'resolutionRules'
        sub3LockFile.get('empty') == 'annotationProcessor,testAnnotationProcessor'
    }

    def 'Migrating to Gradle core locking works'() {
        when:
        def nebulaPluginResult = runTasks('generateLock', 'saveLock')

        then:
        nebulaPluginResult.task(":generateLock").outcome == TaskOutcome.SUCCESS
        nebulaPluginResult.task(":saveLock").outcome == TaskOutcome.SUCCESS

        when:
        new File("${projectDir}/gradle.properties").text = """
            systemProp.nebula.features.coreLockingSupport=true
            dependencyLock.additionalConfigurationsToLock=resolutionRules
            """.stripIndent()

        def result = runTasks('migrateToCoreLocks')

        then:
        result.task(":migrateToCoreLocks").outcome == TaskOutcome.SUCCESS

        def rootLockFile = coreLockContent(new File(projectDir, 'gradle.lockfile'))
        def sub1LockFile = coreLockContent(new File(projectDir, 'sub1/gradle.lockfile'))
        def sub2LockFile = coreLockContent(new File(projectDir, 'sub2/gradle.lockfile'))
        def sub3LockFile = coreLockContent(new File(projectDir, 'sub3/gradle.lockfile'))

        rootLockFile.get('test.rules:resolution-rules:1.0.0') == 'resolutionRules'
        rootLockFile.get('empty') == 'annotationProcessor,compileClasspath,runtimeClasspath,testAnnotationProcessor,testCompileClasspath,testRuntimeClasspath'

        sub1LockFile.get('org.apache.commons:commons-lang3:3.12.0') == 'compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath'
        sub1LockFile.get('test.rules:resolution-rules:1.0.0') == 'resolutionRules'
        sub1LockFile.get('empty') == 'annotationProcessor,testAnnotationProcessor'

        sub2LockFile.get('commons-io:commons-io:2.11.0') == 'compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath'
        sub2LockFile.get('org.apache.commons:commons-lang3:3.12.0') == 'runtimeClasspath,testRuntimeClasspath'
        sub2LockFile.get('test.rules:resolution-rules:1.0.0') == 'resolutionRules'
        sub2LockFile.get('empty') == 'annotationProcessor,testAnnotationProcessor'

        sub3LockFile.get('commons-logging:commons-logging:1.2') == 'compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath'
        sub3LockFile.get('test.rules:resolution-rules:1.0.0') == 'resolutionRules'
        sub3LockFile.get('empty') == 'annotationProcessor,testAnnotationProcessor'
    }

    private void setupRules() {
        def rulesFolder = new File(projectDir, 'rules')
        rulesFolder.mkdirs()
        def rulesJsonFile = new File(rulesFolder, 'rules.json')
        rulesJsonFile.text = '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [], "align": []
            }
        '''.stripIndent()

        mavenForRules = new File(projectDir, 'repo')
        mavenForRules.mkdirs()
        def locked = new File(mavenForRules, 'test/rules/resolution-rules/1.0.0')
        locked.mkdirs()
        createRulesJar([rulesFolder], projectDir, new File(locked, 'resolution-rules-1.0.0.jar'))
        createPom('test.rules', 'resolution-rules', '1.0.0', locked)

        def mavenMetadataXml = new File(mavenForRules, 'test/rules/resolution-rules/maven-metadata.xml')
        mavenMetadataXml.createNewFile()
        mavenMetadataXml << '''<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>test.rules</groupId>
  <artifactId>resolution-rules</artifactId>
  <versioning>
    <latest>1.0.0</latest>
    <release>1.0.0</release>
    <versions>
      <version>1.0.0</version>
    </versions>
    <lastUpdated>20211130014943</lastUpdated>
  </versioning>
</metadata>
'''
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

    private Map<String, String> coreLockContent(File lockFile) {
        lockFile.readLines().findAll {!it.startsWith("#")}.collectEntries {
            it.split('=').toList()
        }
    }
}
