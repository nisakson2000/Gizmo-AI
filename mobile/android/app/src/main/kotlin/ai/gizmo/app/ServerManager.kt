package ai.gizmo.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ServerManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gizmo_servers", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    fun getServers(): List<Server> {
        val json = prefs.getString("server_list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Server(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    isDefault = obj.optBoolean("isDefault", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getServer(id: String): Server? = getServers().find { it.id == id }

    fun getDefault(): Server? {
        val servers = getServers()
        return servers.find { it.isDefault } ?: servers.firstOrNull()
    }

    fun addServer(server: Server) {
        val servers = getServers().toMutableList()
        val newServer = if (servers.isEmpty()) server.copy(isDefault = true) else server
        servers.add(newServer)
        save(servers)
    }

    fun updateServer(id: String, name: String, url: String) {
        val servers = getServers().map {
            if (it.id == id) it.copy(name = name, url = url) else it
        }
        save(servers)
    }

    fun deleteServer(id: String) {
        val servers = getServers().toMutableList()
        val deleted = servers.find { it.id == id }
        servers.removeAll { it.id == id }
        if (deleted?.isDefault == true && servers.isNotEmpty()) {
            servers[0] = servers[0].copy(isDefault = true)
        }
        save(servers)
    }

    fun setDefault(id: String) {
        val servers = getServers().map {
            it.copy(isDefault = it.id == id)
        }
        save(servers)
    }

    fun isFirstLaunch(): Boolean = !prefs.getBoolean("first_launch_done", false)

    fun setFirstLaunchDone() {
        prefs.edit().putBoolean("first_launch_done", true).apply()
    }

    fun importDefaults(): Boolean {
        return try {
            val json = appContext.assets.open("gizmo-defaults.json").bufferedReader().readText()
            val root = JSONObject(json)
            val arr = root.getJSONArray("servers")
            val servers = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Server(
                    name = obj.getString("name"),
                    url = obj.getString("url").trimEnd('/'),
                    isDefault = i == 0
                )
            }
            if (servers.isEmpty()) return false
            save(servers)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun save(servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("url", s.url)
                put("isDefault", s.isDefault)
            })
        }
        prefs.edit().putString("server_list", arr.toString()).apply()
    }
}
