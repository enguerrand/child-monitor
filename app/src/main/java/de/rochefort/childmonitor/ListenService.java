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
package de.rochefort.childmonitor;

import static de.rochefort.childmonitor.AudioCodecDefines.CODEC;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public class ListenService extends Service {
    private static final String TAG = "ListenService";
    public static final String CHANNEL_ID = TAG;
    public static final int ID = 902938409;

    private final int frequency = AudioCodecDefines.FREQUENCY;
    private final int channelConfiguration = AudioCodecDefines.CHANNEL_CONFIGURATION_OUT;
    private final int audioEncoding = AudioCodecDefines.ENCODING;
    private final int bufferSize = AudioTrack.getMinBufferSize(frequency, channelConfiguration, audioEncoding);
    private final int byteBufferSize = bufferSize*2;

    private final IBinder binder = new ListenBinder();
    private NotificationManager notificationManager;
    private Thread listenThread;

    private final VolumeHistory volumeHistory = new VolumeHistory(16_384);
    private String childDeviceName;

    public ListenService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
        // Display a notification about us starting.  We put an icon in the status bar.
        createNotificationChannel();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            String name = extras.getString("name");
            childDeviceName = name;
            Notification n = buildNotification(name);
            final int foregroundServiceType =  (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0; // Keep the linter happy
            ServiceCompat.startForeground(this, ID, n, foregroundServiceType);
            String address = extras.getString("address");
            int port = extras.getInt("port");
            doListen(address, port);
        }

        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        Thread lt = listenThread;
        if (lt != null) {
            lt.interrupt();
            listenThread = null;
        }

        // Cancel the persistent notification.
        int NOTIFICATION = R.string.listening;
        notificationManager.cancel(NOTIFICATION);

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        // Tell the user we stopped.
        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public VolumeHistory getVolumeHistory() {
        return volumeHistory;
    }

    private Notification buildNotification(String name) {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = getText(R.string.listening);

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, ListenActivity.class), PendingIntent.FLAG_IMMUTABLE);

        // Set the info for the views that show in the notification panel.
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID);
        b.setSmallIcon(R.drawable.listening_notification)  // the status icon
                .setOngoing(true)
                .setTicker(text)  // the status text
                .setContentTitle(text)  // the label of the entry
                .setContentText(name)  // the contents of the entry
                .setContentIntent(contentIntent);
        return b.build();
    }


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            notificationManager.createNotificationChannel(serviceChannel);
        }
    }

    public void setErrorCallback(Runnable errorCallback) {
        this.mErrorCallback = errorCallback;
    }

    public void setUpdateCallback(Runnable updateCallback) {
        this.mUpdateCallback = updateCallback;
    }

    public class ListenBinder extends Binder {
        ListenService getService() {
            return ListenService.this;
        }
    }

    private Runnable mErrorCallback;
    private Runnable mUpdateCallback;

    private void doListen(String address, int port) {
        Thread lt = new Thread(() -> {
            try {
                final Socket socket = new Socket(address, port);
                streamAudio(socket);
            } catch (IOException e) {
                Log.e(TAG, "Failed to stream audio", e);
            }

            if (!Thread.currentThread().isInterrupted()) {
                // If this thread has not been interrupted, likely something
                // bad happened with the connection to the child device. Play
                // an alert to notify the user that the connection has been
                // interrupted.
                playAlert();

                final Runnable errorCallback = mErrorCallback;
                if (errorCallback != null) {
                    errorCallback.run();
                }
            }
        });
        listenThread = lt;
        lt.start();
    }


    private void streamAudio(final Socket socket) throws IllegalArgumentException, IllegalStateException, IOException {
        Log.i(TAG, "Setting up stream");

        final AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                frequency,
                channelConfiguration,
                audioEncoding,
                bufferSize,
                AudioTrack.MODE_STREAM);

        final InputStream is = socket.getInputStream();
        int read = 0;

        audioTrack.play();

        try {
            final byte [] readBuffer = new byte[byteBufferSize];
            final short [] decodedBuffer = new short[byteBufferSize*2];

            while (socket.isConnected() && read != -1 && !Thread.currentThread().isInterrupted()) {
                read = is.read(readBuffer);
                int decoded = CODEC.decode(decodedBuffer, readBuffer, read, 0);

                if (decoded > 0) {
                    audioTrack.write(decodedBuffer, 0, decoded);
                    short[] decodedBytes = new short[decoded];
                    System.arraycopy(decodedBuffer, 0, decodedBytes, 0, decoded);
                    volumeHistory.onAudioData(decodedBytes);
                    final Runnable updateCallback = mUpdateCallback;
                    if (updateCallback != null) {
                        updateCallback.run();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Connection failed", e);
        } finally {
            audioTrack.stop();
            socket.close();
        }
    }


    private void playAlert() {
        final MediaPlayer mp = MediaPlayer.create(this, R.raw.upward_beep_chromatic_fifths);
        if(mp != null) {
            Log.i(TAG, "Playing alert");
            mp.setOnCompletionListener(MediaPlayer::release);
            mp.start();
        } else {
            Log.e(TAG, "Failed to play alert");
        }
    }

    public String getChildDeviceName() {
        return childDeviceName;
    }
}
