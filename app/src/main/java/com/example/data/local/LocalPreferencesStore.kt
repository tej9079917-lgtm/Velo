package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.browser.BrowserSettings
import com.example.browser.SearchEngine
import com.example.browser.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.veloDataStore by preferencesDataStore(name = "velo_browser_prefs")

class LocalPreferencesStore(private val context: Context) {

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_ADBLOCK = booleanPreferencesKey("ad_block_enabled")
        private val KEY_HTTPS_ONLY = booleanPreferencesKey("https_only_enabled")
        private val KEY_DARK_PAGES = booleanPreferencesKey("dark_web_pages_enabled")
        private val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        private val KEY_SEARCH_ENGINE = stringPreferencesKey("search_engine")
        private val KEY_TRACKERS_BLOCKED = longPreferencesKey("trackers_blocked_total")
        private val KEY_CUSTOM_BLOCKED_HOSTS = stringSetPreferencesKey("custom_blocked_hosts")
        private val KEY_FOCUS_BLOCKLIST = stringSetPreferencesKey("focus_blocklist")

        val DEFAULT_FOCUS_DOMAINS: Set<String> = setOf(
            "x.com",
            "twitter.com",
            "instagram.com",
            "tiktok.com",
            "reddit.com",
            "facebook.com",
            "youtube.com"
        )
    }

    val settingsFlow: Flow<BrowserSettings> = context.veloDataStore.data.map { prefs ->
        BrowserSettings(
            themeMode = ThemeMode.fromWire(prefs[KEY_THEME]),
            adBlockEnabled = prefs[KEY_ADBLOCK] ?: true,
            httpsOnlyEnabled = prefs[KEY_HTTPS_ONLY] ?: true,
            darkWebPagesEnabled = prefs[KEY_DARK_PAGES] ?: false,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS] ?: true,
            searchEngine = SearchEngine.fromWire(prefs[KEY_SEARCH_ENGINE]),
            trackersBlockedTotal = prefs[KEY_TRACKERS_BLOCKED] ?: 0L
        )
    }

    val customBlockedHostsFlow: Flow<Set<String>> = context.veloDataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_BLOCKED_HOSTS] ?: emptySet()
    }

    val focusBlocklistFlow: Flow<Set<String>> = context.veloDataStore.data.map { prefs ->
        prefs[KEY_FOCUS_BLOCKLIST] ?: DEFAULT_FOCUS_DOMAINS
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.veloDataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setAdBlockEnabled(enabled: Boolean) {
        context.veloDataStore.edit { it[KEY_ADBLOCK] = enabled }
    }

    suspend fun setHttpsOnlyEnabled(enabled: Boolean) {
        context.veloDataStore.edit { it[KEY_HTTPS_ONLY] = enabled }
    }

    suspend fun setDarkWebPagesEnabled(enabled: Boolean) {
        context.veloDataStore.edit { it[KEY_DARK_PAGES] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.veloDataStore.edit { it[KEY_NOTIFICATIONS] = enabled }
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        context.veloDataStore.edit { it[KEY_SEARCH_ENGINE] = engine.name }
    }

    suspend fun incrementTrackersBlocked(delta: Long) {
        if (delta <= 0L) return
        context.veloDataStore.edit { prefs ->
            val current = prefs[KEY_TRACKERS_BLOCKED] ?: 0L
            prefs[KEY_TRACKERS_BLOCKED] = current + delta
        }
    }

    suspend fun setCustomBlockedHost(host: String, blocked: Boolean) {
        val clean = host.trim().lowercase().removePrefix("www.")
        if (clean.isBlank()) return
        context.veloDataStore.edit { prefs ->
            val updated = (prefs[KEY_CUSTOM_BLOCKED_HOSTS] ?: emptySet()).toMutableSet()
            if (blocked) updated.add(clean) else updated.remove(clean)
            prefs[KEY_CUSTOM_BLOCKED_HOSTS] = updated
        }
    }

    suspend fun addFocusDomain(domain: String) {
        val clean = domain.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/")
        if (clean.isBlank()) return
        context.veloDataStore.edit { prefs ->
            val updated = (prefs[KEY_FOCUS_BLOCKLIST] ?: DEFAULT_FOCUS_DOMAINS).toMutableSet()
            updated.add(clean)
            prefs[KEY_FOCUS_BLOCKLIST] = updated
        }
    }

    suspend fun removeFocusDomain(domain: String) {
        context.veloDataStore.edit { prefs ->
            val updated = (prefs[KEY_FOCUS_BLOCKLIST] ?: DEFAULT_FOCUS_DOMAINS).toMutableSet()
            updated.remove(domain)
            prefs[KEY_FOCUS_BLOCKLIST] = updated
        }
    }
}
