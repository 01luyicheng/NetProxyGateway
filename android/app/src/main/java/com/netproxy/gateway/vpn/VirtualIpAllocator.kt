package com.netproxy.gateway.vpn

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 虚拟IP分配器接口
 *
 * 用于为真实目标IP分配虚拟源IP，实现VPN场景下的地址转换。
 * 支持IP池自动回收（当IP耗尽时重置）。
 */
interface VirtualIpAllocator {
    /**
     * 根据给定的真实目标IP返回已有的虚拟IP，或为其分配并返回一个新的虚拟IP。
     *
     * 如果 [realDstIp] 已存在映射，会直接返回已有的虚拟IP（不会触发 [onNewAllocation]）。
     * 若不存在映射，则从可用地址池分配一个未被占用的虚拟IP，写入双向映射后返回。
     * 当分配器发现计数器越界并实际清空/重置地址池时，会在同步块外触发一次 [onPoolReset]。
     * 在实际产生新的映射时，会在同步块外触发 [onNewAllocation]；获取已有映射时不触发该回调。
     *
     * @param realDstIp 目标真实IP，不能为空或空白
     * @param virtualIpPool 从真实目标IP到虚拟IP的映射表（写入新的映射）
     * @param reverseIpMap 从虚拟IP到真实目标IP的反向映射表（用于检测占用并写入反向映射）
     * @param nextVirtualIp 用于生成下一个候选虚拟IP的计数器（会在分配时递增，越界时会触发池重置）
     * @param onPoolReset 地址池被实际重置时的可选回调（在同步块外执行，仅在真实发生重置时触发一次）
     * @param onNewAllocation 当方法为 [realDstIp] 新分配虚拟IP时的可选回调，回调参数为 `(virtualIp, realDstIp)`（在同步块外执行）
     * @return 分配得到或已存在的虚拟IP地址字符串
     * @throws IllegalArgumentException 如果 [realDstIp] 为空或仅包含空白字符，或在地址池耗尽时抛出（表示无法为该真实IP分配虚拟IP）
     */
    fun getOrAllocateVirtualIp(
        realDstIp: String,
        virtualIpPool: MutableMap<String, String>,
        reverseIpMap: MutableMap<String, String>,
        nextVirtualIp: AtomicInteger,
        onPoolReset: (() -> Unit)? = null,
        onNewAllocation: ((virtualIp: String, realDstIp: String) -> Unit)? = null,
    ): String
}

/**
 * 虚拟 IP 分配器默认实现
 *
 * 线程安全：使用专用锁对象保护共享状态，回调在同步块外执行以避免死锁。
 * 并发安全：使用 AtomicInteger 确保 IP 分配的原子性，通过锁保护重置操作的完整性。
 */
@Singleton
class VirtualIpAllocatorImpl @Inject constructor() : VirtualIpAllocator {

    // 专用锁对象，保护所有共享状态的复合操作
    private val ipAllocationLock = Any()

