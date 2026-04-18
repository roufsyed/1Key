package com.onekey.core.domain.model

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val exception: Throwable, val message: String? = null) : AppResult<Nothing>()

    val isSuccess get() = this is Success
    val isError get() = this is Error

    fun getOrNull(): T? = if (this is Success) data else null
    fun errorOrNull(): Throwable? = if (this is Error) exception else null

    inline fun onSuccess(action: (T) -> Unit): AppResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (Throwable, String?) -> Unit): AppResult<T> {
        if (this is Error) action(exception, message)
        return this
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Error -> this
}

inline fun <T> runCatchingResult(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: Exception) {
    AppResult.Error(e, e.message)
}
