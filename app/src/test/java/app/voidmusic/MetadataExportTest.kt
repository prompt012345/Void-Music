package app.voidmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataExportTest {
    @Test fun escapesQuotesAndCommasForCsv() {
        assertEquals("\"a,\"\"b\"\"\"", MetadataExport.csv("a,\"b\""))
    }
    @Test fun quotesEmptyValues() {
        assertEquals("\"\"", MetadataExport.csv(""))
    }
}
