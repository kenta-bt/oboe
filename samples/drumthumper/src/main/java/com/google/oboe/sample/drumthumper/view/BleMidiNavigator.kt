package com.google.oboe.sample.drumthumper.view

import android.media.midi.MidiDevice

interface BleMidiNavigator {
    fun onMidiDeviceOpened(midiDevice: MidiDevice)
    fun onMidiDeviceClosed()
}