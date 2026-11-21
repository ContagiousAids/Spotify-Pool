package com.ollfx.spotifypool

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pixel-coordinate geometry supplied by the vision tracker. */
data class PoolEnginePoint(val x: Float, val y: Float) {
    operator fun plus(other: PoolEnginePoint) = PoolEnginePoint(x + other.x, y + other.y)
    operator fun minus(other: PoolEnginePoint) = PoolEnginePoint(x - other.x, y - other.y)
    operator fun times(scale: Float) = PoolEnginePoint(x * scale, y * scale)
    fun length(): Float = hypot(x, y)
    fun normalized(): PoolEnginePoint {
        val length = length().coerceAtLeast(0.0001f)
        return PoolEnginePoint(x / length, y / length)
    }
    fun distanceTo(other: PoolEnginePoint): Float = (this - other).length()
    fun dot(other: PoolEnginePoint): Float = x * other.x + y * other.y
}

data class PoolEngineRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(p: PoolEnginePoint, margin: Float = 0f) =
        p.x >= left + margin && p.x <= right - margin &&
            p.y >= top + margin && p.y <= bottom - margin
}

data class PoolEngineBall(
    val id: Int,
    val center: PoolEnginePoint,
    val radius: Float,
    val isCueBall: Boolean = false
)

data class PoolEnginePocket(val id: Int, val center: PoolEnginePoint, val captureRadius: Float)

data class PoolEngineSegment(val start: PoolEnginePoint, val end: PoolEnginePoint)

data class PoolEngineShotPlan(
    val cuePath: List<PoolEngineSegment>,
    val objectPath: List<PoolEngineSegment>,
    val deflectedCuePath: List<PoolEngineSegment>,
    val contactPoint: PoolEnginePoint,
    val targetBallId: Int,
    val pocketId: Int,
    val bankCount: Int,
    val confidence: Float,
    val estimatedPower: Float
)

/**
 * Finds the lowest-cost visible pot for the current ball layout.
 * Coordinates must all be in the same captured-frame pixel space.
 * This class is deliberately independent of Android and OpenCV so it can be unit-tested.
 */
class PoolPhysicsEngine {
    private data class ObjectRoute(
        val points: List<PoolEnginePoint>,
        val bankCount: Int,
        val length: Float
    )

    private data class Candidate(
        val plan: PoolEngineShotPlan,
        val score: Float
    )

    fun calculateBestShot(
        table: PoolEngineRect,
        balls: List<PoolEngineBall>,
        pockets: List<PoolEnginePocket>,
        cueBallId: Int
    ): PoolEngineShotPlan? {
        val cue = balls.firstOrNull { it.id == cueBallId } ?: balls.firstOrNull { it.isCueBall } ?: return null
        val objects = balls.filter { it.id != cue.id && it.radius > 0f }
        if (objects.isEmpty() || pockets.isEmpty() || cue.radius <= 0f) return null

        val candidates = mutableListOf<Candidate>()
        for (target in objects) {
            val combinedRadius = cue.radius + target.radius
            for (pocket in pockets) {
                for (route in objectRoutes(table, target.center, pocket)) {
                    val firstDirection = (route.points[1] - target.center).normalized()
                    val contact = target.center - firstDirection * combinedRadius
                    if (!table.contains(contact, cue.radius * 0.2f)) continue
                    if (!clearSegment(cue.center, contact, balls, cue.id, target.id, cue.radius * 0.88f)) continue
                    if (!clearRoute(route.points, balls, target.id, cue.radius * 0.9f)) continue

                    val incoming = (contact - cue.center).normalized()
                    val cueDistance = cue.center.distanceTo(contact)
                    val cutCos = incoming.dot(firstDirection).coerceIn(-1f, 1f)
                    val cutPenalty = (1f - cutCos).coerceAtLeast(0f) * 85f
                    val bankPenalty = route.bankCount * 95f
                    val totalLength = cueDistance + route.length
                    val score = totalLength + cutPenalty + bankPenalty

                    val cueSegments = listOf(PoolEngineSegment(cue.center, contact))
                    val objectSegments = route.points.zipWithNext().map { PoolEngineSegment(it.first, it.second) }
                    val normal = firstDirection
                    val reflected = incoming - normal * (2f * incoming.dot(normal))
                    val deflected = if (reflected.length() > 0.08f) {
                        listOf(PoolEngineSegment(contact, contact + reflected.normalized() * (table.width * 0.65f)))
                    } else emptyList()

                    val clearFactor = (1f - (cueDistance + route.length) / (table.width * 5f)).coerceIn(0.15f, 1f)
                    val angleFactor = (1f - abs(cutCos - 1f) * 0.55f).coerceIn(0.2f, 1f)
                    val confidence = (clearFactor * angleFactor * if (route.bankCount == 0) 1f else 0.78f).coerceIn(0.05f, 0.99f)
                    val power = ((cueDistance + route.length * 0.55f) / (table.width * 1.7f)).coerceIn(0.18f, 1f)
                    candidates += Candidate(
                        PoolEngineShotPlan(
                            cuePath = cueSegments,
                            objectPath = objectSegments,
                            deflectedCuePath = deflected,
                            contactPoint = contact,
                            targetBallId = target.id,
                            pocketId = pocket.id,
                            bankCount = route.bankCount,
                            confidence = confidence,
                            estimatedPower = power
                        ), score
                    )
                }
            }
        }
        return candidates.minByOrNull { it.score }?.plan
    }

