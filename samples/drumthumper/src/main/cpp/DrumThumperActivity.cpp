//
// Created by Kenta Harada on 2020-03-11.
//

#include <jni.h>

extern "C" {

// Data callback stuff
JavaVM* theJvm;
jobject dataCallbackObj;
jmethodID midDataCallback;

/**
 * Initializes JNI interface stuff, specifically the info needed to call back into the Java
 * layer when MIDI data is received.
 */

JNICALL void Java_com_google_oboe_sample_drumthumper_view_DrumThumperActivity_initNative(JNIEnv * env, jobject instance) {
    env->GetJavaVM(&theJvm);

    // Setup the receive data callback (into Java)
    jclass clsMainActivity = env->FindClass("com/google/oboe/sample/drumthumper/view/DrumThumperActivity");
    dataCallbackObj = env->NewGlobalRef(instance);
    midDataCallback = env->GetMethodID(clsMainActivity, "onNativeMessageReceive", "([B)V");
}

} // extern "C"