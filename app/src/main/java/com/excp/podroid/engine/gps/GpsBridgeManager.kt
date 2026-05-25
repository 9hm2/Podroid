/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Android Location → NMEA bridge into the QEMU guest. Mirrors the USB
 * passthrough pattern: subscribes to the system LocationManager while the
 * VM is Running, formats fixes as NMEA-0183 sentences ($GPGGA + $GPRMC),
 * and writes them to QemuEngine.gpsSockPath — exposed to the guest as
 * /dev/hvc4. Inside the VM, gpsd is wired to read that device, so
 * wardriving tools (kismet, wifite, gpspipe, cgps) get real-time GPS as if
 * a USB receiver were attached.
 *
 * Skipped cleanly when ACCESS_FINE_LOCATION isn't granted — the bridge
 * logs and stays inactive instead of crashing; the user grants the
 * permission via system Settings on first use.
 */
package com.excp.podroid.engine.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.abs

class GpsBridgeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VmEngine,
) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "GPS bridge coroutine failed", e) }
    )
    private val writeMutex = Mutex()

    @Volatile private var started = false
    @Volatile private var socket: LocalSocket? = null
    private val listener = LocationListener { loc -> scope.launch { writeFix(loc) } }

    // Only backends that expose a GPS channel (QEMU; nullable on AVF) yield a
    // path. When null the bridge stays inactive — same code path as "VM not up".
    private val socketPath: String? get() = engine.gpsSockPath

    /** Subscribe to GPS updates and stream NMEA into the VM. Idempotent.
     *  Called by PodroidService once the VM reaches Running. */
    @SuppressLint("MissingPermission") // checked explicitly below
    fun start() {
        if (started) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted — GPS bridge inactive. Grant it in system Settings.")
            return
        }
        val provider = pickProvider() ?: run {
            Log.w(TAG, "No usable Location provider on this device — GPS bridge inactive.")
            return
        }
        started = true
        Log.d(TAG, "GPS bridge armed (provider=$provider)")
        connectSocket()
        // Push the last known fix immediately so kismet/gpsd see *something*
        // before the first interval expires.
        locationManager.getLastKnownLocation(provider)?.let { scope.launch { writeFix(it) } }
        try {
            locationManager.requestLocationUpdates(
                provider, 1000L, 0f, listener, Looper.getMainLooper(),
            )
        } catch (se: SecurityException) {
            Log.w(TAG, "requestLocationUpdates denied: ${se.message}")
            started = false
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager.removeUpdates(listener) }
        socket?.let { runCatching { it.close() } }
        socket = null
        scope.cancel()
        Log.d(TAG, "GPS bridge disarmed")
    }

    private fun pickProvider(): String? = when {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
        else -> null
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun connectSocket() {
        val path = socketPath ?: return
        runCatching {
            LocalSocket().also { s ->
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = s
            }
        }.onFailure { e ->
            Log.d(TAG, "gps.sock not ready yet (${e.message}); will retry on next fix")
        }
    }

    private suspend fun writeFix(loc: Location) = writeMutex.withLock {
        val nmea = formatNmea(loc)
        val out: OutputStream? = socket?.outputStream ?: run { connectSocket(); socket?.outputStream }
        out ?: return@withLock
        runCatching {
            out.write(nmea.toByteArray())
            out.flush()
        }.onFailure {
            // QEMU likely tore the chardev down (VM stop). Drop the socket so the
            // next fix reconnects when the VM comes back.
            runCatching { socket?.close() }
            socket = null
        }
    }

    // ── NMEA-0183 formatting ─────────────────────────────────────────────────

    /** $GPGGA + $GPGSA + $GPGST + $GPRMC sentence quartet for one location
     *  fix. GGA carries the position + HDOP; GSA carries fix mode and full
     *  DOPs; GST carries pseudorange error stats so gpsd can populate
     *  EPX / EPY / EPV from Android's accuracy estimates; RMC carries the
     *  recommended-minimum data + speed/course/date.
     *  All numeric formatting goes through Locale.US so the decimal
     *  separator is a dot — NMEA-0183 mandates it, and gpsd silently drops
     *  sentences with comma decimals (hu-HU et al. would emit commas
     *  otherwise via the default Locale). */
    private fun formatNmea(loc: Location): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = loc.time }
        val hms = String.format(Locale.US, "%02d%02d%02d.00",
            cal[Calendar.HOUR_OF_DAY], cal[Calendar.MINUTE], cal[Calendar.SECOND],
        )
        val dmy = String.format(Locale.US, "%02d%02d%02d",
            cal[Calendar.DAY_OF_MONTH], cal[Calendar.MONTH] + 1, cal[Calendar.YEAR] % 100,
        )
        val (latStr, ns) = degToNmea(loc.latitude, true)
        val (lonStr, ew) = degToNmea(loc.longitude, false)
        val alt        = String.format(Locale.US, "%.1f", loc.altitude)
        val speedKnots = String.format(Locale.US, "%.1f", loc.speed * 1.94384f) // m/s -> knots
        val course     = String.format(Locale.US, "%.1f", loc.bearing)
        val sats       = String.format(Locale.US, "%02d", loc.extras?.getInt("satellites") ?: 0)
        // Synthesised DOPs from Android's reported accuracy. gpsd's default
        // UERE is 5.1 m, so EPX = HDOP * UERE — pick HDOP = accuracy / 5.1
        // (clamped ≥ 0.5) so cgps's "2D Err" tracks Android's accuracy.
        val haccM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 5.0
        val vaccM = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else haccM * 1.5
        val hdopV = (haccM / 5.1).coerceAtLeast(0.5)
        val vdopV = (vaccM / 5.1).coerceAtLeast(0.5)
        val pdopV = kotlin.math.sqrt(hdopV * hdopV + vdopV * vdopV)
        val hdop  = String.format(Locale.US, "%.1f", hdopV)
        val vdop  = String.format(Locale.US, "%.1f", vdopV)
        val pdop  = String.format(Locale.US, "%.1f", pdopV)
        val gga = "GPGGA,$hms,$latStr,$ns,$lonStr,$ew,1,$sats,$hdop,$alt,M,0.0,M,,"
        // GSA: A=auto-select mode, 3=3D fix (we shipped altitude in GGA), no
        // satellite-ID list (Android doesn't expose it cleanly), then PDOP /
        // HDOP / VDOP. With this gpsd reports a 3D fix and full DOPs instead
        // of leaving PDOP/VDOP as n/a.
        val gsa = "GPGSA,A,3,,,,,,,,,,,,,$pdop,$hdop,$vdop"
        // GST: pseudorange error stats — std_lat / std_lon / std_alt (1σ in
        // meters). Populates cgps's EPX / EPY / EPV directly from Android's
        // accuracy estimates instead of leaving them n/a. Other fields
        // (rms, error-ellipse axes) stay empty.
        val gst = String.format(Locale.US, "GPGST,%s,,,,,%.1f,%.1f,%.1f", hms, haccM, haccM, vaccM)
        val rmc = "GPRMC,$hms,A,$latStr,$ns,$lonStr,$ew,$speedKnots,$course,$dmy,,"
        return buildString {
            append('$').append(gga).append('*').append(cksum(gga)).append("\r\n")
            append('$').append(gsa).append('*').append(cksum(gsa)).append("\r\n")
            append('$').append(gst).append('*').append(cksum(gst)).append("\r\n")
            append('$').append(rmc).append('*').append(cksum(rmc)).append("\r\n")
        }
    }

    private fun degToNmea(deg: Double, isLat: Boolean): Pair<String, Char> {
        val hem = if (isLat) (if (deg >= 0) 'N' else 'S')
                  else      (if (deg >= 0) 'E' else 'W')
        val a = abs(deg)
        val d = a.toInt()
        val m = (a - d) * 60.0
        val s = if (isLat) String.format(Locale.US, "%02d%07.4f", d, m) else String.format(Locale.US, "%03d%07.4f", d, m)
        return s to hem
    }

    private fun cksum(body: String): String {
        var c = 0
        for (b in body.toByteArray()) c = c xor (b.toInt() and 0xFF)
        return String.format(Locale.US, "%02X", c)
    }

    companion object {
        private const val TAG = "GpsBridge"
    }
}
