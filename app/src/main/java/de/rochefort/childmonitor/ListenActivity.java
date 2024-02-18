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
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

public class ListenActivity extends Activity {
    private static final String TAG = "ListenActivity";

    // Don't attempt to unbind from the service unless the client has received some
    // information about the service's state.
    private boolean shouldUnbind;

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            ListenService bs = ((ListenService.ListenBinder) service).getService();

            Toast.makeText(ListenActivity.this, R.string.connect,
                    Toast.LENGTH_SHORT).show();
            final TextView connectedText = findViewById(R.id.connectedTo);
            connectedText.setText(bs.getChildDeviceName());
            final VolumeView volumeView = findViewById(R.id.volume);
            volumeView.setVolumeHistory(bs.getVolumeHistory());
            bs.setUpdateCallback(volumeView::postInvalidate);
            bs.setErrorCallback(ListenActivity.this::postErrorMessage);
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            Toast.makeText(ListenActivity.this, R.string.disconnected,
                    Toast.LENGTH_SHORT).show();
        }
    };


    void ensureServiceRunningAndBind(Bundle bundle) {
        final Context context = this;
        final Intent intent = new Intent(context, ListenService.class);
        if (bundle != null) {
            intent.putExtra("name", bundle.getString("name"));
            intent.putExtra("address", bundle.getString("address"));
            intent.putExtra("port", bundle.getInt("port"));
            ContextCompat.startForegroundService(context, intent);
        }
        // Attempts to establish a connection with the service.  We use an
        // explicit class name because we want a specific service
        // implementation that we know will be running in our own process
        // (and thus won't be supporting component replacement by other
        // applications).
        if (bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            shouldUnbind = true;
            Log.i(TAG, "Bound listen service");
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
        final Intent intent = new Intent(context, ListenService.class);
        context.stopService(intent);
    }

    public void postErrorMessage() {
        TextView status = findViewById(R.id.textStatus);
        status.post(() -> status.setText(R.string.disconnected));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Bundle bundle = getIntent().getExtras();
        ensureServiceRunningAndBind(bundle);

        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_listen);

        final TextView statusText = findViewById(R.id.textStatus);
        statusText.setText(R.string.listening);
    }

    @Override
    public void onDestroy() {
        doUnbindAndStopService();
        super.onDestroy();
    }
}
