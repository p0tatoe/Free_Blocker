package dev.michaelylee.freeblocker.data

import dev.michaelylee.freeblocker.core.DnsProxyServer.UpstreamConfig
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// Singleton DataStore instance scoped to the application context
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_settings")

class UserPreferences(private val context: Context) {

    private object Keys {
        val IS_VPN_ENABLED       = booleanPreferencesKey("is_vpn_enabled")
        val MANUAL_BLOCKED       = stringSetPreferencesKey("manual_blocked_domains")
        val WHITELISTED          = stringSetPreferencesKey("whitelisted_domains")
        val CUSTOM_SOURCE_URLS   = stringSetPreferencesKey("custom_source_urls")
        /**
         * Stores the active upstream as a single encoded string.
         * Encoding format is defined in [UpstreamConfig.encode] /  [UpstreamConfig.decode].
         * Example value: "DOT|1.1.1.1|853|cloudflare-dns.com"
         */
        val UPSTREAM_CONFIG      = stringPreferencesKey("upstream_config")
        val IS_BLOCKING_ENABLED = booleanPreferencesKey("is_blocking_enabled")
        val IS_START_ON_BOOT    = booleanPreferencesKey("is_start_on_boot")
    }

    companion object {
        val DEFAULT_UPSTREAM = UpstreamConfig()
    }


    private fun <T> Flow<T>.catchIo(default: T): Flow<T> =
        catch { e -> if (e is IOException) emit(default) else throw e }

    val isVpnEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catchIo(emptyPreferences())
        .map { it[Keys.IS_VPN_ENABLED] ?: false }

    suspend fun setVpnEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_VPN_ENABLED] = enabled }
    }


    val upstreamConfigFlow: Flow<UpstreamConfig> = context.dataStore.data
        .catchIo(emptyPreferences())
        .map { preferences ->
            preferences[Keys.UPSTREAM_CONFIG]
                ?.let { UpstreamConfig.decode(it) }
                ?: DEFAULT_UPSTREAM
        }

    suspend fun getUpstreamConfig(): UpstreamConfig =
        upstreamConfigFlow.first()

    /**
     * Persists [config] to DataStore.
     * [MyVpnService] observes [upstreamConfigFlow] and calls
     * [DnsProxyServer.updateUpstream] whenever this changes.
     */
    suspend fun setUpstreamConfig(config: UpstreamConfig) {
        context.dataStore.edit { it[Keys.UPSTREAM_CONFIG] = UpstreamConfig.encode(config) }
    }

    val manualBlockedDomainsFlow: Flow<Set<String>> = context.dataStore.data
        .catchIo(emptyPreferences())
        .map { it[Keys.MANUAL_BLOCKED] ?: emptySet() }

    suspend fun getManualBlockedDomains(): Set<String> =
        manualBlockedDomainsFlow.first()

    suspend fun addManualBlockedDomain(domain: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.MANUAL_BLOCKED] ?: emptySet()
            prefs[Keys.MANUAL_BLOCKED] = current + domain.trim().lowercase()
        }
    }

    suspend fun removeManualBlockedDomain(domain: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.MANUAL_BLOCKED] ?: emptySet()
            prefs[Keys.MANUAL_BLOCKED] = current - domain.trim().lowercase()
        }
    }

    val whitelistedDomainsFlow: Flow<Set<String>> = context.dataStore.data
        .catchIo(emptyPreferences())
        .map { it[Keys.WHITELISTED] ?: emptySet() }

    suspend fun getWhitelistedDomains(): Set<String> =
        whitelistedDomainsFlow.first()

    suspend fun addWhitelistedDomain(domain: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.WHITELISTED] ?: emptySet()
            prefs[Keys.WHITELISTED] = current + domain.trim().lowercase()
        }
    }

    suspend fun removeWhitelistedDomain(domain: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.WHITELISTED] ?: emptySet()
            prefs[Keys.WHITELISTED] = current - domain.trim().lowercase()
        }
    }

    val customSourceUrlsFlow: Flow<Set<String>> = context.dataStore.data
        .catchIo(emptyPreferences())
        .map { it[Keys.CUSTOM_SOURCE_URLS] ?: emptySet() }

    suspend fun getCustomSourceUrls(): Set<String> =
        customSourceUrlsFlow.first()

    suspend fun addCustomSourceUrl(url: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_SOURCE_URLS] ?: emptySet()
            prefs[Keys.CUSTOM_SOURCE_URLS] = current + url.trim()
        }
    }

    suspend fun removeCustomSourceUrl(url: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_SOURCE_URLS] ?: emptySet()
            prefs[Keys.CUSTOM_SOURCE_URLS] = current - url.trim()
        }
    }

    val isBlockingEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catchIo(emptyPreferences())
        .map { it[Keys.IS_BLOCKING_ENABLED] ?: true }  // default: blocking on

    suspend fun setBlockingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_BLOCKING_ENABLED] = enabled }
    }

    val isStartOnBootFlow: Flow<Boolean> = context.dataStore.data
        .catchIo(emptyPreferences())
        .map { it[Keys.IS_START_ON_BOOT] ?: false }  // default: don't autostart

    suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_START_ON_BOOT] = enabled }
    }
}