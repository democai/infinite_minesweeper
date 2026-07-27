package com.infinite.minesweeper.core.coords

/**
 * Android-free two-dimensional point used by viewport calculations.
 */
data class Point2D(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite()) { "x must be finite" }
        require(y.isFinite()) { "y must be finite" }
    }
}

/**
 * Parameters for converting between world and screen coordinates.
 *
 * [worldCenter] maps to the center of the screen. Increasing world and screen coordinates both
 * move right/down, matching the board's cell coordinates and Android canvas coordinates.
 */
data class WorldScreenTransform(
    val worldCenter: Point2D,
    val screenWidth: Double,
    val screenHeight: Double,
    val pixelsPerWorldUnit: Double,
) {
    init {
        require(screenWidth.isFinite() && screenWidth >= 0.0) {
            "screenWidth must be finite and non-negative"
        }
        require(screenHeight.isFinite() && screenHeight >= 0.0) {
            "screenHeight must be finite and non-negative"
        }
        require(pixelsPerWorldUnit.isFinite() && pixelsPerWorldUnit > 0.0) {
            "pixelsPerWorldUnit must be finite and greater than zero"
        }
    }
}

fun worldToScreen(
    world: Point2D,
    transform: WorldScreenTransform,
): Point2D = Point2D(
    x = (world.x - transform.worldCenter.x) * transform.pixelsPerWorldUnit +
        transform.screenWidth / 2.0,
    y = (world.y - transform.worldCenter.y) * transform.pixelsPerWorldUnit +
        transform.screenHeight / 2.0,
)

fun screenToWorld(
    screen: Point2D,
    transform: WorldScreenTransform,
): Point2D = Point2D(
    x = (screen.x - transform.screenWidth / 2.0) / transform.pixelsPerWorldUnit +
        transform.worldCenter.x,
    y = (screen.y - transform.screenHeight / 2.0) / transform.pixelsPerWorldUnit +
        transform.worldCenter.y,
)
