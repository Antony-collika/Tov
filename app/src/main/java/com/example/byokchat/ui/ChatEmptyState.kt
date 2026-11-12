package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Empty chat.
 * - no provider yet: a card "No AI connected";
 * - provider but no model chosen: a card "Choose a model";
 * - otherwise just the greeting "Hi, Welcome" (no suggestions).
 */
@Composable
fun ChatEmptyState(
    noProvider: Boolean,
    noModel: Boolean,
    onConnect: () -> Unit,
    onPickModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            noProvider -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No AI connected yet", style = MaterialTheme.typography.titleMedium)
                    Text("Connect an AI (OpenAI, Gemini, or a compatible server...) to start chatting.")
                    Button(onClick = onConnect) { Text("Connect AI") }
                }
            }
            noModel -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose a model", style = MaterialTheme.typography.titleMedium)
                    Text("Pick a model so the AI can answer you.")
                    Button(onClick = onPickModel) { Text("Choose model") }
                }
            }
            else -> Text(
                "Hi, Welcome",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
