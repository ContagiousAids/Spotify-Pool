package com.ollfx.spotifypool

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import java.util.ArrayDeque
import kotlin.math.PI
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
        val objectSegments: List<TrajectorySegment>
    )

    private const val BASE_MAX_REACH_PX = 1850f
    private const val STEP_PX = 5f

    fun analyzeFrame(bitmap: Bitmap, step: Int): ShotPlan {
        val w = bitmap.width
        val h = bitmap.height
        val safeStep = step.coerceAtLeast(2)

        val tableRect = detectTableBounds(bitmap, w, h, safeStep)
        val pockets = computeSixPockets(tableRect)
        val balls = detectBalls(bitmap, tableRect, safeStep)

        val cueBall = balls.firstOrNull { it.isCueBall }
            ?: balls.minByOrNull { hypot(it.center.x - tableRect.centerX(), it.center.y - tableRect.centerY()) }

        val ballRadius = cueBall?.radius
            ?: balls.map { it.radius }.average().toFloat().takeIf { !it.isNaN() && it > 4f }
            ?: max(10f, tableRect.width() * 0.015f)

        val powerRatio = detectPowerRatio(bitmap, tableRect, w, h)
        val shotAngleRad = detectCueAngleRad(bitmap, tableRect, cueBall, balls)
        val angleDeg = ((((shotAngleRad * 180.0 / PI) % 360.0) + 360.0) % 360.0).toFloat()

        val reachBudget = BASE_MAX_REACH_PX * (0.20f + 0.80f * powerRatio)
        val cueSegments = ArrayList<TrajectorySegment>()
        var impactPoint: Vec2? = null
        var hitBall: DetectedBall? = null

        if (cueBall != null) {
            var pos = cueBall.center
            var segStart = pos
            var vx = cos(shotAngleRad).toFloat()
            var vy = sin(shotAngleRad).toFloat()
            var traveled = 0f
            var segRail = false

            while (traveled < reachBudget) {
                val candX = pos.x + vx * STEP_PX
                val candY = pos.y + vy * STEP_PX
                traveled += STEP_PX

                var hitRail = false
                var nextX = candX
                var nextY = candY

                if (nextX - ballRadius < tableRect.left) {
                    nextX = tableRect.left + ballRadius
                    vx = -vx
                    hitRail = true
                } else if (nextX + ballRadius > tableRect.right) {
                    nextX = tableRect.right - ballRadius
                    vx = -vx
                    hitRail = true
                }

                if (nextY - ballRadius < tableRect.top) {
                    nextY = tableRect.top + ballRadius
                    vy = -vy
                    hitRail = true
                } else if (nextY + ballRadius > tableRect.bottom) {
                    nextY = tableRect.bottom - ballRadius
                    vy = -vy
                    hitRail = true
                }

                val nextPos = Vec2(nextX, nextY)
                if (hitRail) {
                    if (hypot(nextPos.x - segStart.x, nextPos.y - segStart.y) >= 1f) {
                        cueSegments.add(TrajectorySegment(segStart, nextPos, segRail))
                    }
                    segStart = nextPos
                    segRail = true
                }
                pos = nextPos

                val target = balls.firstOrNull { other ->
                    other !== cueBall &&
                        hypot(other.center.x - pos.x, other.center.y - pos.y) <= (ballRadius + other.radius) * 0.96f
                }
                if (target != null) {
                    if (hypot(pos.x - segStart.x, pos.y - segStart.y) >= 1f) {
                        cueSegments.add(TrajectorySegment(segStart, pos, segRail))
                    }
                    impactPoint = pos
                    hitBall = target
                    break
                }
            }

            if (impactPoint == null && hypot(pos.x - segStart.x, pos.y - segStart.y) >= 1f) {
                cueSegments.add(TrajectorySegment(segStart, pos, segRail))
            }
        }

        val deflectedCueSegments = ArrayList<TrajectorySegment>()
        val objectSegments = ArrayList<TrajectorySegment>()

        if (cueBall != null && impactPoint != null && hitBall != null) {
            val dx = hitBall.center.x - impactPoint.x
            val dy = hitBall.center.y - impactPoint.y
            val dist = hypot(dx, dy).coerceAtLeast(1e-4f)
            val nx = dx / dist
            val ny = dy / dist

            val inX = cos(shotAngleRad).toFloat()
            val inY = sin(shotAngleRad).toFloat()
            val dot = (inX * nx + inY * ny).coerceIn(0f, 1f)

            val objVx = nx * dot
            val objVy = ny * dot
            val cueOutX = inX - objVx
            val cueOutY = inY - objVy

            val objMag = hypot(objVx, objVy)
            if (objMag > 1e-3f) {
                val objBudget = reachBudget * (0.25f + 0.75f * objMag) * (0.4f + 0.6f * powerRatio)
                traceRayWithRails(
                    origin = hitBall.center,
                    dirX = objVx / objMag,
                    dirY = objVy / objMag,
                    maxDistance = objBudget,
                    ballRadius = ballRadius,
                    tableRect = tableRect,
                    pockets = pockets,
                    outSegments = objectSegments
                )
            }

            val cueOutMag = hypot(cueOutX, cueOutY)
            if (cueOutMag > 1e-3f) {
                val cueOutBudget = reachBudget * (0.20f + 0.80f * cueOutMag) * (0.35f + 0.65f * powerRatio)
                traceRayWithRails(
                    origin = impactPoint,
                    dirX = cueOutX / cueOutMag,
                    dirY = cueOutY / cueOutMag,
                    maxDistance = cueOutBudget,
                    ballRadius = ballRadius,
                    tableRect = tableRect,
                    pockets = pockets,
                    outSegments = deflectedCueSegments
                )
            }
        }

        return ShotPlan(
            tableRect = tableRect,
            pockets = pockets,
            ballRadius = ballRadius,
            cueBall = cueBall,
            balls = balls,
            angleDeg = angleDeg,
            powerRatio = powerRatio,
            cueSegments = cueSegments,
            impactPoint = impactPoint,
            hitBall = hitBall,
            deflectedCueSegments = deflectedCueSegments,
            objectSegments = objectSegments
        )
    }

    private fun traceRayWithRails(
        origin: Vec2,
        dirX: Float,
        dirY: Float,
        maxDistance: Float,
        ballRadius: Float,
        tableRect: RectF,
        pockets: List<Vec2>,
        outSegments: MutableList<TrajectorySegment>
    ) {
        var pos = origin
        var segStart = origin
        var vx = dirX
        var vy = dirY
        var traveled = 0f
        var segRail = false
        val pocketCaptureRadius = ballRadius * 1.45f

        while (traveled < maxDistance) {
            val candX = pos.x + vx * STEP_PX
            val candY = pos.y + vy * STEP_PX
            traveled += STEP_PX

            var hitRail = false
            var nextX = candX
            var nextY = candY

            if (nextX - ballRadius < tableRect.left) {
                nextX = tableRect.left + ballRadius
                vx = -vx
                hitRail = true
            } else if (nextX + ballRadius > tableRect.right) {
                nextX = tableRect.right - ballRadius
                vx = -vx
                hitRail = true
            }

            if (nextY - ballRadius < tableRect.top) {
                nextY = tableRect.top + ballRadius
                vy = -vy
                hitRail = true
            } else if (nextY + ballRadius > tableRect.bottom) {
                nextY = tableRect.bottom - ballRadius
                vy = -vy
                hitRail = true
            }

            val nextPos = Vec2(nextX, nextY)
            if (hitRail) {
                if (hypot(nextPos.x - segStart.x, nextPos.y - segStart.y) >= 1f) {
                    outSegments.add(TrajectorySegment(segStart, nextPos, segRail))
                }
                segStart = nextPos
                segRail = true
            }
            pos = nextPos

            val inPocket = pockets.any { p -> hypot(p.x - pos.x, p.y - pos.y) <= pocketCaptureRadius }
            if (inPocket) {
                if (hypot(pos.x - segStart.x, pos.y - segStart.y) >= 1f) {
                    outSegments.add(TrajectorySegment(segStart, pos, segRail))
                }
                return
            }
        }

        if (hypot(pos.x - segStart.x, pos.y - segStart.y) >= 1f) {
            outSegments.add(TrajectorySegment(segStart, pos, segRail))
        }
    }

    private fun computeSixPockets(table: RectF): List<Vec2> {
        val midX = (table.left + table.right) * 0.5f
        return listOf(
            Vec2(table.left, table.top),
            Vec2(midX, table.top),
            Vec2(table.right, table.top),
            Vec2(table.left, table.bottom),
            Vec2(midX, table.bottom),
            Vec2(table.right, table.bottom)
        )
    }

    private fun detectTableBounds(bitmap: Bitmap, w: Int, h: Int, step: Int): RectF {
        var minX = w.toFloat()
        var minY = h.toFloat()
        var maxX = 0f
        var maxY = 0f
        var hits = 0

        val hsv = FloatArray(3)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                val isFeltGreen = hsv[0] in 72f..168f && hsv[1] >= 0.22f && hsv[2] >= 0.16f
                if (isFeltGreen) {
                    if (x < minX) minX = x.toFloat()
                    if (y < minY) minY = y.toFloat()
                    if (x > maxX) maxX = x.toFloat()
                    if (y > maxY) maxY = y.toFloat()
                    hits++
                }
                x += step
            }
            y += step
        }

        val spanW = maxX - minX
        val spanH = maxY - minY
        if (hits < 40 || spanW < w * 0.35f || spanH < h * 0.20f) {
            return RectF(w * 0.14f, h * 0.23f, w * 0.86f, h * 0.77f)
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun detectBalls(bitmap: Bitmap, table: RectF, step: Int): List<DetectedBall> {
        val left = max(0, table.left.toInt())
        val top = max(0, table.top.toInt())
        val right = min(bitmap.width, table.right.toInt())
        val bottom = min(bitmap.height, table.bottom.toInt())

        val cols = max(1, (right - left) / step)
        val rows = max(1, (bottom - top) / step)
        val mask = BooleanArray(cols * rows)
        val cueMask = BooleanArray(cols * rows)
        val hsv = FloatArray(3)

        for (r in 0 until rows) {
            val py = top + r * step
            for (c in 0 until cols) {
                val px = left + c * step
                val color = bitmap.getPixel(px, py)
                Color.colorToHSV(color, hsv)
                val felt = hsv[0] in 72f..168f && hsv[1] >= 0.22f && hsv[2] >= 0.16f
                if (!felt && hsv[2] > 0.14f) {
                    val idx = r * cols + c
                    mask[idx] = true
                    if (hsv[1] < 0.20f && hsv[2] > 0.80f) {
                        cueMask[idx] = true
                    }
                }
            }
        }

        val visited = BooleanArray(cols * rows)
        val result = ArrayList<DetectedBall>()
        val queue = ArrayDeque<Int>()
        val dirs = intArrayOf(-1, 1, -cols, cols)

        for (idx in mask.indices) {
            if (!mask[idx] || visited[idx]) continue
            visited[idx] = true
            queue.clear()
            queue.add(idx)

            var sumX = 0f
            var sumY = 0f
            var count = 0
            var cueHits = 0

            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val r = cur / cols
                val c = cur % cols
                sumX += (left + c * step).toFloat()
                sumY += (top + r * step).toFloat()
                count++
                if (cueMask[cur]) cueHits++

                for (d in dirs) {
                    val nxt = cur + d
                    if (nxt < 0 || nxt >= mask.size || visited[nxt] || !mask[nxt]) continue
                    if (d == -1 && (cur % cols) == 0) continue
                    if (d == 1 && ((cur + 1) % cols) == 0) continue
                    visited[nxt] = true
                    queue.add(nxt)
                }
            }

            if (count in 2..600) {
                val area = count * step * step
                val radiusPx = sqrt(area / PI).toFloat()
                if (radiusPx in 4f..65f) {
                    result.add(
                        DetectedBall(
                            center = Vec2(sumX / count, sumY / count),
                            radius = radiusPx,
                            areaPx = area,
                            isCueBall = cueHits > count * 0.40f
                        )
                    )
                }
            }
        }
        return result
    }

    private fun detectPowerRatio(bitmap: Bitmap, table: RectF, w: Int, h: Int): Float {
        val barLeft = max(0, table.left.toInt())
        val barRight = min(w, table.right.toInt())
        val yStart = min(h - 1, (table.bottom + h * 0.03f).toInt())
        val yEnd = min(h, (table.bottom + h * 0.16f).toInt())
        if (yEnd <= yStart || barRight <= barLeft) return 0.55f

        var activeCols = 0
        var totalCols = 0
        val hsv = FloatArray(3)
        var x = barLeft
        while (x < barRight) {
            totalCols++
            var litInCol = 0
            var y = yStart
            while (y < yEnd) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                if (hsv[1] > 0.42f && hsv[2] > 0.50f) {
                    litInCol++
                }
                y += 3
            }
            if (litInCol >= 2) activeCols++
            x += 5
        }
        return if (totalCols > 0) (activeCols.toFloat() / totalCols.toFloat()).coerceIn(0.08f, 1.0f) else 0.55f
    }

    private fun detectCueAngleRad(
        bitmap: Bitmap,
        table: RectF,
        cueBall: DetectedBall?,
        balls: List<DetectedBall>
    ): Double {
        if (cueBall != null) {
            val minDist = cueBall.radius * 1.15f
            val maxDist = max(65f, cueBall.radius * 6.5f)
            val x0 = max(0, (cueBall.center.x - maxDist).toInt())
            val y0 = max(0, (cueBall.center.y - maxDist).toInt())
            val x1 = min(bitmap.width, (cueBall.center.x + maxDist).toInt())
            val y1 = min(bitmap.height, (cueBall.center.y + maxDist).toInt())

            var sumX = 0.0
            var sumY = 0.0
            var weightSum = 0.0
            val hsv = FloatArray(3)

            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val dx = x - cueBall.center.x
                    val dy = y - cueBall.center.y
                    val d = hypot(dx, dy)
                    if (d in minDist..maxDist) {
                        Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                        val warmStick = (hsv[0] in 12f..58f || hsv[0] in 325f..360f) &&
                            hsv[1] >= 0.30f && hsv[2] >= 0.30f
                        if (warmStick) {
                            val w = d.toDouble()
                            sumX += dx * w
                            sumY += dy * w
                            weightSum += w
                        }
                    }
                    x += 2
                }
                y += 2
            }

            if (weightSum > 0.0) {
                val shaftAngle = atan2(sumY / weightSum, sumX / weightSum)
                return shaftAngle + PI
            }
        }

        val nearest = balls.filter { it !== cueBall }.minByOrNull {
            val cx = cueBall?.center?.x ?: table.centerX()
            val cy = cueBall?.center?.y ?: table.centerY()
            hypot(it.center.x - cx, it.center.y - cy)
        }
        return if (cueBall != null && nearest != null) {
            atan2(
                (nearest.center.y - cueBall.center.y).toDouble(),
                (nearest.center.x - cueBall.center.x).toDouble()
            )
        } else {
            0.0
        }
    }
}
