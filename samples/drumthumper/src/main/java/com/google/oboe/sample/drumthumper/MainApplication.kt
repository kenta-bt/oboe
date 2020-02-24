package com.google.oboe.sample.drumthumper

import android.app.Application
import android.content.Context
import android.media.midi.MidiManager
import com.google.oboe.sample.drumthumper.util.BluetoothUtil
import com.google.oboe.sample.drumthumper.viewmodel.BleMidiViewModel
import com.polidea.rxandroidble2.RxBleClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

class MainApplication : Application() {

    private val module: Module = module {
        single { RxBleClient.create(androidContext()) }
        single { androidContext().getSystemService(Context.MIDI_SERVICE) as MidiManager }
        factory { BluetoothUtil.getRemoteDevice(Constants.M5STACK_BT_ADDR) }
        factory {
            BleMidiViewModel(
                    rxBleClient = get(),
                    midiManager = get(),
                    bleDevice = get()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@MainApplication)
            modules(module)
        }
    }
}