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
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.net.Socket

class ListenService : Service() {
    private val frequency: Int = AudioCodecDefines.FREQUENCY
    private val channelConfiguration: Int = AudioCodecDefines.CHANNEL_CONFIGURATION_OUT
    private val audioEncoding: Int = AudioCodecDefines.ENCODING
    private val bufferSize = AudioTrack.getMinBufferSize(frequency, channelConfiguration, audioEncoding)
    private val byteBufferSize = bufferSize * 2
    private val binder: IBinder = ListenBinder()
    private lateinit var notificationManager: NotificationManager
    private var listenThread: Thread? = null
    val volumeHistory = VolumeHistory(16384)
    var childDeviceName: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        this.notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "Received start id $startId: $intent")
        // Display a notification about us starting.  We put an icon in the status bar.
        createNotificationChannel()
        intent.extras?.let {
            val name = it.getString("name")
            childDeviceName = name
            val n = buildNotification(name)
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0 // Keep the linter happy
            ServiceCompat.startForeground(this, ID, n, foregroundServiceType)
            val address = it.getString("address")
            val port = it.getInt("port")
            doListen(address, port)
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        this.listenThread?.interrupt()
        this.listenThread = null

        // Cancel the persistent notification.
        notificationManager.cancel(R.string.listening)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Tell the user we stopped.
        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun buildNotification(name: String?): Notification {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        val text = getText(R.string.listening)

        // The PendingIntent to launch our activity if the user selects this notification
        val contentIntent = PendingIntent.getActivity(this, 0,
                Intent(this, ListenActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        // Set the info for the views that show in the notification panel.
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
        b.setSmallIcon(R.drawable.listening_notification) // the status icon
                .setOngoing(true)
                .setTicker(text) // the status text
                .setContentTitle(text) // the label of the entry
                .setContentText(name) // the contents of the entry
                .setContentIntent(contentIntent)
        return b.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    inner class ListenBinder : Binder() {
        val service: ListenService
            get() = this@ListenService
    }

    var onError: (() -> Unit)? = null
    var onUpdate: (() -> Unit)? = null
    private fun doListen(address: String?, port: Int) {
        val lt = Thread {
            try {
                val socket = Socket(address, port).apply {
                    soTimeout = 30_000
                    receiveBufferSize = AudioCodecDefines.BUFFER_SIZE
                }
                val success = streamAudio(socket)
                if (!success) {
                    playAlert()
                    onError?.invoke()
                }
            } catch (e : IOException) {
                Log.e(TAG, "Error opening socket to $address on port $port", e)
            }
        }
        this.listenThread = lt
        lt.start()
    }

    private fun streamAudio(socket: Socket): Boolean {
        Log.i(TAG, "Setting up stream")
        val audioTrack = AudioTrack(AudioManager.STREAM_MUSIC,
            frequency,
            channelConfiguration,
            audioEncoding,
            bufferSize,
            AudioTrack.MODE_STREAM)
        try {
            audioTrack.play()
        } catch (e : java.lang.IllegalStateException) {
            Log.e(TAG, "Failed to play streamed audio audio for other reason", e)
            return false
        }

        val inputStream = try {
            socket.getInputStream()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read audio audio for socket", e)
            return false
        }

        val readBuffer = ByteArray(byteBufferSize)
        val decodedBuffer = ShortArray(byteBufferSize * 2)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val len = inputStream.read(readBuffer)
                if (len < 0) {
                    // If the current thread was not interrupted this means the remote stopped streaming
                    return Thread.currentThread().isInterrupted
                }
                val decoded: Int = AudioCodecDefines.CODEC.decode(decodedBuffer, readBuffer, len, 0)
                if (decoded > 0) {
                    audioTrack.write(decodedBuffer, 0, decoded)
                    val decodedBytes = ShortArray(decoded)
                    System.arraycopy(decodedBuffer, 0, decodedBytes, 0, decoded)
                    volumeHistory.onAudioData(decodedBytes)
                    onUpdate?.invoke()
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            return false
        } finally {
            audioTrack.stop()
            socket.close()
        }
    }

    private fun playAlert() {
        val mp = MediaPlayer.create(this, R.raw.upward_beep_chromatic_fifths)
        if (mp != null) {
            Log.i(TAG, "Playing alert")
            mp.setOnCompletionListener { obj: MediaPlayer -> obj.release() }
            mp.start()
        } else {
            Log.e(TAG, "Failed to play alert")
        }
    }

    companion object {
        private const val TAG = "ListenService"
        const val CHANNEL_ID = TAG
        const val ID = 902938409
    }
}
