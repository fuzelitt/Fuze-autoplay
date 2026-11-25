package com.phiauto

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val root: File get() = File(filesDir, "charts")

    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var songSpinner: Spinner
    private lateinit var diffSpinner: Spinner
    private lateinit var algoSpinner: Spinner
    private lateinit var mirror: CheckBox
    private lateinit var manualSync: RadioButton
    private lateinit var delaySync: RadioButton
    private lateinit var delayInput: EditText
    private lateinit var prepareBtn: Button

    private var allSongs: Map<String, List<String>> = emptyMap()
    private var shownSongs: List<String> = emptyList()

    private val REQ_ZIP = 1001
    private val REQ_JSON = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        buildUi()
        refreshSongs()
    }

    override fun onResume() {
        super.onResume()
        updateServiceHint()
    }

    // ------------------------------------------------------------------ UI ---

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, dp(2))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply {
            text = "Phigros Autoplay"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Offline practice helper. Use it with the game in airplane mode / a throwaway account so scores never sync."
            textSize = 12f
        })

        status = TextView(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFFEFEFEF.toInt())
            textSize = 13f
            text = "Ready."
        }
        col.addView(status)

        col.addView(label("1. Charts"))
        col.addView(Button(this).apply {
            text = "Import charts from a .zip…"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_ZIP)
            }
        })
        col.addView(Button(this).apply {
            text = "Open a chart .json file…"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, REQ_JSON)
            }
        })
        col.addView(TextView(this).apply {
            text = "A zip containing Assets/Tracks/<song>/Chart_<difficulty>.json files " +
                "(e.g. the repo's \"Download ZIP\"). Only those chart files are imported."
            textSize = 12f
        })

        col.addView(label("2. Pick a chart"))
        search = EditText(this).apply {
            hint = "Filter songs…"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = applyFilter()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(search)
        songSpinner = Spinner(this)
        diffSpinner = Spinner(this)
        col.addView(songSpinner)
        col.addView(diffSpinner)
        songSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = fillDifficulties()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        col.addView(label("3. Options"))
        algoSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("algo1", "algo2")
            )
        }
        col.addView(TextView(this).apply {
            text = "Planning algorithm (algo1 = conservative, algo2 = aggressive; if one fails try the other)"
            textSize = 12f
        })
        col.addView(algoSpinner)
        mirror = CheckBox(this).apply { text = "Mirror mode (flip left/right)" }
        col.addView(mirror)

        val syncGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        manualSync = RadioButton(this).apply {
            text = "Manual sync: press GO when the first note is about to hit the line"
            id = View.generateViewId()
            isChecked = true
        }
        delaySync = RadioButton(this).apply {
            text = "Delay sync: pause the game, press GO; it resumes and starts after N seconds"
            id = View.generateViewId()
        }
        syncGroup.addView(manualSync)
        syncGroup.addView(delaySync)
        col.addView(syncGroup)
        delayInput = EditText(this).apply {
            hint = "Delay seconds"
            setText("1.95")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        col.addView(delayInput)

        col.addView(label("4. Start"))
        prepareBtn = Button(this).apply {
            text = "Prepare and show floating GO button"
            setOnClickListener { prepare() }
        }
        col.addView(prepareBtn)
        col.addView(Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        updateServiceHint()
    }

    private fun say(msg: String) {
        ui.post {
            status.text = msg
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun updateServiceHint() {
        if (!::status.isInitialized) return
        if (AutoplayService.instance == null && status.text.toString() == "Ready.") {
            status.text = "The tap-injector service is OFF. Turn on \"Phigros Autoplay (tap injector)\" in " +
                "Accessibility settings. (Android 13+: if it is greyed out, open this app's App info, " +
                "tap ⋮ and choose \"Allow restricted settings\" first.)"
        }
    }

    // --------------------------------------------------------------- songs ---

    private fun refreshSongs(select: String? = null) {
        Thread {
            try {
                val json = Python.getInstance().getModule("phi_bridge")
                    .callAttr("list_songs", root.absolutePath).toString()
                val songs = JSONObject(json).getJSONObject("songs")
                val map = LinkedHashMap<String, List<String>>()
                for (k in songs.keys()) {
                    val arr = songs.getJSONArray(k)
                    map[k] = List(arr.length()) { arr.getString(it) }
                }
                ui.post {
                    allSongs = map
                    applyFilter()
                    if (select != null) search.setText(select)   // narrows the list to it
                    if (map.isEmpty()) status.text = "No charts yet. Import a zip in step 1."
                }
            } catch (e: Exception) {
                say("Could not list songs: ${e.message}")
            }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shownSongs = allSongs.keys.filter { q.isEmpty() || it.lowercase().contains(q) }
        songSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, shownSongs
        )
        fillDifficulties()
    }

    private fun fillDifficulties() {
        val song = songSpinner.selectedItem as? String
        val diffs = if (song == null) emptyList() else allSongs[song] ?: emptyList()
        diffSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, diffs
        )
    }

    // ---------------------------------------------------------- extraction ---

    /** Use chart JSON files directly: each becomes its own entry under Assets/Tracks/<file name>/. */
    private fun importJson(uris: List<Uri>) {
        say("Reading chart file(s)…")
        Thread {
            try {
                var last: String? = null
                var ok = 0
                val bad = ArrayList<String>()
                for (uri in uris) {
                    var display = "chart"
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) display = c.getString(0) ?: "chart" }
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (!text.contains("judgeLineList")) {
                        bad.add(display)
                        continue
                    }
                    var song = display.removeSuffix(".json").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
                    if (song.isEmpty() || song == "." || song == "..") song = "chart"
                    val dir = File(root, "Assets/Tracks/$song")
                    dir.mkdirs()
                    File(dir, "Chart_File.json").writeText(text, Charsets.UTF_8)
                    last = song
                    ok++
                }
                say(
                    (if (ok > 0) "Loaded $ok chart file(s). Selected: $last\n" else "") +
                        (if (bad.isNotEmpty()) "Not a Phigros chart (no judgeLineList): ${bad.joinToString()}" else "")
                )
                refreshSongs(last)
            } catch (e: Throwable) {
                say("Could not read the file: ${e.message}")
            }
        }.start()
    }

    private val chartPath = Regex("""(?:^|/)Tracks/([^/]+)/(Chart_[^/]+\.json)$""")

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_JSON && resultCode == RESULT_OK) {
            val uris = ArrayList<Uri>()
            data?.data?.let { uris.add(it) }
            data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
            importJson(uris)
            return
        }
        if (requestCode != REQ_ZIP || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        say("Importing charts…")
        ui.post { prepareBtn.isEnabled = false }
        Thread {
            try {
                var n = 0
                val songs = HashSet<String>()
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open file")
                java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        val m = chartPath.find(e.name.replace('\\', '/')) ?: continue
                        val song = m.groupValues[1]
                        val name = m.groupValues[2]
                        if (song == "." || song == "..") continue
                        val dir = File(root, "Assets/Tracks/$song")
                        dir.mkdirs()
                        File(dir, name).outputStream().use { out -> zip.copyTo(out, 1 shl 16) }
                        songs.add(song)
                        n++
                        if (n % 50 == 0) say("Importing charts… $n files")
                    }
                }
                say(
                    if (n == 0) "No Assets/Tracks/<song>/Chart_*.json files found in that zip."
                    else "Imported $n chart files for ${songs.size} songs."
                )
                refreshSongs()
            } catch (e: Throwable) {
                say("Import failed: ${e.message}")
            } finally {
                ui.post { prepareBtn.isEnabled = true }
            }
        }.start()
    }

    // ------------------------------------------------------------- prepare ---

    private fun screenSize(): Pair<Int, Int> {
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels
        }
        return Pair(max(w, h), min(w, h))   // the game runs in landscape
    }

    private fun prepare() {
        val song = songSpinner.selectedItem as? String
        val diff = diffSpinner.selectedItem as? String
        if (song == null || diff == null) {
            say("Pick a song and difficulty first (import a charts zip in step 1).")
            return
        }
        val algo = algoSpinner.selectedItem as String
        val mir = mirror.isChecked
        val delayMode = delaySync.isChecked
        val delay = delayInput.text.toString().toDoubleOrNull() ?: 1.95
        val (w, h) = screenSize()

        if (AutoplayService.instance == null) {
            say("Turn on \"Phigros Autoplay (tap injector)\" in Accessibility settings, then come back and press this again.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        prepareBtn.isEnabled = false
        say("Planning touches for $song [$diff] with $algo…")
        Thread {
            try {
                val json = Python.getInstance().getModule("phi_bridge")
                    .callAttr("build_plan", root.absolutePath, song, diff, algo, w, h, mir)
                    .toString()
                val plan = Plan.parse(json)
                val svc = AutoplayService.instance
                if (svc == null) {
                    say("The accessibility service stopped. Turn it on again and retry.")
                } else {
                    svc.arm(plan, delayMode, delay)
                    say(
                        "Plan ready: ${plan.touches} touches in ${plan.windows.size} gesture windows " +
                            "(${plan.last / 1000}s long, screen ${plan.screenW}x${plan.screenH}).\n\n" +
                            "Now switch to Phigros, start the song, and use the floating bar. " +
                            if (delayMode) "Pause the game first, then press GO."
                            else "Press GO when the first note is about to reach the judgment line."
                    )
                }
            } catch (e: Exception) {
                say("Planning failed: ${e.message}\n\nTry the other algorithm.")
            } finally {
                ui.post { prepareBtn.isEnabled = true }
            }
        }.start()
    }
}
