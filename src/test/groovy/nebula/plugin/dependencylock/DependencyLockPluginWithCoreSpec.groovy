package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import spock.lang.Unroll

class DependencyLockPluginWithCoreSpec extends IntegrationTestKitSpec {
    def expectedLocks = [
            'annotationProcessor.lockfile',
            'compile.lockfile',
            'compileClasspath.lockfile',
            'compileOnly.lockfile',
            'runtime.lockfile',
            'runtimeClasspath.lockfile',
            'testAnnotationProcessor.lockfile',
            'testCompile.lockfile',
            'testCompileClasspath.lockfile',
            'testCompileOnly.lockfile',
            'testRuntime.lockfile',
            'testRuntimeClasspath.lockfile'
    ] as String[]
    def mavenrepo
    def projectName

    def setup() {
        keepFiles = true
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.coreLockingSupport=true"

        projectName = getProjectDir().getName().replaceAll(/_\d+/, '')
        settingsFile << """\
            rootProject.name = '${projectName}'
        """.stripIndent()

        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:a:1.1.0')
                .addModule('test.nebula:b:1.0.0')
                .addModule('test.nebula:b:1.1.0')
                .addModule('test.nebula:d:1.0.0')
                .addModule('test.nebula:d:1.1.0')
                .addModule(new ModuleBuilder('test.nebula:c:1.0.0').addDependency('test.nebula:d:1.0.0').build())
                .addModule(new ModuleBuilder('test.nebula:c:1.1.0').addDependency('test.nebula:d:1.1.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
        """.stripIndent()

        debug = true // if you want to debug with IntegrationTestKit, this is needed
    }

    def 'generate core lock file'() {
        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
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
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
                compile 'test.nebula:d:1.+'
            }
        """.stripIndent()
        def updateLocksResult = runTasks('dependencies', '--update-locks', 'test.nebula:d')

        then:
        lockFile.text.contains('test.nebula:d:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    def 'run the build with core lock file when newer dependency versions exist'() {
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
                compile 'test.nebula:a:1.+'
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

        def dependencyInsightCompileClasspath = runTasks('dependencyInsight', '--dependency', 'test.nebula:a', '--configuration', 'compileClasspath')
        def dependencyInsightCompile = runTasks('dependencyInsight', '--dependency', 'test.nebula:a', '--configuration', 'compile')

        then:
        // different configurations should use the same version before updating locks
        dependencyInsightCompileClasspath.output.contains('test.nebula:a:1.1.0')
        dependencyInsightCompile.output.contains('test.nebula:a:1.1.0')

        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        !result.output.contains('FAILED')

        def lockfileDir = new File(projectDir, 'gradle/dependency-locks/')
        assert lockfileDir.listFiles().size() > 0
        lockfileDir.listFiles().each { lockFile ->
            if (lockFile.text.contains('test.nebula:a')) {
                assert lockFile.text.contains('test.nebula:a:1.2.0')
            }
        }

        def actualLocks = lockfileDir.list().toList()
        def updatedLocks = expectedLocks + 'shadow.lockfile'
        updatedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert updatedLocks.contains(it)
        }
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
                compile 'test.nebula:a:1.+'
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
        results.output.contains('test.nebula:a:1.+ -> 1.1.0')
        results.output.contains('test.nebula:a:{strictly 1.0.0} -> 1.0.0 ')

        results.output.contains('test.nebula:b:1.+ -> 1.1.0')
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks', '-PlockAllConfigurations=true')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        assert actualLocks.size() > expectedLocks.size()
        def allExpectedLocks = ["compile.lockfile", "archives.lockfile", "testCompileClasspath.lockfile",
                                "compileOnly.lockfile", "annotationProcessor.lockfile", "runtime.lockfile",
                                "compileClasspath.lockfile", "jacocoAnt.lockfile", "testCompile.lockfile",
                                "default.lockfile", "testAnnotationProcessor.lockfile", "testRuntime.lockfile",
                                "jacocoAgent.lockfile", "testRuntimeClasspath.lockfile", "testCompileOnly.lockfile",
                                "runtimeClasspath.lockfile"]
        allExpectedLocks.each {
            assert actualLocks.contains(it)
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
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
        def result = runTasks('dependenciesForAll', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        expectedLocks.each {
            assert sub1ActualLocks.contains(it)
        }
        sub1ActualLocks.each {
            assert expectedLocks.contains(it)
        }
        def sub1LockFile = new File(projectDir, "sub1/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        sub1LockFile.text.contains('test.nebula:a:1.1.0')
        sub1LockFile.text.contains('test.nebula:b:1.1.0')

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()
        expectedLocks.each {
            assert sub2ActualLocks.contains(it)
        }
        sub2ActualLocks.each {
            assert expectedLocks.contains(it)
        }
        def sub2LockFile = new File(projectDir, "sub2/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        sub2LockFile.text.contains('test.nebula:a:1.1.0')
        sub2LockFile.text.contains('test.nebula:c:1.1.0')
        sub2LockFile.text.contains('test.nebula:d:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')

        where:
        configuration    | lockFileToVerify
        'compile'        | 'compileClasspath'
        'implementation' | 'compileClasspath'
    }

    @Unroll
    def 'generate core lock file with kotlin plugin - for configuration #configuration'() {
        given:
        System.setProperty("ignoreDeprecations", "true")
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'nebula.kotlin' version '1.3.21'
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
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
        }

        def lockFile = new File(projectDir, "/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
        System.setProperty("ignoreDeprecations", "false")

        where:
        configuration    | lockFileToVerify
        'compile'        | 'compileClasspath'
        'implementation' | 'compileClasspath'
    }

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
                id 'nebula.kotlin' version '1.3.21'
            }
            dependencies {
                $configuration 'test.nebula:a:1.+'
                $configuration 'test.nebula:b:1.+'
            }
        """.stripIndent())

        when:
        def result = runTasks('dependenciesForAll', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
        }

        def lockFile = new File(projectDir, "sub1/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
        System.setProperty("ignoreDeprecations", "false")

        where:
        configuration    | lockFileToVerify
        'compile'        | 'compileClasspath'
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
                id "nebula.clojure" version "8.1.4"
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
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        expectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert expectedLocks.contains(it)
        }

        def lockFile = new File(projectDir, "/gradle/dependency-locks/${lockFileToVerify}.lockfile")
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')

        where:
        configuration    | lockFileToVerify
        'compile'        | 'compileClasspath'
        'implementation' | 'compileClasspath'
    }

    @Unroll
    def 'ordering language plugin and locking plugin should not matter - #languagePlugin #notes'() {
        given:
        def additionalDependencies = ''
        if (languagePlugin == 'scala') {
            additionalDependencies = """
                compile 'org.scala-lang:scala-library:2.12.7'
                testCompile 'junit:junit:4.12'
                testCompile 'org.scalatest:scalatest_2.12:3.0.5'
                testRuntimeOnly 'org.scala-lang.modules:scala-xml_2.12:1.1.1'
                """.stripIndent()
        } else if (languagePlugin == "nebula.clojure") {
            additionalDependencies = """
                compile 'org.clojure:clojure:1.8.0'
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
                    classpath "com.netflix.nebula:nebula-clojure-plugin:8.1.4"
                    classpath "com.netflix.nebula:nebula-kotlin-plugin:1.3.40"
                }
            }
            $plugins
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'$additionalDependencies
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

        def updatedLocks = languagePlugin != 'scala'
                ? expectedLocks
                : expectedLocks + ['compile.lockfile', 'testCompile.lockfile']
        updatedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert updatedLocks.contains(it)
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

        where:
        languagePlugin   | languagePluginFirst | notes
        'groovy'         | true                | 'applied first'
        'java'           | true                | 'applied first'
        'java-library'   | true                | 'applied first'
        'nebula.clojure' | true                | 'applied first'
        'nebula.kotlin'  | true                | 'applied first'
        'scala'          | true                | 'applied first'

        'groovy'         | false               | 'applied last'
        'java'           | false               | 'applied last'
        'java-library'   | false               | 'applied last'
        'nebula.clojure' | false               | 'applied last'
        'nebula.kotlin'  | false               | 'applied last'
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
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
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert updatedLocks.contains(it)
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
                classpath "gradle.plugin.com.github.spotbugs:spotbugs-gradle-plugin:2.0.0"
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
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
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert updatedLocks.contains(it)
        }

        def lockFile = new File(projectDir, '/gradle/dependency-locks/jacocoAgent.lockfile')
        lockFile.exists()

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
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
                classpath "com.netflix.nebula:nebula-project-plugin:6.0.0"
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
                testCompile 'junit:junit:4.12'
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

        def facetLockfiles = [
                "${facet}AnnotationProcessor.lockfile".toString(),
                "${facet}Compile.lockfile".toString(),
                "${facet}CompileClasspath.lockfile".toString(),
                "${facet}CompileOnly.lockfile".toString(),
                "${facet}Runtime.lockfile".toString(),
                "${facet}RuntimeClasspath.lockfile".toString()
        ]
        def updatedExpectedLocks = expectedLocks + facetLockfiles
        updatedExpectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert updatedExpectedLocks.contains(it)
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

    @Unroll
    def 'scala projects defining dependencies on base configuration "#conf" #areLocked'() {
        // the configurations `incrementalScalaAnalysisFor_x_` are resolvable only from a scala context, and extend from `compile` and `implementation`
        // https://github.com/gradle/gradle/blob/master/subprojects/scala/src/main/java/org/gradle/api/plugins/scala/ScalaBasePlugin.java#L143
        // TODO: determine which of the scala configurations should be locked for project consistency
        given:
        setupScalaProject(conf)

        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        assert actualLocks.size() > 0
        def scalaRelatedLockfiles = [
                "compile.lockfile",
                "testCompile.lockfile"
        ]
        def updatedExpectedLocks = expectedLocks + scalaRelatedLockfiles
        updatedExpectedLocks.each {
            assert actualLocks.contains(it)
        }
        actualLocks.each {
            assert updatedExpectedLocks.contains(it)
        }
        def lockFile = new File(projectDir, "/gradle/dependency-locks/compile.lockfile")
        if (conf == "compile") {
            assert lockFile.text.contains('org.scala-lang:scala-library:')
        } else {
            assert !lockFile.text.contains('org.scala-lang:scala-library:')
        }

        result.output.contains("Cannot lock scala configurations based on the 'implementation' configuration.")

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')

        where:
        conf             | areLocked
        'compile'        | "are locked"
        'implementation' | "are not locked"
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
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:b": {
                    "locked": "1.1.0",
                    "requested": "1.+"
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

    private setupScalaProject(String conf) {
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """
            plugins {
                id 'scala'
                id 'nebula.dependency-lock'
            }
            repositories {
                jcenter()
            }
            dependencies {
                $conf 'org.scala-lang:scala-library:2.12.7'
            
                test${conf.capitalize()} 'junit:junit:4.12'
                test${conf.capitalize()} 'org.scalatest:scalatest_2.12:3.0.5'
            
                testRuntimeOnly 'org.scala-lang.modules:scala-xml_2.12:1.1.1'
            }
            """.stripIndent()

        def scalaFile = createFile("src/main/scala/Library.scala")
        scalaFile << """
            class Library {
              def someLibraryMethod(): Boolean = true
            }
            """.stripIndent()

        def scalaTest = createFile("src/test/scala/LibrarySuite.scala")
        scalaTest << """
            import org.scalatest.FunSuite
            import org.junit.runner.RunWith
            import org.scalatest.junit.JUnitRunner
            
            @RunWith(classOf[JUnitRunner])
            class LibrarySuite extends FunSuite {
              test("someLibraryMethod is always true") {
                def library = new Library()
                assert(library.someLibraryMethod)
              }
            }
            """.stripIndent()
    }

    private static void assertNoErrorsOnAParticularBuildLine(String text) {
        assert !text.contains("* Where:")
    }

    private static void assertFailureOccursAtPluginLevel(String text) {
        assert text.contains("Failed to apply plugin [id 'nebula.dependency-lock']")
    }

}
