package com.sg.linuxgo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SnippetEntity(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val command: String,
    val variablesString: String = ""
)

object SnippetStore {
    private const val PREFS_NAME = "PocketLinuxSnippetPrefs"
    private const val PREF_SNIPPETS = "snippets_v1"

    fun defaultSnippets(): List<SnippetEntity> = listOf(
        SnippetEntity("snip_apt", "Update Packages", "apt update && apt upgrade -y"),
        SnippetEntity("snip_htop", "System Status", "htop"),
        SnippetEntity("snip_df", "Disk Usage", "df -h"),
        SnippetEntity("snip_ls", "List Files", "ls -la"),
        SnippetEntity("snip_pyhttp", "Python HTTP Server", "python3 -m http.server {{port}}", "port"),
        SnippetEntity("snip_ip", "Public IP", "curl -s ifconfig.me"),
        SnippetEntity("snip_pstree", "Process Tree", "pstree -p")
    )

    fun loadSnippets(context: Context): List<SnippetEntity> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_SNIPPETS, null) ?: return defaultSnippets().also { saveSnippets(context, it) }
        return try {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return defaultSnippets()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        SnippetEntity(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            title = obj.optString("title"),
                            command = obj.optString("command"),
                            variablesString = obj.optString("variablesString")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            defaultSnippets()
        }
    }

    fun saveSnippets(context: Context, snippets: List<SnippetEntity>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (s in snippets) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("command", s.command)
                put("variablesString", s.variablesString)
            })
        }
        prefs.edit().putString(PREF_SNIPPETS, arr.toString()).apply()
    }

    fun addSnippet(context: Context, title: String, command: String, variables: String): SnippetEntity {
        val list = loadSnippets(context).toMutableList()
        val newSnippet = SnippetEntity(
            id = "snip_${UUID.randomUUID()}",
            title = title,
            command = command,
            variablesString = variables
        )
        list.add(0, newSnippet)
        saveSnippets(context, list)
        return newSnippet
    }

    fun deleteSnippet(context: Context, id: String) {
        val list = loadSnippets(context).filter { it.id != id }
        saveSnippets(context, list)
    }
}
