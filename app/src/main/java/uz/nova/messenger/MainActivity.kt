package uz.nova.messenger

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("nova_lab", MODE_PRIVATE) }
    private lateinit var server: EditText
    private lateinit var token: EditText
    private lateinit var consent: CheckBox
    private lateinit var locationConsent: CheckBox
    private var pendingLocationListener: LocationListener? = null
    private lateinit var status: TextView
    private lateinit var preview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        server = findViewById(R.id.edtServer)
        token = findViewById(R.id.edtToken)
        consent = findViewById(R.id.chkConsent)
        locationConsent = findViewById(R.id.chkLocationUpload)
        status = findViewById(R.id.txtStatus)
        preview = findViewById(R.id.txtPreview)
        server.setText(prefs.getString("server", ""))
        token.setText(prefs.getString("token", ""))
        consent.isChecked = prefs.getBoolean("consent", false)
        locationConsent.isChecked = prefs.getBoolean("location_upload_consent", false)
        renderDeviceSummary()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
            status.text = "Configuration saved"
            appendEvent("Cloud configuration saved")
        }
        findViewById<Button>(R.id.btnSend).setOnClickListener {
            saveSettings()
            if (!consent.isChecked) { status.text = "Please confirm consent first"; return@setOnClickListener }
            if (!validConfig()) { status.text = "HTTPS URL and lab token are required"; return@setOnClickListener }
            val req = OneTimeWorkRequestBuilder<TelemetryWorker>().build()
            WorkManager.getInstance(this).enqueueUniqueWork("nova-manual-send", ExistingWorkPolicy.REPLACE, req)
            status.text = "Telemetry queued. Android will run when network is available."
            appendEvent("Manual sync queued")
            preview.text = Telemetry.build(this).toString(2)
            observeWork(req.id.toString())
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            consent.isChecked = false
            locationConsent.isChecked = false
            prefs.edit().remove("location_lat").remove("location_lon").remove("location_accuracy")
                .remove("location_captured_at_ms").putBoolean("location_once_pending", false).apply()
            saveSettings()
            WorkManager.getInstance(this).cancelUniqueWork("nova-periodic-sync")
            WorkManager.getInstance(this).cancelUniqueWork("nova-manual-send")
            status.text = "Consent revoked. Scheduled and queued sync cancelled."
            appendEvent("Consent revoked; sync stopped")
        }
        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            preview.text = Telemetry.build(this).toString(2)
            appendEvent("Local telemetry preview generated")
        }
        findViewById<Button>(R.id.btnPermissions).setOnClickListener { showSpecialAccessAudit() }
        findViewById<Button>(R.id.btnCommsLab).setOnClickListener { showSyntheticCommunications() }
        findViewById<Button>(R.id.btnCaptureLocation).setOnClickListener { captureLocationOnce() }

        if (consent.isChecked && validConfig()) schedulePeriodic()
    }

    private fun renderDeviceSummary() {
        val t = Telemetry.build(this)
        findViewById<TextView>(R.id.txtDevice).text = "${t.optString("manufacturer")} ${t.optString("model")}".trim()
        findViewById<TextView>(R.id.txtDeviceSub).text = "Android ${t.optString("android_version")} • SDK ${t.optInt("sdk_int")} • ${t.optString("network_type")}" 
        findViewById<TextView>(R.id.txtBattery).text = if (t.optInt("battery_percent", -1) >= 0) "${t.optInt("battery_percent")}%" else "N/A"
        val freeGb = t.optLong("storage_available_bytes") / (1024.0 * 1024.0 * 1024.0)
        findViewById<TextView>(R.id.txtStorage).text = String.format("%.1f GB", freeGb)
    }

    private fun validConfig(): Boolean = server.text.toString().trim().startsWith("https://") && token.text.isNotBlank()

    private fun saveSettings() {
        prefs.edit()
            .putString("server", server.text.toString().trim().trimEnd('/'))
            .putString("token", token.text.toString().trim())
            .putBoolean("consent", consent.isChecked)
            .putBoolean("location_upload_consent", locationConsent.isChecked)
            .apply()
        if (!locationConsent.isChecked) {
            prefs.edit().remove("location_lat").remove("location_lon").remove("location_accuracy")
                .remove("location_captured_at_ms").putBoolean("location_once_pending", false).apply()
        }
        if (consent.isChecked && validConfig()) schedulePeriodic()
        else WorkManager.getInstance(this).cancelUniqueWork("nova-periodic-sync")
    }

    private fun schedulePeriodic() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = PeriodicWorkRequestBuilder<TelemetryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "nova-periodic-sync", ExistingPeriodicWorkPolicy.UPDATE, req
        )
        status.text = "Consent active • sync scheduled when connected (Android controls exact timing)"
    }

    private fun observeWork(id: String) {
        val uuid = java.util.UUID.fromString(id)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(uuid).observe(this) { info ->
            if (info != null && info.state.isFinished) {
                val ok = info.state == WorkInfo.State.SUCCEEDED
                status.text = if (ok) "Cloud accepted telemetry" else "Sync not completed: ${info.state}"
                appendEvent(if (ok) "Cloud accepted telemetry" else "Sync ended: ${info.state}")
            }
        }
    }


    private fun captureLocationOnce() {
        if (!consent.isChecked) {
            status.text = "Enable general telemetry consent first"
            return
        }
        if (!locationConsent.isChecked) {
            status.text = "Separate location upload consent is required"
            return
        }
        if (!validConfig()) {
            status.text = "HTTPS URL and lab token are required"
            return
        }
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 410)
            return
        }
        requestOneLocationFix(fine)
    }

    private fun requestOneLocationFix(fineGranted: Boolean) {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = if (fineGranted && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            LocationManager.NETWORK_PROVIDER
        } else {
            status.text = "No location provider is enabled. Turn on Location and try again."
            return
        }
        status.text = "Requesting one location sample… keep Nova open"
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try { manager.removeUpdates(this) } catch (_: Exception) {}
                pendingLocationListener = null
                prefs.edit()
                    .putString("location_lat", location.latitude.toString())
                    .putString("location_lon", location.longitude.toString())
                    .putFloat("location_accuracy", location.accuracy)
                    .putLong("location_captured_at_ms", location.time)
                    .putBoolean("location_once_pending", true).apply()
                val req = OneTimeWorkRequestBuilder<TelemetryWorker>().build()
                WorkManager.getInstance(this@MainActivity).enqueueUniqueWork("nova-manual-send", ExistingWorkPolicy.REPLACE, req)
                status.text = "Location sample captured. One-time upload queued."
                preview.text = "LOCATION LAB — ONE-TIME SAMPLE\n\n" +
                    "Latitude: ${location.latitude}\nLongitude: ${location.longitude}\n" +
                    "Accuracy: ${location.accuracy} m\nCaptured: ${java.util.Date(location.time)}\n\n" +
                    "This sample is sent once to the configured lab server and cleared after successful delivery."
                appendEvent("User-triggered one-time location sample queued")
                observeWork(req.id.toString())
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                status.text = "Location provider disabled"
            }
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, statusCode: Int, extras: Bundle?) {}
        }
        pendingLocationListener = listener
        try {
            @Suppress("MissingPermission")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            pendingLocationListener = null
            status.text = "Could not request location: ${e.javaClass.simpleName}"
        }
    }

    @Deprecated("Permission callback for one-time location capture")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 410) {
            val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) requestOneLocationFix(fine)
            else status.text = "Location permission denied; no location was collected."
        }
    }

    override fun onDestroy() {
        pendingLocationListener?.let {
            try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } catch (_: Exception) {}
        }
        pendingLocationListener = null
        super.onDestroy()
    }

    private fun showSpecialAccessAudit() {
        val overlay = Settings.canDrawOverlays(this)
        val usage = try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
        val report = "SPECIAL ACCESS AUDIT\n\n" +
            "Display over other apps: ${if (overlay) "GRANTED" else "NOT GRANTED"}\n" +
            "Usage access: ${if (usage) "GRANTED" else "NOT GRANTED"}\n" +
            "Accessibility service: NOT IMPLEMENTED\n" +
            "Notification listener: NOT IMPLEMENTED\n" +
            "Location permission: ${if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "NOT GRANTED"}\n" +
            "Location mode: one-time, user-triggered only\n" +
            "Device administrator: NOT REQUESTED\n\n" +
            "Nova only reports permission status. It does not read notifications, capture screens, monitor other apps or execute accessibility actions."
        preview.text = report
        appendEvent("Special access status audit viewed")
        AlertDialogCompat.show(this, "Permission audit", report,
            "Open app settings" to { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
            "Usage access settings" to { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
    }

    private fun showSyntheticCommunications() {
        val samples = JSONArray()
        samples.put(JSONObject().put("kind", "SMS_SAMPLE").put("direction", "INBOUND")
            .put("sender", "+998 ** *** ** 01").put("body", "[SYNTHETIC] Lab test message A").put("timestamp", "2026-11-03T10:15:00Z"))
        samples.put(JSONObject().put("kind", "CALL_SAMPLE").put("direction", "OUTGOING")
            .put("number", "+998 ** *** ** 02").put("duration_seconds", 42).put("timestamp", "2026-11-03T10:18:00Z"))
        preview.text = "COMMUNICATIONS LAB — SYNTHETIC ONLY\n\n" + samples.toString(2) +
            "\n\nThese records are generated locally for the mentor demo. They are not read from the device and are never uploaded."
        appendEvent("Synthetic communications dataset generated locally")
    }

    private fun appendEvent(message: String) {
        val old = prefs.getString("events", "") ?: ""
        val updated = ("• ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $message\n" + old).take(2500)
        prefs.edit().putString("events", updated).apply()
        if (preview.text.isNullOrBlank()) preview.text = updated
    }
}

private object AlertDialogCompat {
    fun show(context: Context, title: String, message: String, first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
        android.app.AlertDialog.Builder(context).setTitle(title).setMessage(message)
            .setPositiveButton(first.first) { _, _ -> first.second() }
            .setNegativeButton(second.first) { _, _ -> second.second() }
            .setNeutralButton("Close", null).show()
    }
}
