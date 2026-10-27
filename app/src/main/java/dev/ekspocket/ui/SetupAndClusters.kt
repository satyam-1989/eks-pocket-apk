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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ekspocket.aws.EksCluster
import dev.ekspocket.data.Profile
import dev.ekspocket.data.REGIONS
import dev.ekspocket.data.Session

// ---------------- Add / edit profile ----------------

@Composable
fun EditProfileScreen(
    existing: Profile?,
    isFirst: Boolean,
    canGoBack: Boolean,
    nameTaken: (String) -> Boolean,
    onBack: () -> Unit,
    onSave: (Profile) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: if (isFirst) "default" else "") }
    var accessKey by rememberSaveable { mutableStateOf(existing?.accessKeyId.orEmpty()) }
    var secretKey by rememberSaveable { mutableStateOf(existing?.secretAccessKey.orEmpty()) }
    var sessionToken by rememberSaveable { mutableStateOf(existing?.sessionToken.orEmpty()) }
    var region by rememberSaveable { mutableStateOf(existing?.region ?: "us-east-1") }
    var showSecret by remember { mutableStateOf(false) }

    val nameError = if (name.isNotBlank() && nameTaken(name)) "Another profile already uses this name" else null
    val canSave = name.isNotBlank() && nameError == null && accessKey.isNotBlank() && secretKey.isNotBlank()
    val keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

    AppScaffold(
        title = when {
            isFirst -> "Connect to AWS"
            existing == null -> "Add profile"
            else -> "Edit ${existing.name}"
        },
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
                "Keys are encrypted with the Android Keystore and only used to sign requests to AWS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Profile name") },
                placeholder = { Text("prod, staging, client-a") },
                singleLine = true,
                isError = nameError != null,
                supportingText = { if (nameError != null) Text(nameError) },
                modifier = Modifier.fillMaxWidth(),
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
                    onSave(
                        Profile(
                            id = existing?.id ?: Profile.newId(),
                            name = name.trim(),
                            accessKeyId = accessKey,
                            secretAccessKey = secretKey,
                            sessionToken = sessionToken.ifBlank { null },
                            region = region,
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save and use this profile") }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Use a dedicated IAM user per account rather than admin keys. It needs " +
                        "eks:ListClusters and eks:DescribeCluster, plus an EKS access entry on each " +
                        "cluster with only the Kubernetes permissions you want from your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

// ---------------- Profile list ----------------

@Composable
fun ProfilesScreen(
    session: Session,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Profile) -> Unit,
    onUse: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
) {
    var deleting by remember { mutableStateOf<Profile?>(null) }
    val activeId = session.active?.id

    AppScaffold(
        title = "Profiles",
        onBack = onBack,
        fab = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add profile") },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Tap a profile to switch to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(session.state.profiles, key = { it.id }) { p ->
                ResourceRow(
                    title = p.name,
                    facts = listOfNotNull(
                        p.maskedKey,
                        p.region,
                        if (p.sessionToken != null) "temporary" else null,
                    ),
                    status = if (p.id == activeId) "Active" else null,
                    onClick = { onUse(p) },
                    menu = { close ->
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { close(); onEdit(p) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); deleting = p })
                    },
                )
            }
        }
    }

    deleting?.let { p ->
        val last = session.state.profiles.size == 1
        ConfirmDialog(
            title = "Delete ${p.name}?",
            text = if (last) {
                "This is your only profile. Its keys will be removed from this phone and you'll need to add a profile again."
            } else {
                "Its keys will be removed from this phone."
            },
            confirmLabel = "Delete",
            onConfirm = { deleting = null; onDelete(p) },
            onDismiss = { deleting = null },
        )
    }
}

// ---------------- Clusters ----------------

@Composable
fun ClustersScreen(
    session: Session,
    onOpen: (EksCluster) -> Unit,
    onManageProfiles: () -> Unit,
) {
    val profile = session.active
    val region = session.region
    val loader = rememberLoader(profile) {
        val eks = session.eks()
        eks.listClusters().map { eks.describeCluster(it) }
    }

    AppScaffold(
        title = "Clusters",
        actions = {
            IconButton(onClick = { loader.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onManageProfiles) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "Profiles")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfilePicker(session, onManageProfiles)
                Spacer(Modifier.weight(1f))
                SelectorButton(region, REGIONS) { session.setRegion(it) }
            }
            if (loader.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loader.error?.let { ErrorCard(it, onRetry = { loader.reload() }) }
            val list = loader.data
            if (list != null && list.isEmpty()) {
                EmptyState("No EKS clusters for ${profile?.name ?: "this profile"} in $region. Try another region.")
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
private fun ProfilePicker(session: Session, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = session.active
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                active?.name ?: "No profile",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch profile")
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 440.dp),
        ) {
            session.state.profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    trailingIcon = {
                        if (p.id == active?.id) Icon(Icons.Filled.Check, contentDescription = "Active")
                    },
                    onClick = {
                        session.switchTo(p.id)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage profiles") },
                onClick = {
                    open = false
                    onManage()
                },
            )
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
