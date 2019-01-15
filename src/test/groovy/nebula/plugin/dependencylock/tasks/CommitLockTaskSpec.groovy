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

import nebula.plugin.scm.providers.ScmFactory
import nebula.plugin.scm.providers.ScmProvider
import nebula.test.ProjectSpec

import org.gradle.api.tasks.TaskExecutionException

class CommitLockTaskSpec extends ProjectSpec {
    String branchName = 'master'
    String message = 'test commit'
    String tagName = 'test tag'
    List<String> patterns = ['dependencies.lock']

    CommitLockTask task
    ScmFactory factory
    ScmProvider provider

    def 'commit for non multi project'() {
        nonMultiProjectSetup()
    
        when:
        task.commit()

        then:
        1 * factory.create() >> provider
        1 * provider.commit(message, patterns) >> true
        0 * provider.tag(_,_)
    }

    def 'tag non multi project'() {
        nonMultiProjectSetup()
        task.with {
            shouldCreateTag = true
            tag = tagName
        }

        when:
        task.commit()

        then:
        1 * factory.create() >> provider
        1 * provider.commit(message, patterns) >> true
        1 * provider.tag(tagName, _) >> true
    }

    def 'commit retries'() {
        nonMultiProjectSetup()
    
        when:
        task.commit()

        then:
        1 * factory.create() >> provider
        3 * provider.commit(message, patterns) >>> [false, false, true]
        2 * provider.updateFromRepository() >> true
    }

    def 'commit fails after retries'() {
        nonMultiProjectSetup()
    
        when:
        task.commit()

        then:
        1 * factory.create() >> provider
        3 * provider.commit(message, patterns) >> false
        2 * provider.updateFromRepository() >> true
        thrown TaskExecutionException
    }

    def 'commit fails due to being unable to updateFromRepository'() {
        nonMultiProjectSetup()
    
        when:
        task.commit()

        then:
        1 * factory.create() >> provider
        1 * provider.commit(message, patterns) >> false
        1 * provider.updateFromRepository() >> false
        thrown TaskExecutionException
    }

    private nonMultiProjectSetup() {
        def lock = new File(projectDir, 'dependencies.lock')
        lock.text = 'test lock'

        factory = Mock(ScmFactory)
        provider = Mock(ScmProvider)

        task = project.tasks.create('commitLock', CommitLockTask)
        task.with {
            scmFactory = factory
            branch = branchName
            commitMessage = message
            patternsToCommit = patterns
        }
    } 
}
