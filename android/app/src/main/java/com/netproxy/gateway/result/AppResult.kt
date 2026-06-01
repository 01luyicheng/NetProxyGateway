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
     * 在错误情况下返回封装的异常，否则返回 null。
     *
     * @return `Throwable` 表示错误时的异常，`null` 表示当前实例为成功状态。
     */
    fun exceptionOrNull(): Throwable? = when (this) {
        is Success -> null
        is Error -> exception
    }

    /**
 * 判断当前 AppResult 是否表示成功状态。
 *
 * @return `true` 表示当前实例为 `Success`，`false` 表示为 `Error`。
 */
    fun isSuccess(): Boolean = this is Success

    /**
 * 判断当前实例是否为错误状态。
 *
 * @return `true` 如果实例为 `Error`，`false` 否则。
 */
    fun isError(): Boolean = this is Error

    companion object {
        /**
 * 创建携带指定数据的成功结果。
 *
 * @param data 要包装的成功值
 * @return 包含指定数据的 AppResult.Success
 */
        fun <T> success(data: T): AppResult<T> = Success(data)

        /**
 * 创建携带指定异常的错误结果。
 *
 * @param exception 要封装为错误结果的异常。
 * @return 封装了该异常的 `AppResult.Error` 实例。
 */
        fun <T> error(exception: Throwable): AppResult<T> = Error(exception)

        /**
         * 将 Kotlin 标准库的 `Result` 转换为对应的 `AppResult` 实例。
         *
         * 成功的 `Result` 会被包装为 `Success(data)`；失败的 `Result` 会被包装为 `Error(exception)`。
         *
         * @param result 要转换的 `Result` 实例。
         * @return 对应的 `AppResult`：成功返回 `Success`，失败返回 `Error`。
         */
        fun <T> fromResult(result: Result<T>): AppResult<T> = result.fold(
            onSuccess = { success(it) },
            onFailure = { error(it) }
        )

        /**
         * 执行给定的块并将结果或异常包装为 AppResult。
         *
         * @param block 要执行的操作块，其返回值将作为成功结果；若执行过程中抛出 `Throwable`，则该异常会被捕获并作为错误结果返回。
         * @return `Success` 包含块的返回值，若执行抛出异常则返回 `Error` 并包含该 `Throwable`。
         */
        inline fun <T> runCatching(block: () -> T): AppResult<T> = try {
            success(block())
        } catch (e: Throwable) {
            error(e)
        }
    }
}

/**
 * 在为 Success 时对其携带的数据执行指定动作。
 *
 * @param action 对成功数据执行的函数，接收当前实例的 `data` 作为参数。
 * @return 原始的 `AppResult<T>` 实例（未修改），便于链式调用。
 */
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) {
        action(data)
    }
    return this
}

/**
 * 当当前结果是错误态时执行指定的处理动作，并返回原始结果以支持链式调用。
 *
 * @param action 在错误态时接收异常并执行的回调。
 * @return 原始的 `AppResult<T>` 实例（无论成功或失败），以便链式调用。
 */
inline fun <T> AppResult<T>.onError(action: (Throwable) -> Unit): AppResult<T> {
    if (this is AppResult.Error) {
        action(exception)
    }
    return this
}

/**
 * 将成功的值映射为另一类型的 AppResult。
 *
 * @param transform 在当前为 Success 时用于将值转换为目标类型的函数。
 * @return 当接收者为 `Success` 时返回包含 `transform(data)` 的 `Success`，当接收者为 `Error` 时原样返回该 `Error`（类型为 `AppResult<R>`）。
 */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.success(transform(data))
    is AppResult.Error -> this
}

/**
 * 将当前错误异常转换为新的异常并返回对应的 AppResult。
 *
 * @param transform 在当前为 `Error` 时用于将原始 `Throwable` 转换为新的 `Throwable` 的函数。
 * @return 当前对象本身（若为 `Success`），或包裹转换后异常的 `Error`（若为 `Error`）。
 */
inline fun <T> AppResult<T>.mapError(transform: (Throwable) -> Throwable): AppResult<T> = when (this) {
    is AppResult.Success -> this
    is AppResult.Error -> AppResult.error(transform(exception))
}

/**
 * 将成功值通过提供的转换函数映射为另一个 `AppResult` 并展开用于链式组合。
 *
 * @param transform 将当前成功值转换为另一个 `AppResult` 的函数。
 * @return 如果当前为 `Success`，返回 `transform(data)` 的结果；如果当前为 `Error`，返回原始 `Error`。
 */
inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(data)
    is AppResult.Error -> this
}

