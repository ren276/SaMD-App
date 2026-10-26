package com.example.samdapp.domain.slm

/**
 * The 70 HTML tag pieces of the LiteRT-LM tokenizer that the service's HF tokenizer splits
 * differently (on-device SLM memo 5.6, T1; ruling G-5 final). Characterised on the GPU machine
 * (T1_REPORT, G4): the two tokenizers disagree on a prompt if and only if it contains one of these
 * pieces or an `<unusedN>` literal. `<unusedN>` is already refused through [CONTROL_TOKENS]; and so
 * is `<mask>`, so neither is listed here.
 *
 * Kept as data, pinned by `TokenizerDivergentMarkupTest` against the operator-confirmed fixture
 * `litertlm_html_tag_pieces.txt` (sha256 and entry count), in `src/main` because both engines pass
 * through the seam. Matching is exact and case-sensitive: `<B>` is not a piece and does not diverge.
 */
internal val TOKENIZER_DIVERGENT_MARKUP: Set<String> = setOf(
    "</a>", "</b>", "</bbox>", "</blockquote>", "</body>", "</caption>", "</code>", "</div>",
    "</em>", "</footer>", "</h1>", "</h2>", "</h3>", "</h4>", "</h5>", "</h6>", "</html>",
    "</i>", "</iframe>", "</img>", "</li>", "</s>", "</span>", "</strong>", "</sub>", "</sup>",
    "</table>", "</tbody>", "</td>", "</tfoot>", "</th>", "</thead>", "</tr>", "</u>", "</ul>",
    "<a>", "<b>", "<bbox>", "<blockquote>", "<body>", "<caption>", "<code>", "<div>", "<em>",
    "<footer>", "<h1>", "<h2>", "<h3>", "<h4>", "<h5>", "<h6>", "<html>", "<i>", "<iframe>",
    "<img>", "<li>", "<s>", "<span>", "<strong>", "<sub>", "<sup>", "<table>", "<tbody>",
    "<td>", "<tfoot>", "<th>", "<thead>", "<tr>", "<u>", "<ul>",
)

/**
 * The first [TOKENIZER_DIVERGENT_MARKUP] piece that appears as text in [text], or null. Substring
 * membership, exactly like [firstControlTokenIn]. A clinical angle bracket such as `BP <90` or
 * `>=95%` contains no piece and passes.
 */
internal fun firstDivergentMarkupIn(text: String): String? = TOKENIZER_DIVERGENT_MARKUP.firstOrNull { it in text }

/** The `INPUT_REFUSED` audit reason that distinguishes this refusal from [SlmRefusal.PROMPT_CONTROL_TOKENS] (G-5). */
internal const val TOKENIZER_DIVERGENT_MARKUP_AUDIT_REASON = "tokenizer_divergent_markup"
