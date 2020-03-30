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
package com.google.oboe.sample.drumthumper.view

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import com.google.oboe.sample.drumthumper.Constants
import com.google.oboe.sample.drumthumper.DrumPlayer
import com.google.oboe.sample.drumthumper.R
import com.google.oboe.sample.drumthumper.util.BluetoothUtil
import com.google.oboe.sample.drumthumper.util.isSupportMidi
import com.google.oboe.sample.drumthumper.viewmodel.BleMidiViewModel
import es.dmoral.toasty.Toasty
import org.koin.android.ext.android.inject
import java.util.*
import kotlin.concurrent.schedule
import kotlinx.android.synthetic.main.drumthumper_activity.*

class DrumThumperActivity : AppCompatActivity(), TriggerPad.DrumPadTriggerListener, LifecycleOwner {
    private val TAG = "DrumThumperActivity"

    private var mAudioMgr: AudioManager? = null

    private var mDrumPlayer = DrumPlayer()

    private var mDeviceListener: DeviceListener = DeviceListener()

    private val mUseDeviceChangeFallback = true
    private var mDevicesInitialized = false

    private val viewModel by inject<BleMidiViewModel>()

    init {
        // Load the library containing the a native code including the JNI  functions
        System.loadLibrary("drumthumper")
    }

    /*:
     * This  implements a "fallback" mechanism for devices that do not correctly call
     * AudioStreamCallback::onErrorAfterClose(). It works by noticing that the connected
     * Devices have changed, and so the player needs to be restarted.
     *
     * Caveat: This callback also gets called when it is installed in the AudioManager and in
     * that case, there is as yet no device change so no need to restart the audio stream.
     */
    inner class DeviceListener : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            // Note: This will get called when the callback is installed.
            if (mDevicesInitialized) {
                // This is not the initial callback, so devices have changed
                Toast.makeText(applicationContext, "Audio device added.", Toast.LENGTH_LONG).show()
                resetOutput()
            }
            mDevicesInitialized = true
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Toast.makeText(applicationContext, "Audio device removed.", Toast.LENGTH_LONG).show()
            resetOutput()
        }

        fun resetOutput() {
            if (mDrumPlayer.getOutputReset()) {
                // the (native) stream has been reset by the onErrorAfterClose() callback
                mDrumPlayer.clearOutputReset()
            } else {
                // give the (native) stream a chance to close it.
                val timer = Timer("stream restart timer", false)
                // schedule a single event
                timer.schedule(3000) {
                    if (!mDrumPlayer.getOutputReset()) {
                        // still didn't get reset, so lets do it ourselves
                        mDrumPlayer.restartStream();
                    }
                }
            }

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNative()

        viewModel.init(navigator)
        lifecycle.addObserver(viewModel)

        mAudioMgr = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (!isSupportMidi()) {
            Toasty.error(this, "MIDI is not supported.", Toast.LENGTH_SHORT, true).show()
            finish()
        }

        if (!BluetoothUtil.isBtEnabled()) {
            Toasty.error(this, "Bluetooth is disabled.", Toast.LENGTH_SHORT, true).show()
            finish()
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(Constants.TAG, "PERMISSION_GRANTED")
                Toasty.success(this, "Permission granted.", Toast.LENGTH_SHORT, true).show()
            } else {
                Log.e(Constants.TAG, "PERMISSION_DENIED")
                Toasty.error(this, "Permission denied.", Toast.LENGTH_SHORT, true).show()
                finish()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.options, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.open_midi_device -> {
                Toasty.success(applicationContext, "Connecting MIDI device...", Toast.LENGTH_SHORT, true).show()
                viewModel.connectBleDevice()
                true
            }
            R.id.close_midi_device -> {
                Toasty.success(applicationContext, "Close MIDI device.", Toast.LENGTH_SHORT, true).show()
                viewModel.closeMidiDevice()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()

        if (mUseDeviceChangeFallback) {
            mAudioMgr!!.registerAudioDeviceCallback(mDeviceListener, null)
        }

        // UI
        setContentView(R.layout.drumthumper_activity)

        // hookup the UI
        run {
            kickPad.addListener(this)
        }

        run {
            snarePad.addListener(this)
        }

        run {
            hihatClosedPad.addListener(this)
        }

        mDrumPlayer.setupAudioStream()
        mDrumPlayer.loadWavAssets(getAssets())
    }

    override fun onPause() {
        super.onPause()

        mAudioMgr!!.unregisterAudioDeviceCallback(mDeviceListener)
    }

    override fun onStop() {
        mDrumPlayer.teardownAudioStream()
        mDrumPlayer.stopReadingMidi()
        super.onStop()
    }

    //
    // DrumPad.DrumPadTriggerListener
    //
    override fun triggerDown(pad: TriggerPad) {
        // trigger the sound based on the pad
        when (pad.id) {
            R.id.kickPad -> mDrumPlayer.trigger(DrumPlayer.BASSDRUM)
            R.id.snarePad -> mDrumPlayer.trigger(DrumPlayer.SNAREDRUM)
            R.id.hihatClosedPad -> mDrumPlayer.trigger(DrumPlayer.HIHATCLOSED)
        }
    }

    override fun triggerUp(pad: TriggerPad) {
        // NOP
    }

    private val navigator = object : BleMidiNavigator {
        override fun onMidiDeviceOpened(device: MidiDevice) {
            mDrumPlayer.startReadingMidi(device, Constants.M5STACK_PORT_NO)
            Toasty.success(applicationContext, "MIDI Device opened.", Toast.LENGTH_SHORT, true).show()
        }

        override fun onMidiDeviceClosed() {
            mDrumPlayer.stopReadingMidi()
            Toasty.success(applicationContext, "MIDI Device closed.", Toast.LENGTH_SHORT, true).show()
        }

    }

    //
    // Native Interface methods
    //

    private external fun initNative()

    /**
     * Called from the native code when MIDI messages are received.
     * @param message
     */
    private fun onNativeMessageReceive(message: ByteArray) {
        if (message.size < 2) return
        if (message[0] != 0x90.toByte()) return

        // Messages are received on some other thread, so switch to the UI thread
        // before attempting to access the UI
        runOnUiThread {
            when (message[1]) {
                0x3C.toByte() -> kickPad.receiveMidi()
                0x3F.toByte() -> hihatClosedPad.receiveMidi()
                0x43.toByte() -> snarePad.receiveMidi()
            }
        }
    }
}
