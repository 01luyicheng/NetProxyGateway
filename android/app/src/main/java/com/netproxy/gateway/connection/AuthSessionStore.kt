package com.netproxy.gateway.connection

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthSessionStore @Inject constructor() {

    private var currentSession: ProxyAuthSession? = null

    @Synchronized
    fun update(deviceId: String, authToken: String) {
        currentSession = ProxyAuthSession(deviceId = deviceId, authToken = authToken)
    }

    @Synchronized
    fun clear() {
        currentSession = null
    }

    @Synchronized
    fun isValid(username: String, password: String): Boolean {
        val session = currentSession ?: return false
        return session.deviceId == username && session.authToken == password
    }
}

data class ProxyAuthSession(
    val deviceId: String,
    val authToken: String
)
