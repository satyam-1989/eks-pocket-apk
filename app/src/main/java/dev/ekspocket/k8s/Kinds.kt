package dev.ekspocket.k8s

import org.json.JSONObject

/** A Kubernetes resource type and how to reach it in the API. */
data class Kind(
    val kind: String,
    val plural: String,
    val apiPath: String,
    val namespaced: Boolean,
    val label: String = kind,
    val section: String = "",
) {
    val group: String
        get() = if (apiPath.startsWith("/apis/")) apiPath.removePrefix("/apis/").substringBefore('/') else "core"

    private val isApps: Boolean get() = apiPath == "/apis/apps/v1"
    val scalable: Boolean get() = isApps && plural in setOf("deployments", "statefulsets", "replicasets")
    val restartable: Boolean get() = isApps && plural in setOf("deployments", "statefulsets", "daemonsets")
    val isSecret: Boolean get() = apiPath == "/api/v1" && plural == "secrets"

    fun listPath(ns: String?): String =
        apiPath + (if (namespaced && ns != null) "/namespaces/$ns" else "") + "/$plural"

    fun itemPath(ns: String?, name: String): String =
        apiPath + (if (namespaced) "/namespaces/${ns ?: "default"}" else "") + "/$plural/$name"
}

private const val CORE = "/api/v1"
private const val APPS = "/apis/apps/v1"
private const val NET = "/apis/networking.k8s.io/v1"
private const val STORAGE = "/apis/storage.k8s.io/v1"
private const val RBAC = "/apis/rbac.authorization.k8s.io/v1"

val BUILTIN_KINDS = listOf(
    Kind("Pod", "pods", CORE, true, "Pods", "Workloads"),
    Kind("Deployment", "deployments", APPS, true, "Deployments", "Workloads"),
    Kind("StatefulSet", "statefulsets", APPS, true, "StatefulSets", "Workloads"),
    Kind("ReplicaSet", "replicasets", APPS, true, "ReplicaSets", "Workloads"),
    Kind("Service", "services", CORE, true, "Services", "Network"),
    Kind("Ingress", "ingresses", NET, true, "Ingresses", "Network"),
    Kind("NetworkPolicy", "networkpolicies", NET, true, "NetworkPolicies", "Network"),
    Kind("ConfigMap", "configmaps", CORE, true, "ConfigMaps", "Config"),
    Kind("Secret", "secrets", CORE, true, "Secrets", "Config"),
    Kind("PersistentVolumeClaim", "persistentvolumeclaims", CORE, true, "PVCs", "Storage"),
    Kind("PersistentVolume", "persistentvolumes", CORE, false, "PVs", "Storage"),
    Kind("StorageClass", "storageclasses", STORAGE, false, "StorageClasses", "Storage"),
    Kind("Role", "roles", RBAC, true, "Roles", "Access"),
    Kind("RoleBinding", "rolebindings", RBAC, true, "RoleBindings", "Access"),
    Kind("ClusterRole", "clusterroles", RBAC, false, "ClusterRoles", "Access"),
    Kind("ClusterRoleBinding", "clusterrolebindings", RBAC, false, "ClusterRoleBindings", "Access"),
    Kind("Node", "nodes", CORE, false, "Nodes", "Cluster"),
)

data class RowSummary(
    val status: String?,
    val facts: List<String>,
    val details: List<Pair<String, String>> = emptyList(),
)

/** Keeps only the label/value pairs that have a non-blank value. */
fun detailsOf(vararg pairs: Pair<String, String?>): List<Pair<String, String>> =
    pairs.mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { label to it } }

/** Container images of a pod spec, comma separated. */
fun imagesOf(podSpec: JSONObject?): String? =
    podSpec?.optJSONArray("containers").objects()
        .mapNotNull { it.optString("image").takeIf { img -> img.isNotBlank() } }
        .joinToString(", ")
        .ifBlank { null }

/** "16073196Ki" -> "15.3 GiB". Leaves other formats as they are. */
fun formatMemory(quantity: String): String {
    val ki = quantity.removeSuffix("Ki").toLongOrNull() ?: return quantity
    if (!quantity.endsWith("Ki")) return quantity
    return String.format("%.1f GiB", ki / 1024.0 / 1024.0)
}

