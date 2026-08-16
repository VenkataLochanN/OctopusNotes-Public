package com.lochan.octopusnotes

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis()
)

class StrokeStabilization {
    private var level: Int = 5
    private var strength: Float = 0.55f
    private var windowSize: Int = 6

    private val pointBuffer = ArrayDeque<StrokePoint>()
    private var previousPoint: StrokePoint? = null

    init {
        setLevel(5)
    }

    fun setLevel(newLevel: Int) {
        level = newLevel.coerceIn(1, 10)

        val config = when (level) {
            1 -> Pair(2, 0.85f)
            2 -> Pair(3, 0.80f)
            3 -> Pair(4, 0.75f)
            4 -> Pair(5, 0.65f)
            5 -> Pair(6, 0.55f)
            6 -> Pair(7, 0.45f)
            7 -> Pair(8, 0.35f)
            8 -> Pair(9, 0.25f)
            9 -> Pair(10, 0.18f)
            10 -> Pair(12, 0.10f)
            else -> Pair(6, 0.55f)
        }
        windowSize = config.first
        strength = config.second
    }

    fun getLevel() = level

    fun processPoint(rawPoint: StrokePoint): StrokePoint {
        pointBuffer.addLast(rawPoint)
        if (pointBuffer.size > windowSize) {
            pointBuffer.removeFirst()
        }

        val prev = previousPoint
        val stabilizedPoint = if (prev == null) {
            rawPoint
        } else {
            val newX = prev.x + strength * (rawPoint.x - prev.x)
            val newY = prev.y + strength * (rawPoint.y - prev.y)
            val newPressure = prev.pressure + strength * (rawPoint.pressure - prev.pressure)
            StrokePoint(newX, newY, newPressure, rawPoint.timestamp)
        }

        previousPoint = stabilizedPoint
        return stabilizedPoint
    }

    fun reset() {
        pointBuffer.clear()
        previousPoint = null
    }
}