package com.cebp.rider.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cebp.rider.R
import com.cebp.rider.data.RiderException

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
    data class Failed(val error: RiderException) : LoadState<Nothing>
}

/** The state of a screen's data, plus a way to load it again after a failure. */
class Load<T>(val state: LoadState<T>, val retry: () -> Unit)

/** Runs [load] each time the screen is shown, when [key] changes, and on [Load.retry]. */
@Composable
fun <T> rememberLoad(key: Any?, load: suspend () -> T): Load<T> {
    var attempt by remember(key) { mutableIntStateOf(0) }
    val state by produceState<LoadState<T>>(LoadState.Loading, key, attempt) {
        value = LoadState.Loading
        value = try {
            LoadState.Ready(load())
        } catch (e: RiderException) {
            LoadState.Failed(e)
        }
    }
    return Load(state) { attempt++ }
}

/** Shows a spinner while loading, an error with a retry button on failure, else [content]. */
@Composable
fun <T> LoadContent(
    load: Load<T>,
    padding: PaddingValues,
    content: @Composable (T) -> Unit,
) {
    when (val state = load.state) {
        LoadState.Loading -> LoadingIndicator(padding)
        is LoadState.Failed -> ErrorWithRetry(
            message = stringResource(state.error.messageRes),
            padding = padding,
            onRetry = load.retry,
        )
        is LoadState.Ready -> content(state.value)
    }
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

@Composable
private fun ErrorWithRetry(message: String, padding: PaddingValues, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(message, textAlign = TextAlign.Center)
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
