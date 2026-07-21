package com.netproxy.gateway.utils

/**
 * 安全清理 CharArray，将其所有元素置为零字符。
 * 对 null 输入不做任何操作。
 */
fun CharArray?.securelyClear() {
    this?.fill('\u0000')
}

/**
 * 安全清理 ByteArray，将其所有元素置为零字节。
 * 对 null 输入不做任何操作。
 */
fun ByteArray?.securelyClear() {
    this?.fill(0)
}
