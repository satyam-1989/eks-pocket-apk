@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ekspocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ekspocket.Nav
import dev.ekspocket.Screen
import dev.ekspocket.aws.EksCluster
import dev.ekspocket.data.Session
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
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
            TabRow(selectedTabIndex = tab) {
                listOf("Pods", "Deployments", "Services", "Nodes").forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            when (tab) {
                0 -> PodsTab(k8s, ns, nav)
                1 -> DeploymentsTab(k8s, ns, nav, actions)
                2 -> ServicesTab(k8s, ns, nav, actions)
                else -> NodesTab(k8s, nav, actions)
            }
        }
    }
}

@Composable
private fun NamespacePicker(selected: String?, namespaces: List<String>?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    Box {
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
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 460.dp),
        ) {
            DropdownMenuItem(text = { Text("All namespaces") }, onClick = { onSelect(null); open = false })
            DropdownMenuItem(text = { Text("Type a namespace") }, onClick = { typing = true; open = false })
            HorizontalDivider()
            if (namespaces == null) {
                DropdownMenuItem(text = { Text("Namespaces not listed yet") }, onClick = {}, enabled = false)
            }
            namespaces.orEmpty().forEach { n ->
                DropdownMenuItem(
                    text = { Text(n, fontFamily = Mono) },
                    onClick = { onSelect(n); open = false },
                )
            }
        }
    }
    if (typing) {
        var value by remember { mutableStateOf(selected.orEmpty()) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Namespace") },
            text = {
                OutlinedTextField(value = value, onValueChange = { value = it.trim() }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = value.isNotBlank(),
                    onClick = { onSelect(value); typing = false },
                ) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PodsTab(k8s: K8sClient, ns: String?, nav: Nav) {
    val loader = rememberLoader(k8s, ns) { k8s.pods(ns).sortedBy { it.meta("name") } }
    ResourceList(loader, "No pods here", key = { "${it.meta("namespace")}/${it.meta("name")}" }) { p ->
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
private fun DeploymentsTab(k8s: K8sClient, ns: String?, nav: Nav, actions: Actions) {
    val loader = rememberLoader(k8s, ns) { k8s.deployments(ns).sortedBy { it.meta("name") } }
    var scaling by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }

    ResourceList(loader, "No deployments here", key = { "${it.meta("namespace")}/${it.meta("name")}" }) { d ->
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
private fun ServicesTab(k8s: K8sClient, ns: String?, nav: Nav, actions: Actions) {
    val loader = rememberLoader(k8s, ns) { k8s.services(ns).sortedBy { it.meta("name") } }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }

    ResourceList(loader, "No services here", key = { "${it.meta("namespace")}/${it.meta("name")}" }) { s ->
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
private fun NodesTab(k8s: K8sClient, nav: Nav, actions: Actions) {
    val loader = rememberLoader(k8s) { k8s.nodes().sortedBy { it.meta("name") } }
    ResourceList(loader, "No nodes. Fargate-only clusters don't show EC2 nodes.", key = { it.meta("name") }) { n ->
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
