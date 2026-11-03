package uz.nova.messenger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TelemetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("nova_lab", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("consent", false)) return Result.success()
        val base = prefs.getString("server", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (!base.startsWith("https://") || token.isBlank()) return Result.failure()
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("${base.trimEnd('/')}/api/v1/telemetry")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val body = JSONObject().put("telemetry", Telemetry.build(applicationContext)).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    // A location sample is one-shot. Clear it only after the server accepts it.
                    if (prefs.getBoolean("location_once_pending", false)) {
                        prefs.edit().remove("location_lat").remove("location_lon")
                            .remove("location_accuracy").remove("location_captured_at_ms")
                            .putBoolean("location_once_pending", false).apply()
                    }
                    Result.success()
                }
                408, 425, 429 -> Result.retry()
                in 500..599 -> Result.retry()
                else -> Result.failure()
            }
        } catch (_: Exception) {
            Result.retry()
        } finally {
            connection?.disconnect()
        }
    }
}
