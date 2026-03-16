terraform {
  required_providers {
    coder = {
      source = "coder/coder"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.35"
    }
  }
}

provider "coder" {}

provider "kubernetes" {}

data "coder_workspace" "me" {}
data "coder_workspace_owner" "me" {}

resource "coder_agent" "main" {
  os   = "linux"
  arch = "amd64"
  dir  = "/home/coder"

  startup_script = <<-EOT
    #!/bin/bash
    # Install JDK 21
    sudo apt-get update -qq
    sudo apt-get install -y -qq openjdk-21-jdk-headless > /dev/null 2>&1

    # Install Gradle 8.12
    if [ ! -d "/opt/gradle" ]; then
      curl -sL https://services.gradle.org/distributions/gradle-8.12-bin.zip -o /tmp/gradle.zip
      sudo unzip -q /tmp/gradle.zip -d /opt
      sudo mv /opt/gradle-8.12 /opt/gradle
      rm /tmp/gradle.zip
    fi
    export PATH="/opt/gradle/bin:$PATH"
    echo 'export PATH="/opt/gradle/bin:$PATH"' >> ~/.bashrc

    # Install kubectl
    if ! command -v kubectl &> /dev/null; then
      curl -sLO "https://dl.k8s.io/release/$(curl -sL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
      chmod +x kubectl
      sudo mv kubectl /usr/local/bin/
    fi

    # Install psql client
    sudo apt-get install -y -qq postgresql-client > /dev/null 2>&1

    # Clone pg-manager
    if [ ! -d "/home/coder/pg-manager" ]; then
      git clone https://github.com/terrygao88/k8-dev-env.git /tmp/k8-repo
      cp -r /tmp/k8-repo/pg-manager /home/coder/pg-manager
      rm -rf /tmp/k8-repo
    fi

    echo "Workspace ready!"
  EOT
}

resource "kubernetes_pod" "workspace" {
  count = data.coder_workspace.me.start_count

  metadata {
    name      = "coder-${lower(data.coder_workspace_owner.me.name)}-${lower(data.coder_workspace.me.name)}"
    namespace = "coder"
    labels = {
      "app.kubernetes.io/name"     = "coder-workspace"
      "app.kubernetes.io/instance" = "coder-workspace-${lower(data.coder_workspace.me.name)}"
    }
  }

  spec {
    service_account_name             = "workspace-sa"
    automount_service_account_token  = true

    container {
      name    = "dev"
      image   = "codercom/enterprise-base:ubuntu"
      command = ["sh", "-c", coder_agent.main.init_script]

      security_context {
        run_as_user = 1000
      }

      env {
        name  = "CODER_AGENT_TOKEN"
        value = coder_agent.main.token
      }

      resources {
        requests = {
          cpu    = "500m"
          memory = "512Mi"
        }
        limits = {
          cpu    = "4"
          memory = "2Gi"
        }
      }
    }
  }
}
