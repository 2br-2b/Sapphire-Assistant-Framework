package com.example.componentframework

import android.app.Activity

abstract class SAFActivity: Activity(){
    val STDIO = "ASISTANT_FRAMEWORK_STDIO"
    // This *could* call chatbot
    val STDERR = "ASSISTANT_FRAMEWORK_STDERR"
    // This is important for pipeline
    val FROM = "ASSISTANT_FRAMEWORK_SENDING_MODULE"
    // This is important for core
    val PID = "ASSISTANT_FRAMEWORK_PROCESS_ID"
}