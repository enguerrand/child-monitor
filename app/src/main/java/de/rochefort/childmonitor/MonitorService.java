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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;

public class MonitorService extends Service {
    final static String TAG = "MonitorService";
    final static String CHANNEL_ID = TAG;
    public static final int ID = 1338;
    private final IBinder binder = new MonitorBinder();
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private ServerSocket currentSocket;
    private Object connectionToken;
    private int currentPort;
    private NotificationManager notificationManager;
    private Thread monitorThread;
    private MonitorActivity monitorActivity;

    public void setMonitorActivity(MonitorActivity monitorActivity) {
        this.monitorActivity = monitorActivity;
    }

    private void serviceConnection(Socket socket) {
        final MonitorActivity ma = monitorActivity;
        if (ma != null) {
            ma.runOnUiThread(() -> {
                final TextView statusText = monitorActivity.findViewById(R.id.textStatus);
                statusText.setText(R.string.streaming);
            });
        }

        final int frequency = AudioCodecDefines.FREQUENCY;
        final int channelConfiguration = AudioCodecDefines.CHANNEL_CONFIGURATION_IN;
        final int audioEncoding = AudioCodecDefines.ENCODING;

        final int bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding);
        final AudioRecord audioRecord;
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    frequency,
                    channelConfiguration,
                    audioEncoding,
                    bufferSize
            );
        } catch (SecurityException e) {
            // This should never happen, we asked for permission before
            throw new RuntimeException(e);
        }

        final int pcmBufferSize = bufferSize * 2;
        final short[] pcmBuffer = new short[pcmBufferSize];
        final byte[] ulawBuffer = new byte[pcmBufferSize];

        try {
            audioRecord.startRecording();
            final OutputStream out = socket.getOutputStream();

            socket.setSendBufferSize(pcmBufferSize);
            Log.d(TAG, "Socket send buffer size: " + socket.getSendBufferSize());

            while (socket.isConnected() && currentSocket != null && !Thread.currentThread().isInterrupted()) {
                final int read = audioRecord.read(pcmBuffer, 0, bufferSize);
                int encoded = CODEC.encode(pcmBuffer, read, ulawBuffer, 0);
                out.write(ulawBuffer, 0, encoded);
            }
        } catch (Exception e) {
            Log.e(TAG, "Connection failed", e);
        } finally {
            audioRecord.stop();
        }
    }


    @Override
    public void onCreate() {
        Log.i(TAG, "ChildMonitor start");
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        nsdManager = (NsdManager) this.getSystemService(Context.NSD_SERVICE);
        currentPort = 10000;
        currentSocket = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
        // Display a notification about us starting.  We put an icon in the status bar.
        createNotificationChannel();
        Notification n = buildNotification();
        final int foregroundServiceType =  (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0; // Keep the linter happy
        ServiceCompat.startForeground(this, ID, n, foregroundServiceType);
        ensureMonitorThread();

        return START_REDELIVER_INTENT;
    }

    private void ensureMonitorThread() {
        Thread mt = monitorThread;
        if (mt != null && mt.isAlive()) {
            return;
        }

        final Object currentToken = new Object();
        connectionToken = currentToken;

        mt = new Thread(() -> {
            while (Objects.equals(connectionToken, currentToken)) {
                try (ServerSocket serverSocket = new ServerSocket(currentPort)) {
                    currentSocket = serverSocket;
                    // Store the chosen port.
                    final int localPort = serverSocket.getLocalPort();

                    // Register the service so that parent devices can
                    // locate the child device
                    registerService(localPort);

                    // Wait for a parent to find us and connect
                    try (Socket socket = serverSocket.accept()) {

                        Log.i(TAG, "Connection from parent device received");

                        // We now have a client connection.
                        // Unregister so no other clients will
                        // attempt to connect
                        unregisterService();
                        serviceConnection(socket);
                    }
                } catch (Exception e) {
                    if (Objects.equals(connectionToken, currentToken)) {
                        // Just in case
                        currentPort++;
                        Log.e(TAG, "Failed to open server socket. Port increased to " + currentPort, e);
                    }
                }
            }
        });
        monitorThread = mt;
        mt.start();
    }

    private void registerService(final int port) {
        final NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("ChildMonitor on " + Build.MODEL);
        serviceInfo.setServiceType("_childmonitor._tcp.");
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
                // Save the service name.  Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                final String serviceName = nsdServiceInfo.getServiceName();

                Log.i(TAG, "Service name: " + serviceName);

                final MonitorActivity ma = monitorActivity;
                if (ma != null) {
                    ma.runOnUiThread(() -> {
                        final TextView statusText = ma.findViewById(R.id.textStatus);
                        statusText.setText(R.string.waitingForParent);

                        final TextView serviceText = ma.findViewById(R.id.textService);
                        serviceText.setText(serviceName);

                        final TextView portText = ma.findViewById(R.id.port);
                        portText.setText(Integer.toString(port));
                    });
                }

            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Registration failed!  Put debugging code here to determine why.
                Log.e(TAG, "Registration failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {
                // Service has been unregistered.  This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.

                Log.i(TAG, "Unregistering service");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Unregistration failed.  Put debugging code here to determine why.

                Log.e(TAG, "Unregistration failed: " + errorCode);
            }
        };

        nsdManager.registerService(
                serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private void unregisterService() {
        NsdManager.RegistrationListener currentListener = registrationListener;
        if (currentListener != null) {
            Log.i(TAG, "Unregistering monitoring service");

            nsdManager.unregisterService(currentListener);
            registrationListener = null;
        }
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

    private Notification buildNotification() {
        CharSequence text = "Child Device";
        // Set the info for the views that show in the notification panel.
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID);
        b.setSmallIcon(R.drawable.listening_notification)  // the status icon
                .setOngoing(true)
                .setTicker(text)  // the status text
                .setContentTitle(text);  // the label of the entry
        return b.build();
    }


    @Override
    public void onDestroy() {
        Thread mt = monitorThread;
        if (mt != null) {
            mt.interrupt();
            monitorThread = null;
        }

        unregisterService();

        connectionToken = null;
        if (currentSocket != null) {
            try {
                currentSocket.close();
                currentSocket = null;
            } catch (IOException e) {
                Log.e(TAG, "Failed to close active socket on port " + currentPort);
            }
        }

        // Cancel the persistent notification.
        notificationManager.cancel(R.string.listening);

        stopForeground(true);
        // Tell the user we stopped.
        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public class MonitorBinder extends Binder {
        MonitorService getService() {
            return MonitorService.this;
        }
    }

}
