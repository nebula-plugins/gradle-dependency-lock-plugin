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
package nebula.plugin.dependencylock.dependencyfixture

import java.util.concurrent.atomic.AtomicBoolean
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator

class Fixture {
    static AtomicBoolean created = new AtomicBoolean(false)
    static String repo = new File('build/testrepogen/mavenrepo').absolutePath

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
          'test.example:foobaz:1.0.0 -> test.example:foo:1.0.1|test.example:baz:1.0.0',
          'test.example:transitive:1.0.0 -> test.example:bar:1.0.0|test.example:foobaz:1.0.0',
          'circular:a:1.0.0 -> circular:b:1.0.0',
          'circular:b:1.0.0 -> circular:a:1.0.0',
          'circular:oneleveldeep:1.0.0 -> circular:a:1.0.0'
        ]

        def generator = new GradleDependencyGenerator(new DependencyGraph(myGraph))
        generator.generateTestMavenRepo()
    }

    static createFixtureIfNotCreated() {
        if (!created.getAndSet(true)) {
            createFixture()
        }
    }
}
