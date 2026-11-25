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

/** Called from Python while extracting charts. */
interface ProgressCb {
    fun onProgress(done: Int, total: Int)
}

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

    private val REQ_APK = 1001
    private val GAME_PACKAGE = "com.PigeonGames.Phigros"

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
            text = "Extract charts from installed Phigros"
            setOnClickListener { extractInstalled() }
        })
        col.addView(Button(this).apply {
            text = "Choose a Phigros .apk file…"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_APK)
            }
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

    private fun refreshSongs() {
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
                    if (map.isEmpty()) status.text = "No charts yet. Use step 1 first."
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

    private fun findGamePackage(): String? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
        for (r in apps) {
            val pkg = r.activityInfo.packageName
            val label = r.loadLabel(packageManager).toString()
            if (pkg.contains("phigros", true) || label.contains("phigros", true)) return pkg
        }
        return try {
            packageManager.getApplicationInfo(GAME_PACKAGE, 0)
            GAME_PACKAGE
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun extractInstalled() {
        say("Looking for the installed game…")
        val pkg = findGamePackage()
        if (pkg == null) {
            say("Could not find Phigros among installed apps. Use \"Choose a Phigros .apk file…\" instead.")
            return
        }
        try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            val paths = ArrayList<String>()
            paths.add(ai.sourceDir)
            ai.splitSourceDirs?.let { paths.addAll(it) }
            say("Found $pkg (${paths.size} APK file(s)). Extracting…")
            runExtract(paths, null)
        } catch (e: Exception) {
            say("Could not read the game's APK: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_APK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        say("Copying the APK to app storage…")
        Thread {
            try {
                val tmp = File(cacheDir, "picked.apk")
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                }
                runExtract(listOf(tmp.absolutePath), tmp)
            } catch (e: Exception) {
                say("Could not read that file: ${e.message}")
            }
        }.start()
    }

    private fun runExtract(paths: List<String>, cleanup: File?) {
        say("Extracting charts… this can take a few minutes the first time.")
        ui.post { prepareBtn.isEnabled = false }
        Thread {
            try {
                val cb = object : ProgressCb {
                    override fun onProgress(done: Int, total: Int) = say("Extracting charts… $done / $total")
                }
                val n = Python.getInstance().getModule("phi_bridge")
                    .callAttr("extract_charts", paths.joinToString("\n"), root.absolutePath, cb)
                    .toInt()
                say(
                    if (n == 0) "No charts found in that APK. Make sure it is the full game APK " +
                        "(for split installs, the base + asset split)."
                    else "Extracted $n chart files."
                )
                refreshSongs()
            } catch (e: Throwable) {
                say("Extraction failed: ${e.message}")
            } finally {
                cleanup?.delete()
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
            say("Pick a song and difficulty first (step 1 extracts them).")
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
