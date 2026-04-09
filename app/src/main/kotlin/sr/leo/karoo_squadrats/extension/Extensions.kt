package sr.leo.karoo_squadrats.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.KarooEventParams
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val consumerId = addConsumer<T>(
        onError = { close(Exception(it)) },
        onComplete = { close() },
    ) { event ->
        trySend(event)
    }
    awaitClose { removeConsumer(consumerId) }
}

inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(
    params: KarooEventParams,
): Flow<T> = callbackFlow {
    val consumerId = addConsumer<T>(
        params = params,
        onError = { close(Exception(it)) },
        onComplete = { close() },
    ) { event ->
        trySend(event)
    }
    awaitClose { removeConsumer(consumerId) }
}