private fun joinShort(items: List<String>, max: Int): String? = when {
    items.isEmpty() -> null
    items.size <= max -> items.joinToString(", ")
    else -> items.take(max).joinToString(", ") + " +${items.size - max}"
}

private fun keysOf(o: JSONObject, vararg fields: String): String? =
    joinShort(fields.flatMap { f -> o.optJSONObject(f)?.keys()?.asSequence()?.toList().orEmpty() }.sorted(), 6)

private fun labelDetails(o: JSONObject): List<Pair<String, String>> {
    val labels = o.obj("metadata", "labels") ?: return emptyList()
    val pairs = labels.keys().asSequence().map { "$it=${labels.optString(it)}" }.toList()
    return detailsOf("Labels" to joinShort(pairs, 3))
}

private fun stringList(arr: org.json.JSONArray?): List<String> =
    if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }

private fun JSONObject?.obj(vararg keys: String): JSONObject? {
    var o = this
    for (k in keys) o = o?.optJSONObject(k)
    return o
}

private fun JSONObject?.str(key: String): String? = this?.optString(key)?.takeIf { it.isNotBlank() }

private fun replicaStatus(ready: Int, desired: Int) = when {
    desired == 0 -> "Scaled to 0"
    ready >= desired -> "Available"
    else -> "Progressing"
}

private fun firstPlus(items: List<String>): String? = when (items.size) {
    0 -> null
    1 -> items[0]
    else -> "${items[0]} +${items.size - 1}"
}

private fun keyCount(o: JSONObject, vararg fields: String): String {
    val n = fields.sumOf { o.optJSONObject(it)?.length() ?: 0 }
    return if (n == 1) "1 key" else "$n keys"
}

