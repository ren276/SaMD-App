package com.example.samdapp.presentation.consultation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendClauseTest {

    @Test
    fun `blank field takes the clause as-is`() {
        assertEquals("Diabetes", appendClause("", "Diabetes"))
    }

    @Test
    fun `repeat tap of the same clause is a no-op`() {
        assertEquals("Diabetes", appendClause("Diabetes", "Diabetes"))
    }

    @Test
    fun `a second distinct clause is comma-joined`() {
        assertEquals("Diabetes, Hypertension", appendClause("Diabetes", "Hypertension"))
    }
}
