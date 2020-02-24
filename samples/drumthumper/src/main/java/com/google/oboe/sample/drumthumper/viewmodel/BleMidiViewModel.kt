package com.google.oboe.sample.drumthumper.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ViewModel
import com.google.oboe.sample.drumthumper.Constants
import com.google.oboe.sample.drumthumper.Constants.TAG
import com.google.oboe.sample.drumthumper.view.BleMidiNavigator
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import java.io.IOException

class BleMidiViewModel(
        private val rxBleClient: RxBleClient,
        private val midiManager: MidiManager,
        private val bleDevice: BluetoothDevice
) : ViewModel(), LifecycleObserver {

    private lateinit var navigator: BleMidiNavigator

    private val rxBleDevice
        get() = rxBleClient.getBleDevice(Constants.M5STACK_BT_ADDR)

    private var connectionDisposable: Disposable? = null

    private var midiDevice: MidiDevice? = null

    private val midiDeviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo?) {
            Log.d(TAG, "onDeviceAdded")
            device?.let {
                Log.d(TAG, "outputPortCount : ${it.outputPortCount}")
                Log.d(TAG, "inputPortCount : ${it.inputPortCount}")
            }
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo?) {
            Log.d(TAG, "onDeviceRemoved")
            navigator.onMidiDeviceClosed()
        }
    }

    @SuppressLint("CheckResult")
    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        midiManager.registerDeviceCallback(midiDeviceCallback, Handler(Looper.getMainLooper()))
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        midiManager.unregisterDeviceCallback(midiDeviceCallback)
        closeMidiDevice()
    }

    fun init(navigator: BleMidiNavigator) {
        this.navigator = navigator
    }

    fun connectBleDevice() {
        rxBleDevice
                .establishConnection(false)
                .flatMapSingle {
                    it.requestMtu(Constants.REQUEST_MTU_SIZE)
                            .ignoreElement()
                            .andThen(Single.just(it))
                }
                .flatMapSingle { it.discoverServices() }
                .flatMapCompletable { openMidiDevice() }
                .toObservable<RxBleConnection>()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(AndroidSchedulers.mainThread())
                .subscribeBy(
                        onComplete = { Log.e(TAG, "onComplete") },
                        onError = {
                            closeMidiDevice()
                            Log.e(TAG, it.message)
                            navigator.onMidiDeviceClosed()
                        }
                ).let { connectionDisposable = it }
    }

    private fun openMidiDevice() = Completable.create { emitter ->
        midiManager.openBluetoothDevice(bleDevice, {
            if (it.info.outputPortCount > 0) {
                midiDevice = it
                navigator.onMidiDeviceOpened(it)
            } else {
                emitter.onError(RuntimeException("Output port is 0"))
            }
        }, null)
    }

    fun closeMidiDevice() {
        midiDevice?.let {
            try {
                it.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

        }
        midiDevice = null
        connectionDisposable?.dispose()
    }
}