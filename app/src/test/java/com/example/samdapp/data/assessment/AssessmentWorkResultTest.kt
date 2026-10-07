package com.example.samdapp.data.assessment

import androidx.work.ListenableWorker
import com.example.samdapp.domain.usecase.AssessmentOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class AssessmentWorkResultTest {

    @Test
    fun `only a case that is still on its way asks WorkManager to run again`() {
        assertEquals(ListenableWorker.Result.retry(), assessmentWorkResult(AssessmentOutcome.RetryLater))
        assertEquals(ListenableWorker.Result.success(), assessmentWorkResult(AssessmentOutcome.Done))
    }
}
