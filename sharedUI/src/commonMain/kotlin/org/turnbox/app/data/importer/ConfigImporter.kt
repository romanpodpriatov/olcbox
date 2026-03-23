package org.turnbox.app.data.importer

interface ConfigImporter {
    fun getFromClipboard(): String?
    fun copyToClipboard(text: String)

    /**
     * Прочитать текст из внешнего источника, Any т.к. в android и ios типы разные.
     */
    suspend fun readTextFromSource(source: Any): String?
}