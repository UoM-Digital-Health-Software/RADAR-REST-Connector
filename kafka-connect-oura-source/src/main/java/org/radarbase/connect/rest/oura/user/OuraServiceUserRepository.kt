/*
 * Copyright 2018 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.radarbase.connect.rest.oura.user

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.radarbase.connect.rest.oura.OuraRestSourceConnectorConfig
import org.radarbase.kotlin.coroutines.CacheConfig
import org.radarbase.kotlin.coroutines.CachedSet
import org.radarbase.kotlin.coroutines.CachedValue
import org.radarbase.ktor.auth.ClientCredentialsConfig
import org.radarbase.ktor.auth.clientCredentials
import org.radarbase.oura.user.OuraUser
import org.radarbase.oura.user.User
import org.radarbase.oura.user.UserRepository
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.asSequence
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
class OuraServiceUserRepository : UserRepository {
    private lateinit var userCache: CachedSet<OuraUser>
    private lateinit var client: HttpClient
    private val credentialCaches = ConcurrentHashMap<String, CachedValue<OAuth2UserCredentials>>()
    private val credentialCacheConfig =
        CacheConfig(refreshDuration = 1.days, retryDuration = 1.minutes)

    @Throws(IOException::class)
    override fun get(key: String): User = runBlocking(Dispatchers.Default) {
        makeRequest { url("users/$key") }
    }

    fun initialize(
        config: OuraRestSourceConnectorConfig,
    ) {
        val containedUsers = config.ouraUsers.toHashSet()

        client = createClient(
            baseUrl = config.ouraUserRepositoryUrl,
            tokenUrl = config.ouraUserRepositoryTokenUrl,
            clientId = config.ouraUserRepositoryClientId,
            clientSecret = config.ouraUserRepositoryClientSecret,
        )
        

        userCache = CachedSet(
            CacheConfig(refreshDuration = 1.hours, retryDuration = 1.minutes),
        ) {
            makeRequest<OuraUsers> { url("users?source-type=Oura") }
                .users
                .toHashSet()
            // .filterTo(HashSet()) { u ->
            //     u.isComplete() &&
            //         (containedUsers.isEmpty() || u.versionedId in containedUsers)
            // }
        }
    }

    private fun createClient(
        baseUrl: Url,
        tokenUrl: Url?,
        clientId: String?,
        clientSecret: String?,
    ): HttpClient = HttpClient(CIO) {
        if (tokenUrl != null) {
            install(Auth) {
                clientCredentials(
                    ClientCredentialsConfig(
                        tokenUrl.toString(),
                        clientId,
                        clientSecret,
                    ).copyWithEnv("MANAGEMENT_PORTAL"),
                    baseUrl.host,
                )
            }
        } else if (clientId != null && clientSecret != null) {
            install(Auth) {
                basic {
                    credentials {
                        BasicAuthCredentials(username = clientId, password = clientSecret)
                    }
                    realm = "Access to the '/' path"
                    sendWithoutRequest {
                        it.url.host == baseUrl.host
                    }
                }
            }
        }

        defaultRequest {
            url.takeFrom(baseUrl)
        }

        install(ContentNegotiation) {
            jackson {
                registerModule(JavaTimeModule()) // support java.time.* types
            }
        }

        install(HttpTimeout) {
            connectTimeoutMillis = 60.seconds.inWholeMilliseconds
            requestTimeoutMillis = 90.seconds.inWholeMilliseconds
        }
    }

    override fun stream(): Sequence<OuraUser> = runBlocking(Dispatchers.Default) {
        val valueInCache = userCache.getFromCache()
            .takeIf { it is CachedValue.CacheValue }
            ?.getOrThrow()

        (valueInCache ?: userCache.get())
            .stream()
            // .filter { it.isComplete() }
            .asSequence()
    }

    @Throws(IOException::class, UserNotAuthorizedException::class)
    override fun getAccessToken(user: User): String {
        if (!user.isAuthorized) {
            throw UserNotAuthorizedException("User is not authorized")
        }
        return runBlocking(Dispatchers.Default) {
            credentialCache(user)
                .get { !it.isAccessTokenExpired }
                .value
                .accessToken
        }
    }

    @Throws(IOException::class, UserNotAuthorizedException::class)
    fun refreshAccessToken(user: User): String {
        if (!user.isAuthorized) {
            throw UserNotAuthorizedException("User is not authorized")
        }
        return runBlocking(Dispatchers.Default) {
            val token = requestAccessToken(user) {
                url("users/${user.id}/token")
                method = HttpMethod.Post
                setBody("{}")
                contentType(ContentType.Application.Json)
            }
            credentialCache(user).set(token)
            token.accessToken
        }
    }

    private suspend fun credentialCache(user: User): CachedValue<OAuth2UserCredentials> =
        credentialCaches.computeIfAbsent(user.id) {
            CachedValue(credentialCacheConfig) {
                requestAccessToken(user) { url("users/${user.id}/token") }
            }
        }

    @Throws(UserNotAuthorizedException::class, IOException::class)
    private suspend fun requestAccessToken(
        user: User,
        builder: HttpRequestBuilder.() -> Unit,
    ): OAuth2UserCredentials =
        try {
            makeRequest<OAuth2UserCredentials>(builder)
        } catch (ex: HttpResponseException) {
            if (ex.statusCode == 407) {
                credentialCaches -= user.id
                throw UserNotAuthorizedException(ex.message)
            }
            throw ex
        }

    fun hasPendingUpdates(): Boolean = runBlocking(Dispatchers.Default) {
        userCache.isStale()
    }

    @Throws(IOException::class)
    fun applyPendingUpdates() {
        logger.info("Requesting user information from webservice")

        runBlocking(Dispatchers.Default) {
            userCache.get()
        }
    }

    private suspend inline fun <reified T> makeRequest(
        crossinline builder: HttpRequestBuilder.() -> Unit,
    ): T = withContext(Dispatchers.IO) {
        val response = client.request(builder)
        val contentLength = response.contentLength()
        val hasBody = contentLength != null && contentLength > 0
        if (response.status == HttpStatusCode.NotFound) {
            throw NoSuchElementException("URL " + response.request.url + " does not exist")
        } else if (!response.status.isSuccess() || !hasBody) {
            val message = buildString {
                append("Failed to make request (HTTP status code ")
                append(response.status)
                append(')')
                if (hasBody) {
                    append(": ")
                    append(response.bodyAsText())
                }
            }
            throw HttpResponseException(message, response.status.value)
        }
        response.body<T>()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OuraServiceUserRepository::class.java)
    }
}
