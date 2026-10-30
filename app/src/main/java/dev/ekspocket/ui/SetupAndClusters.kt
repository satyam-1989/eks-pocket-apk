@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ekspocket.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ekspocket.cloud.Cluster
import dev.ekspocket.cloud.GoogleAuth
import dev.ekspocket.cloud.ServiceAccountTokenSource
import dev.ekspocket.data.Profile
import dev.ekspocket.data.ProfileType
import dev.ekspocket.k8s.detailsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
    var type by rememberSaveable { mutableStateOf(existing?.type ?: ProfileType.AWS) }
    // AWS
    var accessKey by rememberSaveable { mutableStateOf(existing?.accessKeyId.orEmpty()) }
    var secretKey by rememberSaveable { mutableStateOf(existing?.secretAccessKey.orEmpty()) }
    var sessionToken by rememberSaveable { mutableStateOf(existing?.sessionToken.orEmpty()) }
    var region by rememberSaveable { mutableStateOf(existing?.region ?: "us-east-1") }
    var showSecret by remember { mutableStateOf(false) }
    // GCP
    var projectId by rememberSaveable { mutableStateOf(existing?.projectId.orEmpty()) }
    var keyJson by rememberSaveable { mutableStateOf(existing?.gcpKeyJson.orEmpty()) }
    var googleEmail by rememberSaveable { mutableStateOf(existing?.googleEmail.orEmpty()) }
    var signedInEmail by rememberSaveable { mutableStateOf(existing?.googleEmail) }

    val keyInfo = remember(keyJson) {
        if (keyJson.isBlank()) null else runCatching { ServiceAccountTokenSource.inspect(keyJson) }
    }
    val nameError = if (name.isNotBlank() && nameTaken(name)) "Another profile already uses this name" else null
    val canSave = name.isNotBlank() && nameError == null && when (type) {
        ProfileType.AWS -> accessKey.isNotBlank() && secretKey.isNotBlank()
        ProfileType.GCP_KEY -> keyInfo?.isSuccess == true && projectId.isNotBlank()
        ProfileType.GCP_ACCOUNT -> signedInEmail != null && projectId.isNotBlank()
    }
    val keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

    AppScaffold(
        title = when {
            isFirst -> "Add your first profile"
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(ProfileType.AWS to "AWS", ProfileType.GCP_KEY to "GCP key", ProfileType.GCP_ACCOUNT to "Google")
                options.forEachIndexed { i, (t, label) ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Profile name") },
                placeholder = { Text("prod, staging, client-a") },
                singleLine = true,
                isError = nameError != null,
                supportingText = if (nameError != null) {
                    { Text(nameError) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            when (type) {
                ProfileType.AWS -> {
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
                }

                ProfileType.GCP_KEY -> ServiceAccountKeyInput(
                    keyJson = keyJson,
                    keyInfo = keyInfo,
                    onKey = { json ->
                        keyJson = json
                        val info = runCatching { ServiceAccountTokenSource.inspect(json) }.getOrNull()
                        if (info != null && projectId.isBlank()) projectId = info.second
                    },
                )

                ProfileType.GCP_ACCOUNT -> GoogleSignInInput(
                    requestedEmail = googleEmail,
                    onRequestedEmail = { googleEmail = it.trim() },
                    signedInEmail = signedInEmail,
                    onSignedIn = { signedInEmail = it },
                )
            }

            if (type != ProfileType.AWS) {
                OutlinedTextField(
                    value = projectId,
                    onValueChange = { projectId = it.trim() },
                    label = { Text("GCP project ID") },
                    supportingText = { Text("Clusters in all regions and zones of this project are listed.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    val id = existing?.id ?: Profile.newId()
                    onSave(
                        when (type) {
                            ProfileType.AWS -> Profile(
                                id = id, name = name.trim(), type = type,
                                accessKeyId = accessKey, secretAccessKey = secretKey,
                                sessionToken = sessionToken.ifBlank { null }, region = region,
                            )
                            ProfileType.GCP_KEY -> Profile(
                                id = id, name = name.trim(), type = type,
                                projectId = projectId, gcpKeyJson = keyJson,
                            )
                            ProfileType.GCP_ACCOUNT -> Profile(
                                id = id, name = name.trim(), type = type,
                                projectId = projectId, googleEmail = signedInEmail,
                            )
                        }
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
                    when (type) {
                        ProfileType.AWS ->
                            "Use a dedicated IAM user per account rather than admin keys. It needs eks:ListClusters " +
                                "and eks:DescribeCluster, plus an EKS access entry on each cluster."
                        ProfileType.GCP_KEY ->
                            "Use a dedicated service account. It needs roles/container.clusterViewer to list " +
                                "clusters, plus Kubernetes access such as roles/container.developer. Keys don't " +
                                "expire, so revoke it in IAM if this phone is lost."
                        ProfileType.GCP_ACCOUNT ->
                            "Signs in with a Google account on this phone through Google Play services. The " +
                                "account needs Kubernetes Engine access in the project. Setup steps are in the README."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun ServiceAccountKeyInput(
    keyJson: String,
    keyInfo: Result<Pair<String, String>>?,
    onKey: (String) -> Unit,
) {
    val context = LocalContext.current
    var readError by remember { mutableStateOf<String?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text.isNullOrBlank() || text.length > 64_000) {
                readError = "Couldn't read that file"
            } else {
                readError = null
                onKey(text.trim())
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Service account key", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
                Text(if (keyJson.isBlank()) "Choose key file" else "Choose another file")
            }
            TextButton(onClick = { showPaste = !showPaste }) { Text(if (showPaste) "Hide paste box" else "Paste instead") }
        }
        if (showPaste) {
            OutlinedTextField(
                value = keyJson,
                onValueChange = { onKey(it) },
                label = { Text("Key JSON") },
                minLines = 4,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            readError != null -> Text(readError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            keyInfo == null -> Text(
                "Pick the JSON key you downloaded from IAM > Service accounts > Keys.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            keyInfo.isSuccess -> Text(
                "Key for ${keyInfo.getOrNull()?.first}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = Mono,
                color = StatusColors.ok,
            )
            else -> Text(
                keyInfo.exceptionOrNull()?.message ?: "This key can't be used",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GoogleSignInInput(
    requestedEmail: String,
    onRequestedEmail: (String) -> Unit,
    signedInEmail: String?,
    onSignedIn: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val http = remember { OkHttpClient() }

    fun finish(token: String?) {
        if (token == null) {
            busy = false
            error = "Google didn't return an access token"
            return
        }
        scope.launch {
            try {
                val email = withContext(Dispatchers.IO) { GoogleAuth.emailFor(token, http) }
                onSignedIn(email)
                error = null
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            val result = runCatching { GoogleAuth.client(context).getAuthorizationResultFromIntent(data) }
            result.onSuccess { finish(it.accessToken) }
            result.onFailure {
                busy = false
                error = "Sign-in failed: ${it.message}"
            }
        } else {
            busy = false
            error = "Sign-in was cancelled"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = requestedEmail,
            onValueChange = onRequestedEmail,
            label = { Text("Google account (optional)") },
            supportingText = { Text("Fill this in if you have several Google accounts on the phone.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    GoogleAuth.client(context)
                        .authorize(GoogleAuth.request(requestedEmail.ifBlank { null }))
                        .addOnSuccessListener { r ->
                            val pending = r.pendingIntent
                            if (r.hasResolution() && pending != null) {
                                launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                            } else {
                                finish(r.accessToken)
                            }
                        }
                        .addOnFailureListener { e ->
                            busy = false
                            error = "Sign-in failed: ${e.message}. Check the OAuth client setup in the README."
                        }
                },
            ) { Text(if (signedInEmail == null) "Sign in with Google" else "Sign in again") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            signedInEmail != null -> Text(
                "Signed in as $signedInEmail",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = Mono,
                color = StatusColors.ok,
            )
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
                    facts = if (p.isAws) {
                        listOfNotNull("AWS", p.maskedKey, p.region, if (p.sessionToken != null) "temporary" else null)
                    } else {
                        listOf(if (p.type == ProfileType.GCP_KEY) "GCP key" else "Google", p.projectId)
                    },
                    details = detailsOf("Identity" to p.identity),
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
    onOpen: (Cluster) -> Unit,
    onManageProfiles: () -> Unit,
) {
    val profile = session.active
    val region = session.region
    val loader = rememberLoader(profile) { session.listClusters() }

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
                if (profile == null || profile.isAws) {
                    SelectorButton(region, REGIONS) { session.setRegion(it) }
                } else {
                    Text(
                        profile.projectId,
                        fontFamily = Mono,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 12.dp).widthIn(max = 180.dp),
                    )
                }
            }
            if (loader.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loader.error?.let { ErrorCard(it, onRetry = { loader.reload() }) }
            val list = loader.data
            if (list != null && list.isEmpty()) {
                EmptyState(
                    if (profile?.isAws != false) {
                        "No EKS clusters for ${profile?.name ?: "this profile"} in $region. Try another region."
                    } else {
                        "No GKE clusters in project ${profile?.projectId}."
                    }
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list.orEmpty(), key = { it.id }) { c ->
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
private fun ClusterCard(c: Cluster, onClick: () -> Unit) {
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
                    "${c.provider} ${c.version}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    c.location,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val access = c.accessNote ?: when {
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
