package com.example.samdapp.data.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every repository write of `localModifiedAt` goes through [SyncStamp], read as source text
 * (app/src is a declared input of the unit test task, so editing a repository reruns this). A
 * single `localModifiedAt = Instant.now()` or `= createdAt` reintroduces the same-millisecond
 * stamp the clock exists to remove, and no behavioural test would notice it on a fast machine.
 * The two allowed exceptions are the mapper parameters whose callers pass a SyncStamp value.
 */
class SyncStampContractTest {

    private val repositories: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .map { File(it, "app/src/main/java/com/example/samdapp/data/repository") }
            .firstOrNull { it.isDirectory }
            ?: error("could not locate the repository sources from $cwd")
    }

    @Test
    fun `every localModifiedAt a repository writes comes from SyncStamp`() {
        val offenders = repositories.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            Regex("localModifiedAt = (?!SyncStamp\\.now\\(\\)|localModifiedAt\\b)([^\\n]+)").findAll(file.readText())
                .map { "${file.name}: localModifiedAt = ${it.groupValues[1].trim()}" }
        }.toList()
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `no DAO time argument that becomes localModifiedAt is taken from the wall clock`() {
        // The DAO statements that copy their time argument into localModifiedAt.
        val stampingCalls = Regex(
            "(caseRecordDao\\.(updateStatus|assignDoctor|sendAllPendingSync|abandon\\w*)|ailmentDao\\.markDeleted|" +
                "consultationDao\\.updateTranscription|consultationDocumentDao\\.retract|referralDao\\.updateStatus)\\(([^\\n]*)\\)",
        )
        val offenders = repositories.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            stampingCalls.findAll(file.readText())
                .filterNot { call -> Regex("SyncStamp\\.now\\(\\)\\)+$").containsMatchIn(call.value) }
                .map { "${file.name}: ${it.value}" }
        }.toList()
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }
}
