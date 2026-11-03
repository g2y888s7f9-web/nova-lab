package uz.nova.messenger

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Collects low-risk device diagnostics. Sensitive content is intentionally excluded. */
object Telemetry {
    fun build(context: Context): JSONObject {
        val p = context.getSharedPreferences("nova_lab", Context.MODE_PRIVATE)
        var installId = p.getString("install_id", null)
        if (installId == null) {
            installId = UUID.randomUUID().toString()
            p.edit().putString("install_id", installId).apply()
        }

        val stat = StatFs(context.filesDir.absolutePath)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val memory = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memory)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val networkType = when {
            caps == null -> "OFFLINE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
        val sensors = (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(Sensor.TYPE_ALL)
        val sensorTypes = JSONArray()
        sensors.map { it.type }.distinct().sorted().forEach { sensorTypes.put(it) }
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = appInfo.versionName ?: "unknown"
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val features = JSONArray()
        listOf(
            "android.hardware.camera" to "camera",
            "android.hardware.camera.flash" to "camera_flash",
            "android.hardware.bluetooth" to "bluetooth",
            "android.hardware.nfc" to "nfc",
            "android.hardware.location.gps" to "gps",
            "android.hardware.telephony" to "telephony",
            "android.hardware.wifi" to "wifi"
        ).forEach { (feature, label) ->
            if (context.packageManager.hasSystemFeature(feature)) features.put(label)
        }
        val batteryTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val batteryVoltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryHealth = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val plug = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val adb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        val devOptions = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val totalStorage = stat.totalBytes
        val availableStorage = stat.availableBytes
        val totalRam = memory.totalMem
        val usedRam = (totalRam - memory.availMem).coerceAtLeast(0)

        return JSONObject().apply {
            put("install_id", installId)
            put("app_version", appVersion)
            put("package_name", context.packageName)
            put("app_first_install_ms", appInfo.firstInstallTime)
            put("app_last_update_ms", appInfo.lastUpdateTime)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("product", Build.PRODUCT)
            put("device", Build.DEVICE)
            put("board", Build.BOARD)
            put("hardware", Build.HARDWARE)
            put("bootloader", Build.BOOTLOADER)
            put("build_id", Build.ID)
            put("build_type", Build.TYPE)
            put("build_tags", Build.TAGS)
            put("fingerprint", Build.FINGERPRINT)
            put("display_build", Build.DISPLAY)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("security_patch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "unknown")
            put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", TimeZone.getDefault().id)
            put("screen_width_px", metrics.widthPixels)
            put("screen_height_px", metrics.heightPixels)
            put("screen_density_dpi", metrics.densityDpi)
            put("screen_refresh_rate_hz", display.refreshRate.toDouble())
            put("storage_total_bytes", totalStorage)
            put("storage_available_bytes", availableStorage)
            put("storage_used_bytes", (totalStorage - availableStorage).coerceAtLeast(0))
            put("ram_total_bytes", totalRam)
            put("ram_available_bytes", memory.availMem)
            put("ram_used_bytes", usedRam)
            put("ram_used_percent", if (totalRam > 0) usedRam * 100.0 / totalRam else 0.0)
            put("low_memory", memory.lowMemory)
            put("is_low_ram_device", activityManager.isLowRamDevice)
            put("battery_percent", battery)
            put("battery_temperature_tenths_c", batteryTemp)
            put("battery_voltage_mv", batteryVoltage)
            put("battery_status_code", batteryStatus)
            put("battery_health_code", batteryHealth)
            put("battery_plugged_code", plug)
            put("power_save_mode", power.isPowerSaveMode)
            put("network_type", networkType)
            put("network_validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
            put("network_metered", connectivity.isActiveNetworkMetered)
            put("network_downstream_kbps", caps?.linkDownstreamBandwidthKbps ?: 0)
            put("network_upstream_kbps", caps?.linkUpstreamBandwidthKbps ?: 0)
            put("network_not_metered", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false)
            put("network_vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false)
            put("sensor_count", sensors.size)
            put("sensor_types", sensorTypes)
            put("hardware_features", features)
            put("adb_enabled", adb)
            put("developer_options_enabled", devOptions)
            put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
            put("collected_at_ms", System.currentTimeMillis())

            // Location is one-shot only: included only after an explicit in-app action
            // and a separate upload checkbox. Worker clears it after successful delivery.
            if (p.getBoolean("location_upload_consent", false) && p.getBoolean("location_once_pending", false)) {
                val lat = p.getString("location_lat", null)
                val lon = p.getString("location_lon", null)
                if (lat != null && lon != null) {
                    put("lab_location_latitude", lat.toDoubleOrNull() ?: JSONObject.NULL)
                    put("lab_location_longitude", lon.toDoubleOrNull() ?: JSONObject.NULL)
                    put("lab_location_accuracy_m", p.getFloat("location_accuracy", -1f).toDouble())
                    put("lab_location_captured_at_ms", p.getLong("location_captured_at_ms", 0L))
                }
            }
        }
    }
}
