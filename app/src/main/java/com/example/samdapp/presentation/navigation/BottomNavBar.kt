package com.example.samdapp.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomNavTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    PATIENTS("Patients", Icons.Filled.List),
    REFERRALS("Referrals", Icons.Filled.Send),
    PROFILE("Profile", Icons.Filled.Person),
}

/**
 * The 4 top-level destinations a worker returns to repeatedly. Rendered inside each visible
 * screen's own `Scaffold(bottomBar = ...)` slot (see AppNavHost) — never as a shared/global
 * overlay — so it's structurally absent, not merely hidden, on flow screens like Register or
 * Consultation. [current] is null on [PatientSummary]: that screen shows the bar (it's a
 * landing/review screen, not an in-progress input flow) but isn't itself one of the 4 tab
 * roots, so no tab lights up for it.
 */
@Composable
fun BottomNavBar(current: BottomNavTab?, onSelect: (BottomNavTab) -> Unit) {
    NavigationBar {
        BottomNavTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}
