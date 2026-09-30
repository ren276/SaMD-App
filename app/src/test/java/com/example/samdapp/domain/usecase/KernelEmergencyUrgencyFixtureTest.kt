package com.example.samdapp.domain.usecase

import com.example.samdapp.data.remote.RetrofitKernelSource
import com.example.samdapp.data.remote.SyncGson
import com.example.samdapp.data.remote.api.KernelApiService
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.KernelAssessmentRequestDto
import com.example.samdapp.data.remote.dto.KernelAssessmentResponseDto
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import com.example.samdapp.testutil.FakeKernelFallbackSource
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import retrofit2.Response

/**
 * Real classifier output fed through the device's real parsing path.
 *
 * The fixtures are real `/v1/assess` output captured from SaMDClassifier (see each file's
 * `_provenance`), not hand-written bodies: every earlier test of this mapping used an urgency token
 * the classifier never sends, so it agreed with itself while the real response fell through.
 * The body goes through the production Gson and the real [RetrofitKernelSource] mapping into
 * [GenerateKernelReportUseCase]; nothing between the fixture and the assertion is a stand-in.
 *
 * Every expected value comes from `classifier-fixtures/expectations.json`, which the backend's
 * `test_kernel_emergency_fixtures.py` reads from its identical copy, so the two sides cannot hold
 * different expectations. [ClassifierFixtureMirrorTest] fails if the copies differ.
 */
@RunWith(Parameterized::class)
class KernelEmergencyUrgencyFixtureTest(private val name: String, private val case: JsonObject) {

    companion object {
        private fun resource(name: String): String =
            requireNotNull(KernelEmergencyUrgencyFixtureTest::class.java.classLoader!!.getResource("classifier-fixtures/$name")) {
                "missing fixture $name"
            }.readText()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> =
            JsonParser.parseString(resource("expectations.json")).asJsonObject.getAsJsonArray("cases")
                .map { it.asJsonObject }
                .map { arrayOf<Any>(it.get("name").asString, it) }
    }

    private class FixtureKernelApiService(private val body: KernelAssessmentResponseDto) : KernelApiService {
        override suspend fun assess(
            request: KernelAssessmentRequestDto,
        ): Response<ApiEnvelopeDto<KernelAssessmentResponseDto>> =
            Response.success(ApiEnvelopeDto(success = true, data = body, meta = null))
    }

    private fun fixtureBody(): KernelAssessmentResponseDto {
        val response = JsonParser.parseString(resource(case.get("fixture").asString)).asJsonObject.getAsJsonObject("response")
        val body = SyncGson.create().fromJson(response, KernelAssessmentResponseDto::class.java)
        val edit = case.getAsJsonObject("edit")
        var edited = body
        edit.get("triage_urgency")?.let { edited = edited.copy(triageUrgency = it.asString) }
        edit.get("condition_tier")?.let { tier ->
            edited = edited.copy(
                differentialDiagnosis = edited.differentialDiagnosis!!.mapIndexed { i, d ->
                    if (i == 0) d.copy(conditionTier = tier.asString) else d
                },
            )
        }
        return edited
    }

    private fun payload() = KernelPayload(
        caseToken = "case-1",
        vitals = VitalsReading(),
        chiefComplaint = "breathless",
        durationBucket = "few_days",
        severityScore = 8,
        relevantHistory = null,
        transcription = null,
        attachments = emptyList(),
    )

    @Test
    fun `derivation matches the shared expectation`() = runTest {
        val audit = FakeAuditLogger()
        val useCase = GenerateKernelReportUseCase(
            FakeKernelReportRepository(),
            FakeDeviceInfoProvider(),
            RetrofitKernelSource(FixtureKernelApiService(fixtureBody())),
            FakeKernelFallbackSource(result = null),
            audit,
        )
        val output = useCase("case-1", payload()).getOrThrow()
        val expected = case.getAsJsonObject("expected")

        // One string, so a failure shows every actual value at once.
        assertEquals(
            "${expected.get("urgency").asString}/${expected.get("risk").asString}/" +
                "verification=${expected.get("verification").asBoolean}",
            "${output.urgencyLevel}/${output.riskCategory}/verification=${output.requiredHumanVerification}",
        )

        val expectedUnrecognised = expected.getAsJsonArray("unrecognised").map { it.asString }.toSet()
        val breadcrumbs = audit.logged.filter { it.action == AuditAction.KERNEL_UNRECOGNISED_OUTPUT.value }
        if (expectedUnrecognised.isEmpty()) {
            assertTrue("no breadcrumb expected, got $breadcrumbs", breadcrumbs.isEmpty())
        } else {
            val fields = JsonParser.parseString(breadcrumbs.single().payload).asJsonObject.keySet() - "modelVersion"
            assertEquals(expectedUnrecognised, fields)
        }
    }
}
