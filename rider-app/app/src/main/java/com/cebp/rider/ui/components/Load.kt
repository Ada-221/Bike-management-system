package com.cebp.rider.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
}

/** Runs [load] each time the screen is shown, or when [key] changes. */
@Composable
fun <T> rememberLoad(key: Any?, load: suspend () -> T): LoadState<T> {
    val state by produceState<LoadState<T>>(LoadState.Loading, key) {
        value = LoadState.Ready(load())
    }
    return state
}

@Composable
fun LoadingIndicator(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
