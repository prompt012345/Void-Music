package app.voidmusic

import org.json.JSONObject

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val link: String,
    val cover: String = "",
    val provider: String = "Deezer"
) {
    val libraryKey: String get() = provider + ":" + id

    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("artist", artist); put("album", album)
        put("duration", duration); put("link", link); put("cover", cover); put("provider", provider)
    }

    companion object {
        fun fromJson(json: JSONObject) = Track(
            json.optString("id"), json.optString("title", "Titre inconnu"),
            json.optString("artist", "Artiste inconnu"), json.optString("album", "Album inconnu"),
            json.optInt("duration"), json.optString("link"), json.optString("cover"),
            json.optString("provider", "Deezer")
        )
    }
}
