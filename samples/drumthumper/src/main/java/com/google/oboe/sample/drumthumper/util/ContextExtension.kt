package com.google.oboe.sample.drumthumper.util

import android.content.Context
import android.content.pm.PackageManager


fun Context.isSupportMidi() = this.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)

