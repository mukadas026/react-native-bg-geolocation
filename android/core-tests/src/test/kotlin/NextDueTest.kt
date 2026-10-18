import com.bggeolocation.core.Fix
import com.bggeolocation.core.InMemoryFixStore
import com.bggeolocation.core.InMemoryStateStore
import com.bggeolocation.core.TrackingConfig
import com.bggeolocation.core.TrackingCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextDueTest {
  private fun core(config: TrackingConfig = TrackingConfig(stopTimeout = 1)) =
    TrackingCore(config, InMemoryFixStore(), InMemoryStateStore())

  @Test
  fun nothingDueWhileStopped() {
    assertNull(core().nextDueAt)
  }

  @Test
  fun stopDeadlineStartsAtFirstFix() {
    val core = core()
    core.start(0)
    assertNull(core.nextDueAt)
    core.onFix(Fix("f1", 0.0, 0.0), 5_000)
    assertEquals(65_000L, core.nextDueAt)
  }

  @Test
  fun retryIsDueAfterFailedUpload() {
    val core = core(TrackingConfig(stopTimeout = 5, syncRetryBaseDelay = 10))
    core.start(0)
    core.onFix(Fix("f1", 0.0, 0.0), 1_000)
    core.onUploadResult(500, 1_000)
    assertEquals(11_000L, core.nextDueAt)
  }

  @Test
  fun nothingDueWhileStationaryAndIdle() {
    val core = core()
    core.start(0)
    core.onFix(Fix("f1", 0.0, 0.0), 0)
    core.onUploadResult(200, 0)
    core.tick(60_000)
    assertNull(core.nextDueAt)
  }
}
