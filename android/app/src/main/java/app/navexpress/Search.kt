package app.navexpress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** A place found by name or address: what to call it, where it is (town, region, country) and the point. */
data class Found(val name: String, val detail: String, val at: LatLon, val mine: Boolean = false)

/**
 * Addresses and places by name, from OpenStreetMap: Photon (made for typing as you go), with
 * Nominatim when Photon does not answer. The rider's own favourite places come first, also offline.
 */
object Search {
    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    /** Languages Photon names places in; any other gets the local names. */
    private val PHOTON_LANGS = setOf("en", "de", "fr", "it")

    /** The rider's favourite places whose name holds [q]. */
    fun mine(q: String): List<Found> {
        val k = plain(q)
        if (k.isEmpty()) return emptyList()
        return Places.list.value.filter { plain(it.name).contains(k) }.map { Found(it.name, "★", LatLon(it.lat, it.lon), mine = true) }
    }

    /** Places matching [q], nearest [near] first when given; empty with no network. */
    suspend fun online(q: String, near: LatLon?, lang: String): List<Found> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(q.trim(), "UTF-8")
        runCatching { photon(enc, near, lang) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { nominatim(enc, near, lang) }.getOrDefault(emptyList())
    }

    private fun photon(enc: String, near: LatLon?, lang: String): List<Found> {
        val u = "$PHOTON?q=$enc&limit=10" +
            (near?.let { String.format(Locale.US, "&lat=%.4f&lon=%.4f", it.lat, it.lon) } ?: "") +
            (if (lang in PHOTON_LANGS) "&lang=$lang" else "")
        val fs = JSONObject(Weather.get(u)).getJSONArray("features")
        return (0 until fs.length()).mapNotNull { i ->
            val f = fs.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() }
            val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ").ifBlank { null }
            val name = s("name") ?: street ?: return@mapNotNull null
            val detail = listOfNotNull(street.takeIf { it != name }, s("postcode"), s("city") ?: s("district") ?: s("county"), s("state"), s("country"))
                .distinct().joinToString(", ")
            Found(name, detail, LatLon(c.getDouble(1), c.getDouble(0)))
        }
    }

    private fun nominatim(enc: String, near: LatLon?, lang: String): List<Found> {
        val u = "$NOMINATIM?q=$enc&format=jsonv2&limit=10" + (if (lang.isNotEmpty()) "&accept-language=$lang" else "") +
            (near?.let { String.format(Locale.US, "&viewbox=%.3f,%.3f,%.3f,%.3f", it.lon - 1, it.lat + 1, it.lon + 1, it.lat - 1) } ?: "")
        val a = JSONArray(Weather.get(u))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val full = o.optString("display_name")
            val name = o.optString("name").ifBlank { full.substringBefore(",") }
            Found(name, full.substringAfter(", ", ""), LatLon(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }
    }

    private fun plain(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
}
