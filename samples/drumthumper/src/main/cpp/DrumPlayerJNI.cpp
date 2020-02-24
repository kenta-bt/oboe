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

#include <jni.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <android/log.h>

#include <io/stream/FileInputStream.h>
#include <io/wav/WavStreamReader.h>

#include <player/OneShotSampleBuffer.h>
#include <player/SimpleMultiPlayer.h>

static const char* TAG = "DrumPlayerJNI";

#include "AndroidDebug.h"
#include <amidi/AMidi.h>
#include <cinttypes>
#include "MidiSpec.h"
// JNI functions are "C" calling convention
#ifdef __cplusplus
extern "C" {
#endif

using namespace wavlib;

static SimpleMultiPlayer sDTPlayer;

static AMidiDevice *sNativeReceiveDevice = nullptr;
// The thread only reads this value, so no special protection is required.
static AMidiOutputPort *sMidiOutputPort = nullptr;

static pthread_t sReadThread;
static std::atomic<bool> sReading(false);

/**
 * Native (JNI) implementation of DrumPlayer.setupAudioStreamNative()
 */
JNIEXPORT void JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_setupAudioStreamNative(
        JNIEnv* env, jobject, jint numSampleBuffers, jint numChannels, jint sampleRate) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", "init()");

    // we know in this case that the sample buffers are all 1-channel, 41K
    sDTPlayer.setupAudioStream(numSampleBuffers, numChannels, sampleRate);
}

/**
 * Native (JNI) implementation of DrumPlayer.teardownAudioStreamNative()
 */
JNIEXPORT void JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_teardownAudioStreamNative(
        JNIEnv* env, jobject, jint numSampleBuffers, jint numChannels, jint sampleRate) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", "deinit()");

    // we know in this case that the sample buffers are all 1-channel, 44.1K
    sDTPlayer.teardownAudioStream();
}

/**
 * Native (JNI) implementation of DrumPlayer.loadWavAssetNative()
 */
JNIEXPORT void JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_loadWavAssetNative(JNIEnv* env, jobject, jbyteArray bytearray, jint index) {
    int len = env->GetArrayLength (bytearray);

    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion (bytearray, 0, len, reinterpret_cast<jbyte*>(buf));
    sDTPlayer.loadSampleDataFromAsset(buf, len, index);
    delete[] buf;
}

/**
 * Native (JNI) implementation of DrumPlayer.trigger()
 */
JNIEXPORT void JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_trigger(JNIEnv* env, jobject, jint index) {
    sDTPlayer.triggerDown(index);
}

/**
 * Native (JNI) implementation of DrumPlayer.getOutputReset()
 */
JNIEXPORT jboolean JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_getOutputReset() {
    return sDTPlayer.getOutputReset();
}

/**
 * Native (JNI) implementation of DrumPlayer.clearOutputReset()
 */
JNIEXPORT void JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_clearOutputReset() {
    sDTPlayer.clearOutputReset();
}

/**
 * Native (JNI) implementation of DrumPlayer.restartStream()
 */
JNIEXPORT void JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_restartStream() {
    sDTPlayer.resetAll();
    sDTPlayer.openStream();
}


/**
 * Formats a midi message set and outputs to the log
 * @param   timestamp   The timestamp for when the message(s) was received
 * @param   dataBytes   The MIDI message bytes
 * @params  numDataBytew    The number of bytes in the MIDI message(s)
 */
static void logMidiBuffer(int64_t timestamp, uint8_t *dataBytes, size_t numDataBytes) {
#define DUMP_BUFFER_SIZE    1024
    char midiDumpBuffer[DUMP_BUFFER_SIZE];
    memset(midiDumpBuffer, 0, sizeof(midiDumpBuffer));
    int pos = snprintf(midiDumpBuffer, DUMP_BUFFER_SIZE,
                       "%" PRIx64 " ", timestamp);
    for (uint8_t *b = dataBytes, *e = b + numDataBytes; b < e; ++b) {
        pos += snprintf(midiDumpBuffer + pos, DUMP_BUFFER_SIZE - pos,
                        "%02x ", *b);
    }
    LOGD("%s", midiDumpBuffer);
}

