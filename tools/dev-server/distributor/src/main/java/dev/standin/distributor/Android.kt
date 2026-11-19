package dev.standin.distributor

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.util.concurrent.Executors

private const val TAG = "StandInDistributor"

/** How it binds to an app's RAISE_TO_FOREGROUND service: BIND_AUTO_CREATE alone, as ntfy and android-distributor do. */
const val RAISE_BIND_FLAGS = Context.BIND_AUTO_CREATE

/** The protocol's names on the wire. */
private object Up {
    const val REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"

    const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"
    const val RAISE_TO_FOREGROUND = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"

    const val TOKEN = "token"
    const val APPLICATION = "application"
    const val PI = "pi"
    const val VAPID = "vapid"
    const val MESSAGE_TEXT = "message"
    const val ID = "id"
    const val ENDPOINT = "endpoint"
    const val REASON = "reason"
    const val BYTES_MESSAGE = "bytesMessage"
    const val USE_DISTRIBUTOR = "useDistributor"
    const val NEW_DISTRIBUTOR = "newDistributor"

    const val DUMMY_APP = "org.unifiedpush.dummy_app"
}

/** The one [Core] of this process, and the thread everything a broadcast asks for runs on. */
object Distributor {
    @Volatile private var core: Core? = null
    val work = Executors.newSingleThreadExecutor { Thread(it, "distributor") }

    fun core(context: Context): Core = core ?: synchronized(this) {
        core ?: context.applicationContext.let { app ->
            Core(Prefs(app), HttpStandIn(), Broadcasts(app), Switches(app)).also { core = it }
        }
    }

    /** Makes sure the polling service runs; from the background that is allowed only while exempted. */
    fun ensurePolling(context: Context) {
        if (PollService.running) return
        try {
            context.startForegroundService(Intent(context, PollService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "The polling service could not start (${e.javaClass.simpleName}): open the stand-in distributor once, or exempt it from battery optimisation.")
        }
    }
}

private class Prefs(context: Context) : Saved {
    private val prefs = context.getSharedPreferences("distributor", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("state", null)
    override fun write(text: String) {
        prefs.edit().putString("state", text).commit()
    }
}

/** [Components] through the package manager; the steering receiver and the main activity stay on. */
private class Switches(private val context: Context) : Components {
    override fun enabled(on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (component in listOf(DistributorReceiver::class.java, LinkActivity::class.java)) {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, component), state, PackageManager.DONT_KILL_APP)
        }
        Log.i(TAG, "Its receiver and link activity are ${if (on) "on" else "off"}.")
    }
}

/** [Apps] as the protocol's explicit broadcasts. Never logs a token, an endpoint or a message. */
private class Broadcasts(private val context: Context) : Apps {
    private val main = Handler(Looper.getMainLooper())

    override fun newEndpoint(app: String, token: String, endpoint: String, id: String) =
        send(Intent(Up.NEW_ENDPOINT).putExtra(Up.TOKEN, token).putExtra(Up.ENDPOINT, endpoint).putExtra(Up.ID, id), app)

    override fun failed(app: String, token: String, reason: String?) =
        send(Intent(Up.REGISTRATION_FAILED).putExtra(Up.TOKEN, token).apply { reason?.let { putExtra(Up.REASON, it) } }, app)

    override fun unregistered(app: String, token: String, moveTo: String?) =
        send(Intent(Up.UNREGISTERED).apply { unregisteredExtras(token, moveTo).forEach { (name, value) -> putExtra(name, value) } }, app)

    override fun tempUnavailable(app: String, token: String, fallback: String?) =
        send(Intent(Up.TEMP_UNAVAILABLE).putExtra(Up.TOKEN, token).apply { fallback?.let { putExtra(Up.NEW_DISTRIBUTOR, it).putExtra(Up.USE_DISTRIBUTOR, it) } }, app)

    /**
     * MESSAGE, through the app's RAISE_TO_FOREGROUND service (the protocol's way to lend it
     * foreground importance): bound with BIND_AUTO_CREATE only, sent once connected, and unbound
     * [RAISE_WAIT_MS] after it was bound, as ntfy does; dropped when no connection comes within
     * [RAISE_WAIT_MS], or the binding is null or dies ([Raise]). An app that offers no such
     * service gets it straight away.
     */
    override fun message(app: String, token: String, body: ByteArray, id: String) {
        val intent = Intent(Up.MESSAGE).putExtra(Up.TOKEN, token).putExtra(Up.BYTES_MESSAGE, body).putExtra(Up.ID, id)
        val raise = Raise()
        lateinit var connection: ServiceConnection
        val unbind = Runnable { runCatching { context.unbindService(connection) } }
        fun decide(event: Raise.Event) {
            val outcome = raise.on(event)
            when (outcome) {
                Raise.Outcome.SEND -> send(intent, app)
                Raise.Outcome.DROP -> {
                    Distributor.core(context).notRaised()
                    val why = if (event == Raise.Event.TIMED_OUT) "did not connect within ${RAISE_WAIT_MS / 1000} s" else "gave no binding"
                    Log.w(TAG, "MESSAGE to $app dropped: its RAISE_TO_FOREGROUND service $why.")
                }
                null -> Unit
            }
            if (Raise.unbinds(event, outcome)) unbind.run()
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = decide(Raise.Event.CONNECTED)
            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onNullBinding(name: ComponentName?) = decide(Raise.Event.NULL_BINDING)
            override fun onBindingDied(name: ComponentName?) = decide(Raise.Event.DIED)
        }
        val bound = runCatching {
            context.bindService(Intent(Up.RAISE_TO_FOREGROUND).setPackage(app), connection, RAISE_BIND_FLAGS)
        }.getOrDefault(false)
        if (!bound) {
            unbind.run()
            Log.i(TAG, "$app offers no RAISE_TO_FOREGROUND service: the message goes without it.")
            send(intent, app)
            return
        }
        main.postDelayed({ decide(Raise.Event.TIMED_OUT) }, RAISE_WAIT_MS)
    }

    private fun send(intent: Intent, app: String) {
        context.sendBroadcast(intent.setPackage(app))
        Log.i(TAG, "${intent.action?.substringAfterLast('.')} to $app")
    }
}

/** REGISTER, UNREGISTER and MESSAGE_ACK from the apps. */
class DistributorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Up.REGISTER && action != Up.UNREGISTER && action != Up.MESSAGE_ACK) return
        val creator = runCatching { intent.getParcelableExtra(Up.PI, PendingIntent::class.java)?.creatorPackage }.getOrNull()
        val (app, how) = Identity.of(sentFromPackage, creator, string(intent, Up.APPLICATION)) ?: run {
            Log.w(TAG, "${action.substringAfterLast('.')} from an app that could not be told: ignored.")
            return
        }
        if (how != How.SHARED && action != Up.MESSAGE_ACK) Log.w(TAG, "$app did not share its identity: known from its ${how.name.lowercase()} extra.")
        val token = string(intent, Up.TOKEN)
        val appContext = context.applicationContext
        val pending = goAsync()
        Distributor.work.execute {
            try {
                val core = Distributor.core(appContext)
                when (action) {
                    Up.REGISTER -> {
                        core.register(app, how, token, string(intent, Up.VAPID), string(intent, Up.MESSAGE_TEXT))
                        Distributor.ensurePolling(appContext)
                    }
                    Up.UNREGISTER -> core.unregister(app, token)
                    Up.MESSAGE_ACK -> core.ack(app, token, string(intent, Up.ID))
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun string(intent: Intent, name: String): String? = runCatching { intent.getStringExtra(name) }.getOrNull()
}

/** `adb shell am broadcast -n <package>/dev.standin.distributor.SteerReceiver -a dev.standin.distributor.<COMMAND>`. */
class SteerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        @Suppress("DEPRECATION")
        val command = Steer.of(intent.action) { name -> extras?.get(name) }
        if (command == null) {
            resultData = "Not a command: FAIL [reason, times], UNREGISTER_ALL [to], NEW_ENDPOINTS, UNAVAILABLE [fallback], AVAILABLE, LINK cancel, SETUP on, STATE."
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        Distributor.work.execute {
            try {
                pending.resultData = Distributor.core(app).steer(command)
                Distributor.ensurePolling(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The link activity: answers at once with a PendingIntent this copy made, or as if cancelled when steered to. */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Distributor.core(this).linkCancels()) {
            setResult(RESULT_CANCELED)
        } else {
            val proof = PendingIntent.getBroadcast(this, 0, Intent(Up.DUMMY_APP).setPackage(Up.DUMMY_APP), PendingIntent.FLAG_IMMUTABLE)
            setResult(RESULT_OK, Intent().putExtra(Up.PI, proof))
        }
        finish()
    }
}

/** What it holds; opening it starts the polling service. */
class MainActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(48, 48, 48, 48); setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(text) })
    }

    override fun onResume() {
        super.onResume()
        Distributor.ensurePolling(this)
        text.text = Distributor.core(this).describe()
    }
}

