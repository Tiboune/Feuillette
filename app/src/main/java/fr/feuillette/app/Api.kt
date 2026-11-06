package fr.feuillette.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Boite(
    val id: String,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>
) {
    fun titre(): String {
        val nom = tags["name"]
        return if (nom != null && nom.isNotBlank()) nom else "Boîte à livres"
    }

    fun details(): String {
        val lignes = ArrayList<String>()
        val rue = listOfNotNull(tags["addr:housenumber"], tags["addr:street"]).joinToString(" ")
        val ville = listOfNotNull(tags["addr:postcode"], tags["addr:city"]).joinToString(" ")
        val adresse = listOf(rue, ville).filter { it.isNotBlank() }.joinToString(", ")
        if (adresse.isNotBlank()) lignes.add("📍 $adresse")
        val description = tags["description:fr"] ?: tags["description"]
        if (description != null) lignes.add(description)
        val horaires = tags["opening_hours"]
        if (horaires != null) lignes.add("🕒 $horaires")
        val capacite = tags["capacity"]
        if (capacite != null) lignes.add("📚 Capacité : $capacite")
        val gestion = tags["operator"]
        if (gestion != null) lignes.add("Gérée par : $gestion")
        if (lignes.isEmpty()) lignes.add("Aucun détail renseigné pour cette boîte.")
        return lignes.joinToString("\n")
    }
}

data class Lieu(
    val nom: String,
    val lat: Double,
    val lon: Double,
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double
)

object Api {
    private const val UA = "Feuillette/0.1 (application Android)"

    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private fun request(url: String, post: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 40000
            c.setRequestProperty("User-Agent", UA)
            if (post != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(post.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Boîtes à livres (OpenStreetMap : amenity=public_bookcase) dans un rectangle. */
    fun boites(south: Double, west: Double, north: Double, east: Double): List<Boite> {
        val q = "[out:json][timeout:25];(" +
            "node[\"amenity\"=\"public_bookcase\"]($south,$west,$north,$east);" +
            "way[\"amenity\"=\"public_bookcase\"]($south,$west,$north,$east);" +
            ");out center tags;"
        var derniere: Exception? = null
        for (url in OVERPASS) {
            try {
                return parseBoites(request(url, "data=" + URLEncoder.encode(q, "UTF-8")))
            } catch (e: Exception) {
                derniere = e
            }
        }
        throw derniere ?: IOException("Service indisponible")
    }

    private fun parseBoites(texte: String): List<Boite> {
        val elements = JSONObject(texte).getJSONArray("elements")
        val out = ArrayList<Boite>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val lat: Double
            val lon: Double
            if (e.has("lat") && e.has("lon")) {
                lat = e.getDouble("lat")
                lon = e.getDouble("lon")
            } else {
                val centre = e.optJSONObject("center") ?: continue
                lat = centre.getDouble("lat")
                lon = centre.getDouble("lon")
            }
            val tags = HashMap<String, String>()
            val tj = e.optJSONObject("tags")
            if (tj != null) {
                val cles = tj.keys()
                while (cles.hasNext()) {
                    val k = cles.next()
                    tags[k] = tj.getString(k)
                }
            }
            out.add(Boite(e.getString("type") + e.getLong("id"), lat, lon, tags))
        }
        return out
    }

    /** Recherche d'un lieu en France (ville, adresse, code postal…) via Nominatim. */
    fun geocode(recherche: String): Lieu? {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=fr&q=" +
            URLEncoder.encode(recherche, "UTF-8")
        val arr = JSONArray(request(url))
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        val bb = o.getJSONArray("boundingbox") // [sud, nord, ouest, est]
        return Lieu(
            nom = o.optString("display_name", recherche),
            lat = o.getString("lat").toDouble(),
            lon = o.getString("lon").toDouble(),
            south = bb.getString(0).toDouble(),
            north = bb.getString(1).toDouble(),
            west = bb.getString(2).toDouble(),
            east = bb.getString(3).toDouble()
        )
    }
}
