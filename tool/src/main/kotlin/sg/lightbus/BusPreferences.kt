package sg.lightbus

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val savedStopsKey = stringPreferencesKey("saved_bus_stops")
private val accountKey = stringPreferencesKey("encrypted_lta_account_key")
private const val KEY_ALIAS = "sg.lightbus.datamall"
private const val TRANSFORMATION = "AES/GCM/NoPadding"

class BusPreferences(private val preferences: DataStore<Preferences>) {
    suspend fun favorites(): List<BusStop> = preferences.data.first()[savedStopsKey]?.let {
        Json.decodeFromString<List<BusStop>>(it)
    }.orEmpty()

    suspend fun toggleFavorite(stop: BusStop): List<BusStop> {
        val result = preferences.edit { values ->
            val existing = values[savedStopsKey]?.let {
                Json.decodeFromString<List<BusStop>>(it)
            }.orEmpty()
            val updated = if (existing.any { it.code == stop.code }) {
                existing.filterNot { it.code == stop.code }
            } else {
                existing + stop
            }
            values[savedStopsKey] = Json.encodeToString(updated)
        }
        return Json.decodeFromString(requireNotNull(result[savedStopsKey]))
    }

    suspend fun readKey(): String? = withContext(Dispatchers.IO) {
        val stored = preferences.data.first()[accountKey] ?: return@withContext null
        val (iv, ciphertext) = stored.split(':', limit = 2)
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return@withContext null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(iv)))
        cipher.doFinal(decode(ciphertext)).decodeToString()
    }

    suspend fun saveKey(value: String) = withContext(Dispatchers.IO) {
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = "${encode(cipher.iv)}:${encode(cipher.doFinal(value.encodeToByteArray()))}"
        preferences.edit { it[accountKey] = encrypted }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore",
    ).apply {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
    }.generateKey()

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
