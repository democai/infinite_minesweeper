package com.infinite.minesweeper.core.coords

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldScreenTransformTest {
    @Test
    fun worldCenterMapsToScreenCenter() {
        val transform = WorldScreenTransform(
            worldCenter = Point2D(-76.0, 59.0),
            screenWidth = 1080.0,
            screenHeight = 1920.0,
            pixelsPerWorldUnit = 32.0,
        )

        assertEquals(Point2D(540.0, 960.0), worldToScreen(transform.worldCenter, transform))
    }

    @Test
    fun worldToScreenToWorldIsIdentityForRandomTransforms() {
        val random = Random(0x1F1F17E)

        repeat(1_000) {
            val transform = WorldScreenTransform(
                worldCenter = Point2D(
                    x = random.nextDouble(-100_000.0, 100_000.0),
                    y = random.nextDouble(-100_000.0, 100_000.0),
                ),
                screenWidth = random.nextDouble(0.0, 4_000.0),
                screenHeight = random.nextDouble(0.0, 4_000.0),
                pixelsPerWorldUnit = random.nextDouble(0.01, 1_000.0),
            )
            val world = Point2D(
                x = random.nextDouble(-100_000.0, 100_000.0),
                y = random.nextDouble(-100_000.0, 100_000.0),
            )

            val roundTripped = screenToWorld(worldToScreen(world, transform), transform)

            assertEquals(world.x, roundTripped.x, 1e-8)
            assertEquals(world.y, roundTripped.y, 1e-8)
        }
    }

    @Test
    fun screenToWorldToScreenIsIdentity() {
        val transform = WorldScreenTransform(
            worldCenter = Point2D(12.25, -500.5),
            screenWidth = 1440.0,
            screenHeight = 900.0,
            pixelsPerWorldUnit = 0.25,
        )
        val screen = Point2D(-20.0, 2_500.0)

        val roundTripped = worldToScreen(screenToWorld(screen, transform), transform)

        assertEquals(screen.x, roundTripped.x, 1e-10)
        assertEquals(screen.y, roundTripped.y, 1e-10)
    }
}
