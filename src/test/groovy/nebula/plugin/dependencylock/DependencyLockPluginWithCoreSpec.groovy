package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.util.LockGenerator
import nebula.plugin.dependencylock.utils.GradleVersionUtils
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import org.gradle.api.logging.LogLevel
import org.gradle.util.GradleVersion
import spock.lang.IgnoreIf
import spock.lang.Unroll

class DependencyLockPluginWithCoreSpec extends AbstractDependencyLockPluginSpec {
    def setup() {
        logLevel = LogLevel.INFO
    }

    def 'generate core lock file'() {
        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    def 'generate and update core lock file'() {
        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        // update build file, so it no longer matches locks
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
                implementation 'test.nebula:d:1.+'
            }
        """.stripIndent()
        runTasks('dependencies', '--update-locks', 'test.nebula:d')

        then:
        lockFile.text.contains('test.nebula:d:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    @Unroll
    def 'run the build with core lock file when newer dependency versions exist with #lockingTask'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
                id 'com.github.johnrengelman.shadow' version '5.0.0'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
                shadow 'test.nebula:d:1.+'
            }
        """.stripIndent()

        when:
        def setupLocks = runTasks('dependencies', '--write-locks')

        then:
        !setupLocks.output.contains('FAILED')

        when:
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.2.0')
                .addModule('test.nebula:b:1.2.0')
                .addModule('test.nebula:d:1.2.0')
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def depInsightCompileClasspathWithLockedDeps = runTasks('dependencyInsight', '--dependency', 'test.nebula:a', '--configuration', 'compileClasspath')

        then:
        // different configurations should use the same version before updating locks
        depInsightCompileClasspathWithLockedDeps.output.contains('test.nebula:a:1.1.0')

        when:
        def updateLockTasks = lockingTask == 'write-locks'
                ? ['--write-locks']
                : ['--update-locks', 'test.nebula:a']
        def result = runTasks('dependencies', *updateLockTasks)

        then:
        !result.output.contains('FAILED')

        def lockfileDir = new File(projectDir, 'gradle/dependency-locks/')
        assert lockfileDir.listFiles().size() > 0
        lockfileDir.listFiles().each { lockFile ->
            if (lockFile.text.contains('test.nebula:a')) {
                assert lockFile.text.contains('test.nebula:a:1.2.0')
            }
            if (lockFile.text.contains('test.nebula:b')) {
                if (lockingTask == 'write-locks') {
                    assert lockFile.text.contains('test.nebula:b:1.2.0') // write-locks updates all deps
                } else {
                    assert lockFile.text.contains('test.nebula:b:1.1.0') // update-locks only updates the deps passed in
                }
            }
        }

        def actualLocks = lockfileDir.list().toList()
        def updatedLocks = expectedLocks + 'shadow.lockfile'
        updatedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert updatedLocks.contains(it): "There is an extra lockfile: $it"
        }

