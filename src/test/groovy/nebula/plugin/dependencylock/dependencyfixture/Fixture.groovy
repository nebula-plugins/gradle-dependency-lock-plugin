/*
 * Copyright 2014-2019 Netflix, Inc.
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
package nebula.plugin.dependencylock.dependencyfixture

import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator

import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicBoolean

class Fixture {
    /** Project root (directory containing build/), derived from this class's location. */
    private static File getProjectRoot() {
        def source = Fixture.class.protectionDomain.codeSource.location
        def current = new File(source.toURI())
        if (current.file) {
            current = current.parentFile
        }
        while (current != null) {
            def buildDir = new File(current, 'build')
            if (buildDir.exists() && buildDir.directory) {
                return current
            }
            current = current.parentFile
        }
        throw new IllegalStateException("Could not find project root (directory containing build/) from ${source}")
    }

    private static final String TESTREPOGEN_DIR = new File(getProjectRoot(), 'build/testrepogen').absolutePath
    static final String repo = new File(TESTREPOGEN_DIR, 'mavenrepo').absolutePath

    private static final AtomicBoolean created = new AtomicBoolean(false)
    private static final File LOCK_FILE = new File(TESTREPOGEN_DIR, '.fixture.lock')
    /** Marker so we can detect if another spec overwrote the shared repo (e.g. old PathAwareDependencyDiffSpec). */
    private static final String FIXTURE_MARKER = 'nebula-dependency-lock-fixture-v1'

    static createFixture() {
        def myGraph = [
          'test.example:foo:1.0.0',
          'test.example:foo:1.0.1',
          'test.example:foo:2.0.0',
          'test.example:foo:2.0.1',
          'test.example:bar:1.0.0 -> test.example:foo:1.0.0',
          'test.example:bar:1.1.0 -> test.example:foo:1.+',
          'test.example:baz:1.0.0',
          'test.example:baz:1.1.0',
          'test.example:baz:2.0.0',
          'test.example:qux:1.0.0 -> test.example:foo:1.0.1',
          'test.example:qux:2.0.0 -> test.example:foo:2.0.1',
          'test.example:foobaz:1.0.0 -> test.example:foo:1.0.1|test.example:baz:1.0.0',
          'test.example:transitive:1.0.0 -> test.example:bar:1.0.0|test.example:foobaz:1.0.0',
          'circular:a:1.0.0 -> circular:b:1.0.0',
          'circular:b:1.0.0 -> circular:a:1.0.0',
          'circular:oneleveldeep:1.0.0 -> circular:a:1.0.0'
        ]

        def generator = new GradleDependencyGenerator(new DependencyGraph(myGraph), TESTREPOGEN_DIR)
        generator.generateTestMavenRepo()
        new File(TESTREPOGEN_DIR, '.fixture-marker').text = FIXTURE_MARKER
    }

    /**
     * Creates the shared Maven repo once. Uses an absolute path and a file lock so that
     * when multiple test workers run in parallel, only one creates the repo and others wait.
     */
    static createFixtureIfNotCreated() {
        if (created.getAndSet(true)) {
            return
        }
        LOCK_FILE.parentFile.mkdirs()
        def raf = new RandomAccessFile(LOCK_FILE, 'rw')
        FileLock lock = null
        try {
            lock = raf.channel.lock()
            def markerFile = new File(TESTREPOGEN_DIR, '.fixture-marker')
            if (!markerFile.exists() || markerFile.text != FIXTURE_MARKER) {
                def mavenrepoDir = new File(TESTREPOGEN_DIR, 'mavenrepo')
                if (mavenrepoDir.exists()) {
                    mavenrepoDir.deleteDir()
                }
                createFixture()
            }
        } finally {
            if (lock != null) {
                try {
                    lock.release()
                } catch (IOException ignored) {}
            }
            try {
                raf.close()
            } catch (IOException ignored) {}
        }
    }
}
