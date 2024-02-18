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

import android.os.Handler;
import android.os.Looper;
import androidx.collection.CircularArray;

public class VolumeHistory {
    private double maxVolume = 0.25;

    private double volumeNorm = 1.0 / maxVolume;
    private final CircularArray<Double> historyData;
    private final int maxHistory;

    private final Handler uiHandler;

    VolumeHistory(int maxHistory) {
        uiHandler = new Handler(Looper.getMainLooper());
        this.maxHistory = maxHistory;
        historyData = new CircularArray<>(maxHistory);
    }


    public double getVolumeNorm() {
        return volumeNorm;
    }

    public double get(int i) {
        return historyData.get(i);
    }

    public int size() {
        return historyData.size();
    }

    private void addLast(double volume) {
        // schedule editing of member vars on the ui event loop to avoid concurrency problems
        uiHandler.post(() -> {
            if (volume > maxVolume) {
                maxVolume = volume;
                volumeNorm = 1.0 / volume;
            }
            historyData.addLast(volume);
            historyData.removeFromStart(historyData.size() - maxHistory);
        });
    }

    public void onAudioData(short[] data) {
        if (data.length < 1) {
            return;
        }

        final double scale = 1.0 / 128.0;
        double sum = 0;
        for (final short datum : data) {
            final double rel = datum * scale;
            sum += rel * rel;
        }
        final double volume = sum / data.length;
        addLast(volume);
    }
}
