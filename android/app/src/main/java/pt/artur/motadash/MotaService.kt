package pt.artur.motadash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the link to the dashboard alive in the background:
 *  - waits for the board with a background (autoConnect) BLE connection,
 *    which costs almost nothing while the bike is off;
 *  - once connected, turns the phone GPS on and sends every fix to the
 *    board; GPS goes off again as soon as the board disconnects;
 *  - receives the 10 Hz telemetry and the text command answers.
 */
@SuppressLint("MissingPermission") // every entry point checks Permissions.ready() first
class MotaService : Service() {

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 247
        private const val OP_TIMEOUT_MS = 2000L

        fun start(ctx: Context) {
            if (!Permissions.ready(ctx)) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, MotaService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MotaService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var uartWrite: BluetoothGattCharacteristic? = null
    private var gpsChar: BluetoothGattCharacteristic? = null
    private val rx = StringBuilder()
    private var scanning = false
    private var stopped = false

    // GATT operations run one at a time on Android.
    private sealed class Op {
        class Notify(val c: BluetoothGattCharacteristic) : Op()
        class Write(val c: BluetoothGattCharacteristic, val value: ByteArray, val type: Int, val gps: Boolean = false) : Op()
    }
    private val ops = ArrayDeque<Op>()
    private var busy = false
    private val opTimeout = Runnable { busy = false; nextOp() }

    // GPS
    private var locationManager: LocationManager? = null
    private var gpsOn = false
    private var gpsSeq = 0
    private val fixTimes = ArrayDeque<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ligação à mota", NotificationManager.IMPORTANCE_LOW)
        )
        goForeground("À espera da mota")
        Link.service = this
        locationManager = getSystemService(LocationManager::class.java)
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        stopScan()
        stopGps()
        gatt?.close()
        gatt = null
        Link.service = null
        Link.setTelemetry(null)
        Link.setStatus(LinkStatus.STOPPED)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Mota Dash")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun goForeground(text: String) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Permissions.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), types)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    // ---- Connection ----

    private fun adapter() = getSystemService(BluetoothManager::class.java)?.adapter

    fun reconnect() {
        handler.post {
            stopScan()
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            stopGps()
            connect()
        }
    }

