package com.voiceauto.app.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Note(val id: Long, val title: String, val body: String)

/**
 * The app's own notes, kept in a small JSON file in private storage.
 * (The architecture moves this to Room together with automations.)
 */
class NotesStore(context: Context) {
    private val file = File(context.filesDir, "notes.json")
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    @Synchronized
    fun add(title: String, body: String): Note {
        val note = Note(System.currentTimeMillis(), title, body)
        save(_notes.value + note)
        return note
    }

    @Synchronized
    fun restore(note: Note) = save((_notes.value + note).sortedBy { it.id })

    @Synchronized
    fun remove(id: Long) = save(_notes.value.filterNot { it.id == id })

    /** Matches the words the user said against note titles. */
    fun find(query: String): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        _notes.value.filter { it.title.equals(q, ignoreCase = true) }.let { if (it.isNotEmpty()) return it }
        return _notes.value.filter { it.title.contains(q, ignoreCase = true) }
    }

    private fun save(list: List<Note>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body)) }
        val tmp = File(file.parentFile, "notes.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
        _notes.value = list
    }

    private fun load(): List<Note> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Note(o.getLong("id"), o.getString("title"), o.optString("body"))
        }
    }.getOrDefault(emptyList())
}

/** create_note — the note text is copied verbatim from what the user said. */
class CreateNoteTool(private val store: NotesStore) : Tool {
    override val name = "create_note"
    override val risk = Risk.MEDIUM

    override fun schema() = Schema.tool(
        name, "Write down a new note.",
        Schema.params(
            "text" to Schema.string("the words to write down, exactly as said", maxLength = 500),
            "title" to Schema.string("a title, only if the user gave one", maxLength = 60),
            required = listOf("text"),
        ),
    )

    override fun describe(args: JSONObject) = "Save a note: “${args.optString("text")}”"

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return ToolResult.Failed("What should the note say?")
        val title = args.optString("title").trim().ifEmpty { text.split(" ").take(5).joinToString(" ") }
        val note = store.add(title, text)
        return ToolResult.Done("Note saved: $title") {
            store.remove(note.id)
            "Note removed"
        }
    }
}

/**
 * delete_note — DESTRUCTIVE, so the policy engine always asks first.
 * If several notes match, nothing is deleted and the user is asked to be specific.
 */
class DeleteNoteTool(private val store: NotesStore) : Tool {
    override val name = "delete_note"
    override val risk = Risk.DESTRUCTIVE

    override fun schema() = Schema.tool(
        name, "Delete or remove one of my notes by its title.",
        Schema.params(
            "title" to Schema.string("the title of the note to delete, as said", maxLength = 60),
            required = listOf("title"),
        ),
    )

    override fun describe(args: JSONObject): String {
        val matches = store.find(args.optString("title"))
        return when (matches.size) {
            0 -> "Delete a note called “${args.optString("title")}” (no such note found)"
            1 -> "Delete the note “${matches[0].title}”"
            else -> "Delete a note — ${matches.size} notes match “${args.optString("title")}”"
        }
    }

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val matches = store.find(args.optString("title"))
        return when (matches.size) {
            0 -> ToolResult.Failed("No note is called “${args.optString("title")}”.")
            1 -> {
                val note = matches[0]
                store.remove(note.id)
                ToolResult.Done("Deleted “${note.title}”") {
                    store.restore(note)
                    "Note restored"
                }
            }
            else -> ToolResult.Failed("${matches.size} notes match — say the full title.")
        }
    }
}
