@file:JvmName("MusicServiceModule")
package org.moire.ultrasonic.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.koin.dsl.module.applicationContext
import org.moire.ultrasonic.BuildConfig
import org.moire.ultrasonic.api.subsonic.SubsonicAPIClient
import org.moire.ultrasonic.api.subsonic.SubsonicAPIVersions
import org.moire.ultrasonic.api.subsonic.SubsonicClientConfiguration
import org.moire.ultrasonic.api.subsonic.di.subsonicApiModule
import org.moire.ultrasonic.cache.PermanentFileStorage
import org.moire.ultrasonic.service.CachedMusicService
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.service.OfflineMusicService
import org.moire.ultrasonic.service.RESTMusicService
import org.moire.ultrasonic.subsonic.loader.image.SubsonicImageLoader
import org.moire.ultrasonic.util.Constants
import kotlin.math.abs

internal const val MUSIC_SERVICE_CONTEXT = "CurrentMusicService"
internal const val ONLINE_MUSIC_SERVICE = "OnlineMusicService"
internal const val OFFLINE_MUSIC_SERVICE = "OfflineMusicService"
private const val DEFAULT_SERVER_INSTANCE = 1
private const val UNKNOWN_SERVER_URL = "not-exists"
private const val LOG_TAG = "MusicServiceModule"

fun musicServiceModule(
    sp: SharedPreferences,
    context: Context
) = applicationContext {
    context(MUSIC_SERVICE_CONTEXT) {
        subsonicApiModule()

        bean(name = "ServerInstance") {
            return@bean sp.getInt(
                Constants.PREFERENCES_KEY_SERVER_INSTANCE,
                DEFAULT_SERVER_INSTANCE
            )
        }

        bean(name = "ServerID") {
            val serverInstance = get<Int>(name = "ServerInstance")
            val serverUrl = sp.getString(
                Constants.PREFERENCES_KEY_SERVER_URL + serverInstance,
                null
            )
            return@bean if (serverUrl == null) {
                UNKNOWN_SERVER_URL
            } else {
                abs("$serverUrl$serverInstance".hashCode()).toString()
            }
        }

        bean {
            val serverId = get<String>(name = "ServerID")
            return@bean PermanentFileStorage(get(), serverId, BuildConfig.DEBUG)
        }

        bean {
            val instance = get<Int>(name = "ServerInstance")
            val serverUrl = sp.getString(Constants.PREFERENCES_KEY_SERVER_URL + instance, null)
            val username = sp.getString(Constants.PREFERENCES_KEY_USERNAME + instance, null)
            val password = sp.getString(Constants.PREFERENCES_KEY_PASSWORD + instance, null)
            val allowSelfSignedCertificate = sp.getBoolean(
                Constants.PREFERENCES_KEY_ALLOW_SELF_SIGNED_CERTIFICATE + instance,
                false
            )
            val enableLdapUserSupport = sp.getBoolean(
                Constants.PREFERENCES_KEY_LDAP_SUPPORT + instance,
                false
            )

            if (serverUrl == null ||
                username == null ||
                password == null
            ) {
                Log.i(LOG_TAG, "Server credentials is not available")
                return@bean SubsonicClientConfiguration(
                    baseUrl = "http://localhost",
                    username = "",
                    password = "",
                    minimalProtocolVersion = SubsonicAPIVersions.fromApiVersion(
                        Constants.REST_PROTOCOL_VERSION
                    ),
                    clientID = Constants.REST_CLIENT_ID,
                    allowSelfSignedCertificate = allowSelfSignedCertificate,
                    enableLdapUserSupport = enableLdapUserSupport,
                    debug = BuildConfig.DEBUG
                )
            } else {
                return@bean SubsonicClientConfiguration(
                    baseUrl = serverUrl,
                    username = username,
                    password = password,
                    minimalProtocolVersion = SubsonicAPIVersions.fromApiVersion(
                        Constants.REST_PROTOCOL_VERSION
                    ),
                    clientID = Constants.REST_CLIENT_ID,
                    allowSelfSignedCertificate = allowSelfSignedCertificate,
                    enableLdapUserSupport = enableLdapUserSupport,
                    debug = BuildConfig.DEBUG
                )
            }
        }

        bean { return@bean SubsonicAPIClient(get()) }

        bean<MusicService>(name = ONLINE_MUSIC_SERVICE) {
            return@bean CachedMusicService(RESTMusicService(get(), get()))
        }

        bean<MusicService>(name = OFFLINE_MUSIC_SERVICE) {
            return@bean OfflineMusicService(get(), get())
        }

        bean { return@bean SubsonicImageLoader(context, get()) }
    }
}