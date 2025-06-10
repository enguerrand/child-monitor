/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.RegistrationListener
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.lang.Thread.UncaughtExceptionHandler
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

class Encoder {
    // This defines how much we want to read from the audio-buffer
    private val samplesPerRead = AudioCodecDefines.SAMPLES_PER_READ
    val audioRecord = try {
        with(AudioCodecDefines) {
            // This defines how big the audio-buffer needs to be at least
            // samplesPerRead need to be smaller than audioBufferSize
            val audioBufferSize = AudioRecord.getMinBufferSize(FREQUENCY, CHANNEL_CONFIGURATION_IN, ENCODING)
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                FREQUENCY,
                CHANNEL_CONFIGURATION_IN,
                ENCODING,
                audioBufferSize
            ).also {
                it.startRecording()
            }
        }
    } catch (e: SecurityException) {
        // This should never happen, we asked for permission before
        throw RuntimeException(e)
    }

    private val pcmBuffer = ShortArray(samplesPerRead)
    private val ulawBuffer = ByteArray(samplesPerRead)
    private var encoded = ByteBuffer.wrap(ulawBuffer, 0, 0 )

    fun read() : ByteBuffer {
        val read = audioRecord.read(pcmBuffer, 0, samplesPerRead)
        val encodedLength = AudioCodecDefines.CODEC.encode(pcmBuffer, read, ulawBuffer, 0)
        encoded.rewind()
        encoded.limit(encodedLength)
        return this.encoded
    }
}

class MonitorService : Service() {
    private var encoder: Encoder? = null
    private val binder: IBinder = MonitorBinder()
    private lateinit var nsdManager: NsdManager
    private var registrationListener: RegistrationListener? = null
    private var selector: Selector = Selector.open()
    private lateinit var notificationManager: NotificationManager
    private var monitorThread: Thread? = null
    var monitorActivity: MonitorActivity? = null

    private fun ensureRecording() : Encoder {
        var e = this.encoder
        if (e == null) {
            e = Encoder()
            this.encoder = e
            this.monitorActivity?.let {
                it.runOnUiThread {
                    val statusText = it.findViewById<TextView>(R.id.textStatus)
                    statusText.setText(R.string.streaming)
                }
            }
        }
        return e
    }

    private fun stopRecording() {
        this.encoder?.let {
            this.encoder = null
            it.audioRecord.stop()
            monitorActivity?.let { ma ->
                ma.runOnUiThread {
                    val statusText = ma.findViewById<TextView>(R.id.textStatus)
                    statusText.setText(R.string.waitingForParent)
                }
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG, "ChildMonitor start")
        super.onCreate()
        this.notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        this.nsdManager = this.getSystemService(NSD_SERVICE) as NsdManager
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "Received start id $startId: $intent")
        // Display a notification about us starting.  We put an icon in the status bar.
        createNotificationChannel()
        val n = buildNotification()
        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0 // Keep the linter happy
        ServiceCompat.startForeground(this, ID, n, foregroundServiceType)
        ensureMonitorThread()
        return START_REDELIVER_INTENT
    }

    private fun ensureMonitorThread() {
        var mt = this.monitorThread
        if (mt != null && mt.isAlive) {
            return
        }
        mt = Thread {
            ServerSocketChannel.open().use { serverChannel ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        serverChannel.setOption(
                            StandardSocketOptions.SO_SNDBUF,
                            AudioCodecDefines.BUFFER_SIZE
                        )
                    } catch (e : UnsupportedOperationException) {
                        Log.d(TAG, "Cannot set send buffer size")
                    }
                }
                val port = bindFirstFreePort(serverChannel)
                if (port <= 0) {
                    Log.e(TAG, "No free port found")
                    return@Thread
                }
                serverChannel.configureBlocking(false)
                serverChannel.register(this.selector, SelectionKey.OP_ACCEPT)

                // Register the service so that parent devices can
                // locate the child device
                this.registerService(port)

                try {
                    val thread = Thread.currentThread()
                    while (!thread.isInterrupted) {
                        waitForClient()
                        streamToClients()
                    }
                }
                catch (_: ClosedByInterruptException) {
                    // Unlikely, but might be triggered while we write
                }
                catch (_: InterruptedException) {
                    // Should never happen, but who knows
                }
                finally {
                    stopRecording()
                    unregisterService()
                    if (selector.isOpen) {
                        selector.keys().forEach {
                            it.channel().close()
                        }
                        selector.close()
                    }
                }
            }
        }
        mt.uncaughtExceptionHandler =
            UncaughtExceptionHandler { t, e -> Log.e(TAG, "Uncaught", e) }
        mt.start()
        this.monitorThread = mt
    }

    private fun streamToClients() {
        val encoder = ensureRecording()
        while (selector.keys().size > 1) {
            val buffer = encoder.read() // Read it, even if we can't send it, so we don't buffer it
            val timeout = 1L // As close to non-blocking we can get with the old API
            this.selector.select(timeout)
            selector.selectedKeys().run {
                forEach {
                    if (it.isAcceptable) {
                        handleAccept(it.channel() as ServerSocketChannel)
                    } else if (it.isWritable) {
                        handleWrite(it.channel() as SocketChannel, buffer)
                    } else if (!it.isValid) {
                        it.cancel()
                    } else {
                        // Should never be reached
                        Log.d(TAG, "Unexpected key ${it}")
                    }
                }
                clear()
            }
        }
    }

    private fun handleWrite(clientChannel : SocketChannel, buffer: ByteBuffer) {
        try {
            buffer.rewind()
            clientChannel.write(buffer)
        } catch (e: IOException) {
            Log.w(TAG, "Connection closed or broken")
            clientChannel.close()
        }
    }

    private fun handleAccept(serverChannel: ServerSocketChannel) {
        val clientChannel = serverChannel.accept() ?: return
        clientChannel.run {
            socket().tcpNoDelay = true
            configureBlocking(false)
            register(selector, SelectionKey.OP_WRITE)
        }
    }

    private fun waitForClient() {
        stopRecording()
        while (selector.keys().size <= 1) {
            selector.select()
            selector.selectedKeys().run {
                forEach {
                    if (it.isAcceptable) {
                        handleAccept(it.channel() as ServerSocketChannel)
                    }
                }
                clear()
            }
        }
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo()
        serviceInfo.serviceName = "ChildMonitor on " + Build.MODEL
        serviceInfo.serviceType = "_childmonitor._tcp."
        serviceInfo.port = port
        this.registrationListener = object : RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                // Save the service name.  Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                nsdServiceInfo.serviceName.let { serviceName ->
                    Log.i(TAG, "Service name: $serviceName")
                    monitorActivity?.let { ma ->
                        ma.runOnUiThread {
                            val statusText = ma.findViewById<TextView>(R.id.textStatus)
                            statusText.setText(R.string.waitingForParent)
                            val serviceText = ma.findViewById<TextView>(R.id.textService)
                            serviceText.text = serviceName
                            val portText = ma.findViewById<TextView>(R.id.port)
                            portText.text = port.toString()
                        }
                    }
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Registration failed!  Put debugging code here to determine why.
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                // Service has been unregistered.  This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
                Log.i(TAG, "Unregistering service")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Unregistration failed.  Put debugging code here to determine why.
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }
        nsdManager.registerService(
                serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        this.registrationListener?.let {
            this.registrationListener = null
            Log.i(TAG, "Unregistering monitoring service")
            this.nsdManager.unregisterService(it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            )
            this.notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val text: CharSequence = "Child Device"
        // Set the info for the views that show in the notification panel.
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
        b.setSmallIcon(R.drawable.listening_notification) // the status icon
                .setOngoing(true)
                .setTicker(text) // the status text
                .setContentTitle(text) // the label of the entry
        return b.build()
    }

    override fun onDestroy() {
        // Interrupt the thread: It will cancel i/o operations, such as write or select
        // The thread will take care of cleaning up
        this.monitorThread?.let {
            it.interrupt()
            it.join(500) // Give it a bit time to terminate
        }
        // Cancel the persistent notification.
        this.notificationManager.cancel(R.string.listening)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Tell the user we stopped.
        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class MonitorBinder : Binder() {
        val service: MonitorService
            get() = this@MonitorService
    }

    companion object {
        const val TAG = "MonitorService"
        const val CHANNEL_ID = TAG
        const val ID = 1338
        const val STARTING_PORT = 10_000
    }

    private fun bindFirstFreePort(serverChannel: ServerSocketChannel): Int {
        for (port in STARTING_PORT..65_000) {
            try {
                serverChannel.socket().bind(InetSocketAddress(port))
                return port
            } catch (e: IOException) {
                // Pass
            }
        }
        return 0
    }
}
