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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;

public class VolumeView extends View {
    private final Paint paint;
    private VolumeHistory volumeHistory;

    public VolumeView(Context context) {
        super(context);
        this.paint = initPaint();
    }

    public VolumeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.paint = initPaint();
    }

    public VolumeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.paint = initPaint();
    }

    private Paint initPaint() {
        Paint paint = new Paint();
        paint.setColor(Color.rgb(255, 127, 0));
        return paint;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final VolumeHistory volumeHistory = this.volumeHistory;
        if (volumeHistory == null) {
            return;
        }

        final int height = getHeight();
        final int width = getWidth();

        final int size = volumeHistory.size();    // Size is at most width
        final double volumeNorm = volumeHistory.getVolumeNorm();
        final double relativeBrightness;
        if (size > 0) {
            final double normalizedVolume = volumeHistory.get(size - 1);
            relativeBrightness = Math.max(0.3, normalizedVolume);
        } else {
            relativeBrightness = 0.3;
        }
        int blue;
        int rest;
        if (relativeBrightness > 0.5) {
            blue = 255;
            rest = (int) (2 * 255 * (relativeBrightness - 0.5));
        } else {
            blue = (int) (255 * (relativeBrightness - 0.2) / 0.3);
            rest = 0;
        }
        final int rgb = Color.rgb(rest, rest, blue);
        canvas.drawColor(rgb);
        if (size == 0) {
            return;
        }
        final double margins = height * 0.1;
        final double graphHeight = height - 2.0 * margins;
        int leftMost = Math.max(0, volumeHistory.size() - width);
        final double graphScale = graphHeight * volumeNorm;

        int xPrev = 0;
        int yPrev = ((int) (margins + graphHeight - volumeHistory.get(leftMost) * graphScale));
        int length = Math.min(size, width);
        for (int xNext = 1; xNext < length-1; ++xNext) {
            int yNext = (int) (margins + graphHeight - volumeHistory.get(leftMost + xNext) * graphScale);
            canvas.drawLine(xPrev, yPrev, xNext, yNext, paint);
            xPrev = xNext;
            yPrev = yNext;
        }
    }

    public void setVolumeHistory(VolumeHistory volumeHistory) {
        this.volumeHistory = volumeHistory;
    }
}
