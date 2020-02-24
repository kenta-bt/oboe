/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.oboe.sample.drumthumper

import android.content.res.AssetManager
import android.media.midi.MidiDevice
import android.util.Log
import java.io.IOException

class DrumPlayer {
    companion object {
        // Sample attributes
        val NUM_CHANNELS: Int = 1
        val SAMPLE_RATE: Int = 44100

        // Sample Buffer IDs
        val NUM_SAMPLES: Int = 3

        val BASSDRUM: Int = 0
        val HIHATCLOSED: Int = 1
        val SNAREDRUM: Int = 2

        // Logging Tag
        val TAG: String = "DrumPlayer"
    }

    fun setupAudioStream() {
        setupAudioStreamNative(NUM_SAMPLES, NUM_CHANNELS, SAMPLE_RATE)
    }

    fun teardownAudioStream() {
        teardownAudioStreamNative()
    }

    // asset-based samples
    fun loadWavAssets(assetMgr: AssetManager) {
        loadWavAsset(assetMgr, "KickDrum.wav", BASSDRUM)
        loadWavAsset(assetMgr, "HiHat_Closed.wav", HIHATCLOSED)
        loadWavAsset(assetMgr, "SnareDrum.wav", SNAREDRUM)
    }

    fun loadWavAsset(assetMgr: AssetManager, assetName: String, index: Int) {
        try {
            val assetFD = assetMgr.openFd(assetName)
            val dataStream = assetFD.createInputStream();
            var dataLen = assetFD.getLength().toInt()
            var dataBytes: ByteArray = ByteArray(dataLen)
            dataStream.read(dataBytes, 0, dataLen)
            loadWavAssetNative(dataBytes, index)
            assetFD.close()
        } catch (ex: IOException) {
            Log.i(TAG, "IOException" + ex)
        }
    }
    fun startReadingMidi(receiveDevice: MidiDevice, portNumber: Int){
        startReadingMidiNative(receiveDevice, portNumber)
    }

    fun stopReadingMidi() {
        stopReadingMidiNative()
    }

    external fun setupAudioStreamNative(numSampleBuffers: Int, numChannels: Int, sampleRate: Int)
    external fun teardownAudioStreamNative()

    external fun loadWavAssetNative(wavBytes: ByteArray, index: Int)

    external fun trigger(drumIndex: Int)

    external fun getOutputReset() : Boolean
    external fun clearOutputReset()

    external fun restartStream()

    external fun startReadingMidiNative(receiveDevice: MidiDevice?, portNumber: Int)

    external fun stopReadingMidiNative()
}
