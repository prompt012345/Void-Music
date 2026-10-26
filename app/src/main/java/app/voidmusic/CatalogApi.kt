package app.voidmusic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class SoundCloudCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("void_music_credentials", Context.MODE_PRIVATE)
    private val alias = "void_music_soundcloud_secret"

    fun clientId(): String = prefs.getString("soundcloud_client_id", "").orEmpty()

    fun save(clientId: String, clientSecret: String) {
        prefs.edit().remove("tokens_enc").putString("soundcloud_client_id", clientId.trim())
            .putString("soundcloud_secret_enc", encrypt(clientSecret)).apply()
    }

    fun secret(): String = prefs.getString("soundcloud_secret_enc", null)?.let(::decrypt).orEmpty()

    fun hasCredentials(): Boolean = clientId().isNotBlank() && secret().isNotBlank()

    fun clear() { prefs.edit().clear().apply() }

    fun saveTokens(access: String, refresh: String, expiresAt: Long) {
        val tokens = JSONObject().put("access", access).put("refresh", refresh).put("expiresAt", expiresAt)
        prefs.edit().putString("tokens_enc", encrypt(tokens.toString())).apply()
    }

    fun tokens(): Triple<String, String, Long>? = try {
        val json = JSONObject(prefs.getString("tokens_enc", null)?.let(::decrypt).orEmpty())
        Triple(json.optString("access"), json.optString("refresh"), json.optLong("expiresAt"))
    } catch (_: Exception) { null }

    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(alias, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    } catch (_: Exception) {
        ""
    }
}

class CatalogApi {
    private data class CachedToken(val value: String, val refresh: String, val expiresAt: Long)
    private var cachedToken: CachedToken? = null
    private val activeConnections = java.util.Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    fun cancelActiveRequests() { synchronized(activeConnections) { activeConnections.toList().forEach { it.disconnect() }; activeConnections.clear() } }

    fun clearSoundCloudToken() { cachedToken = null }