static void playSound(const uint8_t incomingMessage[]) {
    if (incomingMessage[0] != kMIDINoteOn) {   // Note on
        return;
    }

    switch (incomingMessage[1]) {
        case 0x3C:
            sDTPlayer.triggerDown(0);
            break;
        case 0x3D:
            sDTPlayer.triggerDown(1);
            break;
        case 0x3E:
            sDTPlayer.triggerDown(2);
            break;
        default:
            break;
    }
}

/*
 * Receiving API
 */
/**
 * This routine polls the input port and dispatches received data to the application-provided
 * (Java) callback.
 */
static void *readThreadRoutine(void *context) {
    (void) context;  // unused

    sReading = true;
    // AMidiOutputPort* outputPort = sMidiOutputPort.load();
    AMidiOutputPort *outputPort = sMidiOutputPort;

    const size_t MAX_BYTES_TO_RECEIVE = 128;
    uint8_t incomingMessage[MAX_BYTES_TO_RECEIVE];

    while (sReading) {
        // AMidiOutputPort_receive is non-blocking, so let's not burn up the CPU unnecessarily
        usleep(2000);

        int32_t opcode;
        size_t numBytesReceived;
        int64_t timestamp;
        ssize_t numMessagesReceived =
                AMidiOutputPort_receive(outputPort,
                                        &opcode, incomingMessage, MAX_BYTES_TO_RECEIVE,
                                        &numBytesReceived, &timestamp);

        if (numMessagesReceived < 0) {
            LOGW("Failure receiving MIDI data %zd", numMessagesReceived);
            // Exit the thread
            sReading = false;
        }
        if (numMessagesReceived > 0 && numBytesReceived >= 3) {
            if (opcode == AMIDI_OPCODE_DATA &&
                (incomingMessage[0] & kMIDISysCmdChan) != kMIDISysCmdChan) {
                // (optionally) Dump to log
                logMidiBuffer(timestamp, incomingMessage, numBytesReceived);
                playSound(incomingMessage);
            } else if (opcode == AMIDI_OPCODE_FLUSH) {
                // ignore
            }
        }
    }   // end while(sReading)

    return NULL;
}

/**
 * Native implementation of TBMidiManager.startReadingMidi() method.
 * Opens the first "output" port from specified MIDI device for sReading.
 * @param   env  JNI Env pointer.
 * @param   (unnamed)   TBMidiManager (Java) object.
 * @param   midiDeviceObj   (Java) MidiDevice object.
 * @param   portNumber      The index of the "output" port to open.
 */
JNIEXPORT void JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_startReadingMidiNative(
        JNIEnv *env, jobject, jobject midiDeviceObj, jint portNumber) {
    LOGD("startReadingMidi");

    media_status_t status;
    status = AMidiDevice_fromJava(env, midiDeviceObj, &sNativeReceiveDevice);
    // int32_t deviceType = AMidiDevice_getType(sNativeReceiveDevice);
    // ssize_t numPorts = AMidiDevice_getNumOutputPorts(sNativeReceiveDevice);

    AMidiOutputPort *outputPort;
    status = AMidiOutputPort_open(sNativeReceiveDevice, portNumber, &outputPort);

    // sMidiOutputPort.store(outputPort);
    sMidiOutputPort = outputPort;

    // Start read thread
    // pthread_init(true);
    /*int pthread_result =*/ pthread_create(&sReadThread, NULL, readThreadRoutine, NULL);
}

/**
 * Native implementation of the (Java) TBMidiManager.stopReadingMidi() method.
 * @param   (unnamed)   JNI Env pointer.
 * @param   (unnamed)   TBMidiManager (Java) object.
 */
JNIEXPORT void JNICALL Java_com_google_oboe_sample_drumthumper_DrumPlayer_stopReadingMidiNative(
        JNIEnv *, jobject) {
    LOGD("stopReadingMidi");
    // need some synchronization here
    sReading = false;
    pthread_join(sReadThread, NULL);

    AMidiOutputPort_close(sMidiOutputPort);
    /*media_status_t status =*/ AMidiDevice_release(sNativeReceiveDevice);
    sNativeReceiveDevice = NULL;
    sMidiOutputPort = NULL;
}

#ifdef __cplusplus
}
#endif
