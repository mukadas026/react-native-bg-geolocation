package com.bggeolocation.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.bggeolocation.core.CoreEvent
import com.bggeolocation.core.EngineState
import com.bggeolocation.core.Fix
import com.bggeolocation.core.Geofence
import com.bggeolocation.core.GeofenceResult
import com.bggeolocation.core.Output
import com.bggeolocation.core.TrackingCore
import com.bggeolocation.core.TrackingState
import com.bggeolocation.engine.AospEngine
import com.bggeolocation.engine.ArmOptions
import com.bggeolocation.engine.EngineCallbacks
import com.bggeolocation.engine.LocationEngine
import com.bggeolocation.engine.MotionSource
import com.bggeolocation.storage.BgGeoDatabase
import com.bggeolocation.storage.PrefsConfigStore
import com.bggeolocation.storage.PrefsStateStore
import com.bggeolocation.storage.SqliteFixStore
import com.bggeolocation.storage.SqliteGeofenceStore
import java.util.UUID

fun interface RuntimeListener {
  fun onEvent(name: String, payload: Map<String, Any?>)
}

class TrackingRuntime private constructor(context: Context) : EngineCallbacks {
  private val app = context.applicationContext
  private val thread = HandlerThread("bggeo-runtime").also { it.start() }
  private val handler = Handler(thread.looper)

  private val database = BgGeoDatabase(app)
  private val fixStore = SqliteFixStore(database)
  private val geofenceStore = SqliteGeofenceStore(database)
  private val stateStore = PrefsStateStore(app)
  private val configStore = PrefsConfigStore(app)
  private val uploader = HttpUploader()
  private val engine: LocationEngine = AospEngine(app, thread.looper, this)
  private val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  private val connectivityManager =
    app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  @Volatile var listener: RuntimeListener? = null
  @Volatile var config: RuntimeConfig = configStore.load()
    private set

  private var core: TrackingCore? = null
  private var engineMode = EngineState.OFF
  private var online = true
  private var networkCallbackRegistered = false
  private var currentFix: Fix? = null
  private var geofenceChecksOn = false
  private var providerReceiverRegistered = false
  private val tickRunnable = Runnable { apply(ensureCore().tick(now())) }

  fun configure(newConfig: RuntimeConfig, done: () -> Unit) {
    handler.post {
      config = newConfig
      configStore.save(newConfig)
      core = null
      ensureCore()
      done()
    }
  }

  fun launch(done: () -> Unit = {}) {
    handler.post {
      ensureCore()
      done()
    }
  }

  fun reportFailure(message: String) {
    Log.w(TAG, "failure: $message")
    app.getSharedPreferences("bggeo", Context.MODE_PRIVATE).edit().putString("failure", message).apply()
    listener?.onEvent("error", mapOf("message" to message))
  }

  private fun clearFailure() {
    app.getSharedPreferences("bggeo", Context.MODE_PRIVATE).edit().remove("failure").apply()
  }

