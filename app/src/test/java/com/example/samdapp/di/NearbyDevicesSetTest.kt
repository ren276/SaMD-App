package com.example.samdapp.di

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Memo P2-R2a, staging and prod half (runs in every flavor's unit suite). The NEARBY_DEVICES set
 * Hilt injects is the union of every `@NearbyDevicesMembers` contribution compiled into the
 * flavor. `src/main` contributes exactly the two members below, and `src/staging` and `src/prod`
 * hold no qualified provider, so in those flavors the set is exactly these two. The dev half is
 * `DevNearbyDevicesSetTest`. No Hilt graph runs here (the project has no Hilt test dependency).
 */
class NearbyDevicesSetTest {

    @Test
    fun `main contributes exactly NEARBY_WIFI_DEVICES and UWB_RANGING`() {
        assertEquals(
            setOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.UWB_RANGING),
            NearbyDevicesModule.provideMainNearbyDevicesMembers(),
        )
    }

    @Test
    fun `exactly one provider in main and in dev, none in staging or prod`() {
        val src = MAIN.parentFile
        // Positive control: the scan reads real files and finds the qualifier in src/main.
        assertTrue("the scan does not reach src/main", kotlinUnder(MAIN).any { "NearbyDevicesMembers" in it.readText() })
        mapOf("main" to 1, "dev" to 1, "staging" to 0, "prod" to 0).forEach { (set, expected) ->
            val tree = File(src, set)
            assertTrue("scan root missing: ${tree.path}", tree.isDirectory)
            assertEquals("@NearbyDevicesMembers providers in src/$set", expected, providersUnder(tree))
        }
    }

    @Test
    fun `the provider count ignores the qualifier declaration and the injection site`() {
        val sample = """
            annotation class NearbyDevicesMembers
            class Check(@NearbyDevicesMembers private val members: Set<String>)
            @Provides
            @ElementsIntoSet
            @NearbyDevicesMembers
            fun provideIt(): Set<String> = emptySet()
        """.trimIndent()
        assertEquals(1, providersIn(sample))
    }

    private fun kotlinUnder(root: File): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun providersUnder(root: File): Int = kotlinUnder(root).sumOf { providersIn(it.readText()) }

    /** A provider is a function whose annotation run carries both @ElementsIntoSet and the qualifier. */
    private fun providersIn(text: String): Int =
        Regex("""((?:@\w+(?:\([^)]*\))?\s+)+)fun\s""").findAll(text).count { run ->
            val annotations = run.groupValues[1]
            "@ElementsIntoSet" in annotations && "@NearbyDevicesMembers" in annotations
        }

    private companion object {
        val MAIN: File = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory } ?: File("src/main")
    }
}
