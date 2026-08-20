package com.example.samdapp.domain.model

/** Which path produced a given [KernelReportOutput] (REQ-HAN-08) — [REAL_INFERENCE] when the
 *  live FastAPI+XGBoost kernel answered, [MOCK_FALLBACK] when it was unreachable and the
 *  build's [com.example.samdapp.domain.kernel.KernelFallbackSource] substituted a mock scenario
 *  (dev builds only — staging/prod bind a fallback source that never produces this), and
 *  [UNAVAILABLE] when the real call failed and no fallback scenario was produced (staging/prod's
 *  honest failure state — no fabricated diagnosis, no mock). Stamped once, at the exact point
 *  that branch is decided, so it can never drift out of sync with which path actually ran.
 *  Strengthens the existing H-09 control ("kernel unavailable/mocked mistaken for real") by
 *  making the distinction a queryable, per-record fact instead of only a Logcat line. */
enum class InferenceSource { REAL_INFERENCE, MOCK_FALLBACK, UNAVAILABLE }
