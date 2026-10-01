package com.rainalarm.app.ui

import com.rainalarm.app.data.distanceMetres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarTravelCameraFollowerTest {
    @Test fun `gesture gate blocks every moving camera frame while accepting five hertz targets`() {
        val gate = RadarTravelCameraGestureGate()
        val trajectory = RadarTravelCameraTrajectory()
        trajectory.start(51.0, 0.0, 1_000_000_000L)
        assertTrue(gate.pause())

        var cameraWrites = 0
        var targetLongitude = 0.0
        repeat(5) { targetIndex ->
            targetLongitude += 0.000025
            assertEquals(
                RadarTravelCameraTargetAction.RETARGETED,
                trajectory.acceptTarget(
                    latitude = 51.0,
                    longitude = targetLongitude,
                    targetElapsedNanos = 1_200_000_000L + targetIndex * 200_000_000L,
                ),
            )
            gate.targetAccepted()
            repeat(12) { frameIndex ->
                if (gate.allowsCameraMutation(enabled = true)) {
                    if (trajectory.step(
                            1_000_000_000L +
                                (targetIndex * 12L + frameIndex + 1L) * 16_666_667L,
                        )
                    ) cameraWrites++
                }
            }
        }

        assertEquals(0, cameraWrites)
        assertEquals(0.0, trajectory.longitude, 0.0)
        assertEquals(
            RadarTravelCameraResumeAction.SCHEDULE,
            gate.resume(enabled = true, settled = trajectory.settled),
        )
        assertTrue(gate.allowsCameraMutation(enabled = true))
        assertTrue(trajectory.step(2_100_000_000L))
        assertTrue(trajectory.longitude in 0.0..targetLongitude)
    }

    @Test fun `stationary unclaimed touch applies only a newly received target and stop cannot resume`() {
        val gate = RadarTravelCameraGestureGate()
        assertTrue(gate.pause())
        assertEquals(
            RadarTravelCameraResumeAction.NONE,
            gate.resume(enabled = true, settled = true),
        )

        assertTrue(gate.pause())
        gate.targetAccepted()
        assertEquals(
            RadarTravelCameraResumeAction.APPLY_CURRENT,
            gate.resume(enabled = true, settled = true),
        )

        assertTrue(gate.pause())
        gate.targetAccepted()
        gate.reset()
        assertEquals(
            RadarTravelCameraResumeAction.NONE,
            gate.resume(enabled = false, settled = false),
        )
        assertFalse(gate.allowsCameraMutation(enabled = false))
    }

    @Test fun `frame diagnostics are coordinate free and sampled rather than emitted at vsync`() {
        val sampler = RadarTravelFrameDiagnosticSampler(intervalNanos = 100L)
        assertEquals(null, sampler.record(1_000L))
        repeat(5) { index -> assertEquals(null, sampler.record(1_010L + index * 10L)) }
        assertEquals(7, sampler.record(1_100L))
        assertEquals(null, sampler.record(1_110L))
    }

    @Test fun `travel viewport work is throttled while native frames remain unthrottled`() {
        assertTrue(RadarTravelViewportPolicy.shouldPublish(
            following = true,
            nowElapsedNanos = 1_000_000_000L,
            lastPublishedElapsedNanos = 0L,
        ))
        assertFalse(RadarTravelViewportPolicy.shouldPublish(
            following = true,
            nowElapsedNanos = 1_500_000_000L,
            lastPublishedElapsedNanos = 1_000_000_000L,
        ))
        assertTrue(RadarTravelViewportPolicy.shouldPublish(
            following = true,
            nowElapsedNanos = 2_000_000_000L,
            lastPublishedElapsedNanos = 1_000_000_000L,
        ))
        assertTrue(RadarTravelViewportPolicy.shouldPublish(
            following = false,
            nowElapsedNanos = 1_000_000_001L,
            lastPublishedElapsedNanos = 1_000_000_000L,
        ))
    }

    @Test fun `one hertz targets produce continuous display-frame positions without overshoot`() {
        val trajectory = RadarTravelCameraTrajectory()
        assertTrue(trajectory.start(51.0, 0.0, 1_000_000_000L))
        assertEquals(
            RadarTravelCameraTargetAction.RETARGETED,
            trajectory.acceptTarget(51.0, 0.00018, 2_000_000_000L),
        )
        val positions = mutableListOf<Double>()
        repeat(60) { index ->
            val changed = trajectory.step(1_000_000_000L + (index + 1) * 16_666_667L)
            if (changed) positions += trajectory.longitude
        }
        assertTrue(positions.size > 20)
        assertTrue(positions.zipWithNext().all { (first, second) -> second >= first })
        assertTrue(positions.all { it in 0.0..0.00018 })
        assertTrue(positions.last() > 0.00017)
    }

    @Test fun `five hertz retargets keep position and velocity continuous`() {
        val trajectory = RadarTravelCameraTrajectory()
        trajectory.start(51.0, 0.0, 1_000_000_000L)
        var targetLongitude = 0.0
        var frameTime = 1_000_000_000L
        var priorPosition = trajectory.longitude
        var appliedFrames = 0
        repeat(90) { index ->
            frameTime += 16_666_667L
            if (index % 12 == 0) {
                targetLongitude += 0.000025
                val beforePosition = trajectory.longitude
                val beforeVelocity = trajectory.longitudeVelocity
                trajectory.acceptTarget(51.0, targetLongitude, frameTime)
                assertEquals(beforePosition, trajectory.longitude, 0.0)
                assertEquals(beforeVelocity, trajectory.longitudeVelocity, 0.0)
            }
            if (trajectory.step(frameTime)) {
                appliedFrames++
                assertTrue(trajectory.longitude >= priorPosition)
                assertTrue(trajectory.longitude <= targetLongitude)
                priorPosition = trajectory.longitude
            }
        }
        assertTrue(appliedFrames >= 75)
    }

    @Test fun `frame stalls are bounded to the configured integration interval`() {
        fun prepared(): RadarTravelCameraTrajectory = RadarTravelCameraTrajectory().apply {
            start(51.0, 0.0, 1_000_000_000L)
            acceptTarget(51.0, 0.001, 1_100_000_000L)
        }
        val stalled = prepared()
        val bounded = prepared()
        assertTrue(stalled.step(2_000_000_000L))
        assertTrue(bounded.step(1_050_000_000L))
        assertEquals(bounded.longitude, stalled.longitude, 1e-12)
        assertEquals(bounded.longitudeVelocity, stalled.longitudeVelocity, 1e-12)
    }

    @Test fun `teleport resets while ordinary retarget never jumps`() {
        val trajectory = RadarTravelCameraTrajectory()
        trajectory.start(51.0, 0.0, 1_000_000_000L)
        assertEquals(
            RadarTravelCameraTargetAction.RETARGETED,
            trajectory.acceptTarget(51.0, 0.0001, 1_100_000_000L),
        )
        assertEquals(0.0, trajectory.longitude, 0.0)
        assertEquals(
            RadarTravelCameraTargetAction.RESET,
            trajectory.acceptTarget(52.0, 1.0, 1_200_000_000L),
        )
        assertEquals(52.0, trajectory.latitude, 0.0)
        assertEquals(1.0, trajectory.longitude, 0.0)
        assertTrue(trajectory.settled)
    }

    @Test fun `stale targets invalid values and stopped lifecycle cannot move camera`() {
        val trajectory = RadarTravelCameraTrajectory()
        trajectory.start(51.0, 0.0, 1_000_000_000L)
        trajectory.acceptTarget(51.0, 0.0001, 2_000_000_000L)
        assertEquals(
            RadarTravelCameraTargetAction.IGNORED,
            trajectory.acceptTarget(51.0, 0.0002, 1_900_000_000L),
        )
        assertEquals(
            RadarTravelCameraTargetAction.IGNORED,
            trajectory.acceptTarget(Double.NaN, 0.0, 2_100_000_000L),
        )
        trajectory.stop()
        assertFalse(trajectory.step(3_000_000_000L))
        assertFalse(trajectory.active)
    }

    @Test fun `retarget reversal is clamped to latest safe target`() {
        val trajectory = RadarTravelCameraTrajectory()
        trajectory.start(51.0, 0.0, 1_000_000_000L)
        trajectory.acceptTarget(51.0, 0.0003, 1_100_000_000L)
        repeat(8) { index -> trajectory.step(1_000_000_000L + (index + 1) * 16_666_667L) }
        val retargetStart = trajectory.longitude
        trajectory.acceptTarget(51.0, 0.00002, 1_200_000_000L)
        repeat(90) { index ->
            trajectory.step(1_150_000_000L + (index + 1) * 16_666_667L)
            assertTrue(trajectory.longitude in 0.00002..retargetStart)
        }
        assertTrue(distanceMetres(51.0, trajectory.longitude, 51.0, 0.00002) < 0.05)
    }
}
