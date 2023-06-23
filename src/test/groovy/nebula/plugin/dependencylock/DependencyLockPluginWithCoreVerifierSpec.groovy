package nebula.plugin.dependencylock

import nebula.plugin.dependencyverifier.DependencyResolutionVerifierKt
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import org.gradle.util.GradleVersion
import spock.lang.IgnoreIf
import spock.lang.Subject
import spock.lang.Unroll

@Subject(DependencyResolutionVerifierKt)
class DependencyLockPluginWithCoreVerifierSpec extends AbstractDependencyLockPluginSpec {
    private static final String BASELINE_LOCKFILE_CONTENTS = """# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
test.nebula:a:1.1.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
test.nebula:b:1.1.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
empty=annotationProcessor,testAnnotationProcessor
""".stripIndent()

    private static final String MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES = """ \
        dependencies {
            implementation 'test.nebula:d:1.0.0'
            testImplementation 'test.nebula:c' // missing from a BOM, for example
            testRuntimeOnly 'test.nebula:e' // missing from a BOM, for example
            testRuntimeOnly 'has.missing.transitive:a:1.0.0' // transitive dep not found
            testRuntimeOnly 'not.available:a:1.0.0' // has version number, but not found
        }
        """.stripIndent()

    def setup() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:e:1.0.0')
                .addModule('test.nebula:f:1.0.0')
                .addModule(new ModuleBuilder('has.missing.transitive:a:1.0.0').addDependency('transitive.not.available:a:1.0.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def transitiveNotAvailableDep = new File(mavenrepo.getMavenRepoDir(), "transitive/not/available/a")
        transitiveNotAvailableDep.deleteDir() // to create a missing transitive dependency
    }

    @Unroll
    def 'fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'warn when dependency is unresolvable upon update via #lockArg and project requests warnings only via property'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasks(*tasks(lockArg), '-PdependencyResolutionVerifier.unresolvedDependenciesFailTheBuild=false')

        then:
        results.output.contains("Failed to resolve the following dependencies")
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'fail when dependency is missing from the lock state'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << """ \
        dependencies {
            implementation 'test.nebula:d:1.0.0'
        }
        """.stripIndent()

        def results = runTasksAndFail('dependencies')

        then:
        results.output.contains('FAILURE')
        results.output.contains('Resolved dependencies were missing from the lock state')
        results.output.contains('Resolved \'test.nebula:d:1.0.0\' which is not part of the dependency lock state')
    }

    @Unroll
    def 'multiproject: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createMultiProjectBaseline()

