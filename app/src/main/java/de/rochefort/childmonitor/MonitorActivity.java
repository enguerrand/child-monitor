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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.IBinder;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class MonitorActivity extends Activity {
    final static String TAG = "ChildMonitor";
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service. Because we have bound to an explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            MonitorService bs = ((MonitorService.MonitorBinder) service).getService();
            bs.setMonitorActivity(MonitorActivity.this);
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            Toast.makeText(MonitorActivity.this, R.string.disconnected,
                    Toast.LENGTH_SHORT).show();
        }
    };
    private boolean shouldUnbind;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "ChildMonitor start");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitor);
        final TextView addressText = findViewById(R.id.address);

        List<String> listenAddresses = getListenAddresses();
        if (!listenAddresses.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < listenAddresses.size(); i++) {
                String listenAddress = listenAddresses.get(i);
                sb.append(listenAddress);
                if (i != listenAddresses.size() - 1) {
                    sb.append("\n\n");
                }
            }
            addressText.setText(sb.toString());
        } else {
            addressText.setText(R.string.notConnected);
        }

        ensureServiceRunningAndBind();
    }

    private List<String> getListenAddresses() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        List<String> listenAddresses = new ArrayList<>();
        if (cm != null) {
            for (Network network : cm.getAllNetworks()) {
                NetworkInfo networkInfo = cm.getNetworkInfo(network);
                boolean connected = networkInfo.isConnected();
                if (connected) {
                    List<LinkAddress> linkAddresses = cm.getLinkProperties(network).getLinkAddresses();
                    for (LinkAddress linkAddress : linkAddresses) {
                        InetAddress address = linkAddress.getAddress();
                        if (!address.isLinkLocalAddress() && !address.isLoopbackAddress()) {
                            listenAddresses.add(address.getHostAddress() + " (" + networkInfo.getTypeName() + ")");
                        }
                    }
                }
            }
        }
        return listenAddresses;
    }

    @Override
    public void onDestroy() {
        doUnbindAndStopService();
        super.onDestroy();
    }

    void ensureServiceRunningAndBind() {
        final Context context = this;
        final Intent intent = new Intent(context, MonitorService.class);
        ContextCompat.startForegroundService(context, intent);
        // Attempts to establish a connection with the service.  We use an
        // explicit class name because we want a specific service
        // implementation that we know will be running in our own process
        // (and thus won't be supporting component replacement by other
        // applications).
        if (bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            shouldUnbind = true;
            Log.i(TAG, "Bound monitor service");
        } else {
            Log.e(TAG, "Error: The requested service doesn't " +
                    "exist, or this client isn't allowed access to it.");
        }
    }

    void doUnbindAndStopService() {
        if (shouldUnbind) {
            // Release information about the service's state.
            unbindService(connection);
            shouldUnbind = false;
        }
        final Context context = this;
        final Intent intent = new Intent(context, MonitorService.class);
        context.stopService(intent);
    }
}