    /**
     * 为指定的真实目标 IP 返回已有的虚拟 IP 映射，或在可用时分配并保存一个新的虚拟 IP。
     *
     * 该方法会在必要时更新 `virtualIpPool`（real -> virtual）和 `reverseIpMap`（virtual -> real），并可能重置 `nextVirtualIp` 计数器；若发生重置则调用 `onPoolReset`（在同步块外触发），若完成新的分配则调用 `onNewAllocation`（在同步块外触发）。
     *
     * @param realDstIp 目标真实 IP，不能为空或仅空白。
     * @param virtualIpPool 可变映射，用于保存从真实 IP 到虚拟 IP 的映射（会在成功分配时写入，重置时清空）。
     * @param reverseIpMap 可变映射，用于保存从虚拟 IP 到真实 IP 的反向映射（会在成功分配时写入，重置时清空）。
     * @param nextVirtualIp 原子整数，作为下一个候选虚拟 IP 编号的计数器（在必要时会被重置或递增）。
     * @param onPoolReset 在实际发生池重置时调用一次；该回调在同步块外执行。可为 null。
     * @param onNewAllocation 在成功分配新的虚拟 IP 后调用，参数为（分配的虚拟 IP，真实目标 IP）；该回调在同步块外执行。可为 null.
     *
     * @return 分配或已有的虚拟 IP 字符串（格式例如 "10.0.0.x"）。
     *
     * @throws IllegalArgumentException 当 `realDstIp` 为空或仅空白时抛出。
     * @throws IllegalArgumentException 当遍历整个可用地址空间仍无法找到未被占用的虚拟 IP 时抛出（表示 IP 池耗尽）。
     */
    override fun getOrAllocateVirtualIp(
        realDstIp: String,
        virtualIpPool: MutableMap<String, String>,
        reverseIpMap: MutableMap<String, String>,
        nextVirtualIp: AtomicInteger,
        onPoolReset: (() -> Unit)?,
        onNewAllocation: ((virtualIp: String, realDstIp: String) -> Unit)?,
    ): String {
        require(realDstIp.isNotBlank()) { "realDstIp must not be blank" }

        var poolResetTriggered = false
        var isNewAllocation = false

        val ip = synchronized(ipAllocationLock) {
            // 检查是否已存在映射，如果存在直接返回（不触发回调）
            virtualIpPool[realDstIp]?.let { existingIp ->
                return@synchronized existingIp
            }

            // 检查是否需要重置 IP 池（在递增之前检查，避免使用无效 IP）
            val currentIp = nextVirtualIp.get()
            if (currentIp > MAX_IP || currentIp < START_IP) {
                // 在持有锁的情况下执行重置，确保只发生一次
                // 其他线程会在锁外等待，直到重置完成
                poolResetTriggered = true
                virtualIpPool.clear()
                reverseIpMap.clear()
                nextVirtualIp.set(START_IP)
            }

            // 获取下一个 IP 编号（原子操作）
            var ipNum = nextVirtualIp.getAndIncrement()

            // 循环分配 IP，直到找到未被占用的 IP
            var assignedIp: String
            val maxAttempts = MAX_IP - START_IP + 1
            var attempts = 0

            do {
                // 正常范围内直接使用，超出范围（溢出为负数或大于MAX_IP）使用 floorMod 映射
                val safeIpNum = if (ipNum in START_IP..MAX_IP) {
                    ipNum
                } else {
                    Math.floorMod(ipNum - START_IP, MAX_IP - START_IP + 1) + START_IP
                }
                assignedIp = "$NETWORK_PREFIX.$safeIpNum"

                // 找到可用 IP，退出循环
                if (!reverseIpMap.containsKey(assignedIp)) {
                    break
                }

                // IP 被占用，使用原子操作获取下一个 IP
                ipNum = nextVirtualIp.getAndIncrement()
                attempts++

                // 防止无限循环，限制尝试次数
                require(attempts < maxAttempts) {
                    "IP pool exhausted: cannot allocate virtual IP for $realDstIp"
                }
            } while (true)

            // 在确认分配成功后才标记为新分配
            isNewAllocation = true
            virtualIpPool[realDstIp] = assignedIp
            reverseIpMap[assignedIp] = realDstIp
            assignedIp
        }

        // 回调在同步块外执行，避免死锁和长时间持有锁
        if (poolResetTriggered) {
            onPoolReset?.invoke()
        }
        if (isNewAllocation) {
            onNewAllocation?.invoke(ip, realDstIp)
        }
        return ip
    }

    companion object {
        /**
         * 网络前缀：使用 RFC1918 私有地址空间 10.0.0.0/8
         * 选择 /24 子网（254 个主机地址）以满足典型 VPN 场景需求
         */
        private const val NETWORK_PREFIX = "10.0.0"

        /**
         * 起始 IP：从 1 开始，避免使用网络地址（10.0.0.0）
         */
        private const val START_IP = 1

        /**
         * 最大 IP：254，避免使用广播地址（10.0.0.255）
         * 限制原因：单个 VPN 连接池容量限制，防止内存溢出
         */
        private const val MAX_IP = 254
    }
}
