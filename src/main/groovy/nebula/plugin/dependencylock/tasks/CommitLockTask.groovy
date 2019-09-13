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
import nebula.plugin.scm.providers.ScmFactory
import nebula.plugin.scm.providers.ScmProvider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException

class CommitLockTask extends AbstractLockTask {
    @Internal
    String description = 'Commit the lock files if an gradle-scm-plugin implementation is applied'

    @Internal
    ScmFactory scmFactory

    @Input
    String branch

    @Input
    String commitMessage

    @Input
    List<String> patternsToCommit

    @Input
    @Optional
    Boolean shouldCreateTag = false

    @Input
    @Optional
    String tag

    @Input
    @Optional
    Integer remoteRetries = 3

    @TaskAction
    void commit() {
        ScmProvider provider = getScmFactory().create()

        commitLocks(provider)

        if (getShouldCreateTag()) {
            provider.tag(getTag(), "Creating ${getTag()}")
        }
    }

    private commitLocks(ScmProvider provider) {
        int commitTries = 0
        boolean successfulCommit = false

        while ((commitTries < getRemoteRetries()) && !successfulCommit) {
            successfulCommit = provider.commit(getCommitMessage(), getPatternsToCommit())
            logger.info("Commit returns: ${successfulCommit}")
            if (!successfulCommit) {
                commitTries++
                if ((commitTries < getRemoteRetries()) && provider.updateFromRepository()) {
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
}
