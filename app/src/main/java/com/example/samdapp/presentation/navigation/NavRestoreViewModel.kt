package com.example.samdapp.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Writes the one audit row a discarded back stack produces.
 *
 * This exists because [NavBackStackSaver]'s `Saver` cannot: it runs outside any coroutine scope
 * and has no injected [AuditLogger]. The saver parks a reason in [NavStackRestoreReporter] and
 * `MainNavHost` drains it through here, once, on composition.
 */
@HiltViewModel
class NavRestoreViewModel @Inject constructor(
    private val auditLogger: AuditLogger,
) : ViewModel() {

    /**
     * Logs a pending discard if there is one. Safe to call on every composition: [
     * NavStackRestoreReporter.consume] clears the reason, so one discard produces exactly one row.
     *
     * The payload carries the reason and nothing else. No patient id, no case id, no route names:
     * the stack that was discarded is exactly the thing that may have carried them.
     */
    fun logPendingDiscard() {
        val reason = NavStackRestoreReporter.consume() ?: return
        viewModelScope.launch {
            auditLogger.log(
                action = AuditAction.NAV_STACK_RESTORE_DISCARDED,
                payload = auditPayload("reason" to reason.value),
            )
        }
    }
}
