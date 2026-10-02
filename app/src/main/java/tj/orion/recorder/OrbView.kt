package tj.orion.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The record button: a juicy iridescent orb with eyes.
 * IDLE = eyes closed, calm. RECORDING = vivid, breathing, eyes open + blink + rare glance.
 * BUSY = vivid, breathing, eyes closed (processing).
 */
class OrbView @JvmOverloads constructor(ctx: Context, a: AttributeSet? = null) : View(ctx, a) {

    enum class State { IDLE, RECORDING, BUSY }
    var state: State = State.IDLE

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 107) }

    private var cx = 0f
    private var cy = 0f
    private var baseR = 0f
    private val eyeRect = RectF()

    private val startNanos = System.nanoTime()
    private var openCur = 0.12f
    private var glanceCur = 0f

    private val orbColors = intArrayOf(
        Color.rgb(167, 240, 255), Color.rgb(77, 139, 255), Color.rgb(162, 77, 255),
        Color.rgb(255, 63, 160), Color.rgb(255, 90, 46), Color.rgb(255, 209, 160)
    )
    private val orbStops = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.84f, 1f)

    private data class Spark(val ang: Float, val dist: Float, val size: Float, val phase: Float)
    private val sparks = ArrayList<Spark>()

    init {
        val rnd = Random(7)
        repeat(26) {
            sparks.add(
                Spark(
                    ang = (rnd.nextFloat() * (2 * Math.PI)).toFloat(),
                    dist = 1.08f + rnd.nextFloat() * 0.55f,
                    size = 1.2f + rnd.nextFloat() * 2.2f,
                    phase = rnd.nextFloat() * 6.28f
                )
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f
        baseR = minOf(w, h) / 2f * 0.46f
        orbPaint.shader = LinearGradient(
            cx - baseR, cy - baseR, cx + baseR, cy + baseR,
            orbColors, orbStops, Shader.TileMode.CLAMP
        )
        glossPaint.shader = RadialGradient(
            cx - baseR * 0.35f, cy - baseR * 0.42f, baseR * 0.95f,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - startNanos) / 1_000_000_000f
        val active = state != State.IDLE

        val breathe = if (active) 1f + 0.05f * sin(2.0 * Math.PI * t / 3.2).toFloat() else 0.94f

        // halo glow
        val haloR = baseR * 1.75f * breathe
        val glow = if (active) 150 else 70
        haloPaint.shader = RadialGradient(
            cx, cy, haloR,
            intArrayOf(Color.argb(glow, 150, 90, 255), Color.argb((glow * 0.5f).toInt(), 255, 70, 150), Color.argb(0, 255, 70, 150)),
            floatArrayOf(0.45f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, haloR, haloPaint)

        // orb + gloss (scaled by breathing)
        canvas.save()
        canvas.scale(breathe, breathe, cx, cy)
        canvas.drawCircle(cx, cy, baseR, orbPaint)
        canvas.drawCircle(cx, cy, baseR, glossPaint)
        canvas.restore()

        // sparkles
        if (active) {
            for (s in sparks) {
                val a = 0.18f + 0.82f * abs(sin(t * 1.8f + s.phase))
                sparkPaint.alpha = (a * 255).toInt()
                val rr = baseR * s.dist * breathe
                canvas.drawCircle(cx + cos(s.ang) * rr, cy + sin(s.ang) * rr, s.size, sparkPaint)
            }
        }

        // eyes
        val openTarget = if (state == State.RECORDING) 1f else 0.12f
        openCur += (openTarget - openCur) * 0.18f
        var blink = 1f
        var glanceTarget = 0f
        if (state == State.RECORDING) {
            val p = t % 4.6f
            if (p in 4.15f..4.45f) blink = 0.08f
            val g = t % 8f
            glanceTarget = when {
                g in 4f..4.7f -> -1f
                g in 5.1f..5.8f -> 1f
                else -> 0f
            }
        }
        glanceCur += (glanceTarget - glanceCur) * 0.15f

        val ew = baseR * 0.16f
        val eh = baseR * 0.48f * openCur * blink
        val gap = baseR * 0.18f
        val gx = glanceCur * baseR * 0.12f
        for (sx in intArrayOf(-1, 1)) {
            val ecx = cx + sx * (gap / 2 + ew / 2) + gx
            eyeRect.set(ecx - ew / 2, cy - eh / 2, ecx + ew / 2, cy + eh / 2)
            canvas.drawRoundRect(eyeRect, ew / 2, ew / 2, eyePaint)
        }

        postInvalidateOnAnimation()
    }
}
