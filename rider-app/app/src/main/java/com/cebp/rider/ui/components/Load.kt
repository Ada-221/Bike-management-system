package com.cebp.rider.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cebp.rider.R
import com.cebp.rider.data.RiderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How often screens showing live fleet data reload it while visible. */
const val AUTO_REFRESH_MILLIS = 30_000L

/** Shortest time the pull-to-refresh circle stays up, so it always animates away. */
private const val MIN_REFRESH_SPINNER_MILLIS = 500L

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
    data class Failed(val error: RiderException) : LoadState<Nothing>
}

/** The state of a screen's data, and how to load it again. */
class Load<T>(
    val state: LoadState<T>,
    /** True while a refresh the rider asked for is running. */
    val isRefreshing: Boolean,
    /** Why the last refresh failed. The data loaded before is still shown. */
    val refreshError: RiderException?,
    /** Loads again: a retry after a failure, or a refresh that keeps the current data on screen. */
    val reload: () -> Unit,
)

/**
 * Runs [load] when the screen is shown, when [key] changes and on [Load.reload]. With
 * [autoRefreshMillis], it also reloads at that interval while the app is in the foreground.
 */
@Composable
fun <T> rememberLoad(
    key: Any?,
    autoRefreshMillis: Long? = null,
    load: suspend () -> T,
): Load<T> {
    val latestLoad by rememberUpdatedState(load)
    var state by remember(key) { mutableStateOf<LoadState<T>>(LoadState.Loading) }
    var refreshing by remember(key) { mutableStateOf(false) }
    var refreshError by remember(key) { mutableStateOf<RiderException?>(null) }
    var requests by remember(key) { mutableIntStateOf(0) }
    var riderAsked by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key, requests) {
        val hadData = state is LoadState.Ready
        val showSpinner = hadData && riderAsked
        if (showSpinner) refreshing = true else if (!hadData) state = LoadState.Loading
        val startedAt = System.currentTimeMillis()
        val result = try {
            Result.success(latestLoad())
        } catch (e: RiderException) {
            Result.failure(e)
        }
        if (showSpinner) {
            // The pull-to-refresh circle only animates away after it has seen the refresh run.
            // A refresh that ends at once (such as a refused connection) would leave it stuck.
            val remaining = MIN_REFRESH_SPINNER_MILLIS - (System.currentTimeMillis() - startedAt)
            if (remaining > 0) delay(remaining)
        }
        result.fold(
            onSuccess = {
                state = LoadState.Ready(it)
                refreshError = null
            },
            onFailure = {
                // Keep showing what was loaded before; only a first load turns into an error screen.
                if (hadData) refreshError = it as RiderException else state = LoadState.Failed(it as RiderException)
            },
        )
        refreshing = false
        riderAsked = false
    }

    if (autoRefreshMillis != null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(key, lifecycle) {
            // Main dispatcher explicitly, so the wait is real time and stops in the background.
            withContext(Dispatchers.Main) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        delay(autoRefreshMillis)
                        requests++
                    }
                }
            }
        }
    }

    return Load(state, refreshing, refreshError) {
        riderAsked = true
        // Report the refresh at once, while the rider's pull gesture is still being handled.
        if (state is LoadState.Ready) refreshing = true
        requests++
    }
}

/**
 * Shows a spinner while loading and an error with a retry button on failure. Loaded [content]
 * can be pulled down to refresh, and a failed refresh is reported above it. [content] should
 * scroll vertically, so that the pull gesture works.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
            onRetry = load.reload,
        )
        is LoadState.Ready -> PullToRefreshBox(
            isRefreshing = load.isRefreshing,
            onRefresh = load.reload,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                load.refreshError?.let { RefreshFailedNote(it) }
                content(state.value)
            }
        }
    }
}

@Composable
private fun RefreshFailedNote(error: RiderException) {
    Text(
        text = stringResource(R.string.refresh_failed, stringResource(error.messageRes)),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
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
