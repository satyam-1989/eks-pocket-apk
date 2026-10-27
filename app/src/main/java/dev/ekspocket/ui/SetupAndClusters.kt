@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ekspocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.ekspocket.aws.EksCluster
import dev.ekspocket.data.REGIONS
import dev.ekspocket.data.Session
import dev.ekspocket.data.StoredConfig

@Composable
fun SetupScreen(
    initial: StoredConfig?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSave: (StoredConfig) -> Unit,
    onRemoveKeys: (() -> Unit)?,
) {
    var accessKey by rememberSaveable { mutableStateOf(initial?.accessKeyId.orEmpty()) }
    var secretKey by rememberSaveable { mutableStateOf(initial?.secretAccessKey.orEmpty()) }
    var sessionToken by rememberSaveable { mutableStateOf(initial?.sessionToken.orEmpty()) }
    var region by rememberSaveable { mutableStateOf(initial?.region ?: "us-east-1") }
    var showSecret by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

    AppScaffold(
        title = if (initial == null) "Connect to AWS" else "AWS keys",
        onBack = if (canGoBack) onBack else null,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Your keys are encrypted with the Android Keystore and only used to sign requests to AWS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = accessKey,
                onValueChange = { accessKey = it.trim() },
                label = { Text("Access key ID") },
                singleLine = true,
                keyboardOptions = keyboard.copy(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secretKey,
                onValueChange = { secretKey = it.trim() },
                label = { Text("Secret access key") },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showSecret = !showSecret }) {
                        Text(if (showSecret) "Hide" else "Show")
                    }
                },
                keyboardOptions = keyboard,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = sessionToken,
                onValueChange = { sessionToken = it.trim() },
                label = { Text("Session token (only for temporary keys)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = keyboard,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Region", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SelectorButton(region, REGIONS) { region = it }
            }
            Button(
                onClick = {
                    onSave(StoredConfig(accessKey, secretKey, sessionToken.ifBlank { null }, region))
                },
                enabled = accessKey.isNotBlank() && secretKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save and show clusters") }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Use a dedicated IAM user rather than admin keys. It needs eks:ListClusters and " +
                        "eks:DescribeCluster, plus an EKS access entry on each cluster with only the " +
                        "Kubernetes permissions you want from your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp),
                )
            }

            if (onRemoveKeys != null) {
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove saved keys") }
            }
        }
    }

    if (confirmRemove && onRemoveKeys != null) {
        ConfirmDialog(
            title = "Remove saved keys?",
            text = "The keys will be deleted from this phone. You'll need to enter them again to connect.",
            confirmLabel = "Remove",
            onConfirm = { confirmRemove = false; onRemoveKeys() },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
fun ClustersScreen(
    session: Session,
    onOpen: (EksCluster) -> Unit,
    onSettings: () -> Unit,
) {
    val region = session.region
    val loader = rememberLoader(region, session.config) {
        val eks = session.eks()
        eks.listClusters().map { eks.describeCluster(it) }
    }

    AppScaffold(
        title = "Clusters",
        actions = {
            SelectorButton(region, REGIONS) { session.setRegion(it) }
            IconButton(onClick = { loader.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "AWS keys")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loader.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loader.error?.let { ErrorCard(it, onRetry = { loader.reload() }) }
            val list = loader.data
            if (list != null && list.isEmpty()) {
                EmptyState("No EKS clusters in $region. Pick another region above.")
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list.orEmpty(), key = { it.arn.ifBlank { it.name } }) { c ->
                    ClusterCard(c) { onOpen(c) }
                }
            }
        }
    }
}

@Composable
private fun ClusterCard(c: EksCluster, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                c.name,
                fontFamily = Mono,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusPill(c.status)
                Text(
                    "Kubernetes ${c.version}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val access = when {
                c.publicAccess && c.privateAccess -> "Public and private endpoint"
                c.publicAccess -> "Public endpoint"
                else -> "Private endpoint only. Connect a VPN into the VPC to manage it."
            }
            Text(
                access,
                style = MaterialTheme.typography.bodySmall,
                color = if (c.publicAccess) MaterialTheme.colorScheme.onSurfaceVariant else StatusColors.warn,
            )
        }
    }
}
