package pt.artur.motadash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The phone on the handlebar as an attitude sensor: compass heading, the bike's pitch and its lean.
 *
 * Standing or slow, lean and pitch come from gravity, relative to the "zero" the rider set with the
 * bike upright. Riding, gravity can't show the lean (in a steady turn the felt "down" leans with the
 * bike), so lean comes from the turn instead: atan(speed x yaw rate / g).
 */
object Attitude : SensorEventListener {
    /** Degrees. Lean: right positive. Pitch: nose up positive. Azimuth: where the screen's top points. */
    data class State(
        val azimuth: Float = 0f, val pitch: Float = 0f, val lean: Float = 0f,
        val maxLeft: Float = 0f, val maxRight: Float = 0f, val ok: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var sm: SensorManager? = null
    private var rotation = Surface.ROTATION_0
    private val g = FloatArray(3)
    private var haveG = false
    private val w = FloatArray(3)
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)
    private var azimuth = 0f
    private var pitch = 0f
    private var lean = 0f
    private var rawPitch = 0f
    private var rawLean = 0f
    private var pitch0 = 0f
    private var lean0 = 0f
    private var maxLeft = 0f
    private var maxRight = 0f
    private var lastEmit = 0L
    private var wm: WindowManager? = null
    private var rotationAt = 0L

    @Suppress("DEPRECATION")
    fun start(ctx: Context) {
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        sm = m
        wm = ctx.getSystemService(WindowManager::class.java)
        rotation = wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val p = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        pitch0 = p.getFloat("att_pitch0", 0f)
        lean0 = p.getFloat("att_lean0", 0f)
        (m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
            ?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm?.unregisterListener(this)
        sm = null
    }

    /** "This is upright and level": the current reading becomes zero, and the lean maxima reset. */
    fun zero(ctx: Context) {
        pitch0 = rawPitch
        lean0 = rawLean
        maxLeft = 0f
        maxRight = 0f
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putFloat("att_pitch0", pitch0).putFloat("att_lean0", lean0).apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        // The screen can turn while running (reverse landscape, auto): the axes must follow it.
        val t = SystemClock.elapsedRealtime()
        if (t - rotationAt > 1000) { rotationAt = t; wm?.defaultDisplay?.rotation?.let { rotation = it } }
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                val (x, y) = when (rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rot, x, y, remapped)
                SensorManager.getOrientation(remapped, orient)
                azimuth = ((Math.toDegrees(orient[0].toDouble()).toFloat() + 360f) % 360f)
            }
            Sensor.TYPE_GYROSCOPE -> { w[0] = e.values[0]; w[1] = e.values[1]; w[2] = e.values[2] }
            else -> {
                // Gravity (or the accelerometer, smoothed).
                val k = if (e.sensor.type == Sensor.TYPE_GRAVITY || !haveG) 1f else 0.15f
                for (i in 0..2) g[i] += (e.values[i] - g[i]) * k
                haveG = true
                fuse()
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmit >= 50) {
            lastEmit = now
            _state.value = State(azimuth, pitch, lean, maxLeft, maxRight, haveG)
        }
    }

    private fun fuse() {
        // Gravity in screen axes: x to the right of the shown screen, y up it, z out of it towards the rider.
        val (sx, sy) = when (rotation) {
            Surface.ROTATION_90 -> g[1] to -g[0]
            Surface.ROTATION_180 -> -g[0] to -g[1]
            Surface.ROTATION_270 -> -g[1] to g[0]
            else -> g[0] to g[1]
        }
        val sz = g[2]
        rawLean = Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
        rawPitch = Math.toDegrees(atan2(sz, sqrt(sx * sx + sy * sy)).toDouble()).toFloat()

        val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(1f)
        val yawRate = (w[0] * g[0] + w[1] * g[1] + w[2] * g[2]) / norm // rad/s about "up", left turn positive
        val kmh = Link.telemetry.value?.displaySpeed ?: Link.gps.value.speedKmh ?: 0.0
        val v = kmh / 3.6
        val target = if (v > 4.0) -Math.toDegrees(atan(v * yawRate / 9.81)).toFloat() else wrap(rawLean - lean0)
        // Smoothed the short way round (never through a whole turn), and never past lying flat.
        lean = wrap(lean + wrap(target - lean) * 0.25f).coerceIn(-90f, 90f)
        pitch = wrap(pitch + wrap(wrap(rawPitch - pitch0) - pitch) * 0.2f).coerceIn(-90f, 90f)
        if (v > 4.0) {
            maxRight = max(maxRight, lean)
            maxLeft = max(maxLeft, -lean)
        }
    }

    private fun wrap(d: Float): Float = ((d + 540f) % 360f) - 180f
}
