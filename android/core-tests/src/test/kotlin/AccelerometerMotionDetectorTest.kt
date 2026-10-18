import com.bggeolocation.core.AccelerometerMotionDetector
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccelerometerMotionDetectorTest {
  private val sampleIntervalNanos = 200_000_000L

  private fun feed(
    detector: AccelerometerMotionDetector,
    seconds: Int,
    startNanos: Long,
    sample: (Int) -> Float,
  ): Pair<Boolean, Long> {
    var now = startNanos
    var detected = false
    repeat(seconds * 5) { index ->
      detected = detector.onSample(now, 0f, 0f, sample(index)) || detected
      now += sampleIntervalNanos
    }
    return detected to now
  }

  private val still: (Int) -> Float = { 9.81f + if (it % 2 == 0) 0.01f else -0.01f }
  private val shaking: (Int) -> Float = { if (it % 2 == 0) 14f else 5f }

  @Test
  fun stillDeviceNeverTriggers() {
    val (detected, _) = feed(AccelerometerMotionDetector(), 60, 0, still)
    assertFalse(detected)
  }

  @Test
  fun sustainedMotionTriggers() {
    val (detected, _) = feed(AccelerometerMotionDetector(), 20, 0, shaking)
    assertTrue(detected)
  }

  @Test
  fun singleBumpDoesNotTrigger() {
    val detector = AccelerometerMotionDetector()
    val (bumped, afterBump) = feed(detector, 5, 0, shaking)
    val (settled, _) = feed(detector, 20, afterBump, still)
    assertFalse(bumped || settled)
  }

  @Test
  fun resetDiscardsPartialEvidence() {
    val detector = AccelerometerMotionDetector()
    val (first, now) = feed(detector, 5, 0, shaking)
    detector.reset()
    val (second, _) = feed(detector, 5, now, shaking)
    assertFalse(first || second)
  }
}