        where:
        lockingTask << ['write-locks', 'update-locks']
    }

    def 'fails to use same versions across configurations when newer dependency versions exist AND only the end result configurations are locked'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
                id 'com.github.johnrengelman.shadow' version '5.0.0'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()

        def lockfileDir = new File(projectDir, 'gradle/dependency-locks/')
        lockfileDir.mkdirs()
        def endResultConfigurations = ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath']

        endResultConfigurations.each { config ->
            def lockFile = new File(lockfileDir, "${config}.lockfile")
            lockFile.text = '''
                test.nebula:a:1.0.0
                test.nebula:b:1.0.0
                '''.stripIndent()
        }

        when:
        def results = runTasks('dependencies')

        then:
        results.output.contains('test.nebula:a:1.+ -> 1.0.0')
        results.output.contains('test.nebula:a:{strictly 1.0.0} -> 1.0.0 ')

        results.output.contains('test.nebula:b:1.+ -> 1.0.0')
        results.output.contains('test.nebula:b:{strictly 1.0.0} -> 1.0.0 ')
    }

    def 'generate core lock file while locking all configurations via property'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
                id 'jacoco'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks', '-PlockAllConfigurations=true')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        assert actualLocks.size() > expectedLocks.size()
        def allExpectedLocks = ["testCompileClasspath.lockfile",
                                "annotationProcessor.lockfile",
                                "compileClasspath.lockfile", "jacocoAnt.lockfile", "testAnnotationProcessor.lockfile",
                                "jacocoAgent.lockfile", "testRuntimeClasspath.lockfile",
                                "runtimeClasspath.lockfile"]
        allExpectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/jacocoAgent.lockfile')
        lockFile.exists()

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    def 'generate core lock file but do not all configurations by default'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
                id 'jacoco'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/jacocoAgent.lockfile')
        !lockFile.exists()

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    @Unroll
    def 'generate core lock file with multiproject setup - for configuration #configuration'() {
        given:
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """
            plugins {
                id 'java'
            }
            subprojects {
                task dependenciesForAll(type: DependencyReportTask) {}
            }
            """.stripIndent()

        addSubproject("sub1", """
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                $configuration 'test.nebula:a:1.+'
                $configuration 'test.nebula:b:1.+'
            }
            """.stripIndent())

        addSubproject("sub2", """
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                $configuration 'test.nebula:a:1.+'
                $configuration 'test.nebula:c:1.+'
            }
            """.stripIndent())

        when:
        def result = runTasks('dependenciesForAll', '--write-locks', '--warning-mode', 'none') // turn off warnings to continue using 'compile' configuration

        then:
        result.output.contains('coreLockingSupport feature enabled')

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        expectedLocks.each {
            assert sub1ActualLocks.contains(it): "There is a missing lockfile: $it"
        }
        sub1ActualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }
        def sub1LockFile = new File(projectDir, "sub1/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        sub1LockFile.text.contains('test.nebula:a:1.1.0')
        sub1LockFile.text.contains('test.nebula:b:1.1.0')

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()
        expectedLocks.each {
            assert sub2ActualLocks.contains(it): "There is a missing lockfile: $it"
        }
        sub2ActualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }
        def sub2LockFile = new File(projectDir, "sub2/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        sub2LockFile.text.contains('test.nebula:a:1.1.0')
        sub2LockFile.text.contains('test.nebula:c:1.1.0')
        sub2LockFile.text.contains('test.nebula:d:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build', '--warning-mode', 'none')

        then:
        !cleanBuildResults.output.contains('FAILURE')

        where:
        configuration    | lockFileToVerify
        'implementation' | 'compileClasspath'
    }

    //kotlin plugin adds compile which causes unexpected results ignore until we know more https://youtrack.jetbrains.com/issue/KT-44462
    @IgnoreIf({ GradleVersion.current().baseVersion >= GradleVersion.version("7.0")})
    @Unroll
    def 'generate core lock file with kotlin plugin - for configuration #configuration'() {
        given:
        System.setProperty("ignoreDeprecations", "true")
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'nebula.kotlin' version '1.3.41'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                $configuration 'test.nebula:a:1.+'
                $configuration 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks', '--warning-mode', 'none') // turn off warnings to continue using 'compile' configuration

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, "/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build', '--warning-mode', 'none')

        then:
        !cleanBuildResults.output.contains('FAILURE')
        System.setProperty("ignoreDeprecations", "false")

        where:
        configuration    | lockFileToVerify
        'implementation' | 'compileClasspath'
    }

    //kotlin plugin adds compile which causes unexpected results ignore until we know more https://youtrack.jetbrains.com/issue/KT-44462
    @IgnoreIf({ GradleVersion.current().baseVersion >= GradleVersion.version("7.0")})
    @Unroll
    def 'generate core lock file with kotlin plugin with multiproject setup - for configuration #configuration'() {
        given:
        System.setProperty("ignoreDeprecations", "true")

        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
            }
            allprojects {
                task dependenciesForAll(type: DependencyReportTask) {}
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                    mavenCentral()
                }
            }
            """.stripIndent()

        addSubproject("sub1", """
            plugins {
                id 'nebula.dependency-lock'
                id 'nebula.kotlin' version '1.3.41'
            }
            dependencies {
                $configuration 'test.nebula:a:1.+'
                $configuration 'test.nebula:b:1.+'
            }
        """.stripIndent())

        when:
        def result = runTasks('dependenciesForAll', '--write-locks', '--warning-mode', 'none') // turn off warnings to continue using 'compile' configuration

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, "sub1/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build', '--warning-mode', 'none')

        then:
        !cleanBuildResults.output.contains('FAILURE')
        System.setProperty("ignoreDeprecations", "false")

        where:
        configuration    | lockFileToVerify
        'implementation' | 'compileClasspath'
    }

    @Unroll
    def 'generate core lock file with clojure plugin - for configuration #configuration'() {
        given:
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id "nebula.clojure" version "9.4.3"
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                $configuration 'org.clojure:clojure:1.8.0'
                $configuration 'test.nebula:a:1.+'
                $configuration 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks', '--warning-mode', 'none') // turn off warnings to continue using 'compile' configuration

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, "/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build', '--warning-mode', 'none')

        then:
        !cleanBuildResults.output.contains('FAILURE')

        where:
        configuration    | lockFileToVerify
        'implementation' | 'compileClasspath'
    }

    @Unroll
    def 'ordering language plugin and locking plugin should not matter - #languagePlugin #notes'() {
        given:
        def additionalDependencies = ''
        if (languagePlugin == 'scala') {
            additionalDependencies = """
                implementation 'org.scala-lang:scala-library:2.12.7'
                testImplementation 'junit:junit:4.12'
                testImplementation 'org.scalatest:scalatest_2.12:3.0.5'
                testRuntimeOnly 'org.scala-lang.modules:scala-xml_2.12:1.1.1'
                """.stripIndent()
        } else if (languagePlugin == "nebula.clojure") {
            additionalDependencies = """
                implementation 'org.clojure:clojure:1.8.0'
                """.stripIndent()
        }
        definePluginOutsideOfPluginBlock = true

        def plugins = languagePluginFirst
                ? """
                apply plugin: '$languagePlugin'
                apply plugin: 'nebula.dependency-lock'
                """.stripIndent()
                : """
                apply plugin: 'nebula.dependency-lock'
                apply plugin: '$languagePlugin'
                """.stripIndent()

        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """\
            buildscript {
                repositories { maven { url "https://plugins.gradle.org/m2/" } }
                dependencies {
                    classpath "com.netflix.nebula:nebula-clojure-plugin:9.4.3"
                    classpath "com.netflix.nebula:nebula-kotlin-plugin:1.3.40"
                }
            }
            $plugins
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'$additionalDependencies
            }
        """.stripIndent()

        if (languagePlugin == 'nebula.kotlin') {
            System.setProperty("ignoreDeprecations", "true")
        }
        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        assert new File(projectDir, "/gradle/").exists()

        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFileToVerify = "compileClasspath"

        def lockFile = new File(projectDir, "/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')

        if (languagePlugin == 'nebula.kotlin') {
            System.setProperty("ignoreDeprecations", "false")
        }

        //TODO kotlin plugin adds compile which causes unexpected results ignore until we know more https://youtrack.jetbrains.com/issue/KT-44462
        where:
        languagePlugin   | languagePluginFirst | notes
        'groovy'         | true                | 'applied first'
        'java'           | true                | 'applied first'
        'java-library'   | true                | 'applied first'
        'nebula.clojure' | true                | 'applied first'
        //'nebula.kotlin'  | true                | 'applied first'
        'scala'          | true                | 'applied first'

        'groovy'         | false               | 'applied last'
        'java'           | false               | 'applied last'
        'java-library'   | false               | 'applied last'
        'nebula.clojure' | false               | 'applied last'
        //'nebula.kotlin'  | false               | 'applied last'
        'scala'          | false               | 'applied last'
    }

    @Unroll
    def 'generate core lock should lock additional configurations via property via #setupStyle'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
                id 'jacoco'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()

        if (setupStyle == 'properties file') {
            def file = new File("${projectDir}/gradle.properties")
            file << """
                dependencyLock.additionalConfigurationsToLock=jacocoAnt,jacocoAgent
                """.stripIndent()
        }

        when:
        def tasks = ['dependencies', '--write-locks']
        if (setupStyle == 'command line') {
            tasks += '-PdependencyLock.additionalConfigurationsToLock=jacocoAnt,jacocoAgent'
        }
        def result = runTasks(*tasks)

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        def updatedLocks = expectedLocks + ["jacocoAnt.lockfile", "jacocoAgent.lockfile"]
        updatedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert updatedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/jacocoAgent.lockfile')
        lockFile.exists()

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')

        where:
        setupStyle << ['command line', 'properties file']
    }

    def 'generate core lock should lock additional configurations via property and extension configuration'() {
        given:
        definePluginOutsideOfPluginBlock = true
        buildFile.text = """\
            buildscript {
              repositories {
                maven {
                  url "https://plugins.gradle.org/m2/"
                }
              }
              dependencies {
                classpath "gradle.plugin.com.github.spotbugs.snom:spotbugs-gradle-plugin:4.6.0"
              }
            }
            apply plugin: 'test.wrapper-plugin'
            apply plugin: 'java'
            apply plugin: 'jacoco'
            apply plugin: 'com.github.spotbugs'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()

        def file = new File("${projectDir}/gradle.properties")
        file << """
            dependencyLock.additionalConfigurationsToLock=jacocoAnt,jacocoAgent
            """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks', '--warning-mode', 'all')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        def updatedLocks = expectedLocks + ["jacocoAnt.lockfile", "jacocoAgent.lockfile", "spotbugs.lockfile"]
        updatedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert updatedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/jacocoAgent.lockfile')
        lockFile.exists()

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    def 'generate core lock should ignore extra lockfiles and then delete stale lockfiles when regenerating'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            configurations {
                customConfiguration
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                customConfiguration 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks', '-PdependencyLock.additionalConfigurationsToLock=customConfiguration')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        def updatedLocks = expectedLocks + ["customConfiguration.lockfile"]
        updatedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert updatedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/customConfiguration.lockfile')
        lockFile.exists()

        when:
        def buildResult = runTasks('clean', 'build')

        then:
        !buildResult.output.contains('FAIL')

        def customConfigurationLockFile = new File(projectDir, '/gradle/dependency-locks/customConfiguration.lockfile')
        assert customConfigurationLockFile.exists()

        when:
        runTasks('dependencies', '--write-locks')

        then:
        def updatedActualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()
        expectedLocks.each { expected ->
            assert updatedActualLocks.contains(expected)
        }
        updatedActualLocks.each { actual ->
            assert expectedLocks.contains(actual)
        }

        def customConfigurationLockFileAfterWriteLocks = new File(projectDir, '/gradle/dependency-locks/customConfiguration.lockfile')
        assert !customConfigurationLockFileAfterWriteLocks.exists()
    }

    def 'generate core lock should ignore extra lockfiles and then delete stale lockfiles when regenerating - multiproject setup'() {
        given:
        definePluginOutsideOfPluginBlock = true
        buildFile.text = """\
            allprojects {
                apply plugin: 'nebula.dependency-lock'
                apply plugin: 'java' 
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                }
                task dependenciesForAll(type: DependencyReportTask) {}
            }
        """.stripIndent()

        addSubproject("sub1", """
            configurations {
                customConfiguration
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                customConfiguration 'test.nebula:b:1.+'
            }
            """.stripIndent())

        addSubproject("sub2", """
            configurations {
                customConfiguration
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                customConfiguration 'test.nebula:b:1.+'
            }
            """.stripIndent())

        when:
        def result = runTasks('dependenciesForAll', '--write-locks', '-PdependencyLock.additionalConfigurationsToLock=customConfiguration')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()

        def updatedLocks = expectedLocks + ["customConfiguration.lockfile"]
        updatedLocks.each {
            assert sub1ActualLocks.contains(it): "There is a missing lockfile: $it"
        }
        sub1ActualLocks.each {
            assert updatedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, 'sub1/gradle/dependency-locks/customConfiguration.lockfile')
        lockFile.exists()

        when:
        def buildResult = runTasks('clean', 'build')

        then:
        !buildResult.output.contains('FAIL')

        def customConfigurationLockFile = new File(projectDir, 'sub1/gradle/dependency-locks/customConfiguration.lockfile')
        assert customConfigurationLockFile.exists()

        when:
        runTasks('dependenciesForAll', '--write-locks')

        then:
        def updatedSub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        expectedLocks.each { expected ->
            assert updatedSub1ActualLocks.contains(expected)
        }
        updatedSub1ActualLocks.each { actual ->
            assert expectedLocks.contains(actual)
        }

        def customConfigurationLockFileAfterWriteLocks = new File(projectDir, 'sub1/gradle/dependency-locks/customConfiguration.lockfile')
        assert !customConfigurationLockFileAfterWriteLocks.exists()
    }

    @Unroll
    def 'generate core lock file with #facet facet configurations'() {
        // TODO: Lock all the facet configurations by default
        given:
        def sourceSetConfig
        if (setParentSourceSet) {
            sourceSetConfig = """{
                parentSourceSet = 'test'
            }""".stripIndent()
        } else {
            sourceSetConfig = ''
        }

        buildFile.text = """
            buildscript {
              repositories {
                maven {
                  url "https://plugins.gradle.org/m2/"
                }
              }
              dependencies {
                classpath "com.netflix.nebula:nebula-project-plugin:7.0.9"
              }
            }
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            apply plugin: '$plugin'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
                testImplementation 'junit:junit:4.12'
            }
            facets {
                $facet $sourceSetConfig
            }
            """.stripIndent()

        def facetTestFile = createFile("${projectDir}/src/${facet}/java/${facet.capitalize()}.java")
        facetTestFile.text = """
            import org.junit.Test;
            public class ${facet.capitalize()} {
                @Test
                public void helloWorld${facet.capitalize()}() {
                    System.out.println("Hello World");
                }
            }
            """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        def facetLockfiles = GradleVersionUtils.currentGradleVersionIsLessThan("6.0")
                ? ["${facet}AnnotationProcessor.lockfile".toString(),
                   "${facet}Compile.lockfile".toString(),
                   "${facet}CompileClasspath.lockfile".toString(),
                   "${facet}CompileOnly.lockfile".toString(),
                   "${facet}Runtime.lockfile".toString(),
                   "${facet}RuntimeClasspath.lockfile".toString()
        ]
                : [
                "${facet}AnnotationProcessor.lockfile".toString(),
                "${facet}CompileClasspath.lockfile".toString(),
                "${facet}RuntimeClasspath.lockfile".toString()
        ]

        def updatedExpectedLocks = expectedLocks + facetLockfiles
        updatedExpectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert updatedExpectedLocks.contains(it): "There is an extra lockfile: $it"
        }
        def lockFile = new File(projectDir, "/gradle/dependency-locks/${facet}CompileClasspath.lockfile")
        assert lockFile.text.contains('test.nebula:a:1.1.0')
        assert lockFile.text.contains('test.nebula:b:1.1.0')
        assert lockFile.text.contains('junit:junit:4.12')
        assert lockFile.text.contains('org.hamcrest:hamcrest-core:1.3')

        where:
        facet       | plugin             | setParentSourceSet
        'integTest' | 'nebula.integtest' | false
