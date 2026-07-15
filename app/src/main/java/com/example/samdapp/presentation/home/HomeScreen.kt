package com.example.samdapp.presentation.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.samdapp.R

@Composable
fun HomeScreen(onRegisterNewPatient: () -> Unit) {
    Scaffold { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(120.dp).padding(bottom = 16.dp),
            )
            Text(text = "PHC Patient Care", style = MaterialTheme.typography.headlineMedium)
            Button(
                onClick = onRegisterNewPatient,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(1f).padding(top = 32.dp),
            ) {
                Text(text = "Register new patient", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
