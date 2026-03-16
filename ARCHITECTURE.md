# K8s Dev Environment — Technical Architecture

Local Kubernetes environment on WSL2 where developers get isolated, on-demand PostgreSQL instances through Coder workspaces.

**Stack:** k3s + Coder (Helm) + pg-manager (Kotlin/fabric8) + PostgreSQL 16

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                    K3S CLUSTER (single-node)                          │
│                                                                      │
│  Namespace: coder                                                    │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                                                                │  │
│  │  Coder Server [Pod]                                            │  │
│  │    │                                                           │  │
│  │    ├── Workspace user1 [Pod]       Workspace user2 [Pod]       │  │
│  │    │   uses: workspace-sa          uses: workspace-sa          │  │
│  │    │   [ServiceAccount]            [ServiceAccount]            │  │
│  │    │   └── pg-manager              └── pg-manager              │  │
│  │    │       [App inside Pod]            [App inside Pod]        │  │
│  │    │         │                              │                  │  │
│  └────│─────────│──────────────────────────────│──────────────────┘  │
│       │         │  K8s API calls               │                     │
│       │         ▼                              ▼                     │
│  Namespace: postgres-ns                                              │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                                                                │  │
│  │  user1's resources                user2's resources             │  │
│  │  ┌──────────────────────┐        ┌──────────────────────┐      │  │
│  │  │ postgres-user1       │        │ postgres-user2       │      │  │
│  │  │ [StatefulSet]        │        │ [StatefulSet]        │      │  │
│  │  │   └── postgres-      │        │   └── postgres-      │      │  │
│  │  │       user1-0 [Pod]  │        │       user2-0 [Pod]  │      │  │
│  │  │         └── postgres-│        │         └── postgres-│      │  │
│  │  │         data-user1   │        │         data-user2   │      │  │
│  │  │         [PVC]        │        │         [PVC]        │      │  │
│  │  │ postgres-user1       │        │ postgres-user2       │      │  │
│  │  │ [Service]            │        │ [Service]            │      │  │
│  │  └──────────────────────┘        └──────────────────────┘      │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  RBAC: workspace-sa [ServiceAccount] (in coder)                     │
│    ──▶ workspace-db-binding [RoleBinding]                            │
│      ──▶ workspace-db-role [Role] (in postgres-ns)                  │
│          allows CRUD on StatefulSets, Services, PVCs, Pods           │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
External access: http://localhost:30080 [NodePort Service]

Legend:
  [Pod]            = Running container
  [StatefulSet]    = Controller that manages pods + guarantees PVC reattachment
  [PVC]            = Persistent disk storage mounted into pod
  [Service]        = DNS endpoint to reach a pod
  [ServiceAccount] = Identity/permissions assigned to pods
  [Role]           = Allowed K8s API actions
  [RoleBinding]    = Links ServiceAccount to Role
```

**Flow:** Developer logs into Coder → creates workspace → runs `pg-manager start` → pg-manager calls K8s API → creates StatefulSet + PVC + Service in `postgres-ns` → developer connects via `postgres-{name}.postgres-ns.svc.cluster.local:5432`

---

## Setup

**Prerequisites:** WSL2 (Docker not required). Recommended: `memory=10GB` in `C:\Users\<username>\.wslconfig`.

```bash
# 1. Install k3s
sudo bash k3s-setup.sh

# 2. Install Helm + Coder CLI
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
curl -L https://coder.com/install.sh | sh

# 3. Deploy everything via Helm chart
cd helm-chart && helm dependency update
kubectl create namespace coder
helm install coder-dev-env . --namespace coder
kubectl -n coder rollout status deployment/coder
# → Open Coder UI, create admin: admin@local.dev / Passw0rd1234

# 4. Push workspace template
cd ../coder-template && coder templates push k8s-dev-workspace

# 5. Create users
coder users create --email user1@local.dev --username user1 --password UserPass1234
```

> **WSL2 note:** `localhost:30080` works from inside WSL. From Windows, use your WSL IP (run `wsl hostname -I` in PowerShell).

The Helm chart creates: Coder server (subchart) with NodePort 30080, `postgres-ns` namespace, `workspace-sa` ServiceAccount, RBAC Role + RoleBinding, base PVC. Edit `helm-chart/values.yaml` to customize. Uninstall: `helm uninstall coder-dev-env --namespace coder`.

---

## Developer Usage

From inside a workspace terminal:

```bash
cd pg-manager

gradle run --args="start --name user1"      # Create PostgreSQL (StatefulSet + PVC + Service)
gradle run --args="status --name user1"     # Check status
gradle run --args="stop --name user1"       # Scale to 0 (data preserved)
gradle run --args="start --name user1"      # Restart (data intact)
gradle run --args="destroy --name user1"    # Delete everything including data
```

**Connect to database:**
```bash
psql "postgresql://postgres:devpassword@postgres-user1.postgres-ns.svc.cluster.local:5432/devdb"
```

**Resource naming** — all derived from `--name`:

| Resource | Pattern | Example |
|---|---|---|
| StatefulSet | `postgres-{name}` | `postgres-user1` |
| Pod | `postgres-{name}-0` | `postgres-user1-0` |
| PVC | `postgres-data-{name}` | `postgres-data-user1` |
| Service/DNS | `postgres-{name}.postgres-ns.svc.cluster.local` | `postgres-user1.postgres-ns...` |

**Data lifecycle:**

| Action | PostgreSQL | PVC (data) |
|---|---|---|
| `stop` | Scaled to 0 | Preserved |
| `start` after stop | Scaled to 1 | Reattached, data intact |
| `destroy` | Deleted | **Deleted — data gone** |

**Programmatic usage (Kotlin):**
```kotlin
PostgresManager(instanceName = "user1").use { mgr ->
    mgr.start()
    println(mgr.connectionString())
    mgr.stop()
}
```

---

## Data Persistence

| Scenario | Data survives? |
|---|---|
| Pod crash / restart | Yes — StatefulSet recreates with same PVC |
| `stop` then `start` | Yes — PVC untouched |
| k3s service restart / machine reboot | Yes — PVC on local disk |
| `destroy` | **No** — PVC deleted |
| `k3s-uninstall.sh` | **No** — everything gone |

---

## Multi-Tenant Isolation

| Boundary | Enforced? |
|---|---|
| Workspace access (users only see own workspaces) | Yes (Coder) |
| Database naming (`postgres-{name}` prevents conflicts) | Yes (by convention) |
| Namespace separation (workspaces in `coder`, databases in `postgres-ns`) | Yes |
| Cross-user DB access (shared `workspace-sa` — can access others' DBs) | **No** |

> For strict isolation, add NetworkPolicies or per-user namespaces.

---

## Cluster Management

```bash
sudo systemctl status k3s        # Check status
sudo systemctl stop k3s          # Stop (preserves data)
sudo systemctl start k3s         # Start
sudo /usr/local/bin/k3s-uninstall.sh  # Uninstall (DESTROYS everything)
```

---

## Gotchas

| Issue | Fix |
|---|---|
| `CODER_ACCESS_URL` | Must be `http://coder.coder.svc.cluster.local` w
| WSL2 networking | `localhost` doesn't always forward — use WSL IP from Windows browser |
| NodePort assignment | Helm may assign random port; patch with `kubectl -n coder patch svc coder --type='json' -p='[{"op":"replace","path":"/spec/ports/0/nodePort","value":30080}]'` |

---

## File Reference

| File | Purpose |
|---|---|
| `k3s-setup.sh` | k3s install (disables Traefik, sets kubeconfig) |
| `helm-chart/Chart.yaml` | Chart definition (Coder as subchart dependency) |
| `helm-chart/values.yaml` | Configurable values (Coder, RBAC, PVC, namespaces) |
| `helm-chart/templates/*.yaml` | Namespace, ServiceAccount, Role, RoleBinding, PVC |
| `coder-template/main.tf` | Workspace template (pre-installs JDK, Gradle, kubectl, psql, pg-manager) |
| `pg-manager/src/.../PostgresManager.kt` | Multi-tenant PostgreSQL lifecycle (StatefulSet + PVC + Service) |
| `pg-manager/src/.../Main.kt` | CLI entry point with `--name` flag |

---

## Quick Reference

```
Admin (one-time)                     Developer (daily)
────────────────                     ─────────────────
1. sudo bash k3s-setup.sh           1. Log into Coder UI
2. helm install coder-dev-env        2. Create workspace from template
3. coder templates push              3. gradle run --args="start --name me"
4. coder users create                4. psql to postgres-me.postgres-ns...
                                     5. gradle run --args="stop --name me"
```
