package com.example.samdapp.domain.connectivity

import java.io.IOException

/**
 * The one local-network gate (memo 11.1). [granted] is read only for a local target, so a public
 * call never pays for a permission check.
 *
 * Reading the grant at call time is exact: a revocation kills the process (memo 1.10), so the
 * state cannot change from granted to denied under a call that already passed.
 */
class LocalNetworkGate(
    private val sdkInt: Int,
    private val granted: () -> Boolean,
) {
    fun blocks(local: Boolean): Boolean = local && isLocalNetworkBlocked(sdkInt, granted(), local)
}

/**
 * The call targeted the local network on an enforcing OS without the grant, and was stopped before
 * any socket was opened. An [IOException] so every existing transport `catch` still sees it; every
 * consumer matches it by type before its generic network branch (memo 11.4). The message names no
 * host or port: some of those branches log it.
 */
class LocalNetworkNotAllowedException : IOException("Local network access is not allowed for this app")
