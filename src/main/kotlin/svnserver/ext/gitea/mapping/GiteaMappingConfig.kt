/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitea.mapping

import io.gitea.ApiException
import io.gitea.api.UserApi
import io.gitea.api.OrganizationApi
import io.gitea.api.RepositoryApi
import org.tmatesoft.svn.core.SVNException
import svnserver.Loggers
import svnserver.StringHelper
import svnserver.config.GitRepositoryConfig
import svnserver.config.RepositoryMappingConfig
import svnserver.context.SharedContext
import svnserver.ext.gitea.config.GiteaContext
import svnserver.repository.RepositoryMapping
import svnserver.repository.git.GitCreateMode
import svnserver.auth.ACL
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.function.Consumer

/**
 * Repository list mapping.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 * @author Andrew Thornton <zeripath@users.noreply.github.com>
 */
class GiteaMappingConfig private constructor(var path: String, var createMode: GitCreateMode) : RepositoryMappingConfig {
    var template: GitRepositoryConfig = GitRepositoryConfig(createMode)
    var repositories = TreeMap<String, Entry>()
    var groups = HashMap<String, Array<String>>()
    private var watcher: DirectoryWatcher? = null
    var cacheTimeSec = 15
    var cacheMaximumSize = 1000

    constructor() : this("/var/git/repositories/", GitCreateMode.ERROR)
    constructor(path: Path, createMode: GitCreateMode) : this(path.toAbsolutePath().toString(), createMode)

    @Throws(IOException::class)
    override fun create(context: SharedContext, canUseParallelIndexing: Boolean): RepositoryMapping<*> {
        val giteaContext = context.sure(GiteaContext::class.java)
        val apiClient = giteaContext.connect()
        val userApi = UserApi(apiClient)
        val organizationApi = OrganizationApi(apiClient)
        val repositoryApi = RepositoryApi(apiClient)
        // Get repositories.
        val mapping = GiteaMapping(context, this)
        try {
            // Retrieve user repository list from Gitea
            val usersList = userApi.userSearch(null, null, null, null)
            for (u in usersList.data) {
                val giteaRepositories = userApi.userListRepos(u.login, null, null)
                for (repository in giteaRepositories) {
                    mapping.addRepository(repository)
                }
            }

            // Retrieve organization repository list from Gitea
            val giteaOrganizations = organizationApi.orgGetAll(null, null)
            for (organization in giteaOrganizations) {
                val giteaRepositories = organizationApi.orgListRepos(organization.username, null, null)
                for (repository in giteaRepositories) {
                    mapping.addRepository(repository)
                }
            }

            // Include repositories with path-based authorization rules
            val uniquePaths = HashSet<Path>()
            repositories.values.stream().map { entry: Entry -> entry.repository.path }.forEach { s: String ->
                if (!uniquePaths.add(Paths.get(s).toAbsolutePath())) throw IllegalStateException("Duplicate repositories in config: $s")
            }

            for (entry in repositories.entries) {
                val projectKey = StringHelper.normalizeDir(entry.key)
                val project = mapping.mapping[projectKey]
                if (project == null) {
                    val ownerName = StringHelper.parentDir(entry.key)
                    val projectName = StringHelper.baseName(entry.key)
                    val giteaRepository = repositoryApi.repoGet(ownerName, projectName)
                    mapping.addRepository(giteaRepository)
                }
            }
        } catch (e: ApiException) {
            throw RuntimeException("Failed to initialize", e)
        }

        // Add directory watcher
        if (watcher == null || !watcher!!.isAlive) {
            watcher = DirectoryWatcher(path, GiteaMapper(apiClient, mapping))
        }
        val init = Consumer { repository: GiteaProject ->
            try {
                repository.initRevisions()
            } catch (e: IOException) {
                throw RuntimeException(String.format("[%s]: failed to initialize", repository.context.name), e)
            } catch (e: SVNException) {
                throw RuntimeException(String.format("[%s]: failed to initialize", repository.context.name), e)
            }
        }
        if (canUseParallelIndexing) {
            mapping.mapping.values.parallelStream().forEach(init)
        } else {
            mapping.mapping.values.forEach(init)
        }
        return mapping
    }

    class Entry {
        val access: Map<String, Map<String, String>> = HashMap()
        val repository: GitRepositoryConfig = GitRepositoryConfig()
        val deployKey: String? = null
    }
}
