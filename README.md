# K3s Dev Environment

Local Kubernetes environment on WSL2 where developers get isolated, on-demand PostgreSQL instances through Coder workspaces.

**Stack:** k3s + Coder (Helm) + pg-manager (Kotlin/fabric8) + PostgreSQL 16

## How It Works

1. **k3s** provides a lightweight single-node Kubernetes cluster
2. **Coder** (deployed via Helm chart) gives each developer an isolated workspace pod
3. **pg-manager** (Kotlin CLI) lets developers create/stop/destroy their own PostgreSQL instances with one command
4. **PostgreSQL** runs as a StatefulSet with persistent storage (PVC) — data survives restarts

## Quick Start

```bash
# 1. Create cluster
sudo bash k3s-setup.sh

# 2. Install Helm + Coder CLI
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
curl -L https://coder.com/install.sh | sh

# 3. Deploy everything
cd helm-chart && helm dependency update
kubectl create namespace coder
helm install coder-dev-env . --namespace coder

# 4. Push workspace template
cd ../coder-template && coder templates push k8s-dev-workspace

# 5. Create users
coder users create --email user1@local.dev --username user1 --password UserPass1234
```

## Developer Workflow

From inside a Coder workspace terminal:

```bash
cd pg-manager
gradle run --args="start --name user1"      # Create PostgreSQL instance
gradle run --args="stop --name user1"       # Pause (data preserved)
gradle run --args="start --name user1"      # Resume (data intact)
gradle run --args="destroy --name user1"    # Delete everything

# Connect
psql "postgresql://postgres:devpassword@postgres-user1.postgres-ns.svc.cluster.local:5432/devdb"
```

## Project Structure

```
k3s-setup.sh          # Cluster bootstrap
helm-chart/           # Helm chart (Coder + RBAC + PVC + namespace)
coder-template/       # Workspace template (Terraform)
pg-manager/           # PostgreSQL lifecycle CLI (Kotlin)
ARCHITECTURE.md       # Full technical architecture
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed architecture, setup instructions, data persistence, multi-tenant isolation, and gotchas.
