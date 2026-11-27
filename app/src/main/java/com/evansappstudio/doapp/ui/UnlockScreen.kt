package com.evansappstudio.doapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.billing.PaywallCopy
import com.evansappstudio.doapp.billing.PaywallTemplates
import com.evansappstudio.doapp.billing.PaywallText
import com.evansappstudio.doapp.billing.SubscriptionUiState

/** The paywall copy templates from strings.xml, for [PaywallCopy.build]. */
@Composable
fun paywallTemplates(): PaywallTemplates = PaywallTemplates(
    introPrice = stringResource(R.string.paywall_price_intro),
    thenRenewal = stringResource(R.string.paywall_price_then),
    renewalOnly = stringResource(R.string.paywall_price_renewal),
    loading = stringResource(R.string.paywall_price_loading),
    smallPrint = stringResource(R.string.paywall_small_print),
    get = stringResource(R.string.paywall_get),
    renew = stringResource(R.string.paywall_renew),
    keepFree = stringResource(R.string.paywall_keep_free),
    active = stringResource(R.string.paywall_active),
    pending = stringResource(R.string.paywall_pending),
    expired = stringResource(R.string.paywall_expired),
    unavailable = stringResource(R.string.paywall_store_unavailable),
)

/**
 * The Do Unlimited paywall (design pass paywall screen). Prices come only from
 * [SubscriptionUiState.price]; [PaywallCopy] picks the text. "Keep 5 for free"
 * closes the screen. With an active subscription it shows Manage subscription.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    state: SubscriptionUiState,
    onBack: () -> Unit,
    onBuy: () -> Unit,
    onRestore: () -> Unit,
    onManage: () -> Unit,
) {
    val text: PaywallText = PaywallCopy.build(state, paywallTemplates())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sub_name)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.active) {
                Text(
                    text = stringResource(R.string.paywall_active),
                    style = MaterialTheme.typography.titleLarge,
                )
                Button(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.paywall_manage))
                }
            } else {
                Text(
                    text = stringResource(R.string.paywall_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(text = stringResource(R.string.paywall_line))
                for (tick in listOf(
                    R.string.paywall_tick_unlimited,
                    R.string.paywall_tick_free,
                    R.string.paywall_tick_nag,
                )) {
                    Text(text = "✓ " + stringResource(tick))
                }
                text.statusLine?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                Text(text = text.priceHeadline, style = MaterialTheme.typography.titleMedium)
                text.priceDetail?.let { Text(text = it) }
                Button(
                    onClick = onBuy,
                    enabled = text.buyEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text.buyLabel)
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text.keepFree)
                }
                text.smallPrint?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.failed && !state.active) {
                Text(
                    text = stringResource(R.string.paywall_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.paywall_restore))
            }
        }
    }
}
