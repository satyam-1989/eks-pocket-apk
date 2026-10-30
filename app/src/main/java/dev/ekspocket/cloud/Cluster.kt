package dev.ekspocket.cloud

/** A Kubernetes cluster from any provider, with what's needed to reach its API server. */
data class Cluster(
    /** Unique across providers and accounts: the EKS ARN or the GKE self link. */
    val id: String,
    val name: String,
    /** "EKS" or "GKE". */
    val provider: String,
    /** AWS region, or GCP region/zone. */
    val location: String,
    val endpoint: String,
    /** Base64 PEM of the cluster CA. Blank means the endpoint uses a publicly trusted certificate. */
    val caData: String,
    val status: String,
    val version: String,
    val publicAccess: Boolean,
    val privateAccess: Boolean,
    /** Optional override for how the endpoint is described on the cluster card. */
    val accessNote: String? = null,
)
