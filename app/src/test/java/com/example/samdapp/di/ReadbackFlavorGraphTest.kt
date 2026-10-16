package com.example.samdapp.di

import com.example.samdapp.BuildConfig
import com.example.samdapp.data.remote.RemoteSlmEngine
import com.example.samdapp.data.remote.api.SlmApiService
import com.example.samdapp.domain.slm.LocalDataOwnerFacility
import com.example.samdapp.domain.slm.OwningFacility
import com.example.samdapp.domain.slm.SlmEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.Optional
import javax.inject.Provider

/**
 * What each flavor's Hilt graph resolves for the readback seam (ruling R-DEV, on-device SLM memo
 * 16). Hilt cannot build the graph in a JVM test here, so the claim is split into three parts that
 * together are the graph:
 * 1. The defaults: [SlmNetworkModule]'s providers, called directly, give [RemoteSlmEngine] and
 *    [OwningFacility.Unknown] when no override is present, and the override when one is.
 * 2. The override is present in dev only: `LocalSlmModule` is on this variant's classpath exactly
 *    when the flavor is dev (every flavor's unit test task runs this).
 * 3. Nothing else provides an override: across every shipped source set, `DevReadbackOverride` is
 *    named only by [SlmNetworkModule] (declaration, `@BindsOptionalOf`, provider parameters) and by
 *    the dev holder's two providers, each line pinned exactly.
 */
class ReadbackFlavorGraphTest {

    @Test
    fun withNoOverrideTheGraphResolvesTheRemoteEngineAndAnUnknownOwner() = runTest {
        val remote = RemoteSlmEngine(unusedApi())
        assertSame(remote, SlmNetworkModule.provideSlmEngine(Optional.empty(), Provider { remote }))
        assertEquals(
            OwningFacility.Unknown,
            SlmNetworkModule.provideLocalDataOwnerFacility(Optional.empty()).owningFacility(),
        )
    }

    @Test
    fun anOverrideReplacesBothDefaultsAndTheRemoteEngineIsNeverBuilt() = runTest {
        val engine: SlmEngine = RemoteSlmEngine(unusedApi())
        val owner = object : LocalDataOwnerFacility {
            override suspend fun owningFacility(): OwningFacility = OwningFacility.Known("fac-1")
        }
        assertSame(
            engine,
            SlmNetworkModule.provideSlmEngine(Optional.of(engine), Provider { error("remote engine built in spite of an override") }),
        )
        assertSame(owner, SlmNetworkModule.provideLocalDataOwnerFacility(Optional.of(owner)))
    }

    @Test
    fun theOverrideModuleIsCompiledIntoDevOnly() {
        assertTrue("Unknown flavor ${BuildConfig.FLAVOR}", BuildConfig.FLAVOR in setOf("dev", "staging", "prod"))
        val present = runCatching { Class.forName("com.example.samdapp.di.LocalSlmModule") }.isSuccess
        assertEquals("LocalSlmModule on the ${BuildConfig.FLAVOR} classpath", BuildConfig.FLAVOR == "dev", present)
    }

    @Test
    fun onlySlmNetworkModuleAndTheDevHolderNameTheOverride() {
        val roots = listOf("main", "dev", "staging", "prod").map { File(SRC, it) }
        assertTrue("Source sets missing under ${SRC.absolutePath}", roots.all { it.isDirectory })
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .map { it.relativeTo(SRC).invariantSeparatorsPath to it.readText() }
        assertTrue("Scan is vacuous: ${files.size} files", files.size > 200)
        assertEquals(emptyList<String>(), overrideViolations(files))
    }

