package com.example.samdapp.presentation.report

/**
 * Minimal, dependency-free Code 128 (subset B) encoder — enough to render a real, scannable
 * barcode of the Patient UID onto a Canvas (REQ-RPT-02 header). No third-party barcode library is
 * added; the human-readable UID is always printed beneath the bars, so the artifact is never
 * ambiguous even to a reader without a scanner.
 *
 * Subset B covers ASCII 32–126, which is a superset of the 62-char alphanumeric Patient UID
 * alphabet, so no code-set switching is needed.
 */
object Code128 {

    /** One [Bar]/space unit: [isBar] true = draw black, false = skip; [modules] is its width in modules. */
    data class Bar(val isBar: Boolean, val modules: Int)

    private const val START_B = 104
    private const val STOP = 106

    // Canonical Code 128 symbol table: value 0..106, each 6 module-widths (Stop is 7).
    private val PATTERNS = listOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212",
        "221213", "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221",
        "223211", "221132", "221231", "213212", "223112", "312131", "311222", "321122", "321221",
        "312212", "322112", "322211", "212123", "212321", "232121", "111323", "131123", "131321",
        "112313", "132113", "132311", "211313", "231113", "231311", "112133", "112331", "132131",
        "113123", "113321", "133121", "313121", "211331", "231131", "213113", "213311", "213131",
        "311123", "311321", "331121", "312113", "312311", "332111", "314111", "221411", "431111",
        "111224", "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
        "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111", "111242",
        "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311",
        "113141", "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
    )

    /** Encodes [data] as an alternating bar/space list, always starting with a bar. Returns an
     *  empty list if [data] contains any character outside ASCII 32–126. */
    fun encodeB(data: String): List<Bar> {
        if (data.isEmpty()) return emptyList()
        val values = mutableListOf(START_B)
        var checksum = START_B
        data.forEachIndexed { i, ch ->
            val v = ch.code - 32
            if (v !in 0..94) return emptyList()
            values.add(v)
            checksum += v * (i + 1)
        }
        values.add(checksum % 103)
        values.add(STOP)

        val bars = mutableListOf<Bar>()
        var moduleIndex = 0
        values.forEach { value ->
            PATTERNS[value].forEach { widthChar ->
                bars.add(Bar(isBar = moduleIndex % 2 == 0, modules = widthChar - '0'))
                moduleIndex++
            }
        }
        return bars
    }

    /** Total module count of an encoded [bars] sequence — for sizing the drawn barcode to a width. */
    fun totalModules(bars: List<Bar>): Int = bars.sumOf { it.modules }
}
