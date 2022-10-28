/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitea.mapping

import org.tmatesoft.svn.core.SVNException
import svnserver.Loggers
import svnserver.StringHelper
import svnserver.context.LocalContext
import svnserver.ext.gitea.config.GiteaContext
import svnserver.repository.git.BranchProvider
import svnserver.repository.git.GitBranch
import svnserver.repository.git.GitRepository
import io.gitea.ApiException
import io.gitea.api.RepositoryApi
import io.gitea.model.Branch
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*

/**
 * Gitea project information.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 * @author Andrew Thornton <zeripath@users.noreply.github.com>
 */
class GiteaProject internal constructor(val context: LocalContext, val repository: GitRepository, val projectId: Long, val owner: String, val repositoryName: String) : AutoCloseable, BranchProvider {

    @Volatile
    var isReady = false
        private set

    @Throws(IOException::class, SVNException::class)
    fun initRevisions() {
        if (!isReady) {
            log.info("[{}]: initing...", context.name)
            updateBranches()
            for (branch in repository.branches.values) branch.updateRevisions()
            isReady = true
        }
    }

    @Throws(IOException::class, SVNException::class)
    fun updateBranches() {
        try {
            val giteaContext: GiteaContext = GiteaContext.sure(context.shared)
            val apiClient = giteaContext.connect()
            val repositoryApi = RepositoryApi(apiClient)
            val projectName = StringHelper.baseName(repositoryName)
            val giteaBranches = repositoryApi.repoListBranches(owner, projectName, null, null)
            if (giteaBranches != null) {
                // Load discovered Gitea repository branches
                for (giteaBranch in giteaBranches) {
                    var branchName = StringHelper.normalizeDir(giteaBranch.name);
                    if (repository.branches.get(branchName) == null) {
                        log.info("[{}]: adding discovered Gitea branch {} {}...", context.name, giteaBranch.name, branchName)
                        var newBranch = GitBranch(repository, giteaBranch.name)
                        repository.branches[branchName] = newBranch
                        newBranch.updateRevisions()
                    }
                }

                // Remove repository branches no longer registered with Gitea
                val giteaBranchNames = giteaBranches!!.map { StringHelper.normalizeDir(it.name) }
                repository.branches.keys.removeIf {
                    val removed = !giteaBranchNames.contains(it);
                    if (removed) log.info("[{}]: removing unreferenced Gitea branch {}...", context.name, repository.branches[it]?.gitBranch);
                    removed
                }
            }
        } catch (e: ApiException) {
            // Skip updating if repository branches are not available
            return;
        }
    }

    override fun close() {
        try {
            context.close()
        } catch (e: Exception) {
            log.error("Can't close context for repository: " + context.name, e)
        }
    }

    override val branches: NavigableMap<String, GitBranch>
        get() {
            if (isReady) {
                updateBranches()
                return repository.branches
            }
            
            return Collections.emptyNavigableMap()
        }

    companion object {
        private val log = Loggers.gitea
    }
}
