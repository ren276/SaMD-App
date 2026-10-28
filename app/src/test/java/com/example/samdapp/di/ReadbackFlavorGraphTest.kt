package com.example.samdapp.di

import com.example.samdapp.BuildConfig
import com.example.samdapp.data.local.MarkerLocalDataOwnerFacility
import com.example.samdapp.data.local.dao.DeviceDataOwnerDao
import com.example.samdapp.data.local.entity.DeviceDataOwnerEntity
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
import java.time.Instant
import java.util.Optional
import javax.inject.Provider

/**
 * What each flavor's Hilt graph resolves for the readback seam (ruling R-DEV, on-device SLM memo
 * 16, as superseded for the owner by facility-scope FS-7). Hilt cannot build the graph in a JVM test
 * here, so the claim is split into parts that together are the graph:
 * 1. The engine: [SlmNetworkModule.provideSlmEngine], called directly, gives [RemoteSlmEngine] with
 *    no override and the override when one is present.
 * 2. The owner: the one provider of [LocalDataOwnerFacility] takes [MarkerLocalDataOwnerFacility]
 *    and returns it, in every flavor (this runs in every flavor's unit test task); no shipped source
 *    declares any other owner.
 * 3. The override is present in dev only: `LocalSlmModule` is on this variant's classpath exactly
 *    when the flavor is dev.
 * 4. Nothing else provides an override: across every shipped source set, `DevReadbackOverride` is
 *    named only by [SlmNetworkModule] (declaration, `@BindsOptionalOf`, the engine provider's
 *    parameter) and by the dev holder's engine provider, each line pinned exactly.
 */
class ReadbackFlavorGraphTest {

    @Test
    fun withNoOverrideTheGraphResolvesTheRemoteEngine() = runTest {
        val remote = RemoteSlmEngine(unusedApi())
        assertSame(remote, SlmNetworkModule.provideSlmEngine(Optional.empty(), Provider { remote }))
    }

    @Test
    fun anOverrideReplacesTheEngineAndTheRemoteEngineIsNeverBuilt() = runTest {
        val engine: SlmEngine = RemoteSlmEngine(unusedApi())
        assertSame(
            engine,
            SlmNetworkModule.provideSlmEngine(Optional.of(engine), Provider { error("remote engine built in spite of an override") }),
        )
    }

    /** FS-7: in every flavor the owner is the marker, and the provider has no other input to choose from. */
    @Test
    fun everyFlavorResolvesTheMarkerBackedOwner() = runTest {
        val owners = SlmNetworkModule::class.java.declaredMethods.filter { it.returnType == LocalDataOwnerFacility::class.java }
        assertEquals("one provider of the owner", 1, owners.size)
        assertEquals(
            "its only input is the marker owner",
            listOf(MarkerLocalDataOwnerFacility::class.java),
            owners.single().parameterTypes.toList(),
        )
        val marker = MarkerLocalDataOwnerFacility(OwnerDao(DeviceDataOwnerEntity(facilityId = "fac-1", facilityName = "PHC One", claimedAt = Instant.EPOCH, claimedByWorkerId = "w-1", legacy = false)))
        val resolved = SlmNetworkModule.provideLocalDataOwnerFacility(marker)
        assertSame(marker, resolved)
        assertEquals(OwningFacility.Known("fac-1"), resolved.owningFacility())
    }

    /** No shipped source declares an owner other than the marker: not the always-UNKNOWN owner, not
     *  the session-facility override. Positive controls: each one reappearing is caught. */
    @Test
    fun noFlavorDeclaresAnyOtherOwner() {
        val files = listOf("main", "dev", "staging", "prod").map { File(SRC, it) }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .map { it.relativeTo(SRC).invariantSeparatorsPath to it.readText() }
        assertEquals(listOf(MARKER_OWNER), ownerImplementers(files))

        val unknownOwner = "main/java/com/example/samdapp/domain/slm/FacilityScope.kt" to
            "class UnknownLocalDataOwnerFacility @Inject constructor() : LocalDataOwnerFacility {\n}"
        val sessionOwner = DEV_HOLDER to
            "fun provideDevLocalDataOwnerFacility(session: SessionFacilitySource): LocalDataOwnerFacility =\n    object : LocalDataOwnerFacility {\n}"
        val qualifiedOwner = DEV_HOLDER to "object : com.example.samdapp.domain.slm.LocalDataOwnerFacility {\n}"
        listOf(unknownOwner, sessionOwner, qualifiedOwner).forEach { extra ->
            assertTrue("${extra.first} was not caught", ownerImplementers(files + extra).size > 1)
        }
    }

    private fun ownerImplementers(files: List<Pair<String, String>>): List<String> = files
        // A class or object declaring the supertype, not a parameter or a return type.
        .filter { (_, text) -> Regex("""(\)|\bobject)\s*:\s*(?:[\w.]+\.)?LocalDataOwnerFacility\s*\{""").containsMatchIn(text) }
        .map { it.first }.sorted()

    private class OwnerDao(private val owner: DeviceDataOwnerEntity?) : DeviceDataOwnerDao {
        override suspend fun insertIfAbsent(owner: DeviceDataOwnerEntity): Long = error("unused")
        override suspend fun read(): DeviceDataOwnerEntity? = owner
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
            // A prod module providing an override.
            real + ("prod/java/com/example/samdapp/di/ProductionClinicalModule.kt" to
                "@Provides @DevReadbackOverride\nfun o(s: SessionFacilitySource): LocalDataOwnerFacility = x"),
            // An override provided from src/main, which every flavor compiles.
            listOf(BINDING_MODULE to "$mainText\n@Provides @DevReadbackOverride\nfun o(): LocalDataOwnerFacility = x", real[1]),
            // A third provider in the dev holder.
            listOf(real[0], DEV_HOLDER to "$holderText\n@Provides @DevReadbackOverride\nfun e(r: RemoteSlmEngine): SlmEngine = r"),
            // The dev session-facility owner override brought back (superseded by FS-7).
            listOf(real[0], DEV_HOLDER to "$holderText\n@Provides @DevReadbackOverride\nfun provideDevLocalDataOwnerFacility(session: SessionFacilitySource): LocalDataOwnerFacility ="),
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

        const val MARKER_OWNER = "main/java/com/example/samdapp/data/local/MarkerLocalDataOwnerFacility.kt"

        /** Declaration, the engine provider's parameter, one `@BindsOptionalOf`. Nothing provides. */
        val MAIN_LINES = listOf(
            "annotation class DevReadbackOverride" to "@Module(includes = [SlmNetworkModule.Bindings::class])",
            "@DevReadbackOverride devEngine: Optional<SlmEngine>," to "remote: Provider<RemoteSlmEngine>,",
            "@DevReadbackOverride" to "fun devReadbackEngine(): SlmEngine",
        )

        val DEV_LINES = listOf(
            "@Provides @DevReadbackOverride" to "fun provideDevReadbackEngine(engine: LocalSlmEngine): SlmEngine = engine",
        )
    }
}
