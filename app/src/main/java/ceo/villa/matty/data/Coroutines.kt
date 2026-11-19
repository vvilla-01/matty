package ceo.villa.matty.data

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for coroutines. A cancellation (the screen that started the work closed, a newer
 * search replaced this one, the user signed out) is passed on: never mistaken for a failure,
 * never shown to anyone as an error. Only real failures land in the [Result].
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
