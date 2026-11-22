package com.ollfx.spotifypool

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object PoolTracker {
    data class Vec2(val x: Float, val y: Float)

    data class DetectedBall(
        val center: Vec2,
        val radius: Float,
        val areaPx: Int,
        val isCueBall: Boolean
    )

    data class TrajectorySegment(
        val start: Vec2,
        val end: Vec2,
        val isRailBounce: Boolean
    )

    data class ShotPlan(
        val tableRect: RectF,
        val pockets: List<Vec2>,
        val ballRadius: Float,
        val cueBall: DetectedBall?,
        val balls: List<DetectedBall>,
        val angleDeg: Float,
        val powerRatio: Float,
        val cueSegments: List<TrajectorySegment>,
        val impactPoint: Vec2?,
        val hitBall: DetectedBall?,
        val deflectedCueSegments: List<TrajectorySegment>,
        val objectSegments: List<TrajectorySegment>,
        val frameWidth: Int,
        val frameHeight: Int
    )

    private const val STEP = 4
    private const val MAX_DISTANCE = 2200f

    private var previousTable: RectF? = null
    private var previousBalls: List<DetectedBall> = emptyList()

    @Synchronized
    fun analyzeFrame(bitmap: Bitmap, step: Int): ShotPlan {
        val w = bitmap.width
        val h = bitmap.height
        val sample = max(2, step)

        val detectedTable = findTable(bitmap, sample)
        val table = smoothTable(detectedTable)

        val detectedBalls = findBalls(bitmap, table, sample)
        val balls = smoothBalls(detectedBalls)
        val cue = balls.firstOrNull { it.isCueBall }

        val radius = cue?.radius
            ?: balls.map { it.radius }.average().toFloat()
                .takeIf { it.isFinite() && it > 2f }
            ?: table.width() * 0.014f

        // Place pocket guides inside the felt boundary, not on its outer edge.
        val inset = radius * 0.85f
        val pockets = listOf(
            Vec2(table.left + inset, table.top + inset),
            Vec2(table.centerX(), table.top + inset * 0.65f),
            Vec2(table.right - inset, table.top + inset),
            Vec2(table.left + inset, table.bottom - inset),
            Vec2(table.centerX(), table.bottom - inset * 0.65f),
            Vec2(table.right - inset, table.bottom - inset)
        )

        val angle = findCueAngle(bitmap, cue, balls, table)
        val power = 0.65f
        val cuePath = ArrayList<TrajectorySegment>()
        val cueDeflection = ArrayList<TrajectorySegment>()
        val objectPath = ArrayList<TrajectorySegment>()
        var impact: Vec2? = null
        var hit: DetectedBall? = null

        if (cue != null) {
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val result = trace(
                cue.center, dx, dy, MAX_DISTANCE * power,
                radius, table, balls.filter { it !== cue },
                cuePath
            )
            impact = result.first
            hit = result.second

            if (impact != null && hit != null) {
                val nx0 = hit.center.x - impact.x
                val ny0 = hit.center.y - impact.y
                val length = hypot(nx0, ny0).coerceAtLeast(0.001f)
                val nx = nx0 / length
                val ny = ny0 / length
                val dot = (dx * nx + dy * ny).coerceIn(0f, 1f)

                val objectX = nx * dot
                val objectY = ny * dot
                val objectLength = hypot(objectX, objectY)

                if (objectLength > 0.01f) {
                    trace(
                        hit.center,
                        objectX / objectLength,
                        objectY / objectLength,
                        MAX_DISTANCE * 0.8f,
                        radius,
                        table,
                        balls.filter { it !== hit && it !== cue },
                        objectPath
                    )
                }

                val cueX = dx - objectX
                val cueY = dy - objectY
                val cueLength = hypot(cueX, cueY)

                if (cueLength > 0.04f) {
                    trace(
                        impact,
                        cueX / cueLength,
                        cueY / cueLength,
                        MAX_DISTANCE * 0.45f,
                        radius,
                        table,
                        balls.filter { it !== cue && it !== hit },
                        cueDeflection
                    )
                }
            }
        }

        val basePlan = ShotPlan(
            tableRect = table,
            pockets = pockets,
            ballRadius = radius,
            cueBall = cue,
            balls = balls,
            angleDeg = ((Math.toDegrees(angle) + 360.0) % 360.0).toFloat(),
            powerRatio = power,
            cueSegments = cuePath,
            impactPoint = impact,
            hitBall = hit,
            deflectedCueSegments = cueDeflection,
            objectSegments = objectPath,
            frameWidth = w,
            frameHeight = h
        )

        // Preserve the player's actual aiming direction.
        return basePlan
    }

    private fun findTable(bitmap: Bitmap, step: Int): RectF {
        val w = bitmap.width
        val h = bitmap.height
        val cols = (w + step - 1) / step
        val rows = (h + step - 1) / step
        val mask = BooleanArray(cols * rows)
        val hsv = FloatArray(3)

        // Support both green and blue pool-table felt.
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                Color.colorToHSV(
                    bitmap.getPixel(min(w - 1, c * step), min(h - 1, r * step)),
                    hsv
                )
                mask[r * cols + c] =
                    hsv[0] in 65f..280f &&
                    hsv[1] > 0.20f &&
                    hsv[2] > 0.12f
            }
        }

        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        var bestCount = 0
        var bestLeft = 0
        var bestTop = 0
        var bestRight = 0
        var bestBottom = 0

        for (startIndex in mask.indices) {
            if (!mask[startIndex] || visited[startIndex]) continue

            queue.clear()
            queue.add(startIndex)
            visited[startIndex] = true

            var count = 0
            var left = cols
            var top = rows
            var right = 0
            var bottom = 0

            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val x = index % cols
                val y = index / cols
                count++

                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)

                val neighbours = intArrayOf(
                    index - 1, index + 1,
                    index - cols, index + cols
                )

                for (next in neighbours) {
                    if (next !in mask.indices || visited[next] || !mask[next]) continue
                    if (next == index - 1 && x == 0) continue
                    if (next == index + 1 && x == cols - 1) continue
                    visited[next] = true
                    queue.add(next)
                }
            }

            val spanW = right - left
            val spanH = bottom - top

            if (spanW > cols * 0.30f &&
                spanH > rows * 0.18f &&
                count > bestCount
            ) {
                bestCount = count
                bestLeft = left
                bestTop = top
                bestRight = right
                bestBottom = bottom
            }
        }

        if (bestCount < 30) {
            return RectF(w * 0.08f, h * 0.15f, w * 0.92f, h * 0.85f)
        }

        return RectF(
            (bestLeft * step).toFloat(),
            (bestTop * step).toFloat(),
            min(w, (bestRight + 1) * step).toFloat(),
            min(h, (bestBottom + 1) * step).toFloat()
        )
    }

    private fun smoothTable(found: RectF): RectF {
        val old = previousTable
        val result = if (old == null ||
            absRatio(old.width(), found.width()) > 0.25f ||
            absRatio(old.height(), found.height()) > 0.25f
        ) {
            RectF(found)
        } else {
            RectF(
                old.left * 0.65f + found.left * 0.35f,
                old.top * 0.65f + found.top * 0.35f,
                old.right * 0.65f + found.right * 0.35f,
                old.bottom * 0.65f + found.bottom * 0.35f
            )
        }
        previousTable = result
        return RectF(result)
    }

    private fun absRatio(a: Float, b: Float): Float =
        kotlin.math.abs(a - b) / max(1f, a)

    private fun findBalls(
        bitmap: Bitmap,
        table: RectF,
        step: Int
    ): List<DetectedBall> {
        val left = max(0, table.left.toInt())
        val top = max(0, table.top.toInt())
        val right = min(bitmap.width, table.right.toInt())
        val bottom = min(bitmap.height, table.bottom.toInt())

        val cols = max(1, (right - left + step - 1) / step)
        val rows = max(1, (bottom - top + step - 1) / step)

        val hsv = FloatArray(3)
        val histogram = IntArray(360)

        // Determine the dominant blue/green felt hue.
        for (y in 0 until rows step 2) {
            for (x in 0 until cols step 2) {
                Color.colorToHSV(
                    bitmap.getPixel(
                        min(bitmap.width - 1, left + x * step),
                        min(bitmap.height - 1, top + y * step)
                    ), hsv
                )
                if (hsv[1] > 0.25f && hsv[2] > 0.20f) {
                    histogram[hsv[0].toInt().coerceIn(0, 359)]++
                }
            }
        }

        val feltHue = histogram.indices.maxByOrNull { histogram[it] }?.toFloat() ?: 190f

        val mask = BooleanArray(cols * rows)
        val whiteMask = BooleanArray(cols * rows)

        // Keep ball-coloured pixels, not every pixel that differs slightly
        // from the felt. This prevents felt texture becoming one giant blob.
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                Color.colorToHSV(
                    bitmap.getPixel(
                        min(bitmap.width - 1, left + x * step),
                        min(bitmap.height - 1, top + y * step)
                    ), hsv
                )

                val hueDiff = min(
                    abs(hsv[0] - feltHue),
                    360f - abs(hsv[0] - feltHue)
                )

                val white = hsv[1] < 0.24f && hsv[2] > 0.72f
                val coloured = hueDiff > 24f &&
                    hsv[1] > 0.28f &&
                    hsv[2] > 0.28f

                val index = y * cols + x
                whiteMask[index] = white
                mask[index] = white || coloured
            }
        }

        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        val found = ArrayList<DetectedBall>()

        val expectedRadius = table.width() * 0.014f
        val minRadius = max(3f, expectedRadius * 0.45f)
        val maxRadius = expectedRadius * 1.65f

        for (startIndex in mask.indices) {
            if (!mask[startIndex] || visited[startIndex]) continue

            queue.clear()
            queue.add(startIndex)
            visited[startIndex] = true

            var count = 0
            var whiteCount = 0
            var sumX = 0f
            var sumY = 0f
            var minX = cols
            var maxX = 0
            var minY = rows
            var maxY = 0

            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val x = index % cols
                val y = index / cols

                count++
                sumX += (left + x * step).toFloat()
                sumY += (top + y * step).toFloat()
                if (whiteMask[index]) whiteCount++

                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until cols || ny !in 0 until rows) continue

                        val next = ny * cols + nx
                        if (visited[next] || !mask[next]) continue

                        visited[next] = true
                        queue.add(next)
                    }
                }
            }

            if (count < 5) continue

            val boxW = (maxX - minX + 1) * step
            val boxH = (maxY - minY + 1) * step
            val aspect = boxW.toFloat() / max(1, boxH)
            if (aspect !in 0.60f..1.55f) continue

            val area = count * step * step
            val radius = sqrt(area / PI).toFloat()
            if (radius !in minRadius..maxRadius) continue

            found.add(
                DetectedBall(
                    center = Vec2(sumX / count, sumY / count),
                    radius = radius,
                    areaPx = area,
                    isCueBall = whiteCount > count * 0.45f
                )
            )
        }

        return found
    }

    private fun smoothBalls(found: List<DetectedBall>): List<DetectedBall> {
        val old = previousBalls
        val result = found.map { ball ->
            val previous = old.minByOrNull {
                hypot(it.center.x - ball.center.x, it.center.y - ball.center.y)
            }
            if (previous != null &&
                hypot(previous.center.x - ball.center.x, previous.center.y - ball.center.y)
                    < ball.radius * 2.5f
            ) {
                ball.copy(
                    center = Vec2(
                        previous.center.x * 0.30f + ball.center.x * 0.70f,
                        previous.center.y * 0.30f + ball.center.y * 0.70f
                    )
                )
            } else {
                ball
            }
        }
        previousBalls = result
        return result
    }

    /**
     * Detects the actual white aiming guide by following continuous bright
     * pixels in narrow corridors radiating from the cue ball.
     */
    private fun findCueAngle(
        bitmap: Bitmap,
        cue: DetectedBall?,
        balls: List<DetectedBall>,
        table: RectF
    ): Double {
        if (cue == null) return 0.0

        val radius = cue.radius.coerceAtLeast(2f)
        val firstDistance = radius * 1.35f
        val lastDistance = radius * 10f
        val distanceStep = max(2f, radius * 0.35f)
        val offsets = floatArrayOf(-0.22f, 0f, 0.22f)
        val hsv = FloatArray(3)

        var bestAngle = Double.NaN
        var bestScore = 0.0

        for (degree in -180..179) {
            val angle = Math.toRadians(degree.toDouble())
            val ux = cos(angle).toFloat()
            val uy = sin(angle).toFloat()
            var hits = 0
            var longestRun = 0
            var run = 0
            var nearHits = 0
            var distance = firstDistance

            while (distance <= lastDistance) {
                val px = cue.center.x + ux * distance
                val py = cue.center.y + uy * distance
                if (!table.contains(px, py)) break

                var foundPixel = false

                for (offset in offsets) {
                    val x = (px - uy * radius * offset).toInt()
                    val y = (py + ux * radius * offset).toInt()

                    if (x !in 0 until bitmap.width ||
                        y !in 0 until bitmap.height) continue

                    if (balls.any { ball ->
                        ball !== cue &&
                            hypot(ball.center.x - x, ball.center.y - y) <
                            ball.radius * 0.92f
                    }) continue

                    Color.colorToHSV(bitmap.getPixel(x, y), hsv)

                    if (hsv[1] < 0.30f && hsv[2] > 0.76f) {
                        foundPixel = true
                        break
                    }
                }

                if (foundPixel) {
                    hits++
                    run++
                    longestRun = max(longestRun, run)
                    if (distance <= radius * 4.5f) nearHits++
                } else {
                    run = 0
                }

                distance += distanceStep
            }

            val continuity = longestRun.toDouble() / max(1, hits)
            val score = hits * (0.65 + continuity * 0.35) +
                nearHits * 0.45

            if (hits >= 3 && score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
        }

        if (!bestAngle.isNaN() && bestScore >= 3.0) {
            return bestAngle
        }

        // Fallback if the game aiming guide is not visible.
        val target = balls.asSequence()
            .filter { it !== cue }
            .minByOrNull {
                hypot(it.center.x - cue.center.x, it.center.y - cue.center.y)
            }

        return target?.let {
            atan2(
                (it.center.y - cue.center.y).toDouble(),
                (it.center.x - cue.center.x).toDouble()
            )
        } ?: 0.0
    }

    private fun trace(
        origin: Vec2,
        directionX: Float,
        directionY: Float,
        maxDistance: Float,
        radius: Float,
        table: RectF,
        obstacles: List<DetectedBall>,
        output: MutableList<TrajectorySegment>
    ): Pair<Vec2?, DetectedBall?> {
        var x = origin.x
        var y = origin.y
        var dx = directionX
        var dy = directionY
        var start = origin
        var bounced = false
        var travelled = 0f
        val step = max(2f, radius * 0.20f)

        while (travelled < maxDistance) {
            var nx = x + dx * step
            var ny = y + dy * step
            travelled += step
            var rail = false

            if (nx - radius < table.left) {
                nx = table.left + radius
                dx = -dx
                rail = true
            } else if (nx + radius > table.right) {
                nx = table.right - radius
                dx = -dx
                rail = true
            }

            if (ny - radius < table.top) {
                ny = table.top + radius
                dy = -dy
                rail = true
            } else if (ny + radius > table.bottom) {
                ny = table.bottom - radius
                dy = -dy
                rail = true
            }

            x = nx
            y = ny

            if (rail) {
                output.add(TrajectorySegment(start, Vec2(x, y), bounced))
                start = Vec2(x, y)
                bounced = true
            }

            val collision = obstacles.firstOrNull {
                hypot(it.center.x - x, it.center.y - y) < radius + it.radius
            }

            if (collision != null) {
                output.add(TrajectorySegment(start, Vec2(x, y), bounced))
                return Pair(Vec2(x, y), collision)
            }
        }

        if (hypot(x - start.x, y - start.y) > 1f) {
            output.add(TrajectorySegment(start, Vec2(x, y), bounced))
        }
        return Pair(null, null)
    }
}
