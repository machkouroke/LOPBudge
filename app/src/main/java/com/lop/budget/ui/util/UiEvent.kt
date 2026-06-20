package com.lop.budget.ui.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One-shot UI events (snackbar messages, navigation triggers) that must not
 * be replayed on configuration change. ViewModels expose a [UiEvent.Emitter]
 * and screens collect its [events] flow inside a LaunchedEffect.
 */
sealed interface UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent
    data class NavigateBack(val message: String? = null) : UiEvent

    /**
     * Encapsulates a [Channel]-backed event bus. ViewModels hold an instance
     * and call [send]; the UI collects [events].
     */
    class Emitter {
        private val channel = Channel<UiEvent>(Channel.BUFFERED)
        val events: Flow<UiEvent> = channel.receiveAsFlow()

        suspend fun send(event: UiEvent) {
            channel.send(event)
        }
    }
}
