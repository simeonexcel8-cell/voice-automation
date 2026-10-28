package com.voiceauto.app.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONObject
import kotlin.math.roundToInt

private fun startActivity(context: Context, intent: Intent, done: String): ToolResult = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    ToolResult.Done(done)
} catch (e: ActivityNotFoundException) {
    ToolResult.Failed("No app on this phone can handle that.")
} catch (e: SecurityException) {
    ToolResult.Failed("Android blocked that action.")
}

/** open_app — the app list is an enum, so Needle can only name installed apps. */
class OpenAppTool(private val installed: InstalledApps) : Tool {
    override val name = "open_app"
    override val risk = Risk.SAFE

    override fun schema(): JSONObject {
        val labels = installed.apps.keys.toList()
        // Enums are compiled into Needle's decoding grammar. Past ~150 names, fall
        // back to free text and let find() match it.
        val app = if (labels.size in 1..MAX_ENUM) {
            Schema.string("the app the user named", enum = labels)
        } else {
            Schema.string("the app the user named, as they said it", maxLength = 60)
        }
        return Schema.tool(
            name, "Open, launch or start an app on this phone.",
            Schema.params("app" to app, required = listOf("app")),
        )
    }

    override fun describe(args: JSONObject) = "Open ${args.optString("app")}"

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val (label, pkg) = installed.find(args.optString("app"))
            ?: return ToolResult.Failed("I couldn't find “${args.optString("app")}” on this phone.")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult.Failed("$label can't be opened directly.")
        return startActivity(context, intent, "Opening $label")
    }

    private companion object { const val MAX_ENUM = 150 }
}

/** open_settings_page — Wi-Fi and mobile data open Android's quick panels. */
class OpenSettingsTool : Tool {
    override val name = "open_settings_page"
    override val risk = Risk.SAFE

    private val pages = linkedMapOf(
        "settings" to Settings.ACTION_SETTINGS,
        "wifi" to Settings.Panel.ACTION_WIFI,
        "mobile data" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
    )

    override fun schema() = Schema.tool(
        name,
        "Open a settings page, such as wifi, mobile data, bluetooth, display, sound, battery, " +
            "location, apps or storage. Use this to turn wifi, data or bluetooth on or off.",
        Schema.params(
            "page" to Schema.string("which settings page", enum = pages.keys.toList()),
            required = listOf("page"),
        ),
    )

    override fun describe(args: JSONObject) = "Open ${args.optString("page")} settings"

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val page = args.optString("page")
        val action = pages[page] ?: return ToolResult.Failed("I don't know the “$page” page.")
        val result = startActivity(context, Intent(action), "Opening $page settings")
        // Some phones lack a page; fall back to the main Settings screen.
        return if (result is ToolResult.Failed) {
            startActivity(context, Intent(Settings.ACTION_SETTINGS), "Opening Settings")
        } else {
            result
        }
    }
}

/** set_flashlight — no permission needed for the torch. */
class FlashlightTool : Tool {
    override val name = "set_flashlight"
    override val risk = Risk.SAFE

    override fun schema() = Schema.tool(
        name, "Turn the flashlight (torch) on or off.",
        Schema.params("on" to Schema.boolean("true to turn it on, false to turn it off"), required = listOf("on")),
    )

    override fun describe(args: JSONObject) = if (args.optBoolean("on")) "Turn the torch on" else "Turn the torch off"

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val on = args.optBoolean("on")
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult.Failed("This phone has no flashlight.")
        return try {
            cameras.setTorchMode(id, on)
            ToolResult.Done(if (on) "Torch on" else "Torch off") {
                cameras.setTorchMode(id, !on)
                if (on) "Torch off" else "Torch on"
            }
        } catch (e: Exception) {
            ToolResult.Failed("The camera is in use, so the torch can't change.")
        }
    }
}

/** set_alarm — handed to the phone's clock app; it keeps working with our app closed. */
class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val risk = Risk.MEDIUM

    override fun schema() = Schema.tool(
        name, "Set an alarm for a time of day.",
        Schema.params(
            "time" to Schema.string(
                "24-hour time as HH:MM, e.g. 06:30 for 6:30 am or 19:00 for 7 pm",
                pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$",
            ),
            "label" to Schema.string("what the alarm is for, if the user said", maxLength = 40),
            required = listOf("time"),
        ),
    )

    override fun describe(args: JSONObject) = "Set an alarm for ${args.optString("time")}"

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val parts = args.optString("time").split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2 || parts[0] !in 0..23 || parts[1] !in 0..59) {
            return ToolResult.Failed("That time doesn't look right.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, parts[0])
            .putExtra(AlarmClock.EXTRA_MINUTES, parts[1])
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return startActivity(context, intent, "Alarm set for ${args.optString("time")}")
    }
}

/** set_timer — minutes and seconds both default to 0, so "30 seconds" still works. */
class SetTimerTool : Tool {
    override val name = "set_timer"
    override val risk = Risk.MEDIUM

    override fun schema() = Schema.tool(
        name, "Start a countdown timer.",
        Schema.params(
            "minutes" to Schema.integer("number of minutes", 0, 600, default = 0),
            "seconds" to Schema.integer("number of seconds", 0, 3599, default = 0),
        ),
    )

    override fun describe(args: JSONObject) = "Start a timer for ${format(args)}"

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val total = args.optInt("minutes") * 60 + args.optInt("seconds")
        if (total <= 0) return ToolResult.Failed("How long should the timer be?")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return startActivity(context, intent, "Timer set for ${format(args)}")
    }

    private fun format(args: JSONObject): String {
        val m = args.optInt("minutes")
        val s = args.optInt("seconds")
        return listOfNotNull(
            m.takeIf { it > 0 }?.let { "$it min" },
            s.takeIf { it > 0 }?.let { "$it s" },
        ).joinToString(" ").ifEmpty { "0 s" }
    }
}

/** set_volume — a level 0–100, mapped to the stream's own steps. */
class SetVolumeTool : Tool {
    override val name = "set_volume"
    override val risk = Risk.MEDIUM

    private val streams = linkedMapOf(
        "media" to AudioManager.STREAM_MUSIC,
        "ringtone" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
    )

    override fun schema() = Schema.tool(
        name, "Set the volume to a level from 0 to 100.",
        Schema.params(
            "level" to Schema.integer("volume level from 0 to 100", 0, 100),
            "stream" to JSONObject()
                .put("type", "string")
                .put("description", "which volume: media, ringtone or alarm")
                .put("enum", org.json.JSONArray(streams.keys.toList()))
                .put("default", "media"),
            required = listOf("level"),
        ),
    )

    override fun describe(args: JSONObject) =
        "Set ${args.optString("stream", "media")} volume to ${args.optInt("level")}%"

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val streamName = args.optString("stream").ifBlank { "media" }
        val stream = streams[streamName] ?: return ToolResult.Failed("I don't know the “$streamName” volume.")
        val level = args.optInt("level", -1)
        if (level !in 0..100) return ToolResult.Failed("Volume must be between 0 and 100.")

        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(stream)
        val before = audio.getStreamVolume(stream)
        return try {
            audio.setStreamVolume(stream, (level / 100.0 * max).roundToInt(), AudioManager.FLAG_SHOW_UI)
            ToolResult.Done("$streamName volume set to $level%") {
                audio.setStreamVolume(stream, before, AudioManager.FLAG_SHOW_UI)
                "$streamName volume restored"
            }
        } catch (e: SecurityException) {
            // Changing the ringer while Do Not Disturb is on needs extra access.
            ToolResult.Failed("Android blocked that while Do Not Disturb is on.")
        }
    }
}
