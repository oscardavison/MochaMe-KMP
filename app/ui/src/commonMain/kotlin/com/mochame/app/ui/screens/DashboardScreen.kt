package com.mochame.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mochame.core.design.AppLogo
import com.mochame.utils.interfaces.MochaTimeUtils

@Composable
fun DashboardScreen(
    onNavigateToBio: (epochDay: Long) -> Unit,
    modifier: Modifier = Modifier,
    timeProvider: MochaTimeUtils
) {
    val today = timeProvider.getMochaDay()
    val yesterday = today - 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        DashboardHeader(
            relativeDate = timeProvider.formatRelativeMochaDay(today)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Daily Context",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Track sleep, morning readiness, and napping.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { onNavigateToBio(today) }) {
                        Text("Log Today")
                    }
                    OutlinedButton(onClick = { onNavigateToBio(yesterday) }) {
                        Text("Edit Yesterday")
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    relativeDate: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        AppLogo(modifier = Modifier.size(46.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "MochaMe",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = relativeDate,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}