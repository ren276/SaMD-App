package com.example.samdapp.domain.slm

/**
 * The two scope gates of `scratchpad/slm-guardrail-service-contract-memo.md` §5.4, as code.
 *
 * **The gate is code, never the prompt.** Justification is harness finding F4, measured rather
 * than assumed: this artifact refuses nothing on its own, so a system prompt saying "only answer
 * questions about the record" is a request to a model observed not to honour that class of
 * request. This file therefore counts as a control; nothing inside the model does.
 *
 * **This is a coarse deterministic instrument and it is chosen knowing that.** It will produce
 * false refusals (a legitimate readback phrased unusually) and false passes (a drug name whose
 * shape it does not recognise). It is accepted because the alternative, a model judging its own
 * scope, has been measured not to work, and because the failure direction of a coarse
 * deterministic gate is refusal, which is the safe direction here (§5.4). Every list below is
 * openly incomplete; widening one is a normal maintenance change, and the output gate is the
 * second line for what the input gate lets through.
 *
 * Both gates are WORKER-tier controls. `CadreTier.PHYSICIAN` bypasses the input gate (§5.3, open
 * querying) - see [SlmReadbackUseCase] for what a physician still gets.
 */

/**
 * Character budget for one question (§4.4). Not a measured number: §4.4 says the build session
 * sets the budgets from measurement, and no generation measurement exists yet because the engine
 * is unbound. What is fixed here is that the bound exists and is enforced in one place before the
 * call. A readback question is short by nature; this is roughly three long sentences, and the
 * cost of it being wrong is a refusal, not a truncated prompt.
 */
internal const val MAX_QUESTION_CHARS = 500

/**
 * Budget for the whole assembled prompt (§4.4, "snapshot over the token budget"). An oversized
 * record silently truncated inside the prompt is a record the model answers about incompletely
 * with no signal that it did, which is why this is a hard reject rather than a trim. Character
 * count stands in for a token count until a tokenizer exists to measure with; it is deliberately
 * conservative.
 */
internal const val MAX_PROMPT_CHARS = 6000

/** Hard output bound handed to the engine (§9.2). Harness F5 ran to about 2000 tokens on an empty
 *  prompt; a plain-language readback of one prescription needs a fraction of that. */
internal const val MAX_OUTPUT_TOKENS = 512

private val TOKEN = Regex("[a-z]+|[0-9]+(?:\\.[0-9]+)?")

/** Lowercased word and numeral tokens. Punctuation and case are noise for every check here. */
internal fun tokensOf(text: String): Set<String> =
    TOKEN.findAll(text.lowercase()).map { it.value }.toSet()

/**
 * Word endings that make a token drug-shaped. Coarse on purpose: the point is not to name every
 * drug, it is to notice that a token which looks like a drug name is not in the record.
 * `"done"`-style endings that collide with ordinary English are excluded - a false refusal is
 * cheap, but a gate that refuses every sentence is a gate somebody deletes.
 */
private val DRUG_SUFFIXES = listOf(
    "cillin", "pril", "profen", "olol", "statin", "mycin", "azole", "dipine", "sartan",
    "cycline", "oxacin", "tidine", "prazole", "formin", "parin", "caine", "zepam", "triptan",
    "vastatin", "codone", "cephalexin", "thiazide", "semide", "sone", "solone",
)

/**
 * Common Indian PHC drugs whose names do not match any suffix above. Drawn from the shapes the
 * app already handles rather than from a formulary import: a real NLEM lexicon belongs to the
 * classifier's dataset work, not to a gate, and this list is explicitly incomplete. What a name
 * missing from here costs is a false pass at the input gate, which the output gate then has to
 * catch - that layering is the reason both gates exist.
 */
private val DRUG_WORDS = setOf(
    "aspirin", "paracetamol", "acetaminophen", "insulin", "warfarin", "digoxin", "salbutamol",
    "levothyroxine", "diclofenac", "tramadol", "codeine", "heparin", "penicillin", "ceftriaxone",
    "cefixime", "ivermectin", "albendazole", "chloroquine", "metformin", "amlodipine", "ors",
)

internal fun isDrugShaped(token: String): Boolean =
    token in DRUG_WORDS || DRUG_SUFFIXES.any { token.length > it.length && token.endsWith(it) }

/** A numeral is dose-shaped: strengths, counts, frequencies and durations all arrive as digits. */
internal fun isDoseNumeral(token: String): Boolean = token.first().isDigit()

