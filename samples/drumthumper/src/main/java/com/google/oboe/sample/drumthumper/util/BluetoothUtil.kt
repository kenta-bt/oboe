package com.google.oboe.sample.drumthumper.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice

object BluetoothUtil {

    fun isBtEnabled() = BluetoothAdapter.getDefaultAdapter().isEnabled

    fun getRemoteDevice(address: String): BluetoothDevice =
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
}