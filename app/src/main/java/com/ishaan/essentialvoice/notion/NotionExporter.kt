package com.ishaan.essentialvoice.notion

import android.content.Context
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.notes.NoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object NotionExporter {

    private const val TAG = "EVNotion"
    private const val NOTION_VERSION = "2022-06-28"

    /**
     * Cleans up Notion Page ID or Notion URL into a standard 32-character hex UUID.
     * E.g. "https://app.notion.com/p/Thoughts-3f6127c792f7805e8e31fc9df080280d" -> "3f6127c7-92f7-805e-8e31-fc9df080280d"
     */
    fun cleanPageId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Prefs.DEFAULT_NOTION_PAGE_ID
        val cleanNoHyphen = trimmed.substringAfterLast("/").substringAfterLast("-").replace("-", "")
        val hexMatch = Regex("[0-9a-fA-F]{32}").find(cleanNoHyphen)
            ?: Regex("[0-9a-fA-F]{32}").find(trimmed.replace("-", ""))
        val hex = hexMatch?.value ?: trimmed.replace("-", "")
        return if (hex.length == 32) {
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        } else {
            trimmed
        }
    }

    suspend fun exportNotes(
        context: Context,
        notes: List<NoteStore.Note>,
        token: String = Prefs.DEFAULT_NOTION_TOKEN,
        rawPageId: String = Prefs.DEFAULT_NOTION_PAGE_ID,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val validNotes = notes.filter { it.text.isNotBlank() }
        if (validNotes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No notes to export"))
        }

        val activeToken = token.ifBlank { Prefs.DEFAULT_NOTION_TOKEN }
        val targetPageId = cleanPageId(rawPageId.ifBlank { Prefs.DEFAULT_NOTION_PAGE_ID })

        try {
            // Notion API allows up to 100 blocks per request
            val batches = validNotes.chunked(100)
            for (batch in batches) {
                val children = JSONArray()
                for (note in batch) {
                    val block = JSONObject()
                    block.put("object", "block")
                    block.put("type", "bulleted_list_item")

                    val bulletItem = JSONObject()
                    val richTextArray = JSONArray()
                    val trimmedText = note.text.trim()
                    // Notion blocks restrict single text elements to <= 2000 chars
                    val chunks = trimmedText.chunked(2000).ifEmpty { listOf(trimmedText) }
                    for (chunk in chunks) {
                        val textObj = JSONObject()
                        textObj.put("type", "text")
                        val contentObj = JSONObject()
                        contentObj.put("content", chunk)
                        textObj.put("text", contentObj)
                        richTextArray.put(textObj)
                    }

                    bulletItem.put("rich_text", richTextArray)
                    block.put("bulleted_list_item", bulletItem)
                    children.put(block)
                }

                val payload = JSONObject()
                payload.put("children", children)

                val url = URL("https://api.notion.com/v1/blocks/$targetPageId/children")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    try {
                        requestMethod = "PATCH"
                    } catch (_: java.net.ProtocolException) {
                        try {
                            val target = if (this is javax.net.ssl.HttpsURLConnection) {
                                val delegateField = this.javaClass.getDeclaredField("delegate")
                                delegateField.isAccessible = true
                                delegateField.get(this)
                            } else this
                            val methodField = HttpURLConnection::class.java.getDeclaredField("method")
                            methodField.isAccessible = true
                            methodField.set(target, "PATCH")
                        } catch (_: Throwable) {
                            requestMethod = "POST"
                            setRequestProperty("X-HTTP-Method-Override", "PATCH")
                        }
                    }
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $activeToken")
                    setRequestProperty("Notion-Version", NOTION_VERSION)
                    setRequestProperty("Content-Type", "application/json")
                }

                try {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(payload.toString())
                        writer.flush()
                    }

                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
                        Log.e(TAG, "Notion API error: $code - $err")
                        return@withContext Result.failure(Exception("Notion error ($code): $err"))
                    }
                } finally {
                    conn.disconnect()
                }
            }

            Log.i(TAG, "Successfully exported ${validNotes.size} notes to Notion page $targetPageId")
            Result.success(validNotes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export notes to Notion", e)
            Result.failure(e)
        }
    }
}