/** Status and short facts for a list row. Unknown kinds get a best-effort status. */
fun summarize(kind: Kind, o: JSONObject): RowSummary {
    val spec = o.optJSONObject("spec")
    val status = o.optJSONObject("status")
    return when ("${kind.group}/${kind.plural}") {
        "apps/statefulsets", "apps/replicasets", "apps/deployments" -> {
            val desired = spec?.optInt("replicas", 1) ?: 1
            val ready = status?.optInt("readyReplicas", 0) ?: 0
            val owner = o.optJSONObject("metadata")?.optJSONArray("ownerReferences").objects()
                .firstOrNull()?.str("name")
            RowSummary(
                replicaStatus(ready, desired),
                listOfNotNull("$ready/$desired ready", owner),
                detailsOf(
                    "Images" to imagesOf(spec.obj("template", "spec")),
                    "Strategy" to (spec.obj("updateStrategy").str("type") ?: spec.obj("strategy").str("type")),
                ),
            )
        }

        "apps/daemonsets" -> {
            val desired = status?.optInt("desiredNumberScheduled", 0) ?: 0
            val ready = status?.optInt("numberReady", 0) ?: 0
            RowSummary(
                if (ready >= desired) "Available" else "Progressing",
                listOf("$ready/$desired ready"),
                detailsOf("Images" to imagesOf(spec.obj("template", "spec"))),
            )
        }

        "networking.k8s.io/ingresses" -> {
            val hosts = spec?.optJSONArray("rules").objects().mapNotNull { it.str("host") }
            val address = status.obj("loadBalancer")?.optJSONArray("ingress").objects().firstOrNull()
                ?.let { it.str("hostname") ?: it.str("ip") }
            val tlsHosts = spec?.optJSONArray("tls").objects().flatMap { stringList(it.optJSONArray("hosts")) }
            RowSummary(
                null,
                listOfNotNull(spec.str("ingressClassName"), firstPlus(hosts)),
                detailsOf(
                    "Address" to address,
                    "Hosts" to if (hosts.size > 1) joinShort(hosts, 5) else null,
                    "TLS" to joinShort(tlsHosts, 3),
                ),
            )
        }

        "networking.k8s.io/networkpolicies" -> {
            val labels = spec.obj("podSelector", "matchLabels")
            val selector = if (labels == null || labels.length() == 0) {
                "all pods"
            } else {
                firstPlus(labels.keys().asSequence().map { "$it=${labels.optString(it)}" }.toList())
            }
            val typesArr = spec?.optJSONArray("policyTypes")
            val types = if (typesArr == null) null else (0 until typesArr.length()).joinToString("/") { typesArr.optString(it) }
            RowSummary(
                null,
                listOfNotNull(selector, types?.ifBlank { null }),
                detailsOf(
                    "Ingress rules" to spec?.optJSONArray("ingress")?.length()?.toString(),
                    "Egress rules" to spec?.optJSONArray("egress")?.length()?.toString(),
                ),
            )
        }

        "core/configmaps" -> RowSummary(
            null,
            listOf(keyCount(o, "data", "binaryData")),
            detailsOf("Keys" to keysOf(o, "data", "binaryData")),
        )

        "core/secrets" -> {
            val type = o.optString("type")
                .removePrefix("kubernetes.io/")
                .removePrefix("helm.sh/")
                .ifBlank { "Opaque" }
            RowSummary(null, listOf(type, keyCount(o, "data")), detailsOf("Keys" to keysOf(o, "data")))
        }

        "core/persistentvolumeclaims" -> RowSummary(
            status.str("phase"),
            listOfNotNull(
                status.obj("capacity").str("storage") ?: spec.obj("resources", "requests").str("storage"),
                spec.str("storageClassName"),
            ),
            detailsOf(
                "Volume" to spec.str("volumeName"),
                "Access" to joinShort(stringList(spec?.optJSONArray("accessModes")), 3),
            ),
        )

        "core/persistentvolumes" -> {
            val claim = spec.obj("claimRef")?.let { "${it.optString("namespace")}/${it.optString("name")}" }
            RowSummary(
                status.str("phase"),
                listOfNotNull(spec.obj("capacity").str("storage"), claim, spec.str("persistentVolumeReclaimPolicy")),
                detailsOf(
                    "Class" to spec.str("storageClassName"),
                    "Access" to joinShort(stringList(spec?.optJSONArray("accessModes")), 3),
                ),
            )
        }

        "storage.k8s.io/storageclasses" -> {
            val isDefault = o.obj("metadata", "annotations")
                ?.optString("storageclass.kubernetes.io/is-default-class") == "true"
            RowSummary(
                null,
                listOfNotNull(if (isDefault) "default" else null, o.str("provisioner"), o.str("reclaimPolicy")),
                detailsOf(
                    "Binding" to o.str("volumeBindingMode"),
                    "Expansion" to if (o.optBoolean("allowVolumeExpansion")) "allowed" else null,
                ),
            )
        }

        "rbac.authorization.k8s.io/roles", "rbac.authorization.k8s.io/clusterroles" -> {
            val n = o.optJSONArray("rules")?.length() ?: 0
            val aggregated = o.has("aggregationRule")
            val resources = o.optJSONArray("rules").objects()
                .flatMap { stringList(it.optJSONArray("resources")) }
                .distinct()
            RowSummary(
                null,
                listOfNotNull(if (n == 1) "1 rule" else "$n rules", if (aggregated) "aggregated" else null),
                detailsOf("Resources" to joinShort(resources, 6)),
            )
        }

        "rbac.authorization.k8s.io/rolebindings", "rbac.authorization.k8s.io/clusterrolebindings" -> {
            val ref = o.optJSONObject("roleRef")?.let { "${it.optString("kind")}/${it.optString("name")}" }
            val subjects = o.optJSONArray("subjects").objects().map { "${it.optString("kind")}:${it.optString("name")}" }
            RowSummary(
                null,
                listOfNotNull(ref, firstPlus(subjects)),
                detailsOf("Subjects" to if (subjects.size > 1) joinShort(subjects, 5) else null),
            )
        }

        else -> {
            // Best effort for custom resources: Argo CD health, a phase, or a Ready condition.
            val health = status.obj("health").str("status")
            val phase = status.str("phase")
            val ready = status?.optJSONArray("conditions").objects()
                .firstOrNull { it.optString("type") == "Ready" }
                ?.let { if (it.optString("status") == "True") "Ready" else "NotReady" }
            val sync = status.obj("sync").str("status")
            RowSummary(health ?: phase ?: ready, listOfNotNull(sync), labelDetails(o))
        }
    }
}