/**
 * 当结果为成功时返回其值，否则返回指定的默认值。
 *
 * @param defaultValue 在当前为错误时返回的默认值。
 * @return 成功的值；若为错误则返回 `defaultValue`。
 */
fun <T> AppResult<T>.getOrDefault(defaultValue: T): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> defaultValue
}

/**
 * 在错误情况下使用提供的函数基于异常计算并返回替代值。
 *
 * @param onFailure 当当前为 `Error` 时被调用，接收该异常并返回用于替代的值。
 * @return `data` 当为 `Success`；否则为 `onFailure(exception)` 的结果。
 */
inline fun <T> AppResult<T>.getOrElse(onFailure: (Throwable) -> T): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> onFailure(exception)
}

/**
 * 获取成功态中的值；如果当前为错误态则抛出封装的异常。
 *
 * @return 成功时封装的值 `T`。
 * @throws Throwable 当结果为 `AppResult.Error` 时抛出其内部的 `exception`。
 */
fun <T> AppResult<T>.getOrThrow(): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> throw exception
}

/**
 * 将当前 AppResult 转换为 Kotlin 标准库的 Result。
 *
 * @return `Result.success(data)` 当当前为 `Success`；`Result.failure(exception)` 当当前为 `Error`。
 */
fun <T> AppResult<T>.toResult(): Result<T> = when (this) {
    is AppResult.Success -> Result.success(data)
    is AppResult.Error -> Result.failure(exception)
}

/**
 * 将错误状态转换为成功值。
 *
 * 当调用者为 `Error` 时，使用 `transform` 根据其异常计算一个替代值并以 `Success` 返回；当为 `Success` 时原样返回。
 *
 * @param transform 根据发生的异常生成替代成功值。
 * @return 若原始为 `Success` 则返回原对象；若为 `Error` 则返回包含 `transform(exception)` 的 `Success`。
 */
inline fun <T> AppResult<T>.recover(transform: (Throwable) -> T): AppResult<T> = when (this) {
    is AppResult.Success -> this
    is AppResult.Error -> AppResult.success(transform(exception))
}

/**
 * 在错误情况下尝试使用提供的恢复函数将错误转换为成功值。
 *
 * 如果当前为 `Success` 则原样返回；如果为 `Error` 则调用 `transform(exception)` 并将结果包装为 `Success`，
 * 若恢复函数抛出异常则返回相应的 `Error`。
 *
 * @param transform 在发生错误时用于计算备用成功值的函数，接收原始异常作为参数。
 * @return `Success` 包含恢复得到的值，`Error` 包含原始异常或恢复过程中抛出的异常。
 */
inline fun <T> AppResult<T>.recoverCatching(transform: (Throwable) -> T): AppResult<T> = when (this) {
    is AppResult.Success -> this
    is AppResult.Error -> AppResult.runCatching { transform(exception) }
}

/**
 * 在 `Success` 情况下根据谓词判断是否保留成功值，不满足时将结果替换为由 `errorProvider` 提供的 `Error`。
 *
 * @param predicate 对 `Success` 中的值执行的判断函数；当返回 `true` 时保留原成功值。
 * @param errorProvider 当 `predicate` 为 `false` 时用于构造返回的异常。
 * @return `Success` 保留原始值；若谓词不满足则返回由 `errorProvider` 提供异常包装的 `Error`；若原对象为 `Error` 则原样返回。
 */
inline fun <T> AppResult<T>.filter(predicate: (T) -> Boolean, errorProvider: () -> Throwable): AppResult<T> = when (this) {
    is AppResult.Success -> if (predicate(data)) this else AppResult.error(errorProvider())
    is AppResult.Error -> this
}

/**
 * 在两者都为成功时将两个结果的数据合并为一个成功结果；若任一为错误则返回该错误。
 *
 * @param other 要与当前结果合并的另一个 `AppResult`。
 * @param transform 将两个成功值合并为目标类型的函数，只有在两者均为 `Success` 时调用。
 * @return 当两者均为 `Success` 时，返回包含 `transform(data, other.data)` 的 `Success`；若任一为 `Error`，返回该 `Error`。
 * @throws IllegalStateException 如果遇到无法识别的状态（理论上不应发生）。
 */
fun <T1, T2, R> AppResult<T1>.zip(other: AppResult<T2>, transform: (T1, T2) -> R): AppResult<R> = when {
    this is AppResult.Error -> this
    other is AppResult.Error -> other
    this is AppResult.Success && other is AppResult.Success -> AppResult.success(transform(data, other.data))
    else -> throw IllegalStateException("Unexpected state in zip")
}
