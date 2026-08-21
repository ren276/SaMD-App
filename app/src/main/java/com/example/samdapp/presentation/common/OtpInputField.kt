@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/**
 * The single 6-digit OTP field, shared by the ABHA login flow
 * ([com.example.samdapp.presentation.abha.AbhaOtpScreen]) and the ABHA create flow
 * ([com.example.samdapp.presentation.abha.AbhaCreateOtpScreen], both of its rounds). Stateless:
 * the caller owns the value, and [label] is the only thing that differs between the three uses.
 *
 * The digit filter lives inside rather than at each call site on purpose. It is the same
 * one-guard-in-the-shared-function reasoning as [filterDigitsOnly]'s other callers: a caller that
 * forgets it lets a paste of `"1 2 3 4 5 6"` through as an eight-character value that then fails a
 * length check for reasons the worker cannot see.
 */
@Composable
fun OtpInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(filterDigitsOnly(it, maxLength = OTP_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Both ABDM OTPs and the mock login OTP are 6 digits. */
const val OTP_LENGTH = 6
