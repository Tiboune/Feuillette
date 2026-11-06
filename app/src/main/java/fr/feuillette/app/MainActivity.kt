package fr.feuillette.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var searchBox: EditText
    private lateinit var statusView: TextView
    private lateinit var card: LinearLayout
    private lateinit var cardTitle: TextView
    private lateinit var cardInfo: TextView
    private lateinit var btnLocate: Button

    private var myLoc: MyLocationNewOverlay? = null
    private var userSearched = false
    private var selected: Boite? = null
    private var lastBox: BoundingBox? = null
    private val markers = HashMap<String, Marker>()

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val loadRunnable = Runnable { loadVisibleArea() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            startLocation()
        } else {
            status("Localisation refusée : la recherche par ville reste disponible.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = Configuration.getInstance()
        cfg.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        cfg.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid")
        base.mkdirs()
        cfg.osmdroidBasePath = base
        val tiles = File(base, "tiles")
        tiles.mkdirs()
        cfg.osmdroidTileCache = tiles

        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        searchBox = findViewById(R.id.searchBox)
        statusView = findViewById(R.id.status)
        card = findViewById(R.id.card)
        cardTitle = findViewById(R.id.cardTitle)
        cardInfo = findViewById(R.id.cardInfo)
        btnLocate = findViewById(R.id.btnLocate)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.overlays.add(CopyrightOverlay(this))
        map.controller.setZoom(6.0)
        map.controller.setCenter(GeoPoint(46.6, 2.4)) // centre de la France
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleLoad()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleLoad()
                return false
            }
        })

        findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }

        btnLocate.setOnClickListener {
            if (!hasLocationPermission()) {
                askLocationPermission()
            } else {
                startLocation()
                userSearched = false
                if (!centerOnMe(15.0)) {
                    status("Recherche de votre position… Vérifiez que la localisation du téléphone est activée.")
                }
            }
        }

        findViewById<Button>(R.id.btnRoute).setOnClickListener {
            val b = selected ?: return@setOnClickListener
            val uri = Uri.parse("geo:${b.lat},${b.lon}?q=${b.lat},${b.lon}(${Uri.encode(b.titre())})")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: Exception) {
                Toast.makeText(this, "Aucune application de cartes trouvée", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnClose).setOnClickListener { hideCard() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (card.visibility == View.VISIBLE) {
                    hideCard()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (hasLocationPermission()) startLocation() else askLocationPermission()
    }

    // ---------- Localisation ----------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun askLocationPermission() {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startLocation() {
        if (myLoc != null) return
        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        overlay.enableMyLocation()
        overlay.runOnFirstFix {
            runOnUiThread {
                if (!userSearched) centerOnMe(15.0)
            }
        }
        map.overlays.add(overlay)
        myLoc = overlay
    }

    private fun centerOnMe(zoom: Double): Boolean {
        val p = myLoc?.myLocation ?: return false
        map.controller.setZoom(zoom)
        map.controller.animateTo(p)
        scheduleLoad()
        return true
    }

    // ---------- Chargement des boîtes à livres ----------

    private fun scheduleLoad() {
        handler.removeCallbacks(loadRunnable)
        handler.postDelayed(loadRunnable, 700)
    }

    private fun loadVisibleArea() {
        if (map.zoomLevelDouble < 11.0) {
            status("Zoomez sur une ville pour voir les boîtes à livres")
            return
        }
        val bb = map.boundingBox
        val last = lastBox
        if (last != null &&
            last.contains(bb.latNorth, bb.lonEast) &&
            last.contains(bb.latSouth, bb.lonWest)
        ) {
            return
        }
        val dLat = (bb.latNorth - bb.latSouth) * 0.25
        val dLon = (bb.lonEast - bb.lonWest) * 0.25
        val req = BoundingBox(
            bb.latNorth + dLat, bb.lonEast + dLon,
            bb.latSouth - dLat, bb.lonWest - dLon
        )
        status("Chargement des boîtes à livres…")
        executor.execute {
            try {
                val list = Api.boites(req.latSouth, req.lonWest, req.latNorth, req.lonEast)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    lastBox = req
                    addMarkers(list)
                    status(
                        if (list.isEmpty()) "Aucune boîte à livres référencée dans cette zone"
                        else "${list.size} boîte(s) à livres dans la zone"
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status("Impossible de charger les données. Vérifiez votre connexion.")
                }
            }
        }
    }

    private fun addMarkers(list: List<Boite>) {
        for (b in list) {
            if (markers.containsKey(b.id)) continue
            val m = Marker(map)
            m.position = GeoPoint(b.lat, b.lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            m.setOnMarkerClickListener { _, _ ->
                showCard(b)
                true
            }
            map.overlays.add(m)
            markers[b.id] = m
        }
        map.invalidate()
    }

    // ---------- Recherche (France entière) ----------

    private fun search() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        status("Recherche de « $q »…")
        executor.execute {
            try {
                val lieu = Api.geocode(q)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (lieu == null) {
                        status("Aucun résultat en France pour « $q »")
                    } else {
                        userSearched = true
                        hideCard()
                        goTo(lieu)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status("Recherche impossible. Vérifiez votre connexion.")
                }
            }
        }
    }

    private fun goTo(lieu: Lieu) {
        val tiny = (lieu.north - lieu.south) < 0.0005 || (lieu.east - lieu.west) < 0.0005
        if (tiny) {
            map.controller.setZoom(16.0)
            map.controller.setCenter(GeoPoint(lieu.lat, lieu.lon))
        } else {
            map.zoomToBoundingBox(
                BoundingBox(lieu.north, lieu.east, lieu.south, lieu.west), false, 100
            )
            if (map.zoomLevelDouble > 16.0) map.controller.setZoom(16.0)
        }
        status("")
        scheduleLoad()
    }

    // ---------- Fiche d'une boîte ----------

    private fun showCard(b: Boite) {
        selected = b
        cardTitle.text = b.titre()
        cardInfo.text = b.details()
        card.visibility = View.VISIBLE
        btnLocate.visibility = View.GONE
    }

    private fun hideCard() {
        selected = null
        card.visibility = View.GONE
        btnLocate.visibility = View.VISIBLE
    }

    private fun status(message: String) {
        if (message.isEmpty()) {
            statusView.visibility = View.GONE
        } else {
            statusView.text = message
            statusView.visibility = View.VISIBLE
        }
    }

    // ---------- Cycle de vie ----------

    override fun onResume() {
        super.onResume()
        map.onResume()
        myLoc?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        myLoc?.disableMyLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(loadRunnable)
        executor.shutdownNow()
    }
}