    /** Produces a direct route and all geometrically valid single-cushion bank routes. */
    private fun objectRoutes(
        table: PoolEngineRect,
        from: PoolEnginePoint,
        pocket: PoolEnginePocket
    ): List<ObjectRoute> {
        val result = mutableListOf<ObjectRoute>()
        val target = pocket.center
        result += ObjectRoute(listOf(from, target), 0, from.distanceTo(target))

        // Mirror the pocket over each cushion; the straight line to the image gives the bank point.
        val mirroredTargets = listOf(
            PoolEnginePoint(2f * table.left - target.x, target.y) to 0,
            PoolEnginePoint(2f * table.right - target.x, target.y) to 1,
            PoolEnginePoint(target.x, 2f * table.top - target.y) to 2,
            PoolEnginePoint(target.x, 2f * table.bottom - target.y) to 3
        )
        for ((image, side) in mirroredTargets) {
            val bank = when (side) {
                0 -> intersectAtX(from, image, table.left)
                1 -> intersectAtX(from, image, table.right)
                2 -> intersectAtY(from, image, table.top)
                else -> intersectAtY(from, image, table.bottom)
            } ?: continue
            val insideRail = when (side) {
                0, 1 -> bank.y > table.top + 1f && bank.y < table.bottom - 1f
                else -> bank.x > table.left + 1f && bank.x < table.right - 1f
            }
            if (!insideRail) continue
            val length = from.distanceTo(bank) + bank.distanceTo(target)
            result += ObjectRoute(listOf(from, bank, target), 1, length)
        }
        return result
    }

    private fun intersectAtX(a: PoolEnginePoint, b: PoolEnginePoint, x: Float): PoolEnginePoint? {
        val dx = b.x - a.x
        if (abs(dx) < 0.0001f) return null
        val t = (x - a.x) / dx
        if (t <= 0f || t >= 1f) return null
        return PoolEnginePoint(x, a.y + (b.y - a.y) * t)
    }

    private fun intersectAtY(a: PoolEnginePoint, b: PoolEnginePoint, y: Float): PoolEnginePoint? {
        val dy = b.y - a.y
        if (abs(dy) < 0.0001f) return null
        val t = (y - a.y) / dy
        if (t <= 0f || t >= 1f) return null
        return PoolEnginePoint(a.x + (b.x - a.x) * t, y)
    }

    private fun clearRoute(
        points: List<PoolEnginePoint>,
        balls: List<PoolEngineBall>,
        movingBallId: Int,
        clearance: Float
    ): Boolean {
        for ((a, b) in points.zipWithNext()) {
            if (balls.any { it.id != movingBallId && pointSegmentDistance(it.center, a, b) < it.radius + clearance }) {
                return false
            }
        }
        return true
    }

    private fun clearSegment(
        a: PoolEnginePoint,
        b: PoolEnginePoint,
        balls: List<PoolEngineBall>,
        movingBallId: Int,
        targetBallId: Int,
        clearance: Float
    ): Boolean {
        return balls.none { ball ->
            ball.id != movingBallId && ball.id != targetBallId &&
                pointSegmentDistance(ball.center, a, b) < ball.radius + clearance
        }
    }

    private fun pointSegmentDistance(p: PoolEnginePoint, a: PoolEnginePoint, b: PoolEnginePoint): Float {
        val ab = b - a
        val denominator = ab.dot(ab).coerceAtLeast(0.0001f)
        val t = ((p - a).dot(ab) / denominator).coerceIn(0f, 1f)
        return p.distanceTo(a + ab * t)
    }
}
