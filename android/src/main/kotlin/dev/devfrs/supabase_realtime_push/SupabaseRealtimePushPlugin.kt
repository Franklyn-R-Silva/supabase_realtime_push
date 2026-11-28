package dev.devfrs.supabase_realtime_push

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import kotlin.concurrent.thread

/** The `supabase_realtime_push` MethodChannel (contract §6). The service does the work; this only configures it. */
class SupabaseRealtimePushPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    PluginRegistry.NewIntentListener,
    PluginRegistry.RequestPermissionsResultListener {
    private companion object {
        const val PERMISSION_REQUEST = 0x5250
        val FUNCTION_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }

    private lateinit var context: Context
    private var channel: MethodChannel? = null
    private var binding: ActivityPluginBinding? = null
    private var launchTap: Map<String, String?>? = null
    private var pendingPermission: Result? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "supabase_realtime_push").also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        attach(binding)
        readTap(binding.activity.intent)?.let { launchTap = it }
        restartIfRunning()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = attach(binding)

    override fun onDetachedFromActivityForConfigChanges() = detach()

    override fun onDetachedFromActivity() = detach()

    private fun attach(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addOnNewIntentListener(this)
        binding.addRequestPermissionsResultListener(this)
    }

    private fun detach() {
        binding?.removeOnNewIntentListener(this)
        binding?.removeRequestPermissionsResultListener(this)
        binding = null
        pendingPermission?.success(false)
        pendingPermission = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        try {
            when (call.method) {
                "device" -> result.success(Store(context).credentialsOrCreate().let { mapOf("topic" to it.topic, "secret" to it.secret) })
                "start" -> start(call, result)
                "stop" -> {
                    stop(call.argument<Boolean>("forgetDevice") == true)
                    result.success(null)
                }
                "status" -> result.success(Store(context).status)
                "isIgnoringBatteryOptimizations" -> result.success(RealtimePushService.isIgnoringBatteryOptimizations(context))
                "requestIgnoreBatteryOptimizations" -> {
                    requestIgnoreBatteryOptimizations()
                    result.success(null)
                }
                "openConnectedNotificationSettings" -> {
                    openConnectedNotificationSettings()
                    result.success(null)
                }
                "requestNotificationPermission" -> requestNotificationPermission(result)
                "launchTap" -> {
                    result.success(launchTap)
                    launchTap = null
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            // The class name only: a message could carry the key.
            Log.w(TAG, "${call.method} failed: ${e.javaClass.simpleName}")
            result.error("error", "${call.method} failed: ${e.javaClass.simpleName}", null)
        }
    }

    private fun start(call: MethodCall, result: Result) {
        fun text(name: String) = call.argument<String>(name)?.takeIf { it.isNotBlank() }
        fun bad(message: String) = result.error("bad_arguments", message, null)
        val config = Config(
            supabaseUrl = text("supabaseUrl")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return bad("supabaseUrl must be an http or https URL"),
            publishableKey = text("publishableKey") ?: return bad("publishableKey is required"),
            snapshotFunction = text("snapshotFunction")?.takeIf { FUNCTION_NAME.matches(it) }
                ?: return bad("snapshotFunction must be a plain SQL function name"),
            channelName = text("channelName") ?: return bad("channelName is required"),
            connectedTitle = text("connectedTitle") ?: return bad("connectedTitle is required"),
            connectedText = text("connectedText") ?: return bad("connectedText is required"),
            smallIcon = text("smallIcon"),
            channels = call.argument<Map<String, String>>("channels").orEmpty()
                .filter { (id, name) -> id.isNotBlank() && name.isNotBlank() },
        )
        val store = Store(context)
        store.credentialsOrCreate()
        store.config = config
        store.status = Store.RUNNING
        try {
            RealtimePushService.start(context)
        } catch (e: Exception) {
            // The status stays running, so a reboot, an app update or the next start retries.
            Log.w(TAG, "the system refused to start the service: ${e.javaClass.simpleName}")
            return result.error("start_failed", "Android refused to start the service: ${e.javaClass.simpleName}", null)
        }
        result.success(null)
    }

    /**
     * Without the battery exemption, Android 12+ lets neither the START_STICKY restart nor the
     * inexact watch alarm start a foreground service from the background: once the system kills
     * the process, the service stays down. The app on screen may start it, so every launch does.
     */
    private fun restartIfRunning() {
        if (Store(context).status != Store.RUNNING) return
        try {
            RealtimePushService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "service not restarted on launch: ${e.javaClass.simpleName}")
        }
    }

    private fun stop(forgetDevice: Boolean) {
        val store = Store(context)
        // Status first: a snapshot still in flight sees it and leaves the shade alone.
        store.status = Store.STOPPED
        RealtimePushService.stop(context)
        Shade(context).clear()
        store.clearMemory()
        if (forgetDevice) store.forgetDevice()
    }

    /**
     * The direct dialog needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which this package does not
     * declare: Google Play allows it only for some app types, so the app opts in by declaring it.
     * Otherwise the settings list, which needs no permission.
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (RealtimePushService.isIgnoringBatteryOptimizations(context)) return
        val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == true
        openSettings(
            if (declared) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            },
        )
    }

    /**
     * The settings of the "connected" channel alone: turned off there, the notification goes and
     * the service keeps running. Before Android 8 there are no channels, so the app's page.
     */
    private fun openConnectedNotificationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        // The page of a channel that does not exist yet opens empty.
        Shade(context).createChannels(Store(context).config)
        openSettings(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Shade.CONNECTION_CHANNEL),
        )
    }

    private fun openSettings(intent: Intent) {
        try {
            binding?.activity?.startActivity(intent) ?: context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no settings screen for ${intent.action} on this device")
        }
    }

    private fun requestNotificationPermission(result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return result.success(true)
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return result.success(true)
        val activity = binding?.activity ?: return result.success(false)
        if (pendingPermission != null) return result.error("busy", "a permission request is already showing", null)
        pendingPermission = result
        ActivityCompat.requestPermissions(activity, arrayOf(permission), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        pendingPermission?.success(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        pendingPermission = null
        return true
    }

    override fun onNewIntent(intent: Intent): Boolean {
        val tap = readTap(intent) ?: return false
        val channel = channel
        if (channel == null) {
            launchTap = tap
            return true
        }
        // Dart sets its handler only once the app uses the API; until then the tap waits for launchTap.
        channel.invokeMethod(
            "tap",
            tap,
            object : Result {
                override fun success(result: Any?) = Unit

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    launchTap = tap
                }

                override fun notImplemented() {
                    launchTap = tap
                }
            },
        )
        return true
    }

    /**
     * `{id, data}` from a notification tap, or `null`. The tap counts as a dismissal: autoCancel
     * removes the notification without its delete intent, and the next snapshot would put it back.
     */
    private fun readTap(intent: Intent?): Map<String, String?>? {
        if (intent?.action != Shade.ACTION_TAP) return null
        // Reopened from recents after the process died: Android hands back the original
        // intent, key included, and the old tap would navigate again.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        val id = intent.getStringExtra(Shade.EXTRA_ID)
        // The launch activity is exported: only intents carrying this install's key are taps.
        val genuine = intent.getStringExtra(Shade.EXTRA_KEY) == Store(context).tapKey()
        // Consumed, so the same intent read again (a re-attach) is not a second tap.
        intent.removeExtra(Shade.EXTRA_KEY)
        if (!genuine || id == null) return null
        val revision = intent.getLongExtra(Shade.EXTRA_REVISION, Long.MIN_VALUE)
        // Off the main thread: it waits for a snapshot being posted, which can take seconds.
        thread { Shade(context).dismissed(id, revision) }
        return mapOf("id" to id, "data" to intent.getStringExtra(Shade.EXTRA_DATA))
    }
}
