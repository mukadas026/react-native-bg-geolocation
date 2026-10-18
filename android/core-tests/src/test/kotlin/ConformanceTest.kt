import com.bggeolocation.core.CoreEvent
import com.bggeolocation.core.Fix
import com.bggeolocation.core.InMemoryFixStore
import com.bggeolocation.core.InMemoryStateStore
import com.bggeolocation.core.Output
import com.bggeolocation.core.TrackingConfig
import com.bggeolocation.core.TrackingCore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val METERS_PER_DEGREE = 111194.93

class ConformanceTest {
  @TestFactory
  fun fixtures(): List<DynamicTest> {
    val root = File(System.getProperty("conformance.dir"))
    val files = root.walkTopDown().filter { it.extension == "json" }.sortedBy { it.path }.toList()
    assertTrue(files.isNotEmpty(), "no fixtures found in $root")
    return files.map { file ->
      DynamicTest.dynamicTest(file.relativeTo(root).path) { run(JSONObject(file.readText())) }
    }
  }

  private fun run(fixture: JSONObject) {
    val config = parseConfig(fixture.optJSONObject("config"))
    val server = fixture.optJSONArray("server")?.let { arr -> List(arr.length()) { arr.getInt(it) } }
      ?: emptyList()
    var serverIndex = 0

    val fixStore = InMemoryFixStore()
    val stateStore = InMemoryStateStore()
    var online = true
    var core = TrackingCore(config, fixStore, stateStore, online)

    val steps = fixture.getJSONArray("steps")
    for (index in 0 until steps.length()) {
      val step = steps.getJSONObject(index)
      val at = Math.round(step.getDouble("at") * 1000)
      val input = step.getJSONObject("input")
      val where = "step $index (${input.getString("type")} @${step.get("at")})"

      var output: Output = when (input.getString("type")) {
        "start" -> core.start(at)
        "stop" -> core.stop(at)
        "changePace" -> core.changePace(input.getBoolean("isMoving"), at)
        "fix" -> core.onFix(
          Fix(
            input.getString("id"),
            input.getDouble("dy") / METERS_PER_DEGREE,
            input.getDouble("dx") / METERS_PER_DEGREE,
          ),
          at,
        )
        "motionSignal" -> core.onMotionSignal(at)
        "tick" -> core.tick(at)
        "connectivity" -> {
          online = input.getBoolean("online")
          core.onConnectivity(online, at)
        }
        "restart" -> {
          core = TrackingCore(config, fixStore, stateStore, online)
          core.onLaunch(at)
        }
        else -> error("$where: unknown input")
      }

      val events = mutableListOf<CoreEvent>()
      val requests = mutableListOf<List<String>>()
      events += output.events
      while (output.upload != null) {
        requests += output.upload!!.fixes.map { it.id }
        val status = server.getOrElse(serverIndex++) { 200 }
        output = core.onUploadResult(status, at)
        events += output.events
      }

      val expect = step.optJSONObject("expect") ?: continue
      if (expect.has("state")) {
        assertEquals(expect.getString("state"), core.trackingState.name.lowercase(), "$where: state")
      }
      if (expect.has("engine")) {
        assertEquals(expect.getString("engine"), core.engineState.name.lowercase(), "$where: engine")
      }
      if (expect.has("events")) {
        assertEquals(expect.getJSONArray("events").toList { expectedEvent(it) }, events.map { describe(it) }, "$where: events")
      }
      if (expect.has("requests")) {
        val expected = expect.getJSONArray("requests").toList { arr ->
          (arr as JSONArray).toList { it as String }
        }
        assertEquals(expected, requests, "$where: requests")
      }
      if (expect.has("buffer")) {
        val expected = expect.getJSONArray("buffer").toList { it as String }
        assertEquals(expected, fixStore.oldest(Int.MAX_VALUE).map { it.id }, "$where: buffer")
      }
    }
  }

  private fun parseConfig(json: JSONObject?): TrackingConfig {
    val defaults = TrackingConfig()
    if (json == null) return defaults
    return TrackingConfig(
      distanceFilter = json.optDouble("distanceFilter", defaults.distanceFilter),
      stopTimeout = json.optInt("stopTimeout", defaults.stopTimeout),
      stationaryRadius = json.optDouble("stationaryRadius", defaults.stationaryRadius),
      autoSync = json.optBoolean("autoSync", defaults.autoSync),
      maxBatchSize = json.optInt("maxBatchSize", defaults.maxBatchSize),
      syncRetryBaseDelay = json.optLong("syncRetryBaseDelay", defaults.syncRetryBaseDelay),
      syncRetryMaxDelay = json.optLong("syncRetryMaxDelay", defaults.syncRetryMaxDelay),
      maxRecordsToPersist = json.optInt("maxRecordsToPersist", defaults.maxRecordsToPersist),
    )
  }

  private fun describe(event: CoreEvent): String = when (event) {
    is CoreEvent.EnabledChange -> "enabledchange:${event.enabled}"
    is CoreEvent.MotionChange -> "motionchange:${event.isMoving}"
    is CoreEvent.Location -> "location:${event.id}"
    is CoreEvent.BufferOverflow -> "buffer-overflow:${event.dropped.joinToString(",")}"
  }

  private fun expectedEvent(json: Any): String {
    val event = json as JSONObject
    return when (val type = event.getString("type")) {
      "enabledchange" -> "enabledchange:${event.getBoolean("enabled")}"
      "motionchange" -> "motionchange:${event.getBoolean("isMoving")}"
      "location" -> "location:${event.getString("id")}"
      "buffer-overflow" -> "buffer-overflow:${event.getJSONArray("dropped").toList { it as String }.joinToString(",")}"
      else -> error("unknown event type $type")
    }
  }

  private fun <T> JSONArray.toList(transform: (Any) -> T): List<T> =
    List(length()) { transform(get(it)) }
}
