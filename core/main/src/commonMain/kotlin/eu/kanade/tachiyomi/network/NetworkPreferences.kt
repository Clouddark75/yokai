package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.core.preference.PreferenceStore

class NetworkPreferences(
    private val preferenceStore: PreferenceStore,
    private val verboseLogging: Boolean,
) {

    fun verboseLogging() = preferenceStore.getBoolean("verbose_logging", verboseLogging)

    fun dohProvider() = preferenceStore.getInt("doh_provider", -1)

    fun defaultUserAgent() = preferenceStore.getString("default_user_agent", DEFAULT_USER_AGENT)

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"
    }
}