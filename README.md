# EKS Pocket

An Android 16+ app for managing Amazon EKS clusters from your phone.

It signs AWS requests itself with your access key, lists your EKS clusters, and talks straight to each cluster's Kubernetes API. Nothing goes through a third-party server.

## What it does

- Saves several AWS profiles (for example prod, staging, a client account) and switches between them in one tap. The active profile name shows at the top of every screen.
- Lists EKS clusters per region and shows status, version, and endpoint access.
- Browses pods, deployments, services, and nodes, in one namespace or all of them.
- Streams pod logs live, including the previous container's logs after a crash.
- Opens an interactive shell inside a container (bash if present, otherwise sh).
- Scales and restarts deployments, deletes pods, deployments, and services, and cordons or uncordons nodes.
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
  k8s/      Kubernetes client, YAML, terminal buffer
  data/     Encrypted profile storage, session
  ui/       Compose screens
.github/workflows/main.yml   CI build
```
