package com.example.myassistant

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The Jarvis control panel: start/stop, protocols, memory, plugins and help.
 * The assistant itself lives in JarvisService.kt.
 */
class MainActivity : AppCompatActivity() {

    private val cBg = Color.parseColor("#070B10")
    private val cCard = Color.parseColor("#0E1822")
    private val cCyan = Color.parseColor("#00E5FF")
    private val cDim = Color.parseColor("#6FA9B8")
    private val cText = Color.parseColor("#D6F6FF")
    private val cAmber = Color.parseColor("#FFB300")
    private val cLine = Color.parseColor("#12394A")

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private lateinit var reactor: ReactorView
    private lateinit var statusLine: TextView
    private lateinit var startBtn: Button
    private lateinit var checklist: LinearLayout
    private lateinit var protocolList: LinearLayout
    private lateinit var memoryList: LinearLayout
    private lateinit var pluginList: LinearLayout

    private val pages = mutableListOf<View>()
    private val tabViews = mutableListOf<TextView>()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = Color.parseColor("#0B131B")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val content = FrameLayout(this)

        pages.add(buildHome())
        pages.add(buildProtocols())
        pages.add(buildMemory())
        pages.add(buildPlugins())
        pages.add(buildHelp())
        for (p in pages) content.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        showTab(0)
        renderProtocols()
        renderMemories()
        renderPlugins()

