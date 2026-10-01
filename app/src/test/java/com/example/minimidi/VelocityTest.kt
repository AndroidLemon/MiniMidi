package com.example.minimidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityTest {
    @Test fun fixedModeIgnoresContact() {
        assertEquals(100, velocity(10f, 5, fixed = true, fixedValue = 100))
        assertEquals(100, velocity(90f, 5, fixed = true, fixedValue = 100))
    }

    @Test fun fixedValueIsClamped() {
        assertEquals(1, velocity(50f, 5, fixed = true, fixedValue = 0))
        assertEquals(127, velocity(50f, 5, fixed = true, fixedValue = 200))
    }

    // Review Focus 1: devices (and adb taps) that report no contact size must still play.
    @Test fun zeroContactFallsBackToFixedValue() = assertEquals(100, velocity(0f, 5, fixed = false, fixedValue = 100))
    @Test fun nanContactFallsBackToFixedValue() = assertEquals(100, velocity(Float.NaN, 5, fixed = false, fixedValue = 100))

    // Some digitizers report a dummy 1 px contact; treat ≤ 1 dp as "no reading", not "softest hit".
    @Test fun subDpContactFallsBackToFixedValue() = assertEquals(100, velocity(0.8f, 5, fixed = false, fixedValue = 100))

    // Velocity 1 is inaudible in most drum samplers; the softest real touch plays at the floor.
    @Test fun softestTouchPlaysAtFloor() = assertEquals(MIN_VELOCITY, velocity(5f, 5, fixed = false, fixedValue = 100))
    @Test fun hugeContactIsCappedAt127() = assertEquals(127, velocity(500f, 5, fixed = false, fixedValue = 100))

    @Test fun biggerContactNeverSofter() {
        var last = 0
        var c = 1.5f
        while (c < 200f) {
            val v = velocity(c, 5, fixed = false, fixedValue = 100)
            assertTrue("velocity dropped at contact $c", v >= last)
            assertTrue(v in 1..127)
            last = v
            c += 0.5f
        }
    }

    @Test fun higherSensitivityIsLouderForSameTouch() =
        assertTrue(velocity(50f, 10, false, 100) > velocity(50f, 1, false, 100))

    @Test fun outOfRangeSensitivityIsClamped() {
        assertEquals(velocity(50f, SENSITIVITY_MIN, false, 100), velocity(50f, -3, false, 100))
        assertEquals(velocity(50f, SENSITIVITY_MAX, false, 100), velocity(50f, 99, false, 100))
    }
}
