# Manual Setup Without Helm or pg-manager

Step-by-step guide to recreate the entire architecture using only `kubectl` commands and YAML manifests.

---

## 1. Create Namespaces

```bash
kubectl create namespace coder
kubectl create namespace postgres-ns
```

---

## 2. Create ServiceAccount

```bash
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: workspace-sa
  namespace: coder
EOF
```

---

## 3. Create RBAC Role

Grants permission to create StatefulSets, Services, PVCs, and Pods in `postgres-ns`.

```bash
kubectl apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: workspace-db-role
  namespace: postgres-ns
rules:
  - apiGroups: [""]
    resources: ["pods", "pods/exec", "pods/log", "pods/status", "services", "persistentvolumeclaims"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["apps"]
    resources: ["deployments", "statefulsets", "deployments/scale", "statefulsets/scale"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
EOF
```

---

## 4. Create RoleBinding

Links `workspace-sa` (in `coder` namespace) to `workspace-db-role` (in `postgres-ns`).

```bash
kubectl apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: workspace-db-binding
  namespace: postgres-ns
subjects:
  - kind: ServiceAccount
    name: workspace-sa
    namespace: coder
roleRef:
  kind: Role
  name: workspace-db-role
  apiGroup: rbac.authorization.k8s.io
EOF
```

---

## 5. Create a PostgreSQL Instance

This replaces `gradle run --args="start --name user1"`. Creates a PVC, StatefulSet, and Service.

```bash
kubectl apply -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-data-user1
  namespace: postgres-ns
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 1Gi
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres-user1
  namespace: postgres-ns
spec:
  serviceName: postgres-user1
  replicas: 1
  selector:
    matchLabels:
      app: postgres-user1
  template:
    metadata:
      labels:
        app: postgres-user1
    spec:
      containers:
        - name: postgres
          image: postgres:16
          ports:
            - containerPort: 5432
          env:
            - name: POSTGRES_DB
              value: devdb
            - name: POSTGRES_PASSWORD
              value: devpassword
          volumeMounts:
            - name: pgdata
              mountPath: /var/lib/postgresql/data
      volumes:
        - name: pgdata
          persistentVolumeClaim:
            claimName: postgres-data-user1
---
apiVersion: v1
kind: Service
metadata:
  name: postgres-user1
  namespace: postgres-ns
spec:
  clusterIP: None
  selector:
    app: postgres-user1
  ports:
    - port: 5432
EOF
```

---

## 6. Connect to PostgreSQL

```bash
psql "postgresql://postgres:devpassword@postgres-user1.postgres-ns.svc.cluster.local:5432/devdb"
```

---

## 7. Stop PostgreSQL (keep data)

```bash
kubectl scale statefulset postgres-user1 -n postgres-ns --replicas=0
```

---

## 8. Start PostgreSQL Again

```bash
kubectl scale statefulset postgres-user1 -n postgres-ns --replicas=1
```

---

## 9. Destroy PostgreSQL (deletes data)

```bash
kubectl delete statefulset postgres-user1 -n postgres-ns
kubectl delete service postgres-user1 -n postgres-ns
kubectl delete pvc postgres-data-user1 -n postgres-ns
```

---

## Summary

| pg-manager command | Manual equivalent |
|---|---|
| `gradle run --args="start --name user1"` | `kubectl apply` PVC + StatefulSet + Service (step 5) |
| `gradle run --args="stop --name user1"` | `kubectl scale statefulset --replicas=0` (step 7) |
| `gradle run --args="start --name user1"` (restart) | `kubectl scale statefulset --replicas=1` (step 8) |
| `gradle run --args="destroy --name user1"` | `kubectl delete` StatefulSet + Service + PVC (step 9) |
