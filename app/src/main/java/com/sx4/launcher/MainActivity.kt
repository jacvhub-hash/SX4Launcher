package com.sx4.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("sx4", Context.MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var car: CarView
    private lateinit var gauge: GaugeView
    private lateinit var eq: EqView
    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvEq: TextView
    private lateinit var cover: ImageView
    private lateinit var btnPlay: TextView
    private lateinit var btnMode: TextView
    private lateinit var btnRot: TextView

    private var overlay: View? = null
    private var visualizer: Visualizer? = null
    private var controller: MediaController? = null
    private var playing = false
    private var modeSetting = 0 // 0 auto, 1 dia, 2 noche
    private var isNight = false
    private var lastFix = 0L
    private var gpsOn = false

    private val dateFmt = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "MX"))

    private val metaActions = arrayOf(
        "com.android.music.metachanged",
        "com.android.music.metadatachanged",
        "com.android.music.playstatechanged",
        "com.android.music.queuechanged",
        "com.android.music.playbackcomplete",
        "com.htc.music.metachanged",
        "com.miui.player.metachanged",
        "com.sec.android.app.music.metachanged",
        "com.sonyericsson.music.metachanged",
        "com.spotify.music.metadatachanged",
        "com.maxmpz.audioplayer.metachanged",
        "fm.last.android.metachanged"
    )

    private val metaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            MusicBridge.bAction = i.action
            val t = i.getStringExtra("track") ?: i.getStringExtra("title")
            if (!t.isNullOrBlank()) {
                MusicBridge.bTitle = t
                MusicBridge.bArtist = i.getStringExtra("artist")
            }
            val ex = i.extras
            if (ex != null && ex.containsKey("playing")) {
                MusicBridge.bPlaying = ex.getBoolean("playing")
            }
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastFix = System.currentTimeMillis()
            val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            gauge.status = "GPS"
            gauge.setSpeed(if (kmh < 2f) 0f else kmh)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            pollMedia()
            applyNight()
            if (gpsOn && System.currentTimeMillis() - lastFix > 5000L) {
                gauge.setValid(false)
                gauge.status = "Sin senal GPS"
            }
            h.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        Ui.s = minOf(dm.widthPixels / 1280f, dm.heightPixels / 720f)
        modeSetting = prefs.getInt("mode", 0)
        buildUi()
        car.autoRotate = prefs.getBoolean("rot", false)
        updateButtons()
        requestPerms()
        gauge.intro()
    }

    override fun onResume() {
        super.onResume()
        startGps()
        startVisualizer()
        try {
            val f = IntentFilter()
            for (a in metaActions) f.addAction(a)
            registerReceiver(metaReceiver, f)
        } catch (e: Exception) {
        }
        h.removeCallbacks(ticker)
        h.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(ticker)
        try {
            unregisterReceiver(metaReceiver)
        } catch (e: Exception) {
        }
        stopGps()
        stopVisualizer()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeOverlay()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Un launcher no se cierra con "atras".
        if (overlay != null) closeOverlay()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startGps()
        startVisualizer()
    }

    // ---------------------------------------------------------------- interfaz

    private fun glass(radius: Int = 24, fill: Int = 0x55061018): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = px(radius.toFloat())
        g.setStroke(pxi(1), 0x44FFFFFF)
        return g
    }

    private fun tv(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size))
        v.setTextColor(color)
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        return v
    }

    private fun btn(label: String, size: Float = 22f, fill: Int = 0x66000000, onClick: () -> Unit): TextView {
        val b = tv(label, size)
        b.gravity = Gravity.CENTER
        b.background = glass(20, fill)
        b.setPadding(pxi(10), pxi(8), pxi(10), pxi(8))
        b.setOnClickListener {
            b.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
                b.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                onClick()
            }.start()
        }
        return b
    }

    private fun buildUi() {
        root = FrameLayout(this)

        // ---- fondo: el coche (pantalla completa)
        car = CarView(this)
        root.addView(car, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(pxi(24), pxi(16), pxi(24), pxi(16))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL

        // ---- panel izquierdo flotante: marca, hora, tacometro
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.gravity = Gravity.CENTER_HORIZONTAL
        left.background = glass(28)
        left.setPadding(pxi(12), pxi(12), pxi(12), pxi(12))

        val brand = LinearLayout(this)
        brand.orientation = LinearLayout.HORIZONTAL
        brand.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_suzuki)
        brand.addView(logo, LinearLayout.LayoutParams(pxi(62), pxi(62)))
        val brandTxt = tv("  SUZUKI SX4", 25f, 0xFFE6ECF2.toInt(), true)
        brandTxt.letterSpacing = 0.04f
        brand.addView(brandTxt)
        left.addView(brand)

        tvTime = tv("--:--", 64f, Color.WHITE, true)
        tvTime.gravity = Gravity.CENTER
        left.addView(tvTime)
        tvDate = tv("", 18f, 0xCCFFFFFF.toInt())
        tvDate.gravity = Gravity.CENTER
        left.addView(tvDate)

        gauge = GaugeView(this)
        val glp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        glp.topMargin = pxi(6)
        left.addView(gauge, glp)
        top.addView(left, LinearLayout.LayoutParams(pxi(300), ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- centro libre (se ve el coche)
        top.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // ---- panel derecho flotante: musica
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.background = glass(28)
        right.setPadding(pxi(16), pxi(16), pxi(16), pxi(16))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.HORIZONTAL
        info.gravity = Gravity.CENTER_VERTICAL
        cover = ImageView(this)
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.setImageResource(R.drawable.ic_bluetooth)
        cover.clipToOutline = true
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, px(16f))
            }
        }
        info.addView(cover, LinearLayout.LayoutParams(pxi(90), pxi(90)))
        val txtCol = LinearLayout(this)
        txtCol.orientation = LinearLayout.VERTICAL
        tvTitle = tv("Sin musica", 23f, Color.WHITE, true)
        tvTitle.maxLines = 2
        tvTitle.ellipsize = TextUtils.TruncateAt.END
        tvArtist = tv("", 17f, 0xDDFFFFFF.toInt())
        tvArtist.maxLines = 1
        tvArtist.ellipsize = TextUtils.TruncateAt.END
        txtCol.addView(tvTitle)
        txtCol.addView(tvArtist)
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = pxi(12)
        info.addView(txtCol, tlp)
        right.addView(info)

        tvStatus = tv("", 13f, 0xAAFFFFFF.toInt())
        tvStatus.maxLines = 2
        tvStatus.setPadding(0, pxi(8), 0, pxi(8))
        right.addView(tvStatus)

        eq = EqView(this)
        eq.onBass = { b -> h.post { car.scaleX = 1f + 0.015f * b; car.scaleY = 1f + 0.015f * b } }
        right.addView(eq, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tvEq = tv("", 12f, 0x99FFFFFF.toInt())
        tvEq.setPadding(0, pxi(6), 0, pxi(8))
        right.addView(tvEq)

        val ctl = LinearLayout(this)
        ctl.orientation = LinearLayout.HORIZONTAL
        val bPrev = btn("\u23EE", 30f, 0x33FFFFFF) { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        btnPlay = btn("\u23EF", 30f, 0x33FFFFFF) { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        val bNext = btn("\u23ED", 30f, 0x33FFFFFF) { media(KeyEvent.KEYCODE_MEDIA_NEXT) }
        for (b in listOf(bPrev, btnPlay, bNext)) {
            val lp = LinearLayout.LayoutParams(0, pxi(66), 1f)
            lp.setMargins(pxi(4), 0, pxi(4), 0)
            ctl.addView(b, lp)
        }
        right.addView(ctl)
        top.addView(right, LinearLayout.LayoutParams(pxi(310), ViewGroup.LayoutParams.MATCH_PARENT))

        main.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- dock inferior flotante
        val dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        val bMaps = btn("\uD83D\uDDFA  Mapas") { openMaps() }
        val bMusic = btn("\uD83C\uDFB5  Musica") { openMusic() }
        bMusic.setOnLongClickListener { showApps(true); true }
        val bApps = btn("\u25A6  Apps") { showApps(false) }
        val bSet = btn("\u2699  Ajustes") { showSettings() }
        btnMode = btn("") {
            modeSetting = (modeSetting + 1) % 3
            prefs.edit().putInt("mode", modeSetting).apply()
            applyNight()
            updateButtons()
        }
        btnRot = btn("") {
            car.autoRotate = !car.autoRotate
            prefs.edit().putBoolean("rot", car.autoRotate).apply()
            updateButtons()
        }
        for (b in listOf(bMaps, bMusic, bApps, bSet, btnMode, btnRot)) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(pxi(6), 0, pxi(6), 0)
            dock.addView(b, lp)
        }
        val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pxi(80))
        dlp.topMargin = pxi(12)
        main.addView(dock, dlp)

        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        applyNight()

        // animacion de entrada
        car.alpha = 0f
        car.animate().alpha(1f).setDuration(900).start()
        val panels = listOf<View>(left, right, dock)
        for ((i, v) in panels.withIndex()) {
            v.alpha = 0f
            v.translationY = px(40f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(150L + i * 120L).setDuration(600).start()
        }
    }

    private fun updateClock() {
        val cal = Calendar.getInstance()
        var hr = cal.get(Calendar.HOUR)
        if (hr == 0) hr = 12
        val mm = cal.get(Calendar.MINUTE)
        val ap = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val base = hr.toString() + ":" + (if (mm < 10) "0" else "") + mm.toString()
        val full = base + " " + ap
        val sp = SpannableString(full)
        sp.setSpan(RelativeSizeSpan(0.40f), base.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvTime.text = sp
        tvDate.text = dateFmt.format(Date()).replaceFirstChar { it.uppercase() }
    }

    private fun updateButtons() {
        btnMode.text = when (modeSetting) {
            1 -> "\u2600  Dia"
            2 -> "\uD83C\uDF19  Noche"
            else -> "\uD83C\uDF13  Auto"
        }
        btnRot.text = if (car.autoRotate) "\u21BB  Giro: SI" else "\u21BB  Giro: NO"
    }

    private fun applyNight() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = when (modeSetting) {
            1 -> false
            2 -> true
            else -> hour >= 19 || hour < 6
        }
        if (night == isNight && root.background != null) return
        isNight = night
        car.setNight(night)
        val colors = if (night)
            intArrayOf(Color.parseColor("#FF0B1220"), Color.parseColor("#FF000000"))
        else
            intArrayOf(Color.parseColor("#FF3A4048"), Color.parseColor("#FF14171B"))
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
        val accent = if (night) Color.parseColor("#FF6FD3FF") else Color.parseColor("#FFC9D3DD")
        gauge.accent = accent
        eq.accent = accent
    }

    // ---------------------------------------------------------------- apps

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun launchPkg(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { startActivity(i); true } catch (e: Exception) { false }
    }

    private fun openMaps() {
        if (launchPkg("com.google.android.apps.maps")) return
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("No se encontro Google Maps en el estereo")
        }
    }

    private fun openMusic() {
        val pkg = prefs.getString("music_pkg", null)
        if (pkg == null) {
            toast("Elige tu app de musica (se hace una sola vez)")
            showApps(true)
        } else if (!launchPkg(pkg)) {
            toast("No se pudo abrir esa app. Elige otra.")
            showApps(true)
        }
    }

    private fun closeOverlay() {
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    private fun showApps(pick: Boolean) {
        closeOverlay()
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val panel = FrameLayout(this)
        panel.setBackgroundColor(Color.parseColor("#F2080A0D"))
        panel.isClickable = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pxi(40), pxi(24), pxi(40), pxi(24))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = tv(if (pick) "Elige tu app de Musica Bluetooth" else "Aplicaciones", 30f, Color.WHITE, true)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(btn("\u2715  Cerrar") { closeOverlay() }, LinearLayout.LayoutParams(pxi(180), pxi(64)))
        col.addView(head)

        val grid = GridLayout(this)
        grid.columnCount = 6
        for (ri in apps) {
            val cell = tv(ri.loadLabel(pm).toString(), 16f)
            val icon = ri.loadIcon(pm)
            icon.setBounds(0, 0, pxi(72), pxi(72))
            cell.setCompoundDrawables(null, icon, null, null)
            cell.compoundDrawablePadding = pxi(6)
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.maxLines = 2
            cell.ellipsize = TextUtils.TruncateAt.END
            cell.setPadding(pxi(4), pxi(10), pxi(4), pxi(4))
            val pkg = ri.activityInfo.packageName
            cell.setOnClickListener {
                if (pick) {
                    prefs.edit().putString("music_pkg", pkg).apply()
                    toast("Listo: " + ri.loadLabel(pm))
                    closeOverlay()
                } else {
                    if (!launchPkg(pkg)) toast("No se pudo abrir")
                }
            }
            val lp = GridLayout.LayoutParams()
            lp.width = pxi(190)
            lp.height = pxi(130)
            grid.addView(cell, lp)
        }
        val sv = ScrollView(this)
        sv.addView(grid)
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        slp.topMargin = pxi(12)
        col.addView(sv, slp)

        panel.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(220).start()
        overlay = panel
    }

    private fun tryStart(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("Este estereo no tiene esa pantalla de ajustes")
        }
    }

    private fun showSettings() {
        val items = arrayOf(
            "Elegir app de Musica Bluetooth",
            "Dar permisos (ubicacion y audio)",
            "Acceso a notificaciones (nombre de la cancion)",
            "Diagnostico de musica (toma captura y mandamela)",
            "Pantalla de inicio predeterminada",
            "Ajustes de Android",
            "Reiniciar ecualizador"
        )
        AlertDialog.Builder(this)
            .setTitle("Ajustes del launcher")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showApps(true)
                    1 -> requestPerms()
                    2 -> tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    3 -> showDiag()
                    4 -> tryStart(Intent(Settings.ACTION_HOME_SETTINGS))
                    5 -> tryStart(Intent(Settings.ACTION_SETTINGS))
                    6 -> { stopVisualizer(); startVisualizer() }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showDiag() {
        val sb = StringBuilder()
        sb.append("App de musica elegida: ").append(prefs.getString("music_pkg", "(ninguna)")).append("\n\n")
        sb.append("1) Sesiones multimedia:\n")
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(this, MusicListener::class.java))
            if (list.isEmpty()) sb.append("   ninguna\n")
            for (c in list) {
                sb.append("   ").append(c.packageName).append(" | ")
                    .append(c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "(sin titulo)").append("\n")
            }
        } catch (e: SecurityException) {
            sb.append("   SIN ACCESO A NOTIFICACIONES (activalo en Ajustes)\n")
        } catch (e: Exception) {
            sb.append("   error: ").append(e.message).append("\n")
        }
        sb.append("\n2) Notificaciones recientes (app | titulo | texto):\n")
        synchronized(MusicBridge.recent) {
            if (MusicBridge.recent.isEmpty()) sb.append("   ninguna\n")
            for ((k, v) in MusicBridge.recent) sb.append("   ").append(k).append(" | ").append(v).append("\n")
        }
        sb.append("\n3) Transmision de musica: ").append(MusicBridge.bAction ?: "ninguna recibida")
        sb.append("\n\nTip: pon una cancion, espera 5 segundos y vuelve a abrir este diagnostico.")
        AlertDialog.Builder(this)
            .setTitle("Diagnostico de musica")
            .setMessage(sb.toString())
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // ---------------------------------------------------------------- permisos y GPS

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val need = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
            .filter { !granted(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun startGps() {
        if (gpsOn) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            gauge.status = "Sin permiso de ubicacion"
            return
        }
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locListener, Looper.getMainLooper())
            gpsOn = true
            lastFix = System.currentTimeMillis()
            gauge.status = "Buscando GPS..."
        } catch (e: Exception) {
            gauge.status = "GPS no disponible"
        }
    }

    private fun stopGps() {
        if (!gpsOn) return
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locListener)
        } catch (e: Exception) {
        }
        gpsOn = false
    }

    // ---------------------------------------------------------------- musica

    private fun pollMedia() {
        var title: String? = null
        var artist = ""
        var art: Bitmap? = null
        var isPlaying = false
        var src = ""
        var access = true
        var sessionPlaying: Boolean? = null

        // Ruta 1: sesion multimedia (la mejor, si la app la publica)
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list: List<MediaController> =
                msm.getActiveSessions(ComponentName(this, MusicListener::class.java))
            val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
            controller = c
            if (c != null) {
                val md = c.metadata
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
                art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                sessionPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING
                src = "sesion"
            }
        } catch (e: SecurityException) {
            controller = null
            access = false
        } catch (e: Exception) {
            controller = null
        }

        // Ruta 2: notificacion de la app de musica
        if (title.isNullOrBlank()) {
            val nt = MusicBridge.nTitle
            if (!nt.isNullOrBlank()) {
                title = nt
                artist = MusicBridge.nText ?: ""
                art = MusicBridge.nArt
                src = "notificacion"
            }
        }

        // Ruta 3: transmisiones (broadcast) de la app
        if (title.isNullOrBlank()) {
            val bt = MusicBridge.bTitle
            if (!bt.isNullOrBlank()) {
                title = bt
                artist = MusicBridge.bArtist ?: ""
                src = "transmision"
            }
        }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        isPlaying = sessionPlaying ?: (MusicBridge.bPlaying ?: am.isMusicActive)

        if (title.isNullOrBlank()) {
            val msg = if (!access)
                "Falta activar 'Acceso a notificaciones' (Ajustes) para leer la cancion"
            else
                "Esta app no comparte el nombre de la cancion. Revisa Ajustes > Diagnostico."
            showTrack(if (isPlaying) "Reproduciendo audio" else "Sin musica", "", null, isPlaying, msg)
        } else {
            showTrack(title, artist, art, isPlaying,
                (if (isPlaying) "Reproduciendo" else "En pausa") + "  -  fuente: " + src)
        }
    }

    private fun showTrack(title: String, artist: String, art: Bitmap?, isPlaying: Boolean, status: String) {
        tvTitle.text = title
        tvArtist.text = artist
        tvStatus.text = status
        playing = isPlaying
        btnPlay.text = if (isPlaying) "\u23F8" else "\u25B6"
        if (art != null) cover.setImageBitmap(art) else cover.setImageResource(R.drawable.ic_bluetooth)
    }

    private fun media(code: Int) {
        val c = controller
        if (c != null) {
            try {
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_NEXT -> c.transportControls.skipToNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> c.transportControls.skipToPrevious()
                    else -> if (playing) c.transportControls.pause() else c.transportControls.play()
                }
                return
            } catch (e: Exception) {
            }
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------------------------------------------------------------- visualizador

    private fun startVisualizer() {
        if (visualizer != null) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            tvEq.text = "Ecualizador: falta permiso de audio"
            return
        }
        try {
            val v = Visualizer(0)
            v.setEnabled(false)
            v.setCaptureSize(minOf(1024, Visualizer.getCaptureSizeRange()[1]))
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) eq.feed(fft, samplingRate)
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.setEnabled(true)
            visualizer = v
            tvEq.text = "Ecualizador: audio del sistema activo"
        } catch (e: Throwable) {
            visualizer = null
            tvEq.text = "Ecualizador: este estereo no permite capturar el audio"
        }
    }

    private fun stopVisualizer() {
        try {
            visualizer?.setEnabled(false)
            visualizer?.release()
        } catch (e: Throwable) {
        }
        visualizer = null
    }
}
