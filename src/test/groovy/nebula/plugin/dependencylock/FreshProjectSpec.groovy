/*
 * Copyright 2026 Netflix, Inc.
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

import nebula.test.ProjectSpec
import org.gradle.testfixtures.ProjectBuilder

import java.util.UUID

/**
 * Base for specs that need an isolated project per test to avoid ProjectSpec's shared project
 * accumulating state and causing order-dependent failures.
 *
 * {@link #setup()} assigns a fresh project (UUID-based name) to {@code project} before each test,
 * so test bodies just use {@code project} and {@code projectDir}. For multiproject tests, call
 * {@link #useFreshProjectAsRoot(String)} so {@code project} is the root and {@code projectDir} matches.
 */
abstract class FreshProjectSpec extends ProjectSpec {

    def setup() {
        useFreshProject()
    }

    /** After setup(), matches the current (fresh) project's directory so tests can keep using {@code projectDir}. */
    @Override
    File getProjectDir() {
        project.projectDir
    }

    /**
     * Creates a new project in a unique subdirectory (name/dir use a generated UUID) and assigns
     * it to {@code project}. Called from {@link #setup()} so each test gets a fresh project.
     */
    protected void useFreshProject() {
        project = createFreshProject(UUID.randomUUID().toString())
    }

    /**
     * Replaces {@code project} with a fresh named root (e.g. for multiproject tests). Use so tests
     * can keep using {@code project} and {@code projectDir} instead of a local {@code root} variable.
     */
    protected void useFreshProjectAsRoot(String namePrefix) {
        project = createFreshProject(namePrefix)
    }

    /**
     * Creates a new project in a unique subdirectory to avoid test pollution.
     *
     * @param namePrefix project name and dir prefix (default 'project')
     * @return a new Project with an empty projectDir
     */
    protected org.gradle.api.Project createFreshProject(String namePrefix = 'project') {
        def dir = new File(projectDir, "fresh-${namePrefix}-${UUID.randomUUID()}")
        dir.mkdirs()
        ProjectBuilder.builder().withName(namePrefix).withProjectDir(dir).build()
    }
}