    /** Positive controls: each shape that would let a non-dev flavor resolve an override is caught. */
    @Test
    fun theOverrideScanRejectsWhatItExistsFor() {
        val real = listOf(BINDING_MODULE, DEV_HOLDER).map { it to File(SRC, it).readText() }
        assertEquals(emptyList<String>(), overrideViolations(real))
        val mainText = real[0].second
        val holderText = real[1].second
        listOf(
            // A prod module providing the owner override.
            real + ("prod/java/com/example/samdapp/di/ProductionClinicalModule.kt" to
                "@Provides @DevReadbackOverride\nfun o(s: SessionFacilitySource): LocalDataOwnerFacility = x"),
            // An override provided from src/main, which every flavor compiles.
            listOf(BINDING_MODULE to "$mainText\n@Provides @DevReadbackOverride\nfun o(): LocalDataOwnerFacility = x", real[1]),
            // A third provider in the dev holder.
            listOf(real[0], DEV_HOLDER to "$holderText\n@Provides @DevReadbackOverride\nfun e(r: RemoteSlmEngine): SlmEngine = r"),
            // The dev owner provider swapped for another.
            listOf(real[0], DEV_HOLDER to holderText.replace("fun provideDevLocalDataOwnerFacility(", "fun other(")),
        ).forEach { files ->
            assertTrue("An override shape was accepted: ${files.map { it.first }}", overrideViolations(files).isNotEmpty())
        }
    }

    private fun overrideViolations(files: List<Pair<String, String>>): List<String> {
        val naming = files.filter { (_, text) -> NAME in text }.map { it.first }.sorted()
        val out = mutableListOf<String>()
        if (naming != listOf(DEV_HOLDER, BINDING_MODULE).sorted()) out += "files naming $NAME: $naming"
        val texts = files.toMap()
        texts[BINDING_MODULE]?.let { if (overrideLines(it) != MAIN_LINES) out += "$BINDING_MODULE: ${overrideLines(it)}" }
        texts[DEV_HOLDER]?.let { if (overrideLines(it) != DEV_LINES) out += "$DEV_HOLDER: ${overrideLines(it)}" }
        return out
    }

    /** Each code line naming the qualifier, paired with the next code line. */
    private fun overrideLines(text: String): List<Pair<String, String>> {
        val code = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        return code.indices.filter { NAME in code[it] }.map { code[it] to code.getOrElse(it + 1) { "" } }
    }

    private fun unusedApi(): SlmApiService =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SlmApiService::class.java)) { _, _, _ ->
            error("the API must not be called")
        } as SlmApiService

    private companion object {
        val SRC: File = listOf(File("src"), File("app/src")).firstOrNull { File(it, "main").isDirectory } ?: File("src")

        const val NAME = "DevReadbackOverride"
        const val BINDING_MODULE = "main/java/com/example/samdapp/di/SlmNetworkModule.kt"
        const val DEV_HOLDER = "dev/java/com/example/samdapp/di/LocalSlmModule.kt"

        /** Declaration, two parameters of the default providers, two `@BindsOptionalOf`. Nothing provides. */
        val MAIN_LINES = listOf(
            "annotation class DevReadbackOverride" to "@Module(includes = [SlmNetworkModule.Bindings::class])",
            "@DevReadbackOverride devEngine: Optional<SlmEngine>," to "remote: Provider<RemoteSlmEngine>,",
            "@DevReadbackOverride devOwner: Optional<LocalDataOwnerFacility>," to
                "): LocalDataOwnerFacility = devOwner.orElse(UnknownLocalDataOwnerFacility())",
            "@DevReadbackOverride" to "fun devReadbackEngine(): SlmEngine",
            "@DevReadbackOverride" to "fun devLocalDataOwnerFacility(): LocalDataOwnerFacility",
        )

        val DEV_LINES = listOf(
            "@Provides @DevReadbackOverride" to "fun provideDevReadbackEngine(engine: LocalSlmEngine): SlmEngine = engine",
            "@Provides @DevReadbackOverride" to
                "fun provideDevLocalDataOwnerFacility(session: SessionFacilitySource): LocalDataOwnerFacility =",
        )
    }
}
