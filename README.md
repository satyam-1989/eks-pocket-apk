# EKS Pocket

An Android 16+ app for managing Amazon EKS and Google GKE clusters from your phone.

It signs AWS requests itself with your access key, lists your EKS clusters, and talks straight to each cluster's Kubernetes API. Nothing goes through a third-party server.

## What it does

- Works with EKS (AWS access keys) and GKE (a service account key, or your own Google account).
- Remembers the last namespace you used on each cluster.
- Shows each pod's restart count up front (amber from 1, red from 5), when and why it last restarted, and can sort pods by restarts.
- Saves several profiles (for example prod, staging, a client account) and switches between them in one tap. The active profile name shows at the top of every screen.
- Lists EKS clusters per region and shows status, version, and endpoint access.
- Browses, in one namespace or all of them:
  - Workloads: pods, deployments, StatefulSets, ReplicaSets
  - Network: services, ingresses, network policies
  - Config: ConfigMaps, Secrets (values masked until you tap Reveal; screenshots blocked)
  - Storage: PVCs, PVs, StorageClasses
  - Access: Roles, RoleBindings, ClusterRoles, ClusterRoleBindings
  - Cluster: nodes
- Filters every list by name as you type. Plain text matches anywhere in the name (case-insensitive); tap `.*` for regex such as `^api-.*-v2$` or `(worker|cron)`; start with `!` to exclude, like k9s. The namespace picker has the same search.
- Three views for every list, remembered per kind: a compact one-line list, a detailed list (node, IPs, images, capacity, keys, subjects and more), and a grid of status cards.
- "Other kinds" lists every resource type the cluster serves, including CRDs such as Argo CD Applications, with search.
- Streams pod logs live, including the previous container's logs after a crash.
- Opens an interactive shell inside a container (bash if present, otherwise sh).
- Scales and restarts deployments and StatefulSets, scales ReplicaSets, restarts DaemonSets, deletes any resource, and cordons or uncordons nodes.
- Views and edits any of those resources as YAML, and applies new YAML (multiple documents supported) with server-side apply.

## Build the APK with GitHub Actions

1. Create a new GitHub repository (private is fine) and push this folder to it:

   ```
   git init
   git add .
   git commit -m "EKS Pocket"
   git branch -M main
   git remote add origin https://github.com/<you>/eks-pocket.git
   git push -u origin main
   ```

2. Open the repository's **Actions** tab. The "Build APK" workflow starts on every push to `main`; you can also run it manually with **Run workflow**.
3. When the run finishes, open the run's **Summary** page and download the `eks-pocket-apk` artifact. It's a zip containing `app-release.apk`.
4. Copy the APK to your phone and open it. Android will ask you to allow installs from that app (Files, Chrome, etc.) the first time.

If a build fails, copy the error from the "Build debug APK" step log.

## Fixed signing key (so updates keep your profiles)

Android only installs an update over an existing app if both are signed with the same key. Without a fixed key, every build gets a new throwaway key and you have to uninstall first, which deletes your saved profiles.

Do this once:

1. Create a key on your computer. It needs Java's `keytool` (any JDK); the Docker line is an alternative if you don't have Java.

   ```
   keytool -genkeypair -v -keystore eks-pocket.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias ekspocket -dname "CN=EKS Pocket"

   # or, with Docker:
   docker run --rm -it -v "${PWD}:/w" -w /w eclipse-temurin:17-jdk keytool -genkeypair -v -keystore eks-pocket.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias ekspocket -dname "CN=EKS Pocket"
   ```

   It asks for a password. Pick a strong one and remember it.

2. Convert the key file to text:

   ```
   # Windows PowerShell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\eks-pocket.jks")) | Set-Content keystore.txt

   # Linux
   base64 -w0 eks-pocket.jks > keystore.txt

   # macOS
   base64 -i eks-pocket.jks -o keystore.txt
   ```

3. In the GitHub repo, go to **Settings > Secrets and variables > Actions > New repository secret** and add three secrets:

   - `KEYSTORE_BASE64`: the full contents of `keystore.txt`
   - `KEYSTORE_PASSWORD`: the password from step 1
   - `KEY_ALIAS`: `ekspocket`

4. Delete `keystore.txt`, and back up `eks-pocket.jks` and its password somewhere safe, such as a password manager. If you lose them, the next update will need an uninstall again.

Run the workflow again. The first APK signed with the new key still needs one last uninstall of the old app; every update after that installs over the top and keeps your profiles.

If the secrets aren't set, the build still works but shows a warning, and the APK can't update an existing install.

## Set up AWS access

Use a dedicated IAM user for the phone rather than personal admin keys.

**1. IAM permissions** to list and describe clusters:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["eks:ListClusters", "eks:DescribeCluster"],
      "Resource": "*"
    }
  ]
}
```

**2. Kubernetes permissions** on each cluster, through an EKS access entry. The cluster's authentication mode must be `API` or `API_AND_CONFIG_MAP`.

```
aws eks create-access-entry \
  --cluster-name <cluster> \
  --principal-arn arn:aws:iam::<account-id>:user/<phone-user>

