package com.example.samdapp.data.remote

import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import com.example.samdapp.domain.connectivity.isLocalNetworkAddress
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import java.net.InetAddress

/**
 * Stops a blocked local call before OkHttp selects a route (memo 11.1). Must be added with
 * `addInterceptor`, never `addNetworkInterceptor`: a network interceptor runs after the connection
 * is open. Covers IP literals and `.local` names, which OkHttp never passes to [Dns]
 * (`RouteSelector.kt:163-164` in 4.12.0); [LocalNetworkGateDns] covers every other name.
 */
class LocalNetworkGateInterceptor(private val gate: LocalNetworkGate) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (gate.blocksHost(chain.request().url.host)) throw LocalNetworkNotAllowedException()
        return chain.proceed(chain.request())
    }
}

/**
 * Wraps a client's own [Dns]. When the gate is shut, local addresses are dropped from the answer
 * and an answer left empty throws [LocalNetworkNotAllowedException] (memo 11.1, Q1): a public name
 * that resolves to a private address is stopped here, before connect, and a mixed answer keeps its
 * public addresses.
 */
class LocalNetworkGateDns(private val delegate: Dns, private val gate: LocalNetworkGate) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val allowed = addresses.filterNot { gate.blocks(isLocalNetworkAddress(it)) }
        if (allowed.isEmpty() && addresses.isNotEmpty()) throw LocalNetworkNotAllowedException()
        return allowed
    }
}
