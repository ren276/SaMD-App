package com.example.samdapp.presentation.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Home button and the list it opens hold cases that may not have reached the server, so the
 * label must be true of every case in it: "Cases for the doctor", never "Sent to doctor". Read as
 * source text (app/src is a declared input of the unit test task), because the claim is about
 * which string each screen renders and both are composables this suite cannot render.
 */
class CasesForTheDoctorLabelTest {

    private val presentation: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .map { File(it, "app/src/main/java/com/example/samdapp/presentation") }
            .firstOrNull { it.isDirectory }
            ?: error("could not locate the presentation sources from $cwd")
    }

    private fun source(path: String) = File(presentation, path).readText()

    @Test
    fun `the Home button and the list title render the neutral label`() {
        assertTrue(source("home/HomeScreen.kt").contains("R.string.cases_for_the_doctor)"))
        assertTrue(source("doctorlist/DoctorListScreen.kt").contains("R.string.cases_for_the_doctor)"))
        assertTrue(source("doctorlist/DoctorListScreen.kt").contains("R.string.cases_for_the_doctor_empty"))
    }

    @Test
    fun `no presentation source claims a case was sent to the doctor`() {
        val offenders = presentation.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> listOf("\"Sent to doctor\"", "sent to a doctor yet").any { f.readText().contains(it) } }
            .map { it.name }.toList()
        assertTrue("still claiming sent: $offenders", offenders.isEmpty())
    }
}
