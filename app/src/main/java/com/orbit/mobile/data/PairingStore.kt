package com.orbit.mobile.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class Pairing(val target: ServerTarget, val label: String)

class PairingStore(context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
        EncryptedSharedPreferences.create(
            context,
            "omni_pairing",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_PASSWORD = "password"
        const val KEY_USERNAME = "username"
        const val KEY_LABEL = "label"
    }

    fun save(pairing: Pairing) {
        prefs.edit()
            .putString(KEY_HOST, pairing.target.host)
            .putInt(KEY_PORT, pairing.target.port)
            .putString(KEY_PASSWORD, pairing.target.password)
            .putString(KEY_USERNAME, pairing.target.username)
            .putString(KEY_LABEL, pairing.label)
            .apply()
    }

    fun load(): Pairing? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, -1)
        if (port <= 0 || host.isBlank()) return null
        return Pairing(
            target = ServerTarget(
                host = host,
                port = port,
                password = prefs.getString(KEY_PASSWORD, null),
                username = prefs.getString(KEY_USERNAME, null) ?: "opencode",
            ),
            label = prefs.getString(KEY_LABEL, null) ?: host,
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
