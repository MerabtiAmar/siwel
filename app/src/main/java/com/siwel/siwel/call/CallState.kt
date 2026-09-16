package com.siwel.siwel.call

sealed class CallState {
    object Idle   : CallState()
    object Active : CallState()
    object Ended  : CallState()
}