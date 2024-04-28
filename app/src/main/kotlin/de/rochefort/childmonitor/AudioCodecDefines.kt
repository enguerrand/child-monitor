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

import android.media.AudioFormat
import de.rochefort.childmonitor.audio.G711UCodec

object AudioCodecDefines {
    val CODEC = G711UCodec()
    const val FREQUENCY = 8000
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val CHANNEL_CONFIGURATION_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_CONFIGURATION_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val TRANSMIT_FREQUENCY = 50 // Hz
    const val SAMPLES_PER_READ = FREQUENCY / TRANSMIT_FREQUENCY
    const val BUFFER_SIZE = 2 * SAMPLES_PER_READ // Enough space for two packets (on each side)
}