        when:
        new File(projectDir, 'sub1/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES
        new File(projectDir, 'sub2/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg, true))

        then:
        results.output.contains('FAILURE')

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'multiproject: fail when dependency is missing from the lock state'() {
        given:
        createMultiProjectBaseline()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:e:1.0.0')
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        when:
        new File(projectDir, 'sub1/build.gradle') << """ \
        dependencies {
            implementation 'test.nebula:d:1.0.0'
        }
        """.stripIndent()
        new File(projectDir, 'sub2/build.gradle') << """ \
        dependencies {
            implementation 'test.nebula:e:1.0.0'
        }
        """.stripIndent()

        def results = runTasksAndFail('dependenciesForAll')

        then:
        results.output.contains('FAILURE')
        results.output.contains('Resolved dependencies were missing from the lock state')
        results.output.contains('Resolved \'test.nebula:d:1.0.0\' which is not part of the dependency lock state')
    }

    @Unroll
    def 'provide useful error message when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('test.nebula:c FAILED')
        results.output.contains('test.nebula:e FAILED')
        results.output.contains('not.available:a:1.0.0 FAILED')
        results.output.contains('transitive.not.available:a:1.0.0 FAILED')
        results.output.contains('FAILURE')

        results.output.contains("> Failed to resolve the following dependencies")
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'multiproject: provide useful error message when dependency is unresolvable upon update via #lockArg'() {
        given:
        createMultiProjectBaseline()

        when:
        new File(projectDir, 'sub1/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES
        new File(projectDir, 'sub2/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg, true))

        then:
        results.output.contains('test.nebula:c FAILED')
        results.output.contains('test.nebula:e FAILED')
        results.output.contains('not.available:a:1.0.0 FAILED')
        results.output.contains('transitive.not.available:a:1.0.0 FAILED')
        results.output.contains('FAILURE')

        results.output.contains("> Failed to resolve the following dependencies")
        results.output.contains(failedResolutionDependencies('sub1'))

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    //Gradle 7.0 will not update lock state if build failed so those tests are not necessary anymore after we move to Gradle 7.0
    @IgnoreIf({ GradleVersion.current().baseVersion >= GradleVersion.version("7.0")})
    @Unroll
    def 'update lockfiles for resolvable configurations only upon update via #lockArg'() {
        given:
        createSingleProjectBaseline()

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        runTasksAndFail(*tasks(lockArg))

        then:
        def secondRunLockfile = new File(projectDir, 'gradle.lockfile')
        secondRunLockfile.text == BASELINE_LOCKFILE_CONTENTS.replace(
                "empty=annotationProcessor,testAnnotationProcessor",
                "test.nebula:d:1.0.0=compileClasspath,runtimeClasspath\nempty=annotationProcessor,testAnnotationProcessor"
        )

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    //Gradle 7.0 will not update lock state if build failed so those tests are not necessary anymore after we move to Gradle 7.0
    @IgnoreIf({ GradleVersion.current().baseVersion >= GradleVersion.version("7.0")})
    @Unroll
    def 'multiproject: update lockfiles for resolvable configurations only upon update via #lockArg'() {
        given:
        createMultiProjectBaseline()

        when:
        new File(projectDir, 'sub1/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES
        new File(projectDir, 'sub2/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        runTasksAndFail(*tasks(lockArg, true))

        then:
        def secondRunLockfile = new File(projectDir, 'sub1/gradle.lockfile')
        secondRunLockfile.text == BASELINE_LOCKFILE_CONTENTS.replace(
                "empty=annotationProcessor,testAnnotationProcessor",
                "test.nebula:d:1.0.0=compileClasspath,runtimeClasspath\nempty=annotationProcessor,testAnnotationProcessor"
        )

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'multiproject: works for parallel builds with #lockArg'() {
        given:
        createMultiProjectBaseline()

        when:
        new File(projectDir, 'sub1/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES
        new File(projectDir, 'sub2/build.gradle') << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg, true), '--parallel')

        then:
        results.output.contains('FAILURE: Build completed with 2 failures.')

        results.output.findAll("> Failed to resolve the following dependencies:\n" +
                "    1. Failed to resolve 'not.available:a:1.0.0' for project 'sub1'").size() == 1

        results.output.findAll("> Failed to resolve the following dependencies:\n" +
                "    1. Failed to resolve 'not.available:a:1.0.0' for project 'sub2'").size() == 1
        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'multiproject: works for parallel builds with #lockArg and dependencies listed in parent build file'() {
        given:
        createMultiProjectBaseline(false)

        when:
        buildFile << """
            subprojects {
                $MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES
            }
            """.stripIndent()

        def results = runTasksAndFail(*tasks(lockArg, true), '--parallel')

        then:
        results.output.contains('FAILURE: Build completed with 2 failures.')

        results.output.findAll("> Failed to resolve the following dependencies:\n" +
                "    1. Failed to resolve 'not.available:a:1.0.0' for project 'sub1'").size() == 1

        results.output.findAll("> Failed to resolve the following dependencies:\n" +
                "    1. Failed to resolve 'not.available:a:1.0.0' for project 'sub2'").size() == 1

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'scala: fail when dependency is unresolvable upon update via #lockArg (defining dependencies on base configuration "#conf")'() {
        // the configurations `incrementalScalaAnalysisFor_x_` are resolvable only from a scala context, and extend from `implementation`
        // https://github.com/gradle/gradle/blob/master/subprojects/scala/src/main/java/org/gradle/api/plugins/scala/ScalaBasePlugin.java#L143
        given:
        createSingleProjectBaseline('scala', conf)

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('test.nebula:c FAILED')
        results.output.contains('test.nebula:e FAILED')
        results.output.contains('not.available:a:1.0.0 FAILED')
        results.output.contains('transitive.not.available:a:1.0.0 FAILED')
        results.output.contains('FAILURE')

        results.output.contains("> Failed to resolve the following dependencies")
        results.output.contains(failedResolutionDependencies())

        where:
        conf             | lockArg
        'implementation' | "write-locks"
        'implementation' | "update-locks"
    }

    @Unroll
    def 'groovy: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline('groovy')

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'java-library: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline('java-library')

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'org.jetbrains.kotlin.jvm: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        createSingleProjectBaseline('org.jetbrains.kotlin.jvm')

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    @Unroll
    def 'com.netflix.nebula.clojure: fail when dependency is unresolvable upon update via #lockArg'() {
        given:
        //TODO: clojure plugin needs to be refactored to stop using project.convention
        System.setProperty('ignoreDeprecations', 'true')
        createSingleProjectBaseline('com.netflix.nebula.clojure')

        when:
        buildFile << MIX_OF_RESOLVABLE_AND_UNRESOLVABLE_DEPENDENCIES

        def results = runTasksAndFail(*tasks(lockArg))

        then:
        results.output.contains('FAILURE')
        results.output.contains(failedResolutionDependencies())

        where:
        lockArg << ['write-locks', 'update-locks']
    }

    def createSingleProjectBaseline(String languagePlugin = 'java', String conf = '') {
        if (languagePlugin == 'org.jetbrains.kotlin.jvm') {
            createKotlinSingleProjectBaseline()
            return
        }
        if (languagePlugin == 'com.netflix.nebula.clojure') {
            createClojureSingleProjectBaseline()
            return
        }
        if (languagePlugin == 'scala') {
            createScalaSingleProjectBaseline(conf)
            return
        }
        if (languagePlugin != 'java') {
            updateSingleProjectFor(languagePlugin)
        }
        writeHelloWorld()
        writeUnitTest()

        runTasks('dependencies', '--write-locks') // baseline dependency locks

        def lockFile = coreLockContent(new File(projectDir, 'gradle.lockfile'))
        def actualLocks = lockedConfigurations(lockFile)

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def firstRunLockfile = new File(projectDir, 'gradle.lockfile')
        assert firstRunLockfile.text == BASELINE_LOCKFILE_CONTENTS
    }

    def createMultiProjectBaseline(boolean usesOwnBuildFile = true) {
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

        if (usesOwnBuildFile) {
            def subProjectBuildFileContent = """
                plugins {
                    id 'com.netflix.nebula.dependency-lock'
                    id 'java'
                }
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                }
                dependencies {
                    implementation 'test.nebula:a:1.+'
                    implementation 'test.nebula:b:1.+'
                }
                """.stripIndent()

            addSubproject("sub1", subProjectBuildFileContent)
            addSubproject("sub2", subProjectBuildFileContent)
        } else {
            definePluginOutsideOfPluginBlock = true

            buildFile << """
                subprojects {
                    apply plugin: 'com.netflix.nebula.dependency-lock'
                    apply plugin: 'java'
                    repositories {
                        ${mavenrepo.mavenRepositoryBlock}
                    }
                    dependencies {
                        implementation 'test.nebula:a:1.+'
                        implementation 'test.nebula:b:1.+'
                    }
                }
                """.stripIndent()
            addSubproject("sub1")
            addSubproject("sub2")
        }

        writeHelloWorld(new File(projectDir, 'sub1'))
        writeHelloWorld(new File(projectDir, 'sub2'))
        writeUnitTest(new File(projectDir, 'sub1'))
        writeUnitTest(new File(projectDir, 'sub2'))

        runTasks('dependenciesForAll', '--write-locks') // baseline dependency locks

        def sub1LockFile = coreLockContent(new File(projectDir, 'sub1/gradle.lockfile'))
        def sub1ActualLocks = lockedConfigurations(sub1LockFile)

        expectedLocks.each {
            assert sub1ActualLocks.contains(it): "There is a missing lockfile: $it"
        }
        sub1ActualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def sub2LockFile = coreLockContent(new File(projectDir, 'sub2/gradle.lockfile'))
        def sub2ActualLocks = lockedConfigurations(sub2LockFile)

        expectedLocks.each {
            assert sub2ActualLocks.contains(it): "There is a missing lockfile: $it"
        }
        sub2ActualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        def firstRunCompileLockfile = new File(projectDir, 'sub1/gradle.lockfile')
        assert firstRunCompileLockfile.text == BASELINE_LOCKFILE_CONTENTS
    }

    def createScalaSingleProjectBaseline(String conf) {
        setupScalaProject(conf)

        buildFile << """
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
            """.stripIndent()

        runTasks('dependencies', '--write-locks') // baseline dependency locks

        def lockFile = coreLockContent(new File(projectDir, 'gradle.lockfile'))
        def actualLocks = lockedConfigurations(lockFile)

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        lockFile.get("test.nebula:a:1.1.0") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("test.nebula:b:1.1.0") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.scala-lang:scala-library:2.12.7") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("junit:junit:4.12") == "testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.hamcrest:hamcrest-core:1.3") == "testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.scala-lang.modules:scala-xml_2.12:1.0.6") == "testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.scala-lang:scala-reflect:2.12.4") == "testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.scalactic:scalactic_2.12:3.0.5") == "testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.scalatest:scalatest_2.12:3.0.5") == "testCompileClasspath,testRuntimeClasspath"
    }

    def createKotlinSingleProjectBaseline() {
        buildFile.delete()
        buildFile.createNewFile()

        buildFile << """\
                buildscript {
                    repositories { maven { url "https://plugins.gradle.org/m2/" } }
                    dependencies {
                        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.0"
                    }
                }
                plugins {
                    id 'com.netflix.nebula.dependency-lock'
                }
                apply plugin: "org.jetbrains.kotlin.jvm"
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                    mavenCentral()
                }
                dependencies {
                    implementation 'test.nebula:a:1.+'
                    implementation 'test.nebula:b:1.+'
                }
                """.stripIndent()

        def results = runTasks('dependencies', '--write-locks') // baseline dependency locks

        def kotlinVersion
        def jetbrainsAnnotationsVersion
        try {
            kotlinVersion = results.output.findAll("org.jetbrains.kotlin:kotlin-stdlib-common.*").first().split(':')[2]
            jetbrainsAnnotationsVersion = results.output.findAll("org.jetbrains:annotations.*").first().split(':')[2]
        } catch (Exception e) {
            throw new Exception("Could not find needed version(s) for this test", e)
        }

        def lockFile = coreLockContent(new File(projectDir, 'gradle.lockfile'))
        def actualLocks = lockedConfigurations(lockFile)

        def kotlinExpectedLocks = getKotlinExpectedLocks()

        kotlinExpectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert kotlinExpectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        lockFile.get("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("org.jetbrains:annotations:$jetbrainsAnnotationsVersion") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("test.nebula:a:1.1.0") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("test.nebula:b:1.1.0") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
    }

    def createClojureSingleProjectBaseline() {
        buildFile.delete()
        buildFile.createNewFile()

        buildFile << """\
                buildscript {
                    repositories { 
                    maven { url "https://plugins.gradle.org/m2/" }
                    maven { url 'https://clojars.org/repo' }
                    }
                    dependencies {
                        classpath "com.netflix.nebula:nebula-clojure-plugin:13.0.1"
                    }
                }
                plugins {
                    id 'com.netflix.nebula.dependency-lock'
                }
                apply plugin: 'com.netflix.nebula.clojure'
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                    mavenCentral()
                    maven { url 'https://clojars.org/repo' }
                }
                dependencies {
                    implementation 'org.clojure:clojure:1.8.0'
                    implementation 'test.nebula:a:1.+'
                    implementation 'test.nebula:b:1.+'
                }
                """.stripIndent()

        runTasks('dependencies', '--write-locks') // baseline dependency locks

        def lockFile = coreLockContent(new File(projectDir, 'gradle.lockfile'))
        def actualLocks = lockedConfigurations(lockFile)

        expectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }
        actualLocks.each {
            assert expectedLocks.contains(it): "There is an extra lockfile: $it"
        }

        lockFile.get("org.clojure:clojure:1.8.0") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("test.nebula:a:1.1.0") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
        lockFile.get("test.nebula:b:1.1.0") == "compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath"
    }

    def updateSingleProjectFor(String languagePlugin) {
        buildFile.delete()
        buildFile.createNewFile()
        if (languagePlugin == 'groovy') {
            buildFile << """\
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'groovy'
            }
            
        """.stripIndent()
        } else if (languagePlugin == 'java-library') {
            buildFile << """\
                plugins {
                    id 'com.netflix.nebula.dependency-lock'
                    id 'java-library'
                }
                """.stripIndent()

            writeHelloWorld()
            writeUnitTest()
        }

        buildFile << """\
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()
    }

    def tasks(String lockArg, Boolean isMultiProject = false) {
        def tasks = []
        isMultiProject ? tasks.add('dependenciesForAll') : tasks.add('dependencies')

        tasks.addAll('--rerun-tasks', '--warning-mode', 'all')

        lockArg == 'write-locks'
                ? tasks.add("--$lockArg")
                : tasks.addAll("--$lockArg".toString(), 'test.nebula:d,test.nebula:c,test.nebula:e,has.missing.transitive:a,not.available:a')

        return tasks
    }

    private String failedResolutionDependencies(String subprojectName = '') {
        def project = subprojectName != '' ? subprojectName : projectName
        return """
  1. Failed to resolve 'not.available:a:1.0.0' for project '$project'
  2. Failed to resolve 'test.nebula:c' for project '$project'
  3. Failed to resolve 'test.nebula:e' for project '$project'
  4. Failed to resolve 'transitive.not.available:a:1.0.0' for project '$project'"""
    }
}
