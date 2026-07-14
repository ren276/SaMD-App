package com.example.samdapp.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(onRegisterNewPatient: () -> Unit) {
    Scaffold { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "PHC Patient Care", style = MaterialTheme.typography.headlineMedium)
            Button(
                onClick = onRegisterNewPatient,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 32.dp),
            ) {
                Text(text = "Register new patient", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
