package com.example.minimidi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException

/**
 * Sends MIDI to the computer over USB. With the phone's USB mode set to "MIDI", the host appears here as a
 * TYPE_USB device whose *input* port is what we write to (ports are named from the device's point of view).
 * This is the one seam a future Bluetooth output plugs in beside.
 */
class MidiOut(context: Context) {
    var connected by mutableStateOf(false)
        private set

    private val manager = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private var opening = false
    private var started = false // openDevice is async: a callback landing after stop() must not reopen

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = openIfUsb(info)
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id) close()
        }
    }

    // ponytail: the pre-API-33 overloads work on every supported version; move to the transport/executor overloads if they're removed.
    @Suppress("DEPRECATION")
    fun start() {
        started = true
        manager.registerDeviceCallback(callback, handler)
        manager.devices.forEach(::openIfUsb)
    }

    fun stop() {
        started = false
        manager.unregisterDeviceCallback(callback)
        close()
    }

    /** Never throws: a failed write (cable yanked mid-send) just marks us disconnected. */
    fun send(bytes: ByteArray) {
        val p = port ?: return
        try {
            p.send(bytes, 0, bytes.size)
        } catch (e: IOException) {
            close()
        }
    }

    // A phone acting as a USB peripheral can't host another USB MIDI device at the same time,
    // so the first USB device with an input port is the computer.
    private fun openIfUsb(info: MidiDeviceInfo) {
        if (!started || device != null || opening || info.type != MidiDeviceInfo.TYPE_USB || info.inputPortCount == 0) return
        opening = true
        manager.openDevice(info, { d ->
            opening = false
            // Close anything we won't keep: a device held without a port would block every later reconnect.
            val p = if (started) d?.openInputPort(0) else null // null if another app holds the port
            if (d == null || p == null) {
                d?.close()
                return@openDevice
            }
            device = d
            port = p
            connected = true
        }, handler)
    }

    private fun close() {
        port?.close()
        device?.close()
        port = null
        device = null
        connected = false
    }
}
