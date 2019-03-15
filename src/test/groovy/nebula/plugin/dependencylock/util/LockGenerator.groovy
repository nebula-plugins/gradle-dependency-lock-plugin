/*
 * Copyright 2016-2019 Netflix, Inc.
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
package nebula.plugin.dependencylock.util

class LockGenerator {
    static final Collection<String> DEFAULT_CONFIG_NAMES = ['compile', 'compileClasspath', 'default', 'runtime', 'runtimeClasspath', 'testCompile', 'testCompileClasspath', 'testRuntime', 'testRuntimeClasspath']

    /**
     * Helper to copy the exact same lock block multiple times into different configurations
     * @param deps the String of dependencies, indentation should be what you want if that was the only thing going in the file
     * @param configs configurations to duplicate into, defaults to the 4 standard java DEFAULT_CONFIG_NAMES
     * @return the String to put into the file
     */
    static String duplicateIntoConfigs(String deps, Collection<String> configs = DEFAULT_CONFIG_NAMES) {
        def indentedDeps = deps.readLines().collect { "|        $it" }.join('\n')

        def pieces = configs.collect { config ->
            """\
            |    "${config}": {
            ${indentedDeps}
            |    }"""
        }

        def builder = new StringBuilder()
        builder.append('|{\n')
        builder.append(pieces.join(',\n'))
        builder.append('\n|}')

        return builder.toString().stripMargin()
    }

    /**
     * Helper to copy the same lock block multiple times into different configurations, that takes two sets of dependencies
     * and two sets of configurations
     * @param firstDeps the String of dependencies, indentation should be what you want if that was the only thing going in the file
     * @param firstConfigs configurations to duplicate into
     * @param secondDeps the String of the second set of dependencies, indentation should be what you want if that was the only thing going in the file
     * @param secondConfigs configurations to duplicate the second set of dependencies into
     * @return the String to put into the file
     */
    static String duplicateIntoConfigs(String firstDeps, Collection<String> firstConfigs, String secondDeps, Collection<String> secondConfigs) {
        def indentedFirstDeps = firstDeps.readLines().collect { "|        $it" }.join('\n')

        def firstPieces = firstConfigs.collect { config ->
            """\
            |    "${config}": {
            ${indentedFirstDeps}
            |    }"""
        }

        def indentedSecondDeps = secondDeps.readLines().collect { "|        $it" }.join('\n')

        def secondPieces = secondConfigs.collect { config ->
            """\
            |    "${config}": {
            ${indentedSecondDeps}
            |    }"""
        }

        def builder = new StringBuilder()
        builder.append('|{\n')
        builder.append(firstPieces.join(',\n'))
        builder.append(',\n')
        builder.append(secondPieces.join(',\n'))
        builder.append('\n|}')

        return builder.toString().stripMargin()
    }
}
