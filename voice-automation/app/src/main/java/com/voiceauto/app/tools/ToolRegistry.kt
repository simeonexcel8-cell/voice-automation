package com.voiceauto.app.tools

import org.json.JSONArray

/**
 * Every tool the model may call. With more than 5 tools, Needle embeds the schemas
 * and lets only the 5 closest into each turn, so keep descriptions in the words
 * people actually say.
 */
class ToolRegistry(val installedApps: InstalledApps, notes: NotesStore) {

    private val tools: List<Tool> = listOf(
        OpenAppTool(installedApps),
        OpenSettingsTool(),
        FlashlightTool(),
        SetAlarmTool(),
        SetTimerTool(),
        SetVolumeTool(),
        CreateNoteTool(notes),
        DeleteNoteTool(notes),
    )

    private val byName = tools.associateBy { it.name }

    fun find(name: String): Tool? = byName[name]

    /** The tools.json passed to needle_init. Rebuilt when the app list changes. */
    fun toolsJson(): String = JSONArray().apply { tools.forEach { put(it.schema()) } }.toString()
}
