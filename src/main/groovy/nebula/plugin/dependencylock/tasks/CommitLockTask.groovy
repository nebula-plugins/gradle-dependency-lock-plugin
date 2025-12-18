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
package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.exceptions.DependencyLockException
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

import javax.inject.Inject
import java.nio.charset.Charset

@DisableCachingByDefault
abstract class CommitLockTask extends AbstractLockTask {
    @Internal
    String description = 'Commit the lock files if an gradle-scm-plugin implementation is applied'

    @Input
    @Optional
    abstract Property<String> getCommitMessage()

    @Input
    @Optional
    abstract ListProperty<String> getPatternsToCommit()

    @Input
    @Optional
    abstract Property<Boolean> getShouldCreateTag()

    @Input
    @Optional
    abstract Property<String> getTag()

    @Input
    @Optional
    abstract Property<Integer> getRemoteRetries()

    @Internal
    abstract Property<String> getRootDirPath()

    private final ExecOperations execOperations

    @Inject
    CommitLockTask(ExecOperations execOperations) {
        this.execOperations = execOperations
    }

    @TaskAction
    void commit() {
        if(!commitMessage.isPresent() || !patternsToCommit.isPresent()) {
            //Not enough info to run this task
            return
        }
        commitLocks(commitMessage.get(), patternsToCommit.get())

        if (shouldCreateTag.isPresent() && tag.isPresent() && shouldCreateTag.get()) {
           createTag(tag.get())
        }
    }

    private commitLocks(String commitMessage, List<String> patternsToCommit) {
        int commitTries = 0
        boolean successfulCommit = false

        while ((commitTries < remoteRetries.get()) && !successfulCommit) {
            successfulCommit = gitCommit(commitMessage, patternsToCommit)
            logger.info("Commit returns: ${successfulCommit}")
            if (!successfulCommit) {
                commitTries++
                if ((commitTries < remoteRetries.get()) && pull()) {
                    logger.info('Grabbed latest changes from repository')
                    logger.info('Retrying commit')
                } else {
                    logger.error('SCM update failed')
                    throw new TaskExecutionException(this, new DependencyLockException('Cannot commit locks, cannot update from repository'))
                }
            }
        }

        if (!successfulCommit) {
            logger.error('SCM update failed')
            throw new TaskExecutionException(this, new DependencyLockException('Cannot commit locks'))
        }
    }

    private boolean gitCommit(String message, List<String> patternsToCommit) {
        try {
            patternsToCommit.each { String pattern ->
                try {
                    List<String> commandLineArgs = [
                            "find", rootDirPath.get(), "-type", "f", "-name", pattern, "-exec", "git",
                            "--git-dir=${rootDirPath.get()}/.git".toString(), "--work-tree=${rootDirPath.get()}".toString(),
                            "add", "{}", "\\;"]
                    execOperations.exec {
                        ignoreExitValue = true
                        it.setCommandLine(commandLineArgs)
                    }
                } catch (Exception e) {
                    logger.error("Could not add pattern ${pattern}", e)
                }
            }
            executeGitCommand('commit', '-m', message)
            return true
        } catch (Exception e) {
            logger.error('Could not commit locks', e)
           throw new RuntimeException('Could not commit locks', e)
        }
    }

    private void createTag(String tag) {
        try {
            executeGitCommand( "tag", "-a", tag)
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tag ${tag}", e)
        }
    }

    void pull() {
        executeGitCommand( "pull")
    }
    private String executeGitCommand(Object... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        ByteArrayOutputStream error = new ByteArrayOutputStream()
        List<String> commandLineArgs = ["git", "--git-dir=${rootDirPath.get()}/.git".toString(), "--work-tree=${rootDirPath.get()}".toString()]
        commandLineArgs.addAll(args)
        execOperations.exec {
            ignoreExitValue = true
            it.setCommandLine(commandLineArgs)
            it.standardOutput = output
            it.errorOutput = error
        }
        def errorMsg = new String(error.toByteArray(), Charset.defaultCharset())
        if(errorMsg) {
            throw new GradleException(errorMsg)
        }
        return new String(output.toByteArray(), Charset.defaultCharset())
    }

}