    private fun connect() {
        if (stopped) return
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            Link.setStatus(LinkStatus.NO_DEVICE)
            updateNotification("Bluetooth desligado")
            handler.postDelayed({ connect() }, 10_000)
            return
        }
        val address = Link.savedAddress(this)
        if (address == null) {
            startScan()
            return
        }
        Link.setStatus(LinkStatus.WAITING)
        updateNotification("À espera da mota")
        val device = adapter.getRemoteDevice(address)
        // autoConnect = true: the Bluetooth controller connects by itself
        // whenever the board starts advertising, at very low power.
        gatt = device.connectGatt(this, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!scanning) return@post
                stopScan()
                Link.saveAddress(this@MotaService, result.device.address)
                connect()
            }
        }
    }

    private fun startScan() {
        val scanner = adapter()?.bluetoothLeScanner ?: return
        Link.setStatus(LinkStatus.SCANNING)
        updateNotification("À procura da mota")
        scanning = true
        val filters = listOf(ScanFilter.Builder().setDeviceName(BleProtocol.DEVICE_NAME).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(filters, settings, scanCallback)
        // Android limits scans; look for 30 s, rest for 30 s.
        handler.postDelayed({
            if (scanning) {
                stopScan()
                handler.postDelayed({ connect() }, 30_000)
            }
        }, 30_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Link.setStatus(LinkStatus.CONNECTING)
                    g.requestMtu(MTU)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onDisconnected(g)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post { onReady(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { opDone() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { opDone() }
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION") val v = c.value ?: return
                handler.post { onNotify(c.uuid, v) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { onNotify(c.uuid, value) }
        }
    }

    private fun onDisconnected(g: BluetoothGatt) {
        stopGps()
        ops.clear()
        busy = false
        handler.removeCallbacks(opTimeout)
        uartWrite = null
        gpsChar = null
        g.close()
        if (gatt == g) gatt = null
        Link.setTelemetry(null)
        if (stopped) return
        Link.setStatus(LinkStatus.WAITING)
        updateNotification("À espera da mota")
        handler.postDelayed({ if (gatt == null) connect() }, 1000)
    }

    private fun onReady(g: BluetoothGatt) {
        val mota = g.getService(BleProtocol.SERVICE)
        val uart = g.getService(BleProtocol.UART_SERVICE)
        if (mota == null || uart == null) {
            // Not our board (or old firmware): forget it and look again.
            Link.onLine("Este dispositivo não tem o serviço Mota Dash (firmware antigo?)")
            g.disconnect()
            return
        }
        gpsChar = mota.getCharacteristic(BleProtocol.GPS)
        uartWrite = uart.getCharacteristic(BleProtocol.UART_WRITE)
        mota.getCharacteristic(BleProtocol.TELEMETRY)?.let { enqueue(Op.Notify(it)) }
        uart.getCharacteristic(BleProtocol.UART_NOTIFY)?.let { enqueue(Op.Notify(it)) }
        sendCommand("ATE0")
        sendCommand("PARAM")
        sendCommand("TANK")
        Link.readDtc()
        Link.setStatus(LinkStatus.CONNECTED)
        updateNotification("Ligado à mota")
        startGps()
    }

    private fun onNotify(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleProtocol.TELEMETRY -> BleProtocol.decodeTelemetry(value)?.let { Link.setTelemetry(it) }
            BleProtocol.UART_NOTIFY -> {
                rx.append(String(value, Charsets.ISO_8859_1))
                while (true) {
                    val i = rx.indexOfFirst { it == '\r' || it == '\n' || it == '>' }
                    if (i < 0) break
                    val line = rx.substring(0, i)
                    rx.delete(0, i + 1)
                    Link.onLine(line)
                }
                if (rx.length > 512) rx.setLength(0)
            }
        }
    }

    // ---- GATT queue ----

    private fun enqueue(op: Op) {
        if (op is Op.Write && op.gps) {
            // Only the newest fix matters.
            ops.removeAll { it is Op.Write && it.gps }
        }
        ops.addLast(op)
        nextOp()
    }

    private fun opDone() {
        busy = false
        handler.removeCallbacks(opTimeout)
        nextOp()
    }

    private fun nextOp() {
        val g = gatt ?: return
        if (busy) return
        val op = ops.removeFirstOrNull() ?: return
        val ok = when (op) {
            is Op.Notify -> {
                g.setCharacteristicNotification(op.c, true)
                val d = op.c.getDescriptor(BleProtocol.CCCD)
                if (d == null) false else if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
                } else {
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }
            is Op.Write -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(op.c, op.value, op.type) == 0
                } else {
                    @Suppress("DEPRECATION")
                    op.c.writeType = op.type
                    @Suppress("DEPRECATION")
                    op.c.value = op.value
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(op.c)
                }
            }
        }
        if (ok) {
            busy = true
            handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
        } else {
            handler.post { nextOp() }
        }
    }

    fun sendCommand(command: String) {
        handler.post {
            val c = uartWrite ?: return@post
            enqueue(Op.Write(c, (command + "\r").toByteArray(Charsets.ISO_8859_1),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        }
    }

    // ---- GPS ----

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onFix(loc)
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startGps() {
        if (gpsOn || !Permissions.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = locationManager ?: return
        try {
            // 0 ms / 0 m: as fast as the phone's GPS chip delivers.
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, Looper.getMainLooper())
            gpsOn = true
        } catch (e: Exception) {
            Link.onLine("GPS: ${e.message}")
        }
    }

    private fun stopGps() {
        if (!gpsOn) return
        locationManager?.removeUpdates(locationListener)
        gpsOn = false
        fixTimes.clear()
        Link.updateGps { it.copy(hz = 0.0) }
    }

    private fun onFix(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        fixTimes.addLast(now)
        while (fixTimes.isNotEmpty() && now - fixTimes.first() > 5000) fixTimes.removeFirst()
        val hz = if (fixTimes.size >= 2) (fixTimes.size - 1) * 1000.0 / (fixTimes.last() - fixTimes.first()) else 0.0
        val speed = if (loc.hasSpeed()) loc.speed * 3.6 else null
        val speedAcc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond * 3.6 else null
        val hAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000).toInt()
        val packet = BleProtocol.encodeGps(
            seq = gpsSeq++,
            speedKmh = speed,
            speedAccKmh = speedAcc,
            latE7 = Math.round(loc.latitude * 1e7).toInt(),
            lonE7 = Math.round(loc.longitude * 1e7).toInt(),
            hAccM = hAcc,
            altM = if (loc.hasAltitude()) loc.altitude.toInt() else 0,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            fixAgeMs = ageMs,
        )
        var sent = false
        gpsChar?.let {
            enqueue(Op.Write(it, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, gps = true))
            sent = true
        }
        Link.updateGps {
            it.copy(hz = hz, speedKmh = speed, speedAccKmh = speedAcc, hAccM = hAcc,
                fixes = it.fixes + 1, sent = it.sent + if (sent) 1 else 0)
        }
    }
}

object Permissions {
    fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Runtime permissions asked on first start. */
    fun needed(): Array<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    /** What the service needs to run at all. */
    fun ready(ctx: Context): Boolean {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (Build.VERSION.SDK_INT >= 31 &&
            (!has(ctx, Manifest.permission.BLUETOOTH_CONNECT) || !has(ctx, Manifest.permission.BLUETOOTH_SCAN))
        ) return false
        return true
    }

    fun background(ctx: Context) = has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
}
