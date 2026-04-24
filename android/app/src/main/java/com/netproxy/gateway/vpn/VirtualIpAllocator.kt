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
     * 获取或分配虚拟IP
     *
     * 如果 [realDstIp] 已有对应的虚拟IP，则直接返回已存在的映射（不会触发 [onNewAllocation] 回调）。
     * 如果不存在映射，则分配一个新的虚拟IP，并触发 [onNewAllocation] 回调。
     *
     * @param realDstIp 真实目标IP，不能为空
     * @param virtualIpPool 虚拟IP池 (realDstIp -> virtualSrcIp)
     * @param reverseIpMap 反向IP映射 (virtualSrcIp -> realDstIp)
     * @param nextVirtualIp 下一个虚拟IP计数器
     * @param onPoolReset IP池重置时的回调（在同步块外执行，仅在真正发生重置时触发一次）
     * @param onNewAllocation 当**新分配**虚拟IP时的回调（获取已存在IP时不触发，在同步块外执行）
     * @return 虚拟IP地址（新分配或已存在的）
     * @throws IllegalArgumentException 如果 [realDstIp] 为空
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
                    Math.floorMod(ipNum, MAX_IP - START_IP + 1) + START_IP
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