//        'smokeTest' | 'nebula.facet'     | true
//        'examples'  | 'nebula.facet'     | true
    }

    def 'custom configurations not added by a plugin must be setup for locking'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            configurations {
                customConfiguration
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                customConfiguration 'test.nebula:b:1.+'
            }
        """.stripIndent()

        def file = new File("${projectDir}/gradle.properties")
        file << """
                dependencyLock.additionalConfigurationsToLock=customConfiguration
                """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        def updatedLocks = expectedLocks + 'customConfiguration.lockfile'
        updatedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert updatedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/customConfiguration.lockfile')
        assert lockFile.exists()
        assert lockFile.text.contains('test.nebula:b:')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    def 'lock each configuration only once'() {
        when:
        def result = runTasks('dependencies', '--write-locks', '--debug')

        then:
        expectedLocks.each {
            def lockingDebugMessage = "Activated configuration ':${it.split('.lockfile').first()}' for dependency locking"
            assert result.output.contains(lockingDebugMessage)
            assert result.output.findAll(lockingDebugMessage).size() == 1
        }
    }

    def 'fails when generating Nebula locks and writing core locks together'() {
        when:
        def result = runTasksAndFail('dependencies', '--write-locks', 'generateLock', 'saveLock')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        result.output.contains("> Task :generateLock FAILED")
    }

    @Unroll
    def 'fail if legacy global lock is present with core lock when running #task with error at task level'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        new File(projectDir, 'global.lock').text = """{}"""

        when:
        def result = runTasksAndFail(task)

        then:
        result.output.contains("Legacy global locks are not supported with core locking")
        result.output.contains("> Task :${task} FAILED")
        assertNoErrorsOnAParticularBuildLine(result.output)

        where:
        task << ['generateGlobalLock', 'updateGlobalLock']
    }

    def 'fail if legacy global lock is present with core lock when running non-locking tasks with error at plugin level'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        def legacyGlobalLockFile = new File(projectDir, 'global.lock')
        legacyGlobalLockFile.text = """{}"""

        when:
        def result = runTasksAndFail('dependencies')

        then:
        result.output.contains("Legacy global locks are not supported with core locking")
        assertFailureOccursAtPluginLevel(result.output)
        legacyGlobalLockFile.exists()
    }

    def 'fail if legacy lock is present with core lock when running non-locking tasks with error at plugin level'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0"
                },
                "test.nebula:b": {
                    "locked": "1.1.0"
                }'''.stripIndent())

        when:
        def result = runTasksAndFail('dependencies')

        then:
        result.output.contains("Legacy locks are not supported with core locking")
        result.output.contains("If you wish to migrate with the current locked dependencies")
        assertFailureOccursAtPluginLevel(result.output)
        legacyLockFile.exists()
    }

    def 'fail if legacy lock is present with core lock when running locking task with error at task level'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        when:
        def result = runTasksAndFail('generateLock')

        then:
        result.output.contains("generateLock is not supported with core locking")
        result.output.contains("migration with `./gradlew ${DependencyLockPlugin.MIGRATE_TO_CORE_LOCK_TASK_NAME}`")
        result.output.contains("> Task :generateLock FAILED")
        assertNoErrorsOnAParticularBuildLine(result.output)
    }

    def 'fail if legacy lock is present with core lock when running updateLock with error at task level'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        when:
        def result = runTasksAndFail('updateLock')

        then:
        result.output.contains("updateLock is not supported with core locking")
        result.output.contains("Please use `./gradlew dependencies --update-locks group1:module1,group2:module2`")
        result.output.contains("> Task :updateLock FAILED")
        assertNoErrorsOnAParticularBuildLine(result.output)
    }

    private static void assertNoErrorsOnAParticularBuildLine(String text) {
        assert !text.contains("* Where:")
    }

    private static void assertFailureOccursAtPluginLevel(String text) {
        assert text.contains("Failed to apply plugin [id 'nebula.dependency-lock']") ||
                text.contains("Failed to apply plugin 'nebula.dependency-lock'")
    }

}
