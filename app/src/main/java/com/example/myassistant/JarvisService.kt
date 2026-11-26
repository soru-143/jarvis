package com.example.myassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Jarvis in the background: always listening (even with the screen off), floating orb,
 * only reacts when it hears its name.
 *
 * Orb: tap = talk without the name, drag = move, long-press = open the app.
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var running = false
        const val ACTION_RELOAD = "com.example.myassistant.RELOAD"
        private const val ACTION_STOP = "com.example.myassistant.STOP"
        private const val CHANNEL_ID = "jarvis_channel"
        private const val NOTIF_ID = 1

        // JavaScript that every plugin runs on top of. Plugins talk to Jarvis through "jarvis".
        private const val PRELUDE = """
var __cmds = [], __cbs = {}, __n = 0;
var jarvis = {
  command: function (pattern, fn) { __cmds.push({ p: pattern, f: fn }); },
  say: function (t) { AndroidBridge.say(String(t)); },
  run: function (c) { AndroidBridge.run(String(c)); },
  open: function (a) { AndroidBridge.run('open ' + a); },
  openUrl: function (u) { AndroidBridge.openUrl(String(u)); },
  remember: function (l, v) { AndroidBridge.remember(String(l), String(v || '')); },
  recall: function (q) { return AndroidBridge.recall(String(q)); },
  get: function (url, cb) { var id = ++__n; __cbs[id] = cb; AndroidBridge.httpGet(String(url), id); },
  getJSON: function (url, cb) {
    jarvis.get(url, function (t) { var j = null; try { j = JSON.parse(t); } catch (e) {} cb(j); });
  }
};
function __resolve(id, t) {
  var f = __cbs[id]; delete __cbs[id];
  if (f) { try { f(t === '' ? null : t); } catch (e) { AndroidBridge.log('Plugin error: ' + e); } }
}
function __handle(text) {
  for (var i = 0; i < __cmds.length; i++) {
    var c = __cmds[i], m = null;
    try {
      if (c.p instanceof RegExp) {
        m = text.match(c.p);
      } else {
        var p = String(c.p).toLowerCase();
        if (text === p) { m = ''; }
        else if (text.indexOf(p + ' ') === 0) { m = text.substring(p.length + 1).trim(); }
      }
      if (m !== null && m !== undefined && m !== false) { c.f(text, m); return true; }
    } catch (e) { AndroidBridge.log('Plugin error: ' + e); return true; }
  }
  return false;
}
"""
    }

    // Wake words (lowercase). true = FRIDAY persona (Irish accent, calls you "boss").
    // Delete the "friday" line if ordinary talk triggers it too often.
    private val wakeWords = mapOf(
        "jarvis" to false, "jarvas" to false, "jarves" to false,
        "friday" to true
    )

    // Used to turn 10-digit contact numbers into WhatsApp numbers. Change to your country code.
    private val defaultCountryCode = "91"

    private val cyan = Color.parseColor("#00E5FF")
    private val amber = Color.parseColor("#FFB300")

    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var wm: WindowManager

    private var orbRoot: LinearLayout? = null
    private var orb: TextView? = null
    private var orbBg: GradientDrawable? = null
    private var bubble: TextView? = null

    private var web: WebView? = null
    private var webReady = false
    private var hasPlugins = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var ttsReady = false
    private var torchOn = false
    private var speaking = false
    private var protocolMode = false         // while a protocol runs, steps stay silent
    private var forceNext = false            // set by tapping the orb: next sentence needs no wake word
    private var shutdownAfterSpeech = false
    private var sir = "sir"
    private var persona = "Jarvis"

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

    private val smsRegex = Regex(
        "^(?:send (?:a )?(?:message|text|sms) to|text|message) (.+?) (?:saying|that|says) (.+)$"
    )
    private val waRegex = Regex(
        "^(?:send (?:a )?)?whatsapp (?:message )?(?:to )?(.+?) (?:saying|that|says) (.+)$"
    )

    // Apps that Android can open by type, even if their name is different on this phone
    private val categoryApps = mapOf(
        "calculator" to Intent.CATEGORY_APP_CALCULATOR,
        "calendar" to Intent.CATEGORY_APP_CALENDAR,
        "contacts" to Intent.CATEGORY_APP_CONTACTS,
        "gallery" to Intent.CATEGORY_APP_GALLERY,
        "photos" to Intent.CATEGORY_APP_GALLERY,
        "maps" to Intent.CATEGORY_APP_MAPS,
        "music" to Intent.CATEGORY_APP_MUSIC,
        "messages" to Intent.CATEGORY_APP_MESSAGING,
        "messaging" to Intent.CATEGORY_APP_MESSAGING,
        "sms" to Intent.CATEGORY_APP_MESSAGING,
        "browser" to Intent.CATEGORY_APP_BROWSER,
        "internet" to Intent.CATEGORY_APP_BROWSER,
        "email" to Intent.CATEGORY_APP_EMAIL,
        "mail" to Intent.CATEGORY_APP_EMAIL,
        "play store" to Intent.CATEGORY_APP_MARKET
    )

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!startInForeground()) {
            stopSelf()
            return
        }
        running = true

        // Keep the processor awake so listening continues with the screen off
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::Listening").apply {
            setReferenceCounted(false)
            acquire()
        }

        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(listener)
        showOrb()
        loadPlugins()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RELOAD -> loadPlugins()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        orbRoot?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
        }
        web?.let {
            try { it.destroy() } catch (e: Exception) { }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        if (::recognizer.isInitialized) recognizer.destroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    // --------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" and then a command")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun startInForeground(): Boolean {
        val n = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------- floating orb

    private fun showOrb() {
        if (!Settings.canDrawOverlays(this)) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E606222B"))
            setStroke(dp(3), cyan)
        }
        orbBg = bg

        val orbView = TextView(this).apply {
            text = "J"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(cyan)
            background = bg
        }
        orb = orbView

        val bubbleView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            maxWidth = dp(230)
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#EE0A0F14"))
                setStroke(dp(1), cyan)
            }
        }
        bubble = bubbleView

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(orbView, LinearLayout.LayoutParams(dp(60), dp(60)))
        root.addView(
            bubbleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

        orbView.setOnTouchListener(OrbTouch(lp))
        try {
            wm.addView(root, lp)
            orbRoot = root
        } catch (e: Exception) {
            orbRoot = null
        }
    }

    private inner class OrbTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var longPressed = false
        private val longPress = Runnable {
            if (!moved) {
                longPressed = true
                launch(Intent(this@JarvisService, MainActivity::class.java))
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPress, 700L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        orbRoot?.let { wm.updateViewLayout(it, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed) pushToTalk()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            return true
        }
    }

    private fun setOrbColor(color: Int) {
        orbBg?.setStroke(dp(3), color)
        orb?.setTextColor(color)
    }

    private val hideBubble = Runnable { bubble?.visibility = View.GONE }

    private fun showBubble(msg: String) {
        val b = bubble ?: return
        b.text = if (msg.length > 140) msg.take(137) + "..." else msg
        b.visibility = View.VISIBLE
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, 6000L)
    }

    private fun pushToTalk() {
        forceNext = true
        speaking = false
        handler.removeCallbacks(speakGuard)
        if (ttsReady) tts.stop()
        setOrbColor(cyan)
        showBubble("Listening, $sir...")
        restartSoon(100L)
    }

    // ------------------------------------------------------------- voice out

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            showBubble("Text-to-speech isn't available on this phone.")
            restartSoon(500L)
            return
        }
        ttsReady = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { onSpeechFinished() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { onSpeechFinished() }
            }
        })
        applyVoice(false)
        speak("Initializing. ${partOfDay()}, $sir. All systems online.")
    }

    private val speakGuard = Runnable { onSpeechFinished() }

    private fun onSpeechFinished() {
        handler.removeCallbacks(speakGuard)
        speaking = false
        setOrbColor(cyan)
        if (shutdownAfterSpeech) stopSelf() else restartSoon(500L)
    }

    private fun applyVoice(friday: Boolean) {
        sir = if (friday) "boss" else "sir"
        persona = if (friday) "Friday" else "Jarvis"
        if (!ttsReady) return
        if (friday) {
            val r = tts.setLanguage(Locale("en", "IE"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
            tts.setPitch(1.1f)
        } else {
            tts.setLanguage(Locale.getDefault())
            tts.setPitch(0.75f)   // deeper voice for Jarvis
        }
        tts.setSpeechRate(0.95f)
    }

    private fun speak(message: String) {
        showBubble("$persona: $message")
        // While a protocol runs, its steps only show text; the protocol announces itself once
        if (protocolMode) return
        if (!ttsReady) {
            if (shutdownAfterSpeech) stopSelf()
            return
        }
        speaking = true
        setOrbColor(amber)
        // Stop listening while talking, so Jarvis never hears himself
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(speakGuard)
        handler.postDelayed(speakGuard, 60000L)
        recognizer.cancel()
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    // ----------------------------------------------------------- voice in

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showBubble("Speech recognition isn't available. Install or update the Google app.")
            restartSoon(5000L)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            restartSoon(2000L)
        }
    }

    private val restartRunnable = Runnable {
        if (!speaking) {
            recognizer.cancel()
            startListening()
        }
    }

    private fun restartSoon(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!speaking) setOrbColor(cyan)
        }

        override fun onResults(results: Bundle?) {
            val heard = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.lowercase()
            val forced = forceNext
            forceNext = false
            if (heard != null) handleSpeech(heard, forced)
            if (!speaking) restartSoon(300L)
        }

        override fun onError(error: Int) {
            if (speaking) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                showBubble("Microphone permission is missing. Open the app and allow it.")
                stopSelf()
                return
            }
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                forceNext = false
            }
            restartSoon(
                when (error) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> 2500L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                    else -> 400L
                }
            )
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------- plugins

    private fun loadPlugins() {
        web?.let {
            try { it.destroy() } catch (e: Exception) { }
        }
        web = null
        webReady = false

        val active = JarvisStore.plugins(this).filter { it.enabled }
        hasPlugins = active.isNotEmpty()
        if (!hasPlugins) return

        try {
            val w = WebView(applicationContext)
            w.settings.javaScriptEnabled = true
            w.addJavascriptInterface(Bridge(), "AndroidBridge")
            w.webViewClient = object : WebViewClient() {
                // Plugins must never navigate away from the sandbox page
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = true

                override fun onPageFinished(view: WebView?, url: String?) {
                    for (p in active) {
                        val js = "try { eval(" + JSONObject.quote(p.code) + "); } " +
                                "catch (e) { AndroidBridge.log('Plugin ' + " +
                                JSONObject.quote(p.name) + " + ' error: ' + e); }"
                        view?.evaluateJavascript(js, null)
                    }
                    webReady = true
                }
            }
            web = w
            w.loadDataWithBaseURL(
                "https://jarvis.local/",
                "<html><body><script>" + PRELUDE + "</script></body></html>",
                "text/html", "utf-8", null
            )
        } catch (e: Exception) {
            hasPlugins = false
            showBubble("Plugins couldn't start on this phone.")
        }
    }

    /** Functions that plugin code can call. They arrive on a background thread. */
    inner class Bridge {
        @JavascriptInterface
        fun say(text: String) {
            handler.post { speak(text) }
        }

        @JavascriptInterface
        fun run(cmd: String) {
            handler.post { runCommand(normalizeStep(cmd)) }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            handler.post { this@JarvisService.openUrl(url) }
        }

        @JavascriptInterface
        fun remember(label: String, value: String) {
            JarvisStore.addMemory(this@JarvisService, label, value)
        }

        @JavascriptInterface
        fun recall(query: String): String =
            JarvisStore.findMemory(this@JarvisService, query)?.value ?: ""

        @JavascriptInterface
        fun log(msg: String) {
            handler.post { showBubble(msg) }
        }

        @JavascriptInterface
        fun httpGet(url: String, id: Int) {
            Thread {
                val result = try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.inputStream.bufferedReader().use { it.readText() }.take(200000)
                } catch (e: Exception) {
                    ""
                }
                handler.post {
                    web?.evaluateJavascript("__resolve($id, " + JSONObject.quote(result) + ")", null)
                }
            }.start()
        }
    }

    // ---------------------------------------------------------------- brain

    private fun has(cmd: String, vararg words: String) = words.any { cmd.contains(it) }

    private fun handleSpeech(text: String, forced: Boolean) {
        val word = wakeWords.keys.firstOrNull { text.contains(it) }
        if (word == null && !forced) return          // ignore everything without the name
        if (word != null) applyVoice(wakeWords[word] == true)
        val cmd = (if (word != null) text.substringAfter(word) else text)
            .trim().trim(',', ' ', '.')
        dispatch(cmd)
    }

    // Plugins get the first chance; if none of them handles the command, the built-in brain does
    private fun dispatch(cmd: String) {
        val w = web
        if (w == null || !webReady || !hasPlugins || cmd.isEmpty()) {
            runCommand(cmd)
            return
        }
        w.evaluateJavascript("__handle(" + JSONObject.quote(cmd) + ")") { result ->
            if (result != "true") runCommand(cmd)
        }
    }

    // Lines typed into a protocol: keep the case of web addresses, lowercase everything else
    private fun normalizeStep(line: String): String {
        val t = line.trim()
        return if (t.lowercase().startsWith("open ")) "open " + t.substring(5).trim() else t.lowercase()
    }

    private fun runCommand(cmd: String) {
        when {
            cmd.isEmpty() -> speak("At your service, $sir.")

            // ---- commands with arguments (checked first so song names etc. can't confuse them)
            cmd.startsWith("remind me") -> remind(cmd)
            waRegex.containsMatchIn(cmd) -> sendWhatsApp(waRegex.find(cmd)!!)
            smsRegex.containsMatchIn(cmd) -> sendSms(smsRegex.find(cmd)!!)

            // memory
            has(cmd, "do you remember", "did i tell you") -> {
                val m = JarvisStore.findMemory(this, cmd)
                if (m != null) speak(recallLine(m))
                else speak("I'm afraid I don't remember that, $sir. Tell me with: remember, and then what to remember.")
            }
            cmd.startsWith("remember ") -> rememberCmd(cmd)
            cmd.startsWith("forget ") -> forgetCmd(cmd)

            // notes
            has(cmd, "read my notes", "read notes", "what are my notes") -> readNotes()
            has(cmd, "clear notes", "delete notes", "delete my notes") -> {
                prefs.edit().remove("notes").apply()
                speak("Notes cleared, $sir.")
            }
            cmd.startsWith("note ") || cmd.startsWith("take a note") ->
                saveNote(cmd.replace(Regex("^(take a note|note)( that)?"), "").trim())

            cmd.startsWith("open ") || cmd.startsWith("launch ") ->
                openApp(cmd.substringAfter(" ").trim())
            cmd.startsWith("call ") || cmd.startsWith("phone ") ->
                callPerson(cmd.substringAfter(" ").trim())
            cmd.startsWith("play ") -> {
                val q = cmd.removePrefix("play ").replace(" on youtube", "").trim()
                speak("Playing $q, $sir.")
                playOnYoutube(q)
            }
            cmd.startsWith("search ") || cmd.startsWith("google ") -> {
                val q = cmd.substringAfter(" ").removePrefix("for ").trim()
                speak("Searching for $q, $sir.")
                webSearch(q)
            }
            cmd.startsWith("navigate to") || cmd.startsWith("directions to") ||
                    cmd.startsWith("take me to") -> {
                val place = cmd.substringAfter(" to ").trim()
                speak("Navigating to $place, $sir.")
                navigate(place)
            }
            cmd.startsWith("schedule ") || cmd.startsWith("add event ") ->
                addEvent(cmd.removePrefix("schedule ").removePrefix("add event ").trim())
            cmd.startsWith("say ") || cmd.startsWith("repeat after me") ->
                speak(
                    cmd.removePrefix("repeat after me").removePrefix("say ").trim()
                        .ifEmpty { "Say what, $sir?" }
                )

            // ---- protocols ("work protocol", "activate chill protocol", ...)
            findProtocolName(cmd) != null -> runProtocol(findProtocolName(cmd)!!)

            // ---- shut the assistant down completely
            has(cmd, "shut down", "shutdown", "go to sleep", "power off", "stop listening") -> {
                shutdownAfterSpeech = true
                speak("Shutting down, $sir. Open the app to wake me.")
            }

            // ---- Stark personality
            has(cmd, "iron man") ->
                speak("And I am $persona, $sir. Though I suspect you already knew that.")
            has(cmd, "suit up") -> {
                setTorch(true)
                speak("Suit up, $sir. I only have a phone, so I have turned on the flashlight.")
            }
            has(cmd, "party") -> {
                speak("House party protocol initiated, $sir.")
                playOnYoutube("party songs")
            }
            has(cmd, "who are you", "your name") ->
                speak("I am $persona, a rather intelligent system, $sir.")
            has(cmd, "joke") -> speak(jokes().random())
            has(cmd, "quote", "inspire", "motivate") -> speak(starkLines().random())
            has(cmd, "thank") -> speak("Always a pleasure, $sir.")
            has(cmd, "good night") -> speak("Good night, $sir. Rest well.")
            has(cmd, "good morning", "good afternoon", "good evening", "hello") ->
                speak("${partOfDay()}, $sir. All systems are online.")
            has(cmd, "flip a coin", "toss a coin") ->
                speak("It is ${listOf("heads", "tails").random()}, $sir.")
            has(cmd, "roll a dice", "roll the dice", "roll a die") ->
                speak("You rolled a ${(1..6).random()}, $sir.")

            // ---- info
            has(cmd, "status", "report", "diagnostic") -> statusReport()
            has(cmd, "battery") -> speak("Battery is at ${batteryPercent()} percent, $sir.")
            has(cmd, "alarm") -> setAlarm(cmd)
            has(cmd, "timer") -> setTimer(cmd)
            has(cmd, "what time", "the time", "current time", "time is it") ->
                speak("It is ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}, $sir.")
            has(cmd, "the date", "what date", "what day", "today") ->
                speak("Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())}, $sir.")
            has(cmd, "weather") -> {
                val q = if (cmd.contains(" in ")) "weather " + cmd.substringAfter(" in ").trim()
                else "weather today"
                speak("Checking the weather, $sir.")
                webSearch(q)
            }
            has(cmd, "news") -> {
                speak("Pulling up the latest news, $sir.")
                webSearch("latest news")
            }

            // ---- device control
            has(cmd, "flashlight", "torch") -> {
                val on = when {
                    cmd.contains("off") -> false
                    cmd.split(" ").contains("on") -> true
                    else -> !torchOn
                }
                if (setTorch(on)) speak(if (on) "Flashlight on, $sir." else "Flashlight off, $sir.")
                else speak("I can't reach the flashlight, $sir.")
            }
            has(cmd, "stealth mode", "vibrate mode", "silent mode") ->
                setRinger(AudioManager.RINGER_MODE_VIBRATE, "Stealth mode active, $sir. Phone on vibrate.")
            has(cmd, "normal mode", "ring mode", "sound on") ->
                setRinger(AudioManager.RINGER_MODE_NORMAL, "Ringer restored, $sir.")
            has(cmd, "unmute") -> { volume(AudioManager.ADJUST_UNMUTE); speak("Unmuted, $sir.") }
            has(cmd, "mute") -> volume(AudioManager.ADJUST_MUTE)
            has(cmd, "volume up", "louder") -> volume(AudioManager.ADJUST_RAISE)
            has(cmd, "volume down", "quieter", "lower the volume") -> volume(AudioManager.ADJUST_LOWER)
            has(cmd, "wifi", "wi-fi", "wi fi") -> {
                launch(Intent(Settings.ACTION_WIFI_SETTINGS))
                speak("Opening Wi-Fi settings, $sir.")
            }
            has(cmd, "bluetooth") -> {
                launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                speak("Opening Bluetooth settings, $sir.")
            }
            has(cmd, "camera", "photo", "picture") -> {
                launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                speak("Camera ready, $sir.")
            }

            tryRecall(cmd) -> { /* answered inside tryRecall() */ }
            calculate(cmd) -> { /* answered inside calculate() */ }

            else -> speak("I'm afraid I can't do that yet, $sir.")
        }
    }

    // ------------------------------------------------------------ content

    private fun jokes() = listOf(
        "I would tell you a UDP joke, $sir, but you might not get it.",
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I would tell you a joke about construction, $sir, but I am still working on it.",
        "There are only 10 kinds of people, $sir. Those who understand binary and those who do not."
    )

    private fun starkLines() = listOf(
        "Genius is rarely comfortable, $sir, but it is very useful.",
        "Every great suit began as a bad idea and a late night, $sir.",
        "Failure is only data with better lighting, $sir.",
        "Build first, apologize to the neighbors later, $sir.",
        "The future will not wait, $sir. Neither should we."
    )

    private fun partOfDay(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    // ------------------------------------------------------------ protocols

    private fun findProtocolName(cmd: String): String? {
        val m = Regex("(?:activate |start |run |initiate |engage |begin )?(.+?) protocol").find(cmd)
            ?: return null
        val name = m.groupValues[1].trim().removePrefix("the ").trim()
        return if (JarvisStore.findProtocol(this, name) != null) name else null
    }

    private fun runProtocol(name: String) {
        val p = JarvisStore.findProtocol(this, name) ?: return
        val steps = p.steps.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val title = p.name.replaceFirstChar { it.uppercase() }
        if (steps.isEmpty()) {
            speak("The $title protocol has no steps yet, $sir. Add some in the Protocols tab of the app.")
            return
        }
        speak("$title protocol engaged, $sir.")
        protocolMode = true
        steps.forEachIndexed { i, step ->
            handler.postDelayed({ dispatch(normalizeStep(step)) }, 1800L + i * 2500L)
        }
        handler.postDelayed({ protocolMode = false }, 1800L + steps.size * 2500L + 500L)
    }

    // ------------------------------------------------------------ memory

    private fun recallLine(m: JarvisStore.Memory): String {
        val label = m.label.removePrefix("my ").trim()
        return if (m.value.isEmpty()) "Yes, of course, $sir. You asked me to remember: ${m.label}."
        else "Yes, of course, $sir. Your $label is ${m.value}."
    }

    private fun rememberCmd(cmd: String) {
        val body = cmd.removePrefix("remember ").removePrefix("that ").trim()
        if (body.isEmpty()) {
            speak("What should I remember, $sir?")
            return
        }
        val m = Regex("^(?:my )?(.+?) (?:is|are|was|equals) (.+)$").find(body)
        if (m != null) {
            JarvisStore.addMemory(this, m.groupValues[1], m.groupValues[2])
        } else {
            JarvisStore.addMemory(this, body, "")
        }
        speak("Remembered, $sir.")
    }

    private fun forgetCmd(cmd: String) {
        val m = JarvisStore.findMemory(this, cmd.removePrefix("forget "))
        if (m == null) {
            speak("I don't have anything like that stored, $sir.")
        } else {
            JarvisStore.deleteMemoryByLabel(this, m.label)
            speak("Forgotten, $sir.")
        }
    }

    private fun tryRecall(cmd: String): Boolean {
        if (!has(cmd, "what is my", "what's my", "what was my", "tell me my", "recall")) return false
        val m = JarvisStore.findMemory(this, cmd) ?: return false
        speak(recallLine(m))
        return true
    }

    // ------------------------------------------------------------- actions

    private fun batteryIntent() = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun batteryPercent(): Int {
        val i = batteryIntent()
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    private fun statusReport() {
        val charging = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                BatteryManager.BATTERY_STATUS_CHARGING
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val freeGb = StatFs(Environment.getDataDirectory().path).availableBytes / (1024L * 1024L * 1024L)
        speak(
            "All systems online, $sir. It is $time. Battery at ${batteryPercent()} percent" +
                    (if (charging) ", charging" else "") +
                    ". $freeGb gigabytes of storage free."
        )
    }

    private fun setTorch(on: Boolean): Boolean = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.setTorchMode(cm.cameraIdList[0], on)
        torchOn = on
        true
    } catch (e: Exception) {
        false
    }

    private fun volume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun setRinger(mode: Int, message: String) {
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = mode
            speak(message)
        } catch (e: Exception) {
            speak("Android won't let me change that, $sir.")
        }
    }

    private fun toSeconds(n: Int, unit: String) = when (unit) {
        "hour" -> n * 3600
        "minute" -> n * 60
        else -> n
    }

    private fun setAlarm(command: String) {
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(command)
        if (match == null) {
            speak("What time should I set the alarm for, $sir?")
            return
        }
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = match.groupValues[3].replace(".", "")
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (launch(intent)) speak("Alarm set for %d:%02d, $sir.".format(hour, minute))
        else speak("I couldn't reach the clock app, $sir.")
    }

    private fun setTimer(command: String) {
        val m = Regex("(\\d+)\\s*(second|minute|hour)").find(command)
        if (m == null) {
            speak("For how long, $sir?")
            return
        }
        val n = m.groupValues[1].toInt()
        val unit = m.groupValues[2]
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, toSeconds(n, unit))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (launch(intent)) speak("Timer started for $n ${unit}${if (n != 1) "s" else ""}, $sir.")
        else speak("I couldn't reach the clock app, $sir.")
    }

    private fun remind(cmd: String) {
        val m = Regex("remind me (?:to )?(.+?) in (\\d+)\\s*(second|minute|hour)").find(cmd)
        if (m == null) {
            speak("Try saying: remind me to call Mom in 10 minutes, $sir.")
            return
        }
        val task = m.groupValues[1]
        val n = m.groupValues[2].toInt()
        val unit = m.groupValues[3]
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, toSeconds(n, unit))
            putExtra(AlarmClock.EXTRA_MESSAGE, task)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (launch(intent)) speak("I will remind you to $task in $n ${unit}${if (n != 1) "s" else ""}, $sir.")
        else speak("I couldn't reach the clock app, $sir.")
    }

    private fun saveNote(text: String) {
        if (text.isEmpty()) {
            speak("What should I note down, $sir?")
            return
        }
        val stamp = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date())
        val old = prefs.getString("notes", "") ?: ""
        prefs.edit().putString("notes", (if (old.isEmpty()) "" else "$old\n") + "[$stamp] $text").apply()
        speak("Noted, $sir.")
    }

    private fun readNotes() {
        val notes = prefs.getString("notes", "") ?: ""
        if (notes.isEmpty()) {
            speak("You have no notes, $sir.")
            return
        }
        val spoken = notes.lines().joinToString(". ") { it.substringAfter("] ") }
        speak("Your notes, $sir. $spoken")
    }

    private fun addEvent(title: String) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        if (launch(intent)) speak("Opening the calendar for $title, $sir.")
        else speak("I couldn't reach the calendar, $sir.")
    }

    // ---- opening apps and websites

    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim().replace(" dot ", ".")
        return Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(t) ||
                Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$", RegexOption.IGNORE_CASE).matches(t)
    }

    private fun openUrl(raw: String) {
        var u = raw.trim().replace(" dot ", ".")
        if (!u.startsWith("http", ignoreCase = true)) u = "https://$u"
        val host = try { Uri.parse(u).host ?: u } catch (e: Exception) { u }
        speak("Opening $host, $sir.")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    private val fillerApp = setOf("the", "app", "application", "my", "please", "open", "launch", "start")

    private fun normalizeName(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ").filter { it.isNotEmpty() && it !in fillerApp }
            .joinToString(" ")

    /** Finds the installed app whose name best matches what was said. Returns its package name. */
    private fun findApp(name: String): String? {
        val q = name.replace(" ", "")
        if (q.isEmpty()) return null
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var bestPkg: String? = null
        var bestScore = 0
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val label = normalizeName(ri.loadLabel(pm).toString()).replace(" ", "")
            val pkg = ri.activityInfo.packageName
            val score = when {
                label.isEmpty() -> 0
                label == q -> 100
                label.startsWith(q) -> 80
                q.startsWith(label) && label.length >= 3 -> 70
                label.contains(q) && q.length >= 3 -> 60
                q.contains(label) && label.length >= 4 -> 55
                q.length >= 4 && pkg.lowercase().replace(".", "").contains(q) -> 40
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestPkg = pkg
            }
        }
        return if (bestScore >= 40) bestPkg else null
    }

    private fun openApp(raw: String) {
        if (looksLikeUrl(raw)) {
            openUrl(raw)
            return
        }
        val name = normalizeName(raw)
        if (name.isEmpty()) {
            speak("Which app, $sir?")
            return
        }

        // 1) Apps Android can open directly (clock, settings, dialer...), whatever they are called
        val direct: Intent? = when (name) {
            "clock", "alarm", "alarms", "alarm clock", "world clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "timer", "timers", "stopwatch" -> Intent(AlarmClock.ACTION_SHOW_TIMERS)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "dialer", "phone" -> Intent(Intent.ACTION_DIAL)
            else -> null
        }
        if (direct != null && launch(direct)) {
            speak("Opening $name, $sir.")
            return
        }

        // 2) Best name match among the installed apps
        val pkg = findApp(name)
        if (pkg != null) {
            val li = packageManager.getLaunchIntentForPackage(pkg)
            if (li != null && launch(li)) {
                speak("Opening $name, $sir.")
                return
            }
        }

        // 3) Standard app types (calculator, gallery, maps...) and the camera
        val fallback: Intent? = categoryApps[name]
            ?.let { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, it) }
            ?: (if (name == "camera") Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) else null)
        if (fallback != null && launch(fallback)) {
            speak("Opening $name, $sir.")
            return
        }

        speak("I couldn't find $name on this phone, $sir.")
    }

    // ---- contacts and messaging

    private fun resolveNumber(who: String): String? {
        val digits = who.replace(" ", "")
        return if (digits.matches(Regex("\\+?\\d{5,}"))) digits else findContactNumber(who)
    }

    private fun findContactNumber(name: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return null
        val cursor = contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun callPerson(who: String) {
        val number = resolveNumber(who)
        if (number == null) {
            speak("I couldn't find $who in your contacts, $sir.")
            return
        }
        speak("Calling $who, $sir.")
        launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    }

    private fun sendSms(m: MatchResult) {
        val who = m.groupValues[1].trim()
        val body = m.groupValues[2].trim()
        val number = resolveNumber(who)
        if (number == null) {
            speak("I couldn't find $who in your contacts, $sir.")
            return
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", body)
        if (launch(intent)) speak("Message to $who is ready, $sir. Press send.")
        else speak("I couldn't open the messaging app, $sir.")
    }

    private fun normalizeNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) {
            d = d.drop(2)
        } else {
            d = d.trimStart('0')
            if (d.length == 10) d = defaultCountryCode + d
        }
        return d
    }

    private fun sendWhatsApp(m: MatchResult) {
        val who = m.groupValues[1].trim()
        val body = m.groupValues[2].trim()
        val number = resolveNumber(who)?.let { normalizeNumber(it) }
        if (number == null) {
            speak("I couldn't find $who in your contacts, $sir.")
            return
        }
        val url = "https://wa.me/$number?text=" + Uri.encode(body)
        speak("WhatsApp message to $who is ready, $sir. Press send.")
        val inApp = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
        if (!launch(inApp)) launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    // ---- web

    private fun playOnYoutube(query: String) {
        val inApp = Intent(Intent.ACTION_SEARCH)
            .setPackage("com.google.android.youtube")
            .putExtra("query", query)
        if (!launch(inApp)) {
            launch(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
        }
    }

    private fun webSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!launch(intent)) {
            launch(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
    }

    private fun navigate(place: String) {
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
            .setPackage("com.google.android.apps.maps")
        if (!launch(maps)) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        }
    }

    private fun calculate(cmd: String): Boolean {
        val m = Regex(
            "(\\d+(?:\\.\\d+)?)\\s*(plus|\\+|minus|times|multiplied by|x|divided by|over)\\s*(\\d+(?:\\.\\d+)?)"
        ).find(cmd) ?: return false
        val a = m.groupValues[1].toDouble()
        val b = m.groupValues[3].toDouble()
        val r = when (m.groupValues[2]) {
            "plus", "+" -> a + b
            "minus" -> a - b
            "times", "multiplied by", "x" -> a * b
            else -> {
                if (b == 0.0) {
                    speak("Division by zero, $sir. Even I have limits.")
                    return true
                }
                a / b
            }
        }
        val shown = if (r == r.toLong().toDouble()) r.toLong().toString() else "%.2f".format(r)
        speak("That is $shown, $sir.")
        return true
    }

    // From a background service every screen must be opened in a new task
    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