  fun start(done: (Result<Unit>) -> Unit) {
    if (!Permissions.hasLocationPermission(app)) {
      reportFailure(TrackingService.MISSING_PERMISSION)
      done(Result.failure(SecurityException(TrackingService.MISSING_PERMISSION)))
      return
    }
    clearFailure()
    val intent = Intent(app, TrackingService::class.java)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        app.startForegroundService(intent)
      } else {
        app.startService(intent)
      }
    } catch (e: Exception) {
      done(Result.failure(e))
      return
    }
    handler.post {
      apply(ensureCore().start(now()))
      done(Result.success(Unit))
    }
  }

  fun stop(done: () -> Unit) {
    handler.post {
      apply(ensureCore().stop(now()))
      done()
    }
  }

  fun changePace(isMoving: Boolean, done: () -> Unit) {
    handler.post {
      apply(ensureCore().changePace(isMoving, now()))
      done()
    }
  }

  fun tick() {
    handler.post { apply(ensureCore().tick(now())) }
  }

  fun addGeofences(geofences: List<Geofence>, done: (Result<Unit>) -> Unit) {
    handler.post {
      var failure: Throwable? = null
      for (geofence in geofences) {
        val output = ensureCore().addGeofence(geofence, now())
        apply(output)
        when (output.result) {
          GeofenceResult.LIMIT ->
            failure = failure ?: IllegalStateException("geofence limit of ${GEOFENCE_LIMIT} reached")
          GeofenceResult.INVALID ->
            failure = failure ?: IllegalArgumentException("invalid geofence '${geofence.id}'")
          else -> Unit
        }
      }
      done(failure?.let { Result.failure(it) } ?: Result.success(Unit))
    }
  }

  fun removeGeofences(ids: List<String>, done: () -> Unit) {
    handler.post {
      ids.forEach { apply(ensureCore().removeGeofence(it, now())) }
      done()
    }
  }

  fun geofences(): List<Geofence> = geofenceStore.all().map { it.geofence }

  fun providerState(): Map<String, Any?> = Permissions.providerState(app)

  fun capabilities(): Map<String, Any?> = mapOf(
    "platform" to "android",
    "engine" to "aosp",
    "geofenceLimit" to GEOFENCE_LIMIT,
    "activityRecognition" to false,
    "canRelaunchAfterKill" to true,
    "supportsHeadlessJs" to false,
    "managedDevice" to Permissions.isManagedDevice(app),
    "motionSource" to engine.motionSource?.label,
  )

  fun emitProviderChange() {
    listener?.onEvent("providerchange", Permissions.providerState(app))
  }

  fun snapshot(): Map<String, Any?> {
    val state = stateStore.trackingState
    return mapOf(
      "enabled" to (state != TrackingState.STOPPED),
      "trackingState" to state.name.lowercase(),
      "isMoving" to (state == TrackingState.MOVING),
      "count" to fixStore.size(),
      "motionSource" to engine.motionSource?.label,
      "failure" to app.getSharedPreferences("bggeo", Context.MODE_PRIVATE).getString("failure", null),
    )
  }

  fun locations(): List<Fix> = fixStore.oldest(Int.MAX_VALUE)

  fun destroyLocations() {
    fixStore.delete(fixStore.oldest(Int.MAX_VALUE).map { it.id })
  }

  override fun onLocation(location: Location) {
    val fix = Fix(
      id = UUID.randomUUID().toString(),
      lat = location.latitude,
      lon = location.longitude,
      timestamp = location.time,
      accuracy = if (location.hasAccuracy()) location.accuracy else null,
      speed = if (location.hasSpeed()) location.speed else null,
      bearing = if (location.hasBearing()) location.bearing else null,
      altitude = if (location.hasAltitude()) location.altitude else null,
    )
    currentFix = fix
    apply(ensureCore().onFix(fix, now()))
    currentFix = null
  }

  override fun onGeofenceCheck(location: Location) {
    val fix = Fix(
      id = "geofence-check",
      lat = location.latitude,
      lon = location.longitude,
      timestamp = location.time,
    )
    apply(ensureCore().onGeofenceCheck(fix, now()))
  }

  override fun onMotionSignal() {
    apply(ensureCore().onMotionSignal(now()))
  }

  override fun onMotionSource(source: MotionSource) {
    Log.i(TAG, "motion source ${source.label}")
    listener?.onEvent("motionsource", mapOf("source" to source.label))
  }

  override fun onEngineError(message: String) {
    Log.w(TAG, "engine error: $message")
    listener?.onEvent("error", mapOf("message" to message))
  }

  private fun ensureCore(): TrackingCore {
    core?.let { return it }
    registerNetworkCallback()
    registerProviderReceiver()
    val tracking = config.tracking.copy(
      autoSync = config.tracking.autoSync && config.url != null,
      geofenceLimit = GEOFENCE_LIMIT,
    )
    val created = TrackingCore(tracking, fixStore, stateStore, online, geofenceStore)
    core = created
    engineMode = EngineState.OFF
    apply(created.onLaunch(now()))
    return created
  }

  private fun apply(output: Output) {
    val current = core ?: return
    output.events.forEach { dispatch(it) }
    output.upload?.let { request -> upload(request.fixes) }
    reconcileEngine(current)
    reconcileGeofenceChecks(current)
    scheduleAlarm(current)
    if (current.trackingState == TrackingState.STOPPED) {
      app.stopService(Intent(app, TrackingService::class.java))
    }
  }

  private fun upload(fixes: List<Fix>) {
    val url = config.url ?: return
    uploader.post(url, config.headers, fixes) { status ->
      handler.post {
        val current = core ?: return@post
        apply(current.onUploadResult(status, now()))
      }
    }
  }

  private fun reconcileEngine(current: TrackingCore) {
    val desired = current.engineState
    if (desired == engineMode) return
    engineMode = desired
    geofenceChecksOn = false
    when (desired) {
      EngineState.CONTINUOUS -> engine.startContinuous(config.tracking.distanceFilter)
      EngineState.ARMED -> engine.arm(
        ArmOptions(
          stationaryRadius = config.tracking.stationaryRadius,
          pollIntervalMs = config.motionPollInterval * 1000,
          useMotionSensors = config.useMotionSensors,
        )
      )
      EngineState.OFF -> engine.stop()
    }
  }

  private fun reconcileGeofenceChecks(current: TrackingCore) {
    val desired = current.trackingState == TrackingState.STATIONARY && current.geofenceIds.isNotEmpty()
    if (desired == geofenceChecksOn) return
    geofenceChecksOn = desired
    if (desired) {
      engine.startGeofenceChecks(config.geofenceCheckInterval * 1000)
    } else {
      engine.stopGeofenceChecks()
    }
  }

  private fun registerProviderReceiver() {
    if (providerReceiverRegistered) return
    providerReceiverRegistered = true
    app.registerReceiver(
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          emitProviderChange()
        }
      },
      IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
    )
  }

  private fun scheduleAlarm(current: TrackingCore) {
    val pending = PendingIntent.getBroadcast(
      app,
      0,
      Intent(app, AlarmReceiver::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    handler.removeCallbacks(tickRunnable)
    val due = current.nextDueAt
    if (due == null) {
      alarmManager.cancel(pending)
    } else {
      handler.postDelayed(tickRunnable, (due - now()).coerceAtLeast(0))
      alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pending)
    }
  }

  private fun dispatch(event: CoreEvent) {
    val (name, payload) = when (event) {
      is CoreEvent.EnabledChange -> "enabledchange" to mapOf("enabled" to event.enabled)
      is CoreEvent.MotionChange -> "motionchange" to mapOf("isMoving" to event.isMoving)
      is CoreEvent.Location -> "location" to locationPayload(event.id)
      is CoreEvent.BufferOverflow -> "buffer-overflow" to mapOf("dropped" to event.dropped)
      is CoreEvent.GeofenceTransition ->
        "geofence" to mapOf("identifier" to event.id, "action" to event.action.name)
    }
    Log.i(TAG, "$name $payload")
    listener?.onEvent(name, payload)
  }

  private fun locationPayload(id: String): Map<String, Any?> {
    val fix = currentFix?.takeIf { it.id == id }
    return mapOf(
      "uuid" to id,
      "timestamp" to fix?.timestamp,
      "coords" to mapOf(
        "latitude" to fix?.lat,
        "longitude" to fix?.lon,
        "accuracy" to fix?.accuracy?.toDouble(),
        "speed" to fix?.speed?.toDouble(),
        "heading" to fix?.bearing?.toDouble(),
        "altitude" to fix?.altitude,
      ),
    )
  }

  private fun registerNetworkCallback() {
    if (networkCallbackRegistered) return
    networkCallbackRegistered = true
    online = connectivityManager.activeNetwork
      ?.let { connectivityManager.getNetworkCapabilities(it) }
      ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    connectivityManager.registerDefaultNetworkCallback(
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = report(true)

        override fun onLost(network: Network) = report(false)

        private fun report(isOnline: Boolean) {
          handler.post {
            online = isOnline
            core?.let { apply(it.onConnectivity(isOnline, now())) }
          }
        }
      }
    )
  }

  private fun now(): Long = SystemClock.elapsedRealtime()

  companion object {
    private const val TAG = "BgGeo"
    const val GEOFENCE_LIMIT = 20

    @Volatile private var instance: TrackingRuntime? = null

    fun get(context: Context): TrackingRuntime =
      instance ?: synchronized(this) {
        instance ?: TrackingRuntime(context).also { instance = it }
      }
  }
}
