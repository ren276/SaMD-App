package com.example.samdapp.domain.model

/**
 * The 18 ICD classes SaMDClassifier's symptom model (`symptom_model.json`) is actually trained on
 * — see `symptom_model_meta.json`'s `labels` list. A MODIFY correction must be one of these to be
 * usable in a future training-dataset reimport (see [DiagnosisFeedback] KDoc) — anything else has
 * nowhere to go without a full retraining-pipeline change, which is explicitly out of scope here.
 */
data class TrainedIcdCandidate(val icdCode: String, val diseaseName: String) {
    val label: String get() = "$icdCode — $diseaseName"
}

val TRAINED_ICD_CANDIDATES: List<TrainedIcdCandidate> = listOf(
    TrainedIcdCandidate("A01.0", "Typhoid fever"),
    TrainedIcdCandidate("A09", "Gastroenteritis"),
    TrainedIcdCandidate("A15", "Pulmonary tuberculosis"),
    TrainedIcdCandidate("A90", "Dengue fever"),
    TrainedIcdCandidate("A91", "Dengue haemorrhagic fever / warning signs"),
    TrainedIcdCandidate("A92.0", "Chikungunya"),
    TrainedIcdCandidate("B50", "Severe (falciparum) malaria"),
    TrainedIcdCandidate("B54", "Unspecified malaria"),
    TrainedIcdCandidate("D50", "Iron-deficiency anaemia"),
    TrainedIcdCandidate("E05.9", "Hyperthyroidism"),
    TrainedIcdCandidate("E11", "Type 2 diabetes mellitus"),
    TrainedIcdCandidate("E66", "Obesity"),
    TrainedIcdCandidate("F41.0", "Panic disorder"),
    TrainedIcdCandidate("G43.9", "Migraine"),
    TrainedIcdCandidate("I10", "Essential hypertension"),
    TrainedIcdCandidate("J22", "Acute lower respiratory tract infection"),
    TrainedIcdCandidate("M17", "Osteoarthritis (knee)"),
    TrainedIcdCandidate("N39.0", "Urinary tract infection"),
)
