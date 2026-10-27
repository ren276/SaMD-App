package com.example.samdapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every Room entity in [AppDatabase] is classified in [ENTITY_SCOPES] as facility clinical data
 * or not, with the table name its `@Entity` declares. A table added to the database without a
 * classification fails here instead of being silently left out of "holds clinical data"
 * (facility-scope memo 3.2): the project's characteristic defect is a list that a new table was
 * never added to.
 */
class ClinicalEntityClassificationTest {

    private val main: File = File(System.getProperty("user.dir")!!).let { cwd ->
        sequenceOf(cwd, cwd.parentFile).filterNotNull()
            .map { File(it, "app/src/main/java/com/example/samdapp/data/local") }
            .first { File(it, "AppDatabase.kt").isFile }
    }

    /** The entity class names listed in `@Database(entities = [...])`. */
    private fun databaseEntities(source: String): Set<String> {
        val list = Regex("""entities\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(source)!!.groupValues[1]
        return Regex("""(\w+)::class""").findAll(list).map { it.groupValues[1] }.toSet()
    }

    private fun unclassified(entities: Set<String>): Set<String> = entities - ENTITY_SCOPES.keys.map { it.simpleName!! }.toSet()

    @Test
    fun `every entity in the database is classified, and nothing else is`() {
        val entities = databaseEntities(File(main, "AppDatabase.kt").readText())
        assertEquals("the database lists 23 entities", 23, entities.size)

        assertEquals("entities with no classification", emptySet<String>(), unclassified(entities))
        assertEquals("classified entities that are not in the database", emptySet<String>(), ENTITY_SCOPES.keys.map { it.simpleName!! }.toSet() - entities)
    }

    @Test
    fun `positive control - a new entity with no classification is reported`() {
        val source = File(main, "AppDatabase.kt").readText()
            .replace("DeviceDataOwnerEntity::class,", "DeviceDataOwnerEntity::class,\n        FollowUpReminderEntity::class,")
        assertEquals(setOf("FollowUpReminderEntity"), unclassified(databaseEntities(source)))
    }

    @Test
    fun `each classification names the table its entity declares`() {
        ENTITY_SCOPES.forEach { (entity, scope) ->
            val source = File(main, "entity").walk().filter { it.extension == "kt" }
                .map { it.readText() }
                .first { Regex("""class\s+${entity.simpleName}\b""").containsMatchIn(it) }
            // The last @Entity before the class declaration is this entity's own annotation.
            val classAt = Regex("""data class\s+${entity.simpleName}\b""").find(source)!!.range.first
            val annotation = source.substring(source.lastIndexOf("@Entity(", classAt), classAt)
            val declared = Regex("""tableName\s*=\s*"(\w+)"""").find(annotation)?.groupValues?.get(1)
            assertEquals("${entity.simpleName}'s table", declared, scope.table)
        }
    }

    @Test
    fun `only the doctors reference list and the owner marker are not clinical`() {
        assertEquals(
            setOf("doctors", "device_data_owner"),
            ENTITY_SCOPES.values.filterNot { it.clinical }.map { it.table }.toSet(),
        )
        assertTrue("every classification gives a reason", ENTITY_SCOPES.values.all { it.reason.isNotBlank() })
    }
}
