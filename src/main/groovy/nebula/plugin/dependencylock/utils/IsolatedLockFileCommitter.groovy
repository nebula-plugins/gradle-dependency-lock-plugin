/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock.utils

import groovy.transform.CompileStatic
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec

import java.nio.charset.StandardCharsets

/** Task-time Git operations with immutable options and no Gradle model references. */
@CompileStatic
class IsolatedLockFileCommitter {
    private final ExecOperations execOperations
    private final String rootDirectory

    IsolatedLockFileCommitter(ExecOperations execOperations, String rootDirectory) {
        this.execOperations = execOperations
        this.rootDirectory = rootDirectory
    }

    void commit(String message, List<String> patterns) {
        Set<String> paths = new LinkedHashSet<>()
        for (String pattern : patterns) {
            // Git applies its ignore rules and matches the basename at every depth, including root.
            def listed = execute(['ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', ":(glob)**/$pattern".toString()])
            paths.addAll(listed.output.split('\u0000').findAll { !it.empty })
        }
        for (List<String> batch : paths.toList().collate(100)) {
            execute(['add', '--all', '--'] + batch)
        }
        def staged = execute(['diff', '--cached', '--quiet'], [0, 1])
        if (staged.exitCode == 1) execute(['commit', '-m', message])
    }

    void createTag(String tag) {
        execute(['tag', '-a', tag, '-m', tag])
    }

    private GitResult execute(List<String> arguments, List<Integer> acceptedExitCodes = [0]) {
        def output = new ByteArrayOutputStream()
        def error = new ByteArrayOutputStream()
        List<String> command = ['git', "--git-dir=$rootDirectory/.git".toString(), "--work-tree=$rootDirectory".toString()]
        command.addAll(arguments)
        def result = execOperations.exec { ExecSpec spec ->
            spec.setWorkingDir(rootDirectory)
            spec.setCommandLine(command)
            spec.ignoreExitValue = true
            spec.standardOutput = output
            spec.errorOutput = error
        }
        if (!acceptedExitCodes.contains(result.exitValue)) {
            throw new GradleException("Git ${arguments.first()} failed (${result.exitValue}): " + error.toString(StandardCharsets.UTF_8))
        }
        new GitResult(result.exitValue, output.toString(StandardCharsets.UTF_8))
    }

    private static class GitResult {
        final int exitCode
        final String output

        GitResult(int exitCode, String output) {
            this.exitCode = exitCode
            this.output = output
        }
    }
}
