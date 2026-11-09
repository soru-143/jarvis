package com.example.myassistant

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // Wake words (lowercase). true = FRIDAY persona (Irish accent, calls you "boss").
    // Delete "friday" from this list if ambient chatter triggers it too often.
    private val wakeWords = mapOf(
        "jarvis" to false, "jarvas" to false, "jarves" to false,
        "friday" to true
    )

    // Used to turn 10-digit contact numbers into WhatsApp numbers. Change to your country code.
    private val defaultCountryCode = "91"

    private val cyan = Color.parseColor("#00E5FF")
    private val darkBg = Color.parseColor("#0A0F14")

    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var clockText: TextView
    private lateinit var stateText: TextView
    private lateinit var alwaysBtn: Button
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech

    private var ttsReady = false
    private var torchOn = false
    private var alwaysOn = false
    private var speaking = false
    private var paused = false
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

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(darkBg)
            setPadding((20 * d).toInt(), (40 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        }
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 26f
            setTextColor(cyan)
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
        }
        clockText = TextView(this).apply {
            textSize = 34f
            typeface = Typeface.MONOSPACE
            setTextColor(cyan)
            gravity = Gravity.CENTER
        }
        stateText = TextView(this).apply {
            text = "STANDBY"
            textSize = 13f
            setTextColor(Color.parseColor("#5BB8C9"))
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        logText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9BE7F5"))
            text = helpText()
        }
        scroll = ScrollView(this).apply { addView(logText) }

        val size = (150 * d).toInt()
        val talkBtn = Button(this).apply {
            text = "TAP TO\nSPEAK"
            setTextColor(cyan)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#06222B"))
                setStroke((4 * d).toInt(), cyan)
            }
            setOnClickListener { startListening() }
        }
        alwaysBtn = Button(this).apply {
            text = "ALWAYS-ON: OFF"
            setTextColor(cyan)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * d
                setColor(Color.parseColor("#06222B"))
                setStroke((2 * d).toInt(), cyan)
            }
            setOnClickListener { setAlwaysOn(!alwaysOn) }
        }

        root.addView(title, LinearLayout.LayoutParams(match, wrap))
        root.addView(clockText, LinearLayout.LayoutParams(match, wrap))
        root.addView(stateText, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f).apply {
            topMargin = (12 * d).toInt()
        })
        root.addView(talkBtn, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (12 * d).toInt()
        })
        root.addView(alwaysBtn, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = (14 * d).toInt()
        })
        setContentView(root)

        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(listener)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS),
            1
        )
        handler.post(clockTick)
    }

    private fun helpText() = """
        Say my name (Jarvis or Friday), then:

        HANDS-FREE
        • tap ALWAYS-ON and keep the app open

        COMMUNICATION
        • call Mom (or a number)
        • send message to Mom saying I'm late
        • whatsapp Mom saying hello

        PRODUCTIVITY
        • set alarm at 6:30 am
        • set timer 10 minutes
        • remind me to drink water in 30 minutes
        • note buy milk / read my notes / clear notes
        • schedule meeting with team

        DEVICE
        • open YouTube
        • flashlight on / off
        • volume up / down / mute
        • stealth mode / normal mode
        • wifi / bluetooth / camera

        WEB
        • play Shape of You
        • search electric cars
        • navigate to railway station
        • weather in Mumbai / latest news

        INFO
        • what's the time / date
        • battery / status report
        • 12 times 4

        STARK MODE
        • suit up / I am Iron Man
        • party protocol
        • inspire me / tell me a joke
        • flip a coin / roll a dice
        • say <anything>
        • go to sleep (stops always-on)
    """.trimIndent()

    // HUD clock, refreshed every second
    private val clockTick = object : Runnable {
        override fun run() {
            clockText.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) +
                    "  " + batteryPercent() + "%"
            handler.postDelayed(this, 1000)
        }
    }

    private fun log(msg: String) {
        if (logText.length() > 6000) logText.text = logText.text.takeLast(4000)
        logText.append("\n\n$msg")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------- voice out

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            log("Text-to-speech isn't available on this phone.")
            return
        }
        ttsReady = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { speaking = false; if (alwaysOn) restartSoon(500L) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { speaking = false; if (alwaysOn) restartSoon(500L) }
            }
        })
        applyVoice(false)
        speak("Initializing. ${partOfDay()}, $sir. All systems online.")
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
        log("$persona: $message")
        if (!ttsReady) return
        speaking = true
        if (alwaysOn) {
            // Stop listening while talking, so Jarvis never hears himself
            handler.removeCallbacks(restartRunnable)
            recognizer.cancel()
        }
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    // ----------------------------------------------------------- voice in

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("Speech recognition isn't available. Install or update the Google app.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        if (!alwaysOn) stateText.text = "LISTENING..."
        recognizer.startListening(intent)
    }

    private val restartRunnable = Runnable {
        if (alwaysOn && !speaking && !paused) {
            recognizer.cancel()
            startListening()
        }
    }

    private fun restartSoon(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun setAlwaysOn(on: Boolean) {
        alwaysOn = on
        alwaysBtn.text = if (on) "ALWAYS-ON: ON" else "ALWAYS-ON: OFF"
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            stateText.text = "ARC REACTOR ONLINE - LISTENING"
            if (!speaking) restartSoon(200L)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            handler.removeCallbacks(restartRunnable)
            recognizer.cancel()
            stateText.text = "STANDBY"
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val heard = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.lowercase()
            if (heard != null) handleSpeech(heard)
            if (alwaysOn) {
                if (!speaking) restartSoon(400L)
            } else {
                stateText.text = "STANDBY"
            }
        }

        override fun onError(error: Int) {
            when {
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    log("Microphone permission is missing. Enable it in app settings.")
                    setAlwaysOn(false)
                }
                alwaysOn -> restartSoon(
                    when (error) {
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> 2500L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                        else -> 400L
                    }
                )
                else -> {
                    stateText.text = "STANDBY"
                    log(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "I didn't catch that. Tap and try again."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "No internet connection."
                            else -> "Error code $error"
                        }
                    )
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // Stop the mic when the app is in the background; resume when it returns
    override fun onPause() {
        super.onPause()
        paused = true
        handler.removeCallbacks(restartRunnable)
        if (alwaysOn) recognizer.cancel()
    }

    override fun onResume() {
        super.onResume()
        paused = false
        if (alwaysOn && !speaking) restartSoon(600L)
    }

    // ---------------------------------------------------------------- brain

    private fun has(cmd: String, vararg words: String) = words.any { cmd.contains(it) }

    private fun handleSpeech(text: String) {
        val word = wakeWords.keys.firstOrNull { text.contains(it) }
        if (word == null) {
            if (!alwaysOn) log("Heard: \"$text\" (say my name first)")
            return
        }
        applyVoice(wakeWords[word] == true)
        val cmd = text.substringAfter(word).trim().trim(',', ' ', '.')
        log("You: $cmd")

        when {
            cmd.isEmpty() -> speak("At your service, $sir.")

            // ---- commands with arguments (checked first so song names etc. can't confuse them)
            cmd.startsWith("remind me") -> remind(cmd)
            waRegex.containsMatchIn(cmd) -> sendWhatsApp(waRegex.find(cmd)!!)
            smsRegex.containsMatchIn(cmd) -> sendSms(smsRegex.find(cmd)!!)
            has(cmd, "read my notes", "read notes", "what are my notes") -> readNotes()
            has(cmd, "clear notes", "delete notes", "delete my notes") -> {
                prefs.edit().remove("notes").apply()
                speak("Notes cleared, $sir.")
            }
            cmd.startsWith("note ") || cmd.startsWith("take a note") || cmd.startsWith("remember ") ->
                saveNote(cmd.replace(Regex("^(take a note|note|remember)( that)?"), "").trim())
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

            // ---- hands-free mode
            has(cmd, "always on", "always-on", "stay awake", "stay online") -> {
                setAlwaysOn(true)
                speak("Always-on mode engaged, $sir. Keep the app open on screen.")
            }
            has(cmd, "go to sleep", "stop listening", "standby") -> {
                setAlwaysOn(false)
                speak("Going to standby, $sir.")
            }

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
            has(cmd, "wifi", "wi-fi", "wi fi", "internet") -> {
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

    private fun openApp(name: String) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val app = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(name)
        }
        val launchIntent = app?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launchIntent == null) {
            speak("I couldn't find $name, $sir.")
            return
        }
        speak("Opening $name, $sir.")
        startActivity(launchIntent)
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

    private fun launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
