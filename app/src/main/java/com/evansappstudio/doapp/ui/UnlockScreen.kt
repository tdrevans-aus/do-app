package com.evansappstudio.doapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.billing.UnlockUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    state: UnlockUiState,
    onBack: () -> Unit,
    onBuy: () -> Unit,
    onRestore: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.unlock_do)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.unlocked) {
                Text(
                    text = stringResource(R.string.unlock_done),
                    style = MaterialTheme.typography.titleLarge,
                )
            } else {
                Text(text = stringResource(R.string.unlock_pitch))
                Text(
                    text = state.price ?: stringResource(R.string.unlock_price_unavailable),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.pending) {
                    Text(text = stringResource(R.string.unlock_pending))
                }
                Button(
                    onClick = onBuy,
                    enabled = state.buyEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.unlock_buy))
                }
            }
            if (state.failed && !state.unlocked) {
                Text(
                    text = stringResource(R.string.unlock_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.unlock_restore))
            }
        }
    }
}
