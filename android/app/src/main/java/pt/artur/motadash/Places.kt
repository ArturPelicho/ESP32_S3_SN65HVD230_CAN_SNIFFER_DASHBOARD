package pt.artur.motadash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A favourite place (home, work, the café...), shown on the map and one tap from a route. */
data class Place(val id: String, val name: String, val lat: Double, val lon: Double)

object Places {
    private val _list = MutableStateFlow<List<Place>>(emptyList())
    val list: StateFlow<List<Place>> = _list.asStateFlow()
    private var loaded = false
    /** Where the bike was at the last GPS fix on the map, for "go there" outside the map. */
    @Volatile var here: LatLon? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "places.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _list.value = runCatching {
            val a = JSONArray(file(ctx).readText())
            List(a.length()) { i -> a.getJSONObject(i).let { Place(it.getString("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon")) } }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context) {
        val a = JSONArray(_list.value.map { p -> JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lon", p.lon) })
        runCatching { file(ctx).writeText(a.toString()) }
    }

    fun add(ctx: Context, name: String, at: LatLon): Place {
        load(ctx)
        val p = Place(System.currentTimeMillis().toString(), name, at.lat, at.lon)
        _list.update { it + p }
        save(ctx)
        return p
    }

    fun rename(ctx: Context, id: String, name: String) {
        _list.update { l -> l.map { if (it.id == id) it.copy(name = name) else it } }
        save(ctx)
    }

    fun delete(ctx: Context, id: String) {
        _list.update { l -> l.filter { it.id != id } }
        save(ctx)
    }
}
