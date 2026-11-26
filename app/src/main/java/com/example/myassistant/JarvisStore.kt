package com.example.myassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything Jarvis keeps: memories, protocols and plugins.
 * Used by both the app screens and the background service (always re-read, never cached).
 */
object JarvisStore {

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    // ================================================================ memories

    data class Memory(val label: String, val value: String)

    fun memories(c: Context): MutableList<Memory> {
        val out = mutableListOf<Memory>()
        try {
            val arr = JSONArray(prefs(c).getString("memories", null) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Memory(o.optString("l"), o.optString("v")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveMemories(c: Context, list: List<Memory>) {
        val arr = JSONArray()
        for (m in list) arr.put(JSONObject().put("l", m.label).put("v", m.value))
        prefs(c).edit().putString("memories", arr.toString()).apply()
    }

    fun addMemory(c: Context, label: String, value: String) {
        val clean = label.trim()
        if (clean.isEmpty()) return
        val list = memories(c)
        val idx = list.indexOfFirst { it.label.equals(clean, ignoreCase = true) }
        if (idx >= 0) list[idx] = Memory(clean, value.trim()) else list.add(Memory(clean, value.trim()))
        saveMemories(c, list)
    }

    fun deleteMemory(c: Context, index: Int) {
        val list = memories(c)
        if (index in list.indices) {
            list.removeAt(index)
            saveMemories(c, list)
        }
    }

    fun deleteMemoryByLabel(c: Context, label: String) {
        val list = memories(c)
        list.removeAll { it.label.equals(label, ignoreCase = true) }
        saveMemories(c, list)
    }

    private val fillerWords = setOf(
        "do", "you", "remember", "that", "the", "my", "what", "is", "was", "are", "a", "an",
        "about", "tell", "me", "did", "i", "say", "of", "your", "it", "s", "recall", "forget",
        "jarvis", "friday", "please"
    )

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in fillerWords }

    /** Finds the saved memory that best matches a spoken question like "do you remember that book name". */
    fun findMemory(c: Context, query: String): Memory? {
        val q = tokens(query).toSet()
        if (q.isEmpty()) return null
        var best: Memory? = null
        var bestScore = 0
        for (m in memories(c)) {
            val labelTokens = tokens(m.label).toSet()
            val allTokens = labelTokens + tokens(m.value)
            val score = q.count { it in labelTokens } * 2 + q.count { it in allTokens }
            if (score > bestScore) {
                bestScore = score
                best = m
            }
        }
        return best
    }

    // ================================================================ protocols

    data class Protocol(val name: String, val steps: String)

    fun cleanName(name: String): String =
        name.lowercase().trim().removeSuffix("protocol").trim()

    fun protocols(c: Context): MutableList<Protocol> {
        val raw = prefs(c).getString("protocols", null)
        if (raw == null) {
            val defaults = mutableListOf(
                Protocol(
                    "work",
                    "# Add your work website below (remove the # sign)\n" +
                            "# open https://your-website.com\n" +
                            "stealth mode"
                ),
                Protocol("chill", "normal mode\nplay chill lofi music")
            )
            saveProtocols(c, defaults)
            return defaults
        }
        val out = mutableListOf<Protocol>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Protocol(o.optString("n"), o.optString("s")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveProtocols(c: Context, list: List<Protocol>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("n", p.name).put("s", p.steps))
        prefs(c).edit().putString("protocols", arr.toString()).apply()
    }

    fun saveProtocol(c: Context, name: String, steps: String) {
        val n = cleanName(name)
        if (n.isEmpty()) return
        val list = protocols(c)
        val idx = list.indexOfFirst { it.name == n }
        if (idx >= 0) list[idx] = Protocol(n, steps) else list.add(Protocol(n, steps))
        saveProtocols(c, list)
    }

    fun deleteProtocol(c: Context, name: String) {
        val list = protocols(c)
        list.removeAll { it.name == cleanName(name) }
        saveProtocols(c, list)
    }

    fun findProtocol(c: Context, name: String): Protocol? {
        val n = cleanName(name).removePrefix("the ").trim()
        return protocols(c).firstOrNull { it.name == n }
    }

    // ================================================================ plugins

    data class Plugin(val name: String, val code: String, val enabled: Boolean)

    fun plugins(c: Context): MutableList<Plugin> {
        val out = mutableListOf<Plugin>()
        try {
            val arr = JSONArray(prefs(c).getString("plugins", null) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Plugin(o.optString("n"), o.optString("c"), o.optBoolean("e", true)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun savePlugins(c: Context, list: List<Plugin>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("n", p.name).put("c", p.code).put("e", p.enabled))
        prefs(c).edit().putString("plugins", arr.toString()).apply()
    }

    fun addPlugin(c: Context, name: String, code: String) {
        val list = plugins(c)
        val idx = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (idx >= 0) list[idx] = Plugin(name, code, true) else list.add(Plugin(name, code, true))
        savePlugins(c, list)
    }

    fun setPluginEnabled(c: Context, name: String, enabled: Boolean) {
        val list = plugins(c)
        val idx = list.indexOfFirst { it.name == name }
        if (idx >= 0) {
            list[idx] = list[idx].copy(enabled = enabled)
            savePlugins(c, list)
        }
    }

    fun deletePlugin(c: Context, name: String) {
        val list = plugins(c)
        list.removeAll { it.name == name }
        savePlugins(c, list)
    }
}
