package com.pause.dance

sealed class PracticeSessionState {
    data object Preparing : PracticeSessionState()
    data object Ready : PracticeSessionState()
    data object Playing : PracticeSessionState()
    data object Paused : PracticeSessionState()
    data object Completed : PracticeSessionState()
    data class Error(val message: String) : PracticeSessionState()
}
