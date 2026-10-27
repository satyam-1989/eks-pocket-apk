@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ekspocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ekspocket.Nav
import dev.ekspocket.Screen
import dev.ekspocket.aws.EksCluster
import dev.ekspocket.data.Session
import dev.ekspocket.k8s.BUILTIN_KINDS
import dev.ekspocket.k8s.K8sClient
import dev.ekspocket.k8s.age
import dev.ekspocket.k8s.meta
import dev.ekspocket.k8s.objects
import dev.ekspocket.k8s.podSummary
import org.json.JSONObject

@Composable
fun ClusterHomeScreen(session: Session, cluster: EksCluster, nav: Nav) {
    val connection = remember(cluster) { runCatching { session.connect(cluster) } }
    val k8s = connection.getOrNull()
    if (k8s == null) {
        AppScaffold(title = cluster.name, onBack = { nav.pop() }) { pad ->
            Box(Modifier.padding(pad)) {
                ErrorCard("Couldn't set up a secure connection to this cluster. ${connection.exceptionOrNull()?.message}")
            }
        }
        return
    }

    var ns by rememberSaveable { mutableStateOf<String?>("default") }
    var selected by rememberSaveable { mutableStateOf("pods") }
    val kind = BUILTIN_KINDS.firstOrNull { it.plural == selected } ?: BUILTIN_KINDS.first()
    var filterText by rememberSaveable { mutableStateOf("") }
    var filterRegex by rememberSaveable { mutableStateOf(false) }
    val filter = remember(filterText, filterRegex) { NameFilter(filterText, filterRegex) }
    val nsLoader = rememberLoader(k8s) { k8s.namespaces() }
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(snackbar)

    AppScaffold(
        title = cluster.name,
        subtitle = "Kubernetes ${cluster.version}, ${session.region}",
        onBack = { nav.pop() },
        snackbar = snackbar,
        actions = { NamespacePicker(ns, nsLoader.data) { ns = it } },
        fab = {
            ExtendedFloatingActionButton(
                onClick = { nav.push(Screen.Yaml("Apply YAML", null, ns ?: "default")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Apply YAML") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            KindChips(
                selected = kind.plural,
                onSelect = { selected = it },
                onOther = { nav.push(Screen.OtherKinds(ns)) },
            )
            FilterBar(
                text = filterText,
                regex = filterRegex,
                error = filter.error,
                onText = { filterText = it },
                onRegex = { filterRegex = it },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            when (kind.plural) {
                "pods" -> PodsTab(k8s, ns, nav, filter)
                "deployments" -> DeploymentsTab(k8s, ns, nav, actions, filter)
                "services" -> ServicesTab(k8s, ns, nav, actions, filter)
                "nodes" -> NodesTab(k8s, nav, actions, filter)
                else -> GenericTab(k8s, kind, ns, nav, actions, filter)
            }
        }
    }
}

/** Scrollable row of kind chips, grouped by area, plus a way into every other kind. */
@Composable
private fun KindChips(selected: String, onSelect: (String) -> Unit, onOther: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BUILTIN_KINDS.groupBy { it.section }.forEach { (section, kinds) ->
            Text(
                section.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 2.dp),
            )
            kinds.forEach { k ->
                FilterChip(
                    selected = k.plural == selected,
                    onClick = { onSelect(k.plural) },
                    label = { Text(k.label) },
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        AssistChip(onClick = onOther, label = { Text("Other kinds") })
    }
}

@Composable
fun NamespacePicker(selected: String?, namespaces: List<String>?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Text(
            selected ?: "All namespaces",
            fontFamily = Mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp),
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change namespace")
    }
    if (open) {
        NamespaceDialog(
            selected = selected,
            namespaces = namespaces,
            onSelect = {
                onSelect(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun NamespaceDialog(
    selected: String?,
    namespaces: List<String>?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    val filter = remember(query, regex) { NameFilter(query, regex) }
    val shown = namespaces.orEmpty().filter { filter.matches(listOf(it)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Namespace") },
        text = {
            Column {
                FilterBar(
                    text = query,
                    regex = regex,
                    error = filter.error,
                    onText = { query = it },
                    onRegex = { regex = it },
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (!filter.active) {
                        item { NamespaceRow("All namespaces", selected == null, mono = false) { onSelect(null) } }
                    }
                    if (namespaces == null) {
                        item {
                            Text(
                                "Namespaces couldn't be listed. Type the exact name to use it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    } else if (shown.isEmpty()) {
                        item {
                            Text(
                                "No namespaces match",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(shown, key = { it }) { n ->
                        NamespaceRow(n, n == selected, mono = true) { onSelect(n) }
                    }
                }
                val exact = query.trim()
                val canUseExact = exact.isNotEmpty() && !regex && !exact.startsWith("!") &&
                    namespaces?.contains(exact) != true
                if (canUseExact) {
                    TextButton(onClick = { onSelect(exact) }) {
                        Text("Use \"$exact\"", fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NamespaceRow(label: String, selected: Boolean, mono: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontFamily = if (mono) Mono else null,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PodsTab(k8s: K8sClient, ns: String?, nav: Nav, filter: NameFilter) {
    val loader = rememberLoader(k8s, ns) { k8s.pods(ns).sortedBy { it.meta("name") } }
    ResourceList(
        loader, "No pods here",
        key = { "${it.meta("namespace")}/${it.meta("name")}" },
        filter = filter,
        fields = { listOf(it.meta("name"), if (ns == null) it.meta("namespace") else null) },
    ) { p ->
        val s = podSummary(p)
        val facts = buildList {
            if (ns == null) add(p.meta("namespace"))
            add("${s.ready} ready")
            if (s.restarts > 0) add("${s.restarts} restarts")
            add(age(p.meta("creationTimestamp")))
        }
        ResourceRow(
            title = p.meta("name"),
            facts = facts,
            status = s.phase,
            onClick = { nav.push(Screen.PodDetail(p.meta("namespace"), p.meta("name"))) },
        )
    }
}

@Composable
private fun DeploymentsTab(k8s: K8sClient, ns: String?, nav: Nav, actions: Actions, filter: NameFilter) {
    val loader = rememberLoader(k8s, ns) { k8s.deployments(ns).sortedBy { it.meta("name") } }
    var scaling by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }

    ResourceList(
        loader, "No deployments here",
        key = { "${it.meta("namespace")}/${it.meta("name")}" },
        filter = filter,
        fields = { listOf(it.meta("name"), if (ns == null) it.meta("namespace") else null) },
    ) { d ->
        val dns = d.meta("namespace")
        val name = d.meta("name")
        val path = "/apis/apps/v1/namespaces/$dns/deployments/$name"
        val desired = d.optJSONObject("spec")?.optInt("replicas", 1) ?: 1
        val ready = d.optJSONObject("status")?.optInt("readyReplicas", 0) ?: 0
        val status = when {
            desired == 0 -> "Scaled to 0"
            ready >= desired -> "Available"
            else -> "Progressing"
        }
        ResourceRow(
            title = name,
            facts = listOfNotNull(if (ns == null) dns else null, "$ready/$desired ready", age(d.meta("creationTimestamp"))),
            status = status,
            onClick = { nav.push(Screen.Yaml("deployment/$name", path, dns)) },
            menu = { close ->
                DropdownMenuItem(text = { Text("Scale") }, onClick = { close(); scaling = d })
                DropdownMenuItem(text = { Text("Restart") }, onClick = {
                    close()
                    actions.run("Restart $name", after = { loader.reload() }) {
                        k8s.restartDeployment(dns, name)
                        "Restarted $name"
                    }
                })
                DropdownMenuItem(text = { Text("Edit YAML") }, onClick = {
                    close(); nav.push(Screen.Yaml("deployment/$name", path, dns))
                })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); deleting = d })
            },
        )
    }

    scaling?.let { d ->
        val name = d.meta("name")
        val dns = d.meta("namespace")
        ScaleDialog(
            name = name,
            current = d.optJSONObject("spec")?.optInt("replicas", 1) ?: 1,
            onConfirm = { n ->
                scaling = null
                actions.run("Scale $name", after = { loader.reload() }) {
                    k8s.scale(dns, name, n)
                    "Scaled $name to $n"
                }
            },
            onDismiss = { scaling = null },
        )
    }
    deleting?.let { d ->
        val name = d.meta("name")
        val dns = d.meta("namespace")
        ConfirmDialog(
            title = "Delete deployment?",
            text = "$name and its pods in $dns will be deleted.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                actions.run("Delete $name", after = { loader.reload() }) {
                    k8s.delete("/apis/apps/v1/namespaces/$dns/deployments/$name")
                    "Deleted $name"
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ServicesTab(k8s: K8sClient, ns: String?, nav: Nav, actions: Actions, filter: NameFilter) {
    val loader = rememberLoader(k8s, ns) { k8s.services(ns).sortedBy { it.meta("name") } }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }

    ResourceList(
        loader, "No services here",
        key = { "${it.meta("namespace")}/${it.meta("name")}" },
        filter = filter,
        fields = { listOf(it.meta("name"), if (ns == null) it.meta("namespace") else null) },
    ) { s ->
        val sns = s.meta("namespace")
        val name = s.meta("name")
        val path = "/api/v1/namespaces/$sns/services/$name"
        val spec = s.optJSONObject("spec")
        val ports = spec?.optJSONArray("ports").objects().joinToString(",") { p ->
            val node = p.optInt("nodePort", 0)
            "${p.optInt("port")}" + (if (node > 0) ":$node" else "") + "/${p.optString("protocol", "TCP")}"
        }
        val lb = s.optJSONObject("status")?.optJSONObject("loadBalancer")
            ?.optJSONArray("ingress").objects().firstOrNull()
            ?.let { it.optString("hostname").ifBlank { it.optString("ip") } }
        ResourceRow(
            title = name,
            facts = listOfNotNull(
                if (ns == null) sns else null,
                spec?.optString("type"),
                ports.ifBlank { null },
            ),
            status = null,
            onClick = { nav.push(Screen.Yaml("service/$name", path, sns)) },
            menu = { close ->
                if (!lb.isNullOrBlank()) {
                    DropdownMenuItem(text = { Text(lb, fontFamily = Mono, maxLines = 2) }, onClick = close, enabled = false)
                }
                DropdownMenuItem(text = { Text("Edit YAML") }, onClick = {
                    close(); nav.push(Screen.Yaml("service/$name", path, sns))
                })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); deleting = s })
            },
        )
    }

    deleting?.let { s ->
        val name = s.meta("name")
        val sns = s.meta("namespace")
        ConfirmDialog(
            title = "Delete service?",
            text = "$name in $sns will be deleted. If it's a LoadBalancer, AWS will remove the load balancer too.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                actions.run("Delete $name", after = { loader.reload() }) {
                    k8s.delete("/api/v1/namespaces/$sns/services/$name")
                    "Deleted $name"
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun NodesTab(k8s: K8sClient, nav: Nav, actions: Actions, filter: NameFilter) {
    val loader = rememberLoader(k8s) { k8s.nodes().sortedBy { it.meta("name") } }
    ResourceList(
        loader, "No nodes. Fargate-only clusters don't show EC2 nodes.",
        key = { it.meta("name") },
        filter = filter,
        fields = { listOf(it.meta("name")) },
    ) { n ->
        val name = n.meta("name")
        val ready = n.optJSONObject("status")?.optJSONArray("conditions").objects()
            .firstOrNull { it.optString("type") == "Ready" }?.optString("status") == "True"
        val cordoned = n.optJSONObject("spec")?.optBoolean("unschedulable", false) == true
        val status = when {
            !ready -> "NotReady"
            cordoned -> "Cordoned"
            else -> "Ready"
        }
        val labels = n.optJSONObject("metadata")?.optJSONObject("labels")
        ResourceRow(
            title = name,
            facts = listOfNotNull(
                labels?.optString("node.kubernetes.io/instance-type")?.ifBlank { null },
                n.optJSONObject("status")?.optJSONObject("nodeInfo")?.optString("kubeletVersion"),
                age(n.meta("creationTimestamp")),
            ),
            status = status,
            onClick = { nav.push(Screen.Yaml("node/$name", "/api/v1/nodes/$name", "default")) },
            menu = { close ->
                DropdownMenuItem(
                    text = { Text(if (cordoned) "Uncordon" else "Cordon") },
                    onClick = {
                        close()
                        actions.run(if (cordoned) "Uncordon" else "Cordon", after = { loader.reload() }) {
                            k8s.setUnschedulable(name, !cordoned)
                            if (cordoned) "Uncordoned $name" else "Cordoned $name"
                        }
                    },
                )
                DropdownMenuItem(text = { Text("Edit YAML") }, onClick = {
                    close(); nav.push(Screen.Yaml("node/$name", "/api/v1/nodes/$name", "default"))
                })
            },
        )
    }
}
