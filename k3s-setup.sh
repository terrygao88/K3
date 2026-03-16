#!/bin/bash
# k3s cluster setup for Coder dev environment
# Run with: sudo bash k3s-setup.sh

set -e

echo "Installing k3s..."
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server \
  --disable traefik \
  --write-kubeconfig-mode 644" sh -

echo "Waiting for k3s to be ready..."
k3s kubectl wait --for=condition=Ready node --all --timeout=120s

# Make kubeconfig available for non-root kubectl/helm
mkdir -p ~/.kube
cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
chmod 600 ~/.kube/config

echo "k3s is ready!"
kubectl get nodes
