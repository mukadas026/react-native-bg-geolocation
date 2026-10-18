package com.bggeolocation.runtime

import com.bggeolocation.core.Fix
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class HttpUploader {
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "bggeo-upload").apply { isDaemon = true }
  }

  fun post(url: String, headers: Map<String, String>, fixes: List<Fix>, onResult: (Int) -> Unit) {
    executor.execute { onResult(send(url, headers, fixes)) }
  }

  private fun send(url: String, headers: Map<String, String>, fixes: List<Fix>): Int {
    var connection: HttpURLConnection? = null
    return try {
      connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 30_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        headers.forEach { (key, value) -> setRequestProperty(key, value) }
      }
      connection.outputStream.use { it.write(payload(fixes).toByteArray()) }
      val status = connection.responseCode
      runCatching { connection.inputStream.use { it.readBytes() } }
      status
    } catch (_: Exception) {
      0
    } finally {
      connection?.disconnect()
    }
  }

  companion object {
    fun payload(fixes: List<Fix>): String {
      val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
      val locations = JSONArray()
      fixes.forEach { fix ->
        locations.put(
          JSONObject().apply {
            put("uuid", fix.id)
            put("timestamp", format.format(Date(fix.timestamp)))
            put(
              "coords",
              JSONObject().apply {
                put("latitude", fix.lat)
                put("longitude", fix.lon)
                fix.accuracy?.let { put("accuracy", it.toDouble()) }
                fix.speed?.let { put("speed", it.toDouble()) }
                fix.bearing?.let { put("heading", it.toDouble()) }
                fix.altitude?.let { put("altitude", it) }
              }
            )
          }
        )
      }
      return JSONObject().put("location", locations).toString()
    }
  }
}
