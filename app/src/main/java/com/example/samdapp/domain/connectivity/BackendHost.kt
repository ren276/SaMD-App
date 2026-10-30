package com.example.samdapp.domain.connectivity

/**
 * The host every backend call actually reaches, so login and Home can ask [LocalNetworkGate.blocksHost]
 * the question a real call would meet (memo P2-R1, P2-R5). Bound per flavor: in dev it is
 * DevServerConfig's active URL, because the dev host interceptor rewrites every backend call to it;
 * in staging and prod it is `BuildConfig.BACKEND_BASE_URL`.
 */
fun interface BackendHost {
    fun host(): String
}
