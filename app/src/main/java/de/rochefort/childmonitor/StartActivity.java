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

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.widget.Button;

public class StartActivity extends Activity {
    static final String TAG = "ChildMonitor";
    private final static int PERMISSIONS_REQUEST_RECORD_AUDIO = 298349824;
    private final static int PERMISSIONS_REQUEST_MULTICAST = 298349825;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "ChildMonitor launched");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);

        final Button monitorButton = findViewById(R.id.useChildDevice);
        monitorButton.setOnClickListener(v -> {
            Log.i(TAG, "Starting up monitor");

            if (isAudioRecordingPermissionGranted()) {
                startActivity(new Intent(getApplicationContext(), MonitorActivity.class));
            } else {
                requestAudioPermission();
            }
        });

        final Button connectButton = findViewById(R.id.useParentDevice);
        connectButton.setOnClickListener(v -> {
            Log.i(TAG, "Starting connection activity");
            if (isMulticastPermissionGranted()) {
                Intent i = new Intent(getApplicationContext(), DiscoverActivity.class);
                startActivity(i);
            } else {
                requestMulticastPermission();
            }
        });
    }

    private boolean isMulticastPermissionGranted() {
        return ContextCompat.checkSelfPermission(StartActivity.this, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAudioRecordingPermissionGranted() {
        return ContextCompat.checkSelfPermission(StartActivity.this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(StartActivity.this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                PERMISSIONS_REQUEST_RECORD_AUDIO);
    }

    private void requestMulticastPermission() {
        ActivityCompat.requestPermissions(StartActivity.this,
                new String[]{Manifest.permission.CHANGE_WIFI_MULTICAST_STATE},
                PERMISSIONS_REQUEST_MULTICAST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(getApplicationContext(), MonitorActivity.class));
        } else if (requestCode == PERMISSIONS_REQUEST_MULTICAST) {
            // its okay if the permission was denied... the user will have to type the address manually
            startActivity(new Intent(getApplicationContext(), DiscoverActivity.class));
        }
    }
}