aws eks associate-access-policy \
  --cluster-name <cluster> \
  --principal-arn arn:aws:iam::<account-id>:user/<phone-user> \
  --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy \
  --access-scope type=cluster
```

Pick the policy that matches what you want to do from the phone:

- `AmazonEKSViewPolicy` allows browsing and logs only. Shell, edits, and deletes will fail.
- `AmazonEKSEditPolicy` adds shell, scaling, restarts, edits, and deletes in namespaces. It can't cordon nodes.
- `AmazonEKSClusterAdminPolicy` allows everything, including nodes.

Older clusters that only use the `aws-auth` ConfigMap need the IAM user mapped there instead.

**3. Network access:** the phone must be able to reach the cluster's API endpoint. Clusters with a private-only endpoint need a VPN into the VPC. If the public endpoint is restricted by CIDR, your phone's IP must be allowed.

## Set up GKE access

Create a GCP profile in the app. There are two ways to sign in.

### Option A: service account key

1. In the GCP console, go to **IAM & Admin > Service accounts** and create a service account for the phone.
2. Grant it, on the project:
   - `roles/container.clusterViewer` to list clusters
   - Kubernetes access, for example `roles/container.developer` (edit workloads) or `roles/container.admin`. You can use Kubernetes RBAC bindings to the service account's email instead.
3. Open the service account, go to **Keys > Add key > Create new key > JSON**, and move the file to your phone.
4. In the app, add a profile, choose **GCP key**, tap **Choose key file**, and check the project ID.

If key creation is blocked, your organization enforces `iam.disableServiceAccountKeyCreation`; use option B instead. Keys don't expire, so delete the key in IAM if the phone is lost.

### Option B: your Google account

This uses the Google account already on the phone. Google requires an OAuth client registered for the app's package name and signing key, once:

1. Get the SHA-1 fingerprint of your signing key (the same `eks-pocket.jks` used for the CI secrets):

   ```
   keytool -list -v -keystore eks-pocket.jks -alias ekspocket
   # or with Docker:
   docker run --rm -it -v "${PWD}:/w" -w /w eclipse-temurin:17-jdk keytool -list -v -keystore eks-pocket.jks -alias ekspocket
   ```

   Copy the line starting with `SHA1:`.

2. In any GCP project you own, go to **APIs & Services > OAuth consent screen**:
   - With Google Workspace, choose **Internal**.
   - Otherwise choose **External**, leave it in **Testing**, and add your Google account under **Test users**.
3. Go to **APIs & Services > Credentials > Create credentials > OAuth client ID**, choose **Android**, and enter:
   - Package name: `dev.ekspocket`
   - SHA-1: the fingerprint from step 1
4. In each project whose clusters you want to see, make sure the **Kubernetes Engine API** is enabled and your account has a role such as `roles/container.developer`.
5. In the app, add a profile, choose **Google**, tap **Sign in with Google**, and enter the project ID.

If sign-in fails with a "developer error" (code 10), the SHA-1 or package name doesn't match. The APK must be signed with the key from the CI secrets; a build without them uses a throwaway key with a different SHA-1. With an External consent screen in Testing, Google may ask you to sign in again after 7 days.

### GKE networking

The app connects to the cluster's public endpoint. If the public endpoint is disabled but the DNS-based endpoint allows external traffic, it uses that. Clusters with only a private endpoint need a VPN into the VPC. Authorized networks must include your phone's IP.

## Security notes

- Keys are encrypted with an AES-256-GCM key held in the Android Keystore. App backup is disabled.
- Keys are only used to sign requests to `eks.<region>.amazonaws.com`, and to create the short-lived Kubernetes token (a presigned STS URL, the same thing `aws eks get-token` produces).
- TLS to each cluster is verified against that cluster's own certificate authority from `DescribeCluster`.
- Deleting a profile removes its keys. Deleting the last profile also removes the encryption key.
- Temporary credentials (with a session token) work, but you'll need to re-enter them when they expire.

## Known limits

- The shell is a simple line-based terminal: commands, output, Tab completion, history and Ctrl-C work, but full-screen programs like `vim`, `top`, or `less` won't display properly.
- Editing an existing resource replaces the whole object. If someone else changed it after you opened it, saving fails with a conflict; reopen and try again.
- China and GovCloud regions aren't in the region list.

## Project layout

```
app/src/main/java/dev/ekspocket/
  aws/      SigV4 signing, EKS API, EKS token
  cloud/    Provider-neutral cluster model, GKE API, Google auth
  k8s/      Kubernetes client, YAML, terminal buffer
  data/     Encrypted profile storage, session
  ui/       Compose screens
.github/workflows/main.yml   CI build
```
