package pt.artur.navexpress

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Straight lines from the bike to chosen points (a peak, a fuel station, the camp): drawn as the crow
 * flies, with the distance and direction kept up to date as the bike moves. Kept until removed.
 */
object DirectLines {
    private val _list = MutableStateFlow<List<Place>>(emptyList())
    val list: StateFlow<List<Place>> = _list.asStateFlow()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "direct_lines.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _list.value = runCatching {
            val a = JSONArray(file(ctx).readText())
            List(a.length()) { i -> a.getJSONObject(i).let { Place(it.getString("id"), it.optString("name"), it.getDouble("lat"), it.getDouble("lon")) } }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context) {
        val a = JSONArray(_list.value.map { p -> JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lon", p.lon) })
        runCatching { file(ctx).writeText(a.toString()) }
    }

    fun add(ctx: Context, at: LatLon, name: String = "") {
        load(ctx)
        _list.update { it + Place(System.currentTimeMillis().toString(), name, at.lat, at.lon) }
        save(ctx)
    }

    fun delete(ctx: Context, id: String) {
        _list.update { l -> l.filter { it.id != id } }
        save(ctx)
    }

    fun clear(ctx: Context) {
        _list.value = emptyList()
        save(ctx)
    }

}
