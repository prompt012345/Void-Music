package app.voidmusic

object MetadataExport {
    fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
