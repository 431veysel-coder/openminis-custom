package com.openminis.app.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * [MINIS-OZEL] Why a background turn dies — the permissions that decide it.
 *
 * A task that runs fine with the screen on and stalls with it off is almost
 * never a bug in the agent loop: it is one of five OS gates. Without this the
 * only way to tell them apart was to guess, or to read a log after the fact.
 *
 * Every check here reads live OS state (no cached copy), because the user can
 * revoke any of them from Settings while the app runs.
 */
data class BackgroundGate(
    /** Stable id, used as the row key and for the fix intent. */
    val id: String,
    /** Title resource. */
    val titleRes: Int,
    /** One line explaining what breaks when this gate is shut. */
    val detailRes: Int,
    /** True when nothing is blocking the app. */
    val granted: Boolean,
    /** Where to send the user to fix it; null when there is no such screen. */
    val fixIntent: Intent?,
)

object BackgroundGates {

    /** All five gates, most impactful first. */
    fun collect(context: Context): List<BackgroundGate> {
        val out = mutableListOf<BackgroundGate>()

        // 1. Battery optimisation. Doze freezes the process; this is the single
        //    biggest cause of "it stopped while the screen was off".
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringDoze = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        out += BackgroundGate(
            id = "doze",
            titleRes = com.openminis.app.R.string.gate_doze_title,
            detailRes = com.openminis.app.R.string.gate_doze_detail,
            granted = ignoringDoze,
            fixIntent = if (ignoringDoze) {
                null
            } else {
                runCatching {
                    Intent(
                        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                        Uri.parse("package:${context.packageName}"),
                    )
                }.getOrNull()
            },
        )

        // 2. Background restriction (Android 12+). Independent of Doze: the user
        //    can restrict an app without touching battery optimisation, and then
        //    even an exempted app may not start work.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val restricted = am?.isBackgroundRestricted == true
        out += BackgroundGate(
            id = "bg_restricted",
            titleRes = com.openminis.app.R.string.gate_bg_restricted_title,
            detailRes = com.openminis.app.R.string.gate_bg_restricted_detail,
            granted = !restricted,
            fixIntent = if (restricted) {
                appDetails(context)
            } else {
                null
            },
        )

        // 3. Exact alarms (Android 12+). Without it scheduled tasks still fire,
        //    but the OS may add up to an hour of drift.
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarms?.canScheduleExactAlarms() == true
        } else {
            true
        }
        out += BackgroundGate(
            id = "exact_alarm",
            titleRes = com.openminis.app.R.string.gate_exact_alarm_title,
            detailRes = com.openminis.app.R.string.gate_exact_alarm_detail,
            granted = canExact,
            fixIntent = if (!canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
                    .getOrNull()
            } else {
                null
            },
        )

        // 4. Notifications. Not needed to run, but without it a finished
        //    background task has no way to tell the user it is done.
        val notif = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        out += BackgroundGate(
            id = "notifications",
            titleRes = com.openminis.app.R.string.gate_notifications_title,
            detailRes = com.openminis.app.R.string.gate_notifications_detail,
            granted = notif,
            fixIntent = if (notif) null else appDetails(context),
        )

        // 5. Data saver caps background network, which shows up as a provider
        //    timeout rather than a permission error.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val dataSaver = cm?.restrictBackgroundStatus ==
            android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        out += BackgroundGate(
            id = "data_saver",
            titleRes = com.openminis.app.R.string.gate_data_saver_title,
            detailRes = com.openminis.app.R.string.gate_data_saver_detail,
            granted = !dataSaver,
            fixIntent = if (dataSaver) {
                runCatching { Intent(android.provider.Settings.ACTION_DATA_SAVER_SETTINGS) }.getOrNull()
            } else {
                null
            },
        )

        return out
    }

    /** App info screen — the only place several of these toggles live. */
    private fun appDetails(context: Context): Intent? = runCatching {
        Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }.getOrNull()
}
