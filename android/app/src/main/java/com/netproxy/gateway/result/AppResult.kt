package com.netproxy.gateway.result

/**
 * 统一的结果类型密封类，用于替代各模块不一致的错误处理方式
 */
sealed class AppResult<out T> {

    /**
     * 成功状态，包含数据
     */
    data class Success<out T>(val data: T) : AppResult<T>()

    /**
     * 错误状态，包含异常信息
     */
    data class Error(val exception: Throwable) : AppResult<Nothing>()

    /**
     * 获取成功时的数据，失败时返回null
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    /**
     * 获取错误时的异常，成功时返回null
     */
    fun exceptionOrNull(): Throwable? = when (this) {
        is Success -> null
        is Error -> exception
    }

    /**
     * 检查是否为成功状态
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * 检查是否为错误状态
     */
    fun isError(): Boolean = this is Error

    companion object {
        /**
         * 创建成功结果
         */
        fun <T> success(data: T): AppResult<T> = Success(data)

        /**
         * 创建错误结果
         */
        fun <T> error(exception: Throwable): AppResult<T> = Error(exception)

        /**
         * 从Kotlin标准库Result转换为AppResult
         */
        fun <T> fromResult(result: Result<T>): AppResult<T> = result.fold(
            onSuccess = { success(it) },
            onFailure = { error(it) }
        )

        /**
         * 执行可能抛出异常的操作并包装为AppResult
         */
        inline fun <T> runCatching(block: () -> T): AppResult<T> = try {
            success(block())
        } catch (e: Throwable) {
            error(e)
        }
    }
}

/**
 * 当结果为成功时执行操作
 */
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) {
        action(data)
    }
    return this
}

/**
 * 当结果为错误时执行操作
 */
inline fun <T> AppResult<T>.onError(action: (Throwable) -> Unit): AppResult<T> {
    if (this is AppResult.Error) {
        action(exception)
    }
    return this
}

/**
 * 映射成功值到另一种类型
 */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.success(transform(data))
    is AppResult.Error -> this
}

/**
 * 映射错误到新的AppResult
 */
inline fun <T> AppResult<T>.mapError(transform: (Throwable) -> Throwable): AppResult<T> = when (this) {
    is AppResult.Success -> this
    is AppResult.Error -> AppResult.error(transform(exception))
}

/**
 * 展平映射，用于链式操作
 */
inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(data)
    is AppResult.Error -> this
}

/**
 * 获取成功值或默认值
 */
fun <T> AppResult<T>.getOrDefault(defaultValue: T): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> defaultValue
}

/**
 * 获取成功值或从异常计算
 */
inline fun <T> AppResult<T>.getOrElse(onFailure: (Throwable) -> T): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> onFailure(exception)
}

/**
 * 获取成功值或抛出异常
 */
fun <T> AppResult<T>.getOrThrow(): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> throw exception
}

/**
 * 转换为Kotlin标准库Result
 */
fun <T> AppResult<T>.toResult(): Result<T> = when (this) {
    is AppResult.Success -> Result.success(data)
    is AppResult.Error -> Result.failure(exception)
}

/**
 * 恢复错误为成功值
 */
inline fun <T> AppResult<T>.recover(transform: (Throwable) -> T): AppResult<T> = when (this) {
    is AppResult.Success -> this
    is AppResult.Error -> AppResult.success(transform(exception))
}

/**
 * 展平恢复
 */
inline fun <T> AppResult<T>.recoverCatching(transform: (Throwable) -> T): AppResult<T> = when (this) {
    is AppResult.Success -> this
    is AppResult.Error -> AppResult.runCatching { transform(exception) }
}

/**
 * 过滤成功值，不满足条件时转为错误
 */
inline fun <T> AppResult<T>.filter(predicate: (T) -> Boolean, errorProvider: () -> Throwable): AppResult<T> = when (this) {
    is AppResult.Success -> if (predicate(data)) this else AppResult.error(errorProvider())
    is AppResult.Error -> this
}

/**
 * 组合两个AppResult
 */
fun <T1, T2, R> AppResult<T1>.zip(other: AppResult<T2>, transform: (T1, T2) -> R): AppResult<R> = when {
    this is AppResult.Error -> this
    other is AppResult.Error -> other
    this is AppResult.Success && other is AppResult.Success -> AppResult.success(transform(data, other.data))
    else -> throw IllegalStateException("Unexpected state in zip")
}
