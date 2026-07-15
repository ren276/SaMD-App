package com.example.samdapp.presentation.common

/** Strips anything that isn't a digit, so a numeric-keyboard field can never end up holding
 * letters — covers paste and hardware-keyboard input, not just the on-screen IME. */
fun filterDigitsOnly(input: String, maxLength: Int = Int.MAX_VALUE): String =
    input.filter(Char::isDigit).take(maxLength)

/** Same idea for decimal fields (weight, height, temperature): digits plus at most one dot. */
fun filterDecimal(input: String): String {
    var seenDot = false
    return buildString {
        for (c in input) {
            when {
                c.isDigit() -> append(c)
                c == '.' && !seenDot -> {
                    append(c)
                    seenDot = true
                }
            }
        }
    }
}