        if (intent?.getBooleanExtra("autostart", false) == true) {
            window.decorView.post { autoStart() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHome()
    }

    // ---------------------------------------------------------------- UI helpers

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int = 1) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun label(t: String, size: Float = 14f, color: Int = cText, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, cLine, 16, 1)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun primaryButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(cBg)
        background = rounded(cCyan, cCyan, 14, 0)
        setOnClickListener { onClick() }
    }

    private fun ghostButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 12f
        setTextColor(cCyan)
        background = rounded(Color.TRANSPARENT, cCyan, 12, 1)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun input(hintText: String, lines: Int = 1, mono: Boolean = false) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#4F7482"))
        setTextColor(cText)
        textSize = 14f
        background = rounded(Color.parseColor("#0A121A"), Color.parseColor("#1B4A5E"), 12, 1)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (lines > 1) {
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(36), dp(18), dp(28))
    }

    private fun wrapScroll(col: LinearLayout): View = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(col)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- tabs

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B131B"))
        }
        val names = listOf("HOME", "PROTOCOLS", "MEMORY", "PLUGINS", "HELP")
        names.forEachIndexed { i, n ->
            val t = TextView(this).apply {
                text = n
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(16), 0, dp(16))
                setOnClickListener { showTab(i) }
            }
            tabViews.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return bar
    }

    private fun showTab(index: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { i, t -> t.setTextColor(if (i == index) cCyan else cDim) }
        if (index == 0) refreshHome()
    }

    // ---------------------------------------------------------------- HOME

    private fun buildHome(): View {
        val col = column()
        col.addView(label("J.A.R.V.I.S.", 26f, cCyan, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
        }, lp())
        col.addView(label("Just A Rather Very Intelligent System", 12f, cDim).apply {
            gravity = Gravity.CENTER
        }, lp(top = 2))

        reactor = ReactorView(this)
        col.addView(reactor, lp(h = dp(250), top = 8))

        statusLine = label("OFFLINE", 14f, cDim, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        col.addView(statusLine, lp(top = 4))

        startBtn = primaryButton("START JARVIS") { toggleJarvis() }
        col.addView(startBtn, lp(top = 16))

        val sys = card()
        sys.addView(label("SYSTEM CHECK", 12f, cDim, true).apply { letterSpacing = 0.15f })
        checklist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sys.addView(checklist, lp(top = 6))
        col.addView(sys, lp(top = 18))

        return wrapScroll(col)
    }

    private fun addCheckRow(name: String, ok: Boolean, fix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            label((if (ok) "✓  " else "✗  ") + name, 14f, if (ok) cText else cAmber),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        if (!ok) row.addView(ghostButton("FIX", fix), LinearLayout.LayoutParams(WRAP, WRAP))
        checklist.addView(row, lp(top = 8))
    }

    private fun refreshHome() {
        val running = JarvisService.running
        reactor.active = running
        statusLine.text = if (running) "ONLINE  -  LISTENING" else "OFFLINE"
        statusLine.setTextColor(if (running) cCyan else cDim)
        startBtn.text = if (running) "STOP JARVIS" else "START JARVIS"

        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val contacts = granted(Manifest.permission.READ_CONTACTS)
        val notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val overlay = Settings.canDrawOverlays(this)
        val battery = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        checklist.removeAllViews()
        addCheckRow("Microphone", mic) { requestRuntimePermissions() }
        addCheckRow("Contacts (calls and messages)", contacts) { requestRuntimePermissions() }
        addCheckRow("Notifications", notif) { requestRuntimePermissions() }
        addCheckRow("Display over other apps (floating orb)", overlay) { openOverlaySettings() }
        addCheckRow("Unrestricted battery (keeps Jarvis alive)", battery) { askIgnoreBattery() }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            toast("Open Settings > Apps > Jarvis > Display over other apps")
        }
    }

    private fun askIgnoreBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            toast("Open Settings > Apps > Jarvis > Battery > Unrestricted")
        }
    }

    private fun startJarvis() {
        ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java))
        reactor.postDelayed({ refreshHome() }, 800L)
    }

    private fun autoStart() {
        if (!JarvisService.running && granted(Manifest.permission.RECORD_AUDIO)) startJarvis()
    }

    private fun toggleJarvis() {
        if (JarvisService.running) {
            stopService(Intent(this, JarvisService::class.java))
            reactor.postDelayed({ refreshHome() }, 700L)
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            toast("Allow the microphone first.")
            requestRuntimePermissions()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Tip: allow 'display over other apps' so the orb shows and Jarvis can open apps.")
        }
        startJarvis()
    }

    private fun reloadPlugins() {
        if (JarvisService.running) {
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_RELOAD))
        }
    }

    // ---------------------------------------------------------------- PROTOCOLS

    private fun buildProtocols(): View {
        val col = column()
        col.addView(label("PROTOCOLS", 22f, cCyan, true).apply { letterSpacing = 0.1f }, lp())
        col.addView(
            label(
                "A protocol is a list of commands, one per line. Say \"work protocol\" " +
                        "(or any name + protocol) and Jarvis runs them in order. " +
                        "Lines starting with # are ignored.",
                13f, cDim
            ), lp(top = 6)
        )

        val add = card()
        add.addView(label("NEW PROTOCOL", 12f, cDim, true).apply { letterSpacing = 0.15f })
        val nameIn = input("Name, for example: gym")
        add.addView(nameIn, lp(top = 8))
        add.addView(primaryButton("ADD PROTOCOL") {
            val n = JarvisStore.cleanName(nameIn.text.toString())
            if (n.isEmpty()) {
                toast("Type a name first.")
            } else {
                if (JarvisStore.findProtocol(this@MainActivity, n) == null) {
                    JarvisStore.saveProtocol(this@MainActivity, n, "# add one command per line\n")
                }
                nameIn.setText("")
                renderProtocols()
                toast("Added: $n protocol")
            }
        }, lp(top = 10))
        col.addView(add, lp(top = 16))

        protocolList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(protocolList, lp())

        val tips = card()
        tips.addView(label("EXAMPLE LINES", 12f, cDim, true).apply { letterSpacing = 0.15f })
        tips.addView(
            label(
                "open https://your-website.com\nopen slack\nplay lofi music\n" +
                        "navigate to office\nflashlight on\nstealth mode\nnormal mode\n" +
                        "remind me to stretch in 30 minutes",
                13f, cText
            ).apply { typeface = Typeface.MONOSPACE }, lp(top = 6)
        )
        tips.addView(
            label(
                "To reopen the website you worked on last night: open it in Chrome, " +
                        "tap the address bar, copy the link, and paste it after \"open \" in your work protocol.",
                13f, cDim
            ), lp(top = 10)
        )
        col.addView(tips, lp(top = 16))

        return wrapScroll(col)
    }

    private fun renderProtocols() {
        protocolList.removeAllViews()
        for (p in JarvisStore.protocols(this)) {
            val c = card()
            c.addView(label(p.name.uppercase() + " PROTOCOL", 14f, cCyan, true).apply {
                letterSpacing = 0.1f
            })
            val steps = input("one command per line", 5, true)
            steps.setText(p.steps)
            c.addView(steps, lp(top = 8))

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(primaryButton("SAVE") {
                JarvisStore.saveProtocol(this@MainActivity, p.name, steps.text.toString())
                toast("Saved: ${p.name} protocol")
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(ghostButton("DELETE") {
                JarvisStore.deleteProtocol(this@MainActivity, p.name)
                renderProtocols()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
            c.addView(row, lp(top = 10))

            protocolList.addView(c, lp(top = 12))
        }
    }

    // ---------------------------------------------------------------- MEMORY

    private fun buildMemory(): View {
        val col = column()
        col.addView(label("MEMORY", 22f, cCyan, true).apply { letterSpacing = 0.1f }, lp())
        col.addView(
            label(
                "Tell Jarvis things to remember. Later ask: \"Jarvis, do you remember that book name?\"",
                13f, cDim
            ), lp(top = 6)
        )

        val add = card()
        add.addView(label("REMEMBER SOMETHING", 12f, cDim, true).apply { letterSpacing = 0.15f })
        val labelIn = input("What is it? e.g. book name")
        val valueIn = input("Details, e.g. Atomic Habits (optional)")
        add.addView(labelIn, lp(top = 8))
        add.addView(valueIn, lp(top = 8))
        add.addView(primaryButton("SAVE MEMORY") {
            val l = labelIn.text.toString().trim()
            if (l.isEmpty()) {
                toast("Type what to remember first.")
            } else {
                JarvisStore.addMemory(this@MainActivity, l, valueIn.text.toString())
                labelIn.setText("")
                valueIn.setText("")
                renderMemories()
                toast("Saved")
            }
        }, lp(top = 10))
        add.addView(
            label(
                "Tip: tap the microphone key on your keyboard to speak instead of typing. " +
                        "You can also just say: \"Jarvis, remember my book name is Atomic Habits\".",
                12f, cDim
            ), lp(top = 10)
        )
        col.addView(add, lp(top = 16))

        memoryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(memoryList, lp())
        return wrapScroll(col)
    }

    private fun renderMemories() {
        memoryList.removeAllViews()
        val items = JarvisStore.memories(this)
        if (items.isEmpty()) {
            memoryList.addView(label("Nothing saved yet.", 13f, cDim), lp(top = 14))
        }
        items.forEachIndexed { index, m ->
            val c = card()
            c.addView(label(m.label, 15f, cCyan, true))
            if (m.value.isNotEmpty()) c.addView(label(m.value, 14f, cText), lp(top = 4))
            c.addView(ghostButton("DELETE") {
                JarvisStore.deleteMemory(this@MainActivity, index)
                renderMemories()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
            memoryList.addView(c, lp(top = 12))
        }
    }

    // ---------------------------------------------------------------- PLUGINS

    private fun buildPlugins(): View {
        val col = column()
        col.addView(label("PLUGINS", 22f, cCyan, true).apply { letterSpacing = 0.1f }, lp())
        col.addView(
            label(
                "Add new abilities without rebuilding the app. Paste the plugin code Claude gives you, " +
                        "tap INSTALL, and it works straight away.",
                13f, cDim
            ), lp(top = 6)
        )
        col.addView(
            label(
                "Only paste code from Claude or someone you fully trust. Plugins can open apps, " +
                        "start calls and use the internet.",
                12f, cAmber
            ), lp(top = 8)
        )

        val add = card()
        add.addView(label("NEW PLUGIN", 12f, cDim, true).apply { letterSpacing = 0.15f })
        val nameIn = input("Plugin name, e.g. facts")
        val codeIn = input("Paste the code here", 8, true)
        add.addView(nameIn, lp(top = 8))
        add.addView(codeIn, lp(top = 8))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ghostButton("PASTE") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                codeIn.setText(clip.getItemAt(0).coerceToText(this@MainActivity).toString())
            } else {
                toast("Clipboard is empty. Copy the code first.")
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        row.addView(primaryButton("INSTALL") {
            val code = codeIn.text.toString().trim()
            if (code.isEmpty()) {
                toast("Paste the code first.")
            } else {
                val n = nameIn.text.toString().trim()
                    .ifEmpty { "plugin ${JarvisStore.plugins(this@MainActivity).size + 1}" }
                JarvisStore.addPlugin(this@MainActivity, n, code)
                nameIn.setText("")
                codeIn.setText("")
                renderPlugins()
                reloadPlugins()
                toast("Installed: $n")
            }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
        add.addView(row, lp(top = 10))
        col.addView(add, lp(top = 16))

        pluginList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pluginList, lp())
        return wrapScroll(col)
    }

    private fun renderPlugins() {
        pluginList.removeAllViews()
        val items = JarvisStore.plugins(this)
        if (items.isEmpty()) {
            pluginList.addView(label("No plugins installed.", 13f, cDim), lp(top = 14))
        }
        for (p in items) {
            val c = card()
            c.addView(label(p.name, 15f, cCyan, true))
            c.addView(
                label(if (p.enabled) "Active" else "Switched off", 12f, if (p.enabled) cText else cAmber),
                lp(top = 2)
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ghostButton(if (p.enabled) "SWITCH OFF" else "SWITCH ON") {
                JarvisStore.setPluginEnabled(this@MainActivity, p.name, !p.enabled)
                renderPlugins()
                reloadPlugins()
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            row.addView(ghostButton("DELETE") {
                JarvisStore.deletePlugin(this@MainActivity, p.name)
                renderPlugins()
                reloadPlugins()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
            c.addView(row, lp(top = 10))
            pluginList.addView(c, lp(top = 12))
        }
    }

    // ---------------------------------------------------------------- HELP

    private fun buildHelp(): View {
        val col = column()
        col.addView(label("COMMANDS", 22f, cCyan, true).apply { letterSpacing = 0.1f }, lp())
        col.addView(
            label(
                "Say \"Jarvis\" (or \"Friday\") and then a command. " +
                        "Tap the orb to talk without the name. Drag it to move it. Long-press it to open this app.",
                13f, cDim
            ), lp(top = 6)
        )
        col.addView(
            label(helpText(), 14f, cText).apply { typeface = Typeface.MONOSPACE },
            lp(top = 14)
        )
        return wrapScroll(col)
    }

    private fun helpText() = """
        PROTOCOLS
        work protocol
        chill protocol
        <any name> protocol

        MEMORY
        remember my book name is Atomic Habits
        do you remember that book name
        what is my book name
        forget book name

        APPS
        open clock / calculator / camera
        open YouTube / WhatsApp / settings
        open github.com

        COMMUNICATION
        call Mom (or a number)
        send message to Mom saying I'm late
        whatsapp Mom saying hello

        PRODUCTIVITY
        set alarm at 6:30 am
        set timer 10 minutes
        remind me to drink water in 30 minutes
        note buy milk / read my notes / clear notes
        schedule meeting with team

        DEVICE
        flashlight on / off
        volume up / down / mute
        stealth mode / normal mode
        wifi / bluetooth / camera

        WEB
        play Shape of You
        search electric cars
        navigate to railway station
        weather in Mumbai / latest news

        INFO
        what's the time / date
        battery / status report
        12 times 4

        STARK MODE
        suit up / I am Iron Man
        party protocol
        inspire me / tell me a joke
        flip a coin / roll a dice
        say <anything>
        shut down (stops Jarvis)
    """.trimIndent()
}