    fun searchDeezer(query: String): List<Track> {
        val json = getJson("https://api.deezer.com/search?q=" + enc(query) + "&limit=25")
        if (json.has("error")) throw IllegalStateException(json.optJSONObject("error")?.optString("message") ?: "Erreur Deezer")
        val data = json.optJSONArray("data") ?: throw IllegalStateException("Réponse Deezer invalide")
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val artist = item.optJSONObject("artist")
            val album = item.optJSONObject("album")
            val id = item.optLong("id")
            if (id <= 0) null else Track(
                id.toString(), item.optString("title", "Titre inconnu"),
                artist?.optString("name", "Artiste inconnu") ?: "Artiste inconnu",
                album?.optString("title", "Album inconnu") ?: "Album inconnu",
                item.optInt("duration"), item.optString("link"),
                album?.optString("cover_medium") ?: "", "Deezer"
            )
        }
    }

    fun searchAudius(query: String): List<Track> {
        val json = getJson("https://api.audius.co/v1/tracks/search?query=" + enc(query) + "&limit=25")
        val data = json.optJSONArray("data") ?: throw IllegalStateException("Réponse Audius invalide")
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val user = item.optJSONObject("user")
            val artwork = item.optJSONObject("artwork")
            val id = item.optString("id")
            val permalink = item.optString("permalink", item.optString("permalink_url"))
            if (id.isBlank()) null else Track(
                id, item.optString("title", "Titre inconnu"),
                user?.optString("name")?.takeIf(String::isNotBlank)
                    ?: user?.optString("handle", "Artiste inconnu") ?: "Artiste inconnu",
                item.optString("release_name", "Single"), item.optInt("duration"),
                if (permalink.startsWith("http")) permalink else if (permalink.startsWith("/")) "https://audius.co" + permalink else "",
                artwork?.optString("150x150") ?: artwork?.optString("480x480") ?: item.optString("artwork_url"), "Audius"
            )
        }
    }

    fun searchSoundCloud(query: String, clientId: String, clientSecret: String, credentialStore: SoundCloudCredentialStore): List<Track> {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw IllegalStateException("Configure le Client ID et le secret SoundCloud dans Paramètres.")
        }
        val token = soundCloudToken(clientId, clientSecret, credentialStore)
        val body = getBody(
            "https://api.soundcloud.com/tracks?q=" + enc(query) + "&limit=25&linked_partitioning=true",
            "OAuth " + token
        )
        val trimmed = body.trimStart()
        val data = if (trimmed.startsWith("[")) JSONArray(trimmed) else {
            val json = JSONObject(trimmed)
            json.optJSONArray("collection") ?: json.optJSONArray("data")
                ?: if (json.has("id")) JSONArray().put(json) else JSONArray()
        }
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val user = item.optJSONObject("user")
            val id = item.optLong("id")
            if (id <= 0) null else Track(
                id.toString(), item.optString("title", "Titre inconnu"),
                user?.optString("username", "Artiste inconnu") ?: "Artiste inconnu",
                item.optJSONObject("publisher_metadata")?.optString("album_title") ?: "Single",
                item.optLong("duration").div(1000).toInt(), item.optString("permalink_url"),
                item.optString("artwork_url"), "SoundCloud"
            )
        }
    }
    fun testSoundCloud(clientId: String, clientSecret: String, credentialStore: SoundCloudCredentialStore) {
        soundCloudToken(clientId, clientSecret, credentialStore)
    }

    @Synchronized
    private fun soundCloudToken(
        clientId: String,
        clientSecret: String,
        credentialStore: SoundCloudCredentialStore
    ): String {
        val stored = cachedToken ?: credentialStore.tokens()?.let {
            CachedToken(it.first, it.second, it.third)
        }
        if (stored != null && stored.expiresAt > System.currentTimeMillis() + 60_000) {
            cachedToken = stored
            return stored.value
        }

        val refresh = stored?.refresh?.takeIf { it.isNotBlank() }
        val credentials = Base64.encodeToString(
            "$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP
        )
        val connection = URL("https://secure.soundcloud.com/oauth/token").openConnection() as HttpURLConnection
        activeConnections.add(connection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 9000
            connection.readTimeout = 9000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            val form = if (refresh == null) {
                connection.setRequestProperty("Authorization", "Basic $credentials")
                "grant_type=client_credentials"
            } else {
                "grant_type=refresh_token&client_id=" + enc(clientId) +
                    "&client_secret=" + enc(clientSecret) + "&refresh_token=" + enc(refresh)
            }
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(form) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw apiError("SoundCloud", code, body)
            val json = JSONObject(body)
            val token = json.optString("access_token")
            if (token.isBlank()) throw IllegalStateException("SoundCloud n’a pas renvoyé de jeton exploitable.")
            val expiry = (json.optLong("expires_in", 3600) * 1000L).coerceAtLeast(60_000L)
            val refreshToken = json.optString("refresh_token").ifBlank { refresh.orEmpty() }
            val updated = CachedToken(token, refreshToken, System.currentTimeMillis() + expiry)
            cachedToken = updated
            credentialStore.saveTokens(updated.value, updated.refresh, updated.expiresAt)
            return token
        } finally {
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }
    private fun getJson(url: String, authorization: String? = null): JSONObject = JSONObject(getBody(url, authorization))

    private fun getBody(url: String, authorization: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        activeConnections.add(connection)
        try {
            connection.connectTimeout = 9000
            connection.readTimeout = 9000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "VOID-Music/0.4")
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw apiError(if (authorization == null) "Catalogue" else "SoundCloud", code, body)
            return body
        } finally {
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }
    private fun apiError(provider: String, code: Int, body: String): IllegalStateException {
        val detail = try {
            val json = JSONObject(body)
            json.optString("message").takeIf(String::isNotBlank)
                ?: json.optString("error").takeIf(String::isNotBlank)
                ?: json.optJSONArray("errors")?.optJSONObject(0)?.optString("error_message")
        } catch (_: Exception) { null }
        val message = when (code) {
            401 -> "Identifiants ou jeton $provider refusés (HTTP 401)."
            403 -> "Accès refusé par $provider (HTTP 403)."
            429 -> "Limite de requêtes $provider atteinte. Réessaie plus tard."
            else -> detail ?: "Erreur HTTP $code depuis $provider."
        }
        return IllegalStateException(message)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}














