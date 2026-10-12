package com.example.minimidi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException

/**
 * Carries MIDI to and from the computer over USB. With the phone's USB mode set to "MIDI", the host appears here
 * as a TYPE_USB device whose *input* port is what we write to and whose *output* port is what we read from
 * (ports are named from the device's point of view).
 * This is the one seam a future Bluetooth output plugs in beside.
 */
class MidiOut(context: Context) {
    var connected by mutableStateOf(false)
        private set

    /** True only while the device's output port (computer -> phone) is open. */
    var inputConnected by mutableStateOf(false)
        private set

    /** Note-ons from the computer, delivered on the main thread: (channel 1..16, note). */
    var onNote: ((channel: Int, note: Int) -> Unit)? = null

    private val manager = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private var outPort: MidiOutputPort? = null
    private var receiver: MidiReceiver? = null
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
                try { d?.close() } catch (_: IOException) {}
                return@openDevice
            }
            device = d
            port = p
            connected = true
            openInput(d)
        }, handler)
    }

    // Optional: if another app holds the output port, sending still works and inputConnected stays false.
    private fun openInput(d: MidiDevice) {
        val op = d.openOutputPort(0) ?: return
        // The parser lives on the MIDI thread only; just the note posts to main.
        val parser = MidiParser { ch, note, _ ->
            handler.post { if (outPort === op) onNote?.invoke(ch, note) } // drops notes posted before close()
        }
        val r = object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = parser.feed(msg, offset, count)
        }
        op.connect(r)
        outPort = op
        receiver = r
        inputConnected = true
    }

    private fun close() {
        val p = port; val d = device; val op = outPort; val r = receiver
        port = null; device = null; connected = false
        outPort = null; receiver = null; inputConnected = false
        try { if (op != null && r != null) op.disconnect(r) } catch (_: IOException) {}
        try { op?.close() } catch (_: IOException) {}
        try { p?.close() } catch (_: IOException) {}
        try { d?.close() } catch (_: IOException) {}
    }
}
