/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitea.mapping

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import io.gitea.ApiException
import io.gitea.api.RepositoryApi
import io.gitea.api.OrganizationApi
import io.gitea.model.Repository
import io.gitea.model.Organization
import io.gitea.model.Team
import svnserver.Loggers
import svnserver.auth.User
import svnserver.auth.ACL
import svnserver.context.LocalContext
import svnserver.ext.gitea.config.GiteaContext
import svnserver.repository.VcsAccess
import java.util.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Access control by Gitea server.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 * @author Andrew Thornton <zeripath@users.noreply.github.com>
 * @author Marat Radchenko <marat@slonopotamus.org>
 */
internal class GiteaAccess(local: LocalContext, private val config: GiteaMappingConfig, private val repository: Repository) : VcsAccess {
    private val cache: LoadingCache<String, Repository>
    private val orgTeam2MemberCache: LoadingCache<String, Map<String, Array<String>>>

    @Throws(IOException::class)
    override fun canRead(user: User, branch: String, path: String): Boolean {
        return try {
            val access = config.repositories?.get(repository.fullName)?.access
            val group2users = if (access != null) getCachedOrgTeam2MemberList(repository) else null
            var acl = if (access != null && group2users != null) ACL(repository.fullName, group2users, access) else null
            if (acl != null) {
                // Check user or organization team permissions defined by ACL
                // Note: Users or teams may not have permission to read/write to the repositories via Gitea/git access
                // but can still access the repository with path-based authorization rules applied via SVN access.
                acl.canRead(user, branch, path)
            } else {
                // Default to checking individual user permissions
                val repository = getCachedProject(user)
                if (!repository.isPrivate) return true
                val permission = repository.permissions
                permission.isAdmin || permission.isPull
            }
        } catch (ignored: FileNotFoundException) {
            false
        }
    }

    @Throws(IOException::class)
    override fun canWrite(user: User, branch: String, path: String): Boolean {
        return if (user.isAnonymous) false else try {
            val access = config.repositories?.get(repository.fullName)?.access
            val group2users = if (access != null) getCachedOrgTeam2MemberList(repository) else null
            var acl = if (access != null && group2users != null) ACL(repository.fullName, group2users, access) else null
            if (acl != null) {
                // Check user or organization team permissions defined by ACL
                // Note: Users or teams may not have permission to read/write to the repositories via Gitea/git access
                // but can still access the repository with path-based authorization rules applied via SVN access.
                acl.canWrite(user, branch, path)
            } else {
                // Checking user permissions
                val repository = getCachedProject(user)
                val permission = repository.permissions
                permission.isAdmin || permission.isPush
            }
        } catch (ignored: FileNotFoundException) {
            false
        }
    }

    override fun updateEnvironment(environment: MutableMap<String, String>, user: User) {
        environment["GITEA_REPO_ID"] = "" + repository.id
        environment["GITEA_REPO_IS_WIKI"] = "false"
        environment["GITEA_REPO_NAME"] = repository.name
        environment["GITEA_REPO_USER"] = repository.owner.login
        val externalId = user.externalId
        environment["SSH_ORIGINAL_COMMAND"] = "git"
        if (user.email != null)
            environment["GITEA_PUSHER_EMAIL"] = user.email
        if (externalId != null) {
            environment["GITEA_PUSHER_ID"] = user.externalId
        }
        val deployKey = config.repositories?.get(repository.fullName)?.deployKey
        if (deployKey != null)
            environment["GITEA_DEPLOY_KEY_ID"] = deployKey
    }

    @Throws(IOException::class)
    private fun getCachedProject(user: User): Repository {
        return try {
            if (user.isAnonymous) return cache[""]
            val key = user.username
            check(key.isNotEmpty()) { "Found user without identifier: $user" }
            cache[key]
        } catch (e: ExecutionException) {
            if (e.cause is IOException) {
                throw (e.cause as IOException?)!!
            }
            throw IllegalStateException(e)
        }
    }

    @Throws(IOException::class)
    private fun getCachedOrgTeam2MemberList(repository: Repository): Map<String, Array<String>>? {
        return try {
            val key = repository?.owner?.login
            if (key == null || key!!.isEmpty()) return emptyMap()
            check(key.isNotEmpty()) { "Found repository without owner: $repository" }
            orgTeam2MemberCache[key]
        } catch (e: ExecutionException) {
            if (e.cause is IOException) {
                throw (e.cause as IOException?)!!
            }
            throw IllegalStateException(e)
        }
    }

    init {
        val projectId = repository.id
        val context: GiteaContext = GiteaContext.sure(local.shared)
        cache = CacheBuilder.newBuilder().maximumSize(config.cacheMaximumSize.toLong())
            .expireAfterWrite(config.cacheTimeSec.toLong(), TimeUnit.SECONDS).build(object : CacheLoader<String, Repository>() {
                @Throws(Exception::class)
                override fun load(username: String): Repository {
                    if (username.isEmpty()) {
                        try {
                            val apiClient = context.connect()
                            val repositoryApi = RepositoryApi(apiClient)
                            val repository = repositoryApi.repoGetByID(projectId)
                            if (!repository.isPrivate) {
                                // Munge the permissions
                                repository.permissions.isAdmin = false
                                repository.permissions.isPush = false
                                return repository
                            }
                            throw FileNotFoundException()
                        } catch (e: ApiException) {
                            if (e.code == 404) {
                                throw FileNotFoundException()
                            } else {
                                throw e
                            }
                        }
                    }
                    // Sudo as the user
                    return try {
                        val apiClient = context.connect(username)
                        val repositoryApi = RepositoryApi(apiClient)
                        repositoryApi.repoGetByID(projectId)
                    } catch (e: ApiException) {
                        if (e.code == 404) {
                            throw FileNotFoundException()
                        } else {
                            throw e
                        }
                    }
                }
            })

        orgTeam2MemberCache = CacheBuilder.newBuilder().maximumSize(config.cacheMaximumSize.toLong())
            .expireAfterWrite(config.cacheTimeSec.toLong(), TimeUnit.SECONDS).build(object : CacheLoader<String, Map<String, Array<String>>>() {
                @Throws(Exception::class)
                override fun load(repoOwnerOrg: String): Map<String, Array<String>> {
                    if (repoOwnerOrg.isEmpty()) return emptyMap()

                    var orgTeamMapping = mutableMapOf<String, Array<String>>()

                    return try {
                        val apiClient = context.connect()
                        val organizationApi = OrganizationApi(apiClient)
                        var orgTeams = organizationApi.orgListTeams(repoOwnerOrg, null, null)
                        for(team in orgTeams) {
                            var orgTeamMembers = organizationApi.orgListTeamMembers(team.id, null, null)
                            var teamMemberNames = mutableListOf<String>();
                            for(member in orgTeamMembers) {
                                teamMemberNames.add(member.login);
                            }
                            orgTeamMapping[team.name] = teamMemberNames.toTypedArray();
                        }

                        return orgTeamMapping;
                    } catch (e: ApiException) {
                        if (e.code == 404) {
                            throw FileNotFoundException()
                        } else {
                            throw e
                        }
                    }
                }
            })
    }
}