/**
 * Long-polls the stand-in push server, one thread for each endpoint, and hands what comes to the
 * apps. A foreground service, so the process stays while the apps it delivers to are asleep.
 */
class PollService : Service() {
    private val pollers = HashMap<String, Poller>()
    private val main = Handler(Looper.getMainLooper())
    private val standIn = HttpStandIn()

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Polling", NotificationManager.IMPORTANCE_MIN))
        startForeground(NOTIFICATION, notification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Distributor.core(this).onChange = { main.post(::reconcile) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconcile()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Distributor.core(this).onChange = null
        pollers.values.forEach(Poller::halt)
        pollers.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** One poller for each endpoint to poll, and none for any other. */
    private fun reconcile() {
        val core = Distributor.core(this)
        val targets = core.pollTargets().toSet()
        pollers.keys.filter { it !in targets }.forEach { pollers.remove(it)?.halt() }
        targets.filter { it !in pollers }.forEach { id -> pollers[id] = Poller(id, standIn, core).also(Thread::start) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(core.registrations().size))
    }

    private fun notification(registrations: Int): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("Stand-in distributor")
        .setContentText("$registrations registration(s), polling $STAND_IN")
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL = "polling"
        private const val NOTIFICATION = 1
        @Volatile var running = false
    }
}

/** Long-polls one endpoint until halted, or until the push server says it was dropped. */
private class Poller(private val id: String, private val standIn: HttpStandIn, private val core: Core) : Thread("poll") {
    @Volatile private var halted = false
    @Volatile private var connection: HttpURLConnection? = null

    override fun run() {
        var failures = 0
        while (!halted) {
            val answer = standIn.poll(id, WAIT_S) { connection = it }
            connection = null
            when (answer) {
                // A message taken just as it was halted is still handed over (or held) by the core.
                is PollAnswer.Message -> { failures = 0; core.deliver(id, answer.id, answer.body) }
                PollAnswer.Nothing -> failures = 0
                PollAnswer.Gone -> { core.gone(id); return }
                PollAnswer.Retry -> {
                    if (halted) return
                    failures++
                    if (failures == 1 || failures % 10 == 0) Log.w(TAG, "The push server at $STAND_IN does not answer (is adb reverse tcp:7788 tcp:7788 set up?)")
                    try { sleep(backoffMs(failures)) } catch (_: InterruptedException) { return }
                }
            }
        }
    }

    fun halt() {
        halted = true
        interrupt()
        // Cuts a long poll short; off the main thread, which may not touch the network.
        Thread { runCatching { connection?.disconnect() } }.start()
    }

    companion object {
        const val WAIT_S = 25
    }
}