/**
 * Phrasings that ask for a property of a drug or a condition rather than a readback of what the
 * physician decided (§5.2's refused list: interactions, contraindications, alternatives, dosing
 * not in the record, prognosis, "what else could this be"). Checked first and unconditionally: a
 * question carrying one of these is out of scope however well it is tethered, because the answer
 * would be new clinical content and §3 is that this feature adds none.
 */
private val DENIED_PHRASINGS = listOf(
    "safe with", "safe to take", "interaction", "interact", "react with", "reacts with",
    "mix with", "combine", "combined with", "together with", "instead of", "alternative",
    "substitute", "replace", "contraindicat", "allergic", "prognosis", "life expectancy",
    "how long will", "what else could", "could it be", "could this be", "differential",
    "side effect", "adverse", "overdose", "increase the dose", "double the dose",
)

/**
 * Readback-shaped intents (§5.2's allowed list). The question must look like a request to restate
 * or explain what is already in front of the user. A question matching nothing here is refused
 * rather than passed, which is the whole point of an allowlist: an unrecognised shape is not
 * evidence of safety.
 */
private val ALLOWED_INTENTS = listOf(
    "read back", "read this", "read it", "read out", "explain", "what does", "what do",
    "what did", "what is the diagnosis", "what is this", "what has the doctor", "did the doctor",
    "does the doctor", "when should", "when do", "when to", "how do i take", "how should",
    "how many", "how much", "how often", "plain language", "simple words", "in simple",
    "summar", "tell me what", "mean", "dose", "dosage", "after food", "before food",
    "referral", "refer", "prescription", "prescribed", "medicine", "medication", "tablet",
)

/** Everything in the snapshot a question may legitimately be tethered to. */
private fun ApprovedRecordSnapshot.recordText(): String =
    (medicationLines + listOfNotNull(diagnosis) + kernelDecision.name).joinToString(" ")

/**
 * Input scope gate (§5.4, pre-model, WORKER tier). Returns the refusal reason, or null to proceed.
 *
 * Three mechanisms, in the order that makes the reason code most informative:
 * 1. **Phrasing denylist** - asks for a drug or disease property, not a readback.
 * 2. **Intent allowlist** - does not look like a readback request at all.
 * 3. **Entity containment** - names a drug or a dose that is not in the approved record, so there
 *    is nothing in front of the user that the answer could come from.
 *
 * On a refusal the engine is never called (§5.2's worked case: "is ibuprofen safe with
 * lisinopril" is refused here, before the model is loaded, so nothing is generated and there is no
 * answer to leak).
 */
internal fun inputScopeRefusal(invocation: SlmInvocation): SlmRefusal? {
    val question = invocation.question.lowercase()

    if (DENIED_PHRASINGS.any { it in question }) return SlmRefusal.OUT_OF_SCOPE_PHRASING
    if (ALLOWED_INTENTS.none { it in question }) return SlmRefusal.OUT_OF_SCOPE_INTENT

    val record = tokensOf(invocation.snapshot.recordText())
    val untethered = tokensOf(question).any { (isDrugShaped(it) || isDoseNumeral(it)) && it !in record }
    return if (untethered) SlmRefusal.OUT_OF_SCOPE_UNTETHERED else null
}

/**
 * Output scope gate (§5.4, post-model, WORKER tier). True when the generation is grounded in the
 * snapshot: it introduces no drug name absent from the record's medication lines and no dosing
 * numeral absent from the record.
 *
 * **Whole or nothing.** This function answers a yes/no question and returns no edited text,
 * because §5.4 requires that a violating output is suppressed entirely and §6.2 forbids
 * meaning-level rewriting of generated clinical text. There is deliberately no "clean this up"
 * variant to reach for: editing generated clinical prose into compliance is how a hedge the model
 * volunteered (F3) gets stripped and how a half-answer survives a refusal.
 *
 * Note the interaction with the H-17 prescription gate: a REJECTed case read by a worker carries
 * no medication lines at all, so any drug name in the output is ungrounded and the whole output is
 * suppressed. That is the intended behaviour, not an edge case.
 */
internal fun outputIsGrounded(output: String, snapshot: ApprovedRecordSnapshot): Boolean {
    val drugGround = tokensOf(snapshot.medicationLines.joinToString(" "))
    val numeralGround = tokensOf(snapshot.recordText())

    return tokensOf(output).none { token ->
        (isDrugShaped(token) && token !in drugGround) || (isDoseNumeral(token) && token !in numeralGround)
    }
}
