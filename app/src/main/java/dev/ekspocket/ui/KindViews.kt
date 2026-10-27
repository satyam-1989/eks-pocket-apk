@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ekspocket.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ekspocket.Nav
import dev.ekspocket.Screen
import dev.ekspocket.data.Session
import dev.ekspocket.k8s.K8sClient
import dev.ekspocket.k8s.Kind
import dev.ekspocket.k8s.age
import dev.ekspocket.k8s.meta
import dev.ekspocket.k8s.summarize
import org.json.JSONObject
import java.util.Base64

// ---------------- Generic list for any kind ----------------

@Composable
fun GenericTab(k8s: K8sClient, kind: Kind, ns: String?, nav: Nav, actions: Actions, filter: NameFilter) {
    val effectiveNs = if (kind.namespaced) ns else null
    val loader = rememberLoader(k8s, kind, effectiveNs) {
        k8s.list(kind.listPath(effectiveNs))
            .sortedWith(compareBy<JSONObject> { it.meta("namespace") }.thenBy { it.meta("name") })
    }
    var scaling by remember(kind) { mutableStateOf<JSONObject?>(null) }
    var deleting by remember(kind) { mutableStateOf<JSONObject?>(null) }
    val singular = kind.kind.lowercase()

    ResourceList(
        loader,
        emptyText = if (kind.namespaced && ns != null) "No ${kind.label} in $ns" else "No ${kind.label}",
        key = { "${it.meta("namespace")}/${it.meta("name")}" },
        filter = filter,
        fields = { listOf(it.meta("name"), if (kind.namespaced && ns == null) it.meta("namespace") else null) },
    ) { obj ->
        val name = obj.meta("name")
        val objNs = obj.meta("namespace").ifBlank { null }
        val path = kind.itemPath(objNs, name)
        val summary = summarize(kind, obj)
        val facts = buildList {
            if (kind.namespaced && ns == null && objNs != null) add(objNs)
            addAll(summary.facts)
            add(age(obj.meta("creationTimestamp")))
        }
        val editNs = objNs ?: ns ?: "default"
        val open: () -> Unit = if (kind.isSecret) {
            { nav.push(Screen.SecretView(editNs, name)) }
        } else {
            { nav.push(Screen.Yaml("$singular/$name", path, editNs)) }
        }
        ResourceRow(
            title = name,
            facts = facts,
            status = summary.status,
            onClick = open,
            menu = { close ->
                if (kind.scalable) {
                    DropdownMenuItem(text = { Text("Scale") }, onClick = { close(); scaling = obj })
                }
                if (kind.restartable) {
                    DropdownMenuItem(text = { Text("Restart") }, onClick = {
                        close()
                        actions.run("Restart $name", after = { loader.reload() }) {
                            k8s.restartAt(path)
                            "Restarted $name"
                        }
                    })
                }
                if (kind.isSecret) {
                    DropdownMenuItem(text = { Text("View values") }, onClick = { close(); open() })
                }
                DropdownMenuItem(text = { Text("Edit YAML") }, onClick = {
                    close(); nav.push(Screen.Yaml("$singular/$name", path, editNs))
                })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); deleting = obj })
            },
        )
    }

    scaling?.let { obj ->
        val name = obj.meta("name")
        val path = kind.itemPath(obj.meta("namespace").ifBlank { null }, name)
        ScaleDialog(
            name = name,
            current = obj.optJSONObject("spec")?.optInt("replicas", 1) ?: 1,
            onConfirm = { n ->
                scaling = null
                actions.run("Scale $name", after = { loader.reload() }) {
                    k8s.scaleAt(path, n)
                    "Scaled $name to $n"
                }
            },
            onDismiss = { scaling = null },
        )
    }
    deleting?.let { obj ->
        val name = obj.meta("name")
        val objNs = obj.meta("namespace").ifBlank { null }
        val path = kind.itemPath(objNs, name)
        ConfirmDialog(
            title = "Delete $singular?",
            text = if (objNs != null) "$name in $objNs will be deleted." else "$name will be deleted. It's cluster-wide.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                actions.run("Delete $name", after = { loader.reload() }) {
                    k8s.delete(path)
                    "Deleted $name"
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

// ---------------- One kind on its own screen (used by "Other kinds") ----------------

@Composable
fun KindListScreen(session: Session, kind: Kind, initialNs: String?, nav: Nav) {
    val k8s = session.k8s ?: return
    var ns by rememberSaveable { mutableStateOf(initialNs) }
    val nsLoader = rememberLoader(k8s) { k8s.namespaces() }
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(snackbar)
    var filterText by rememberSaveable { mutableStateOf("") }
    var filterRegex by rememberSaveable { mutableStateOf(false) }
    val filter = remember(filterText, filterRegex) { NameFilter(filterText, filterRegex) }

    AppScaffold(
        title = kind.label,
        subtitle = if (kind.namespaced) kind.group else "${kind.group}, cluster-wide",
        onBack = { nav.pop() },
        snackbar = snackbar,
        actions = {
            if (kind.namespaced) NamespacePicker(ns, nsLoader.data) { ns = it }
        },
        fab = {
            ExtendedFloatingActionButton(
                onClick = { nav.push(Screen.Yaml("Apply YAML", null, ns ?: "default")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Apply YAML") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            FilterBar(
                text = filterText,
                regex = filterRegex,
                error = filter.error,
                onText = { filterText = it },
                onRegex = { filterRegex = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            GenericTab(k8s, kind, ns, nav, actions, filter)
        }
    }
}

// ---------------- Browse every kind the cluster serves ----------------

@Composable
fun OtherKindsScreen(session: Session, ns: String?, nav: Nav) {
    val k8s = session.k8s ?: return
    val loader = rememberLoader(k8s) { k8s.discoverKinds() }
    var query by rememberSaveable { mutableStateOf("") }
    var regex by rememberSaveable { mutableStateOf(false) }
    val filter = remember(query, regex) { NameFilter(query, regex) }

    AppScaffold(
        title = "All resource kinds",
        subtitle = k8s.cluster.name,
        onBack = { nav.pop() },
        actions = {
            IconButton(onClick = { loader.reload() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            FilterBar(
                text = query,
                regex = regex,
                error = filter.error,
                onText = { query = it },
                onRegex = { regex = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (loader.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loader.error?.let { ErrorCard(it, onRetry = { loader.reload() }) }
            val shown = loader.data.orEmpty().filter { filter.matches(listOf(it.kind, it.plural, it.group)) }
            if (loader.data != null && shown.isEmpty()) EmptyState("No kinds match \"$query\"")
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { "${it.apiPath}/${it.plural}" }) { kind ->
                    ResourceRow(
                        title = kind.kind,
                        facts = listOf(kind.group, if (kind.namespaced) "namespaced" else "cluster-wide"),
                        status = null,
                        onClick = { nav.push(Screen.KindList(kind, ns)) },
                    )
                }
            }
        }
    }
}

// ---------------- Secret values, masked until revealed ----------------

private fun copySensitive(context: Context, label: String, value: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    // Tells Android to hide the value in the clipboard preview.
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    cm.setPrimaryClip(clip)
}

@Composable
fun SecretScreen(session: Session, ns: String, name: String, nav: Nav) {
    val k8s = session.k8s ?: return
    SecureWindow()
    val path = "/api/v1/namespaces/$ns/secrets/$name"
    val loader = rememberLoader(path) { k8s.getJson(path) }
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(snackbar)
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    AppScaffold(
        title = name,
        subtitle = "Secret in $ns",
        onBack = { nav.pop() },
        snackbar = snackbar,
        actions = {
            IconButton(onClick = { revealed = emptySet(); loader.reload() }) { Icon(Icons.Filled.Refresh, "Refresh") }
            IconButton(onClick = { nav.push(Screen.Yaml("secret/$name", path, ns)) }) {
                Icon(Icons.Filled.Edit, "Edit YAML")
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete secret") }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loader.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            loader.error?.let { e -> item { ErrorCard(e, onRetry = { loader.reload() }) } }
            val secret = loader.data
            if (secret != null) {
                val data = secret.optJSONObject("data") ?: JSONObject()
                val keys = data.keys().asSequence().toList().sorted()
                item {
                    Column {
                        InfoRow("Type", secret.optString("type"))
                        InfoRow("Age", age(secret.meta("creationTimestamp")))
                        Text(
                            "Values are hidden until you tap Reveal. Screenshots are blocked on this screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                if (keys.isEmpty()) item { EmptyState("This secret has no data") }
                items(keys, key = { it }) { key ->
                    val bytes = runCatching { Base64.getDecoder().decode(data.optString(key)) }
                        .getOrDefault(ByteArray(0))
                    val text = String(bytes, Charsets.UTF_8)
                    SecretValueCard(
                        key = key,
                        value = text,
                        size = bytes.size,
                        binary = text.contains('\uFFFD'),
                        revealed = key in revealed,
                        onToggle = { revealed = if (key in revealed) revealed - key else revealed + key },
                        onCopy = { copySensitive(context, key, text) },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete secret?",
            text = "$name in $ns will be deleted. Pods that use it may fail to start.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                actions.run("Delete secret", after = { nav.pop() }) {
                    k8s.delete(path)
                    "Deleted $name"
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SecretValueCard(
    key: String,
    value: String,
    size: Int,
    binary: Boolean,
    revealed: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    key,
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!binary) {
                    TextButton(onClick = onToggle) { Text(if (revealed) "Hide" else "Reveal") }
                    TextButton(onClick = onCopy) { Text("Copy") }
                }
            }
            Text(
                when {
                    binary -> "Binary data, $size bytes"
                    revealed -> value
                    else -> "••••••••  ($size bytes)"
                },
                fontFamily = Mono,
                style = MaterialTheme.typography.bodySmall,
                color = if (revealed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (revealed) 40 else 1,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}
