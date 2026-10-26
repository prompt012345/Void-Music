package app.voidmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores track metadata only. API credentials are managed separately; no audio is persisted. */
class LocalLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("void_music", Context.MODE_PRIVATE)

    private fun readTracks(key: String): List<Track> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Track::fromJson) }
    } catch (_: Exception) { emptyList() }

    private fun writeTracks(key: String, tracks: Collection<Track>) {
        val array = JSONArray()
        tracks.forEach { array.put(it.toJson()) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun favorites(): List<Track> = readTracks("favorite_tracks")

    @Synchronized fun toggleFavorite(track: Track): Boolean {
        val map = favorites().associateBy { it.libraryKey }.toMutableMap()
        val added = !map.containsKey(track.libraryKey)
        if (added) map[track.libraryKey] = track else map.remove(track.libraryKey)
        writeTracks("favorite_tracks", map.values)
        return added
    }

    @Synchronized fun playlists(): Map<String, List<Track>> = try {
        val root = JSONObject(prefs.getString("playlists", "{}"))
        root.keys().asSequence().associateWith { name ->
            val array = root.optJSONArray(name) ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Track::fromJson) }
        }
    } catch (_: Exception) { emptyMap() }

    @Synchronized fun savePlaylist(name: String, tracks: List<Track>) {
        val root = JSONObject(prefs.getString("playlists", "{}"))
        root.put(name, JSONArray().also { array -> tracks.forEach { array.put(it.toJson()) } })
        prefs.edit().putString("playlists", root.toString()).apply()
    }

    @Synchronized fun deletePlaylist(name: String) {
        val root = JSONObject(prefs.getString("playlists", "{}"))
        root.remove(name)
        prefs.edit().putString("playlists", root.toString()).apply()
    }
}


