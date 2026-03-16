package dev.k8.pgmanager

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.readiness.Readiness
import java.util.concurrent.TimeUnit

class PostgresManager(
    private val instanceName: String = "postgres",
    private val namespace: String = "postgres-ns",
    private val pgImage: String = "postgres:16",
    private val pgPort: Int = 5432,
    private val pgPassword: String = "devpassword",
    private val pgDatabase: String = "devdb",
) : AutoCloseable {

    private val statefulSetName: String = "postgres-$instanceName"
    private val pvcName: String = "postgres-data-$instanceName"

    private val client: KubernetesClient = KubernetesClientBuilder().build()

    private val labels = mapOf(
        "app" to "postgres",
        "instance" to instanceName,
        "managed-by" to "pg-manager"
    )

    fun start(): PodStatus {
        ensurePvc()

        val existing = client.apps().statefulSets()
            .inNamespace(namespace)
            .withName(statefulSetName)
            .get()

        if (existing == null) {
            createStatefulSet()
        } else {
            client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(statefulSetName)
                .scale(1)
        }

        ensureService()
        return waitForReady(timeoutSeconds = 120)
    }

    fun stop() {
        client.apps().statefulSets()
            .inNamespace(namespace)
            .withName(statefulSetName)
            .scale(0)

        val podName = "$statefulSetName-0"
        client.pods()
            .inNamespace(namespace)
            .withName(podName)
            .waitUntilCondition(
                { pod -> pod == null },
                120, TimeUnit.SECONDS
            )
    }

    fun status(): PodStatus {
        val ss = client.apps().statefulSets()
            .inNamespace(namespace)
            .withName(statefulSetName)
            .get()
            ?: return PodStatus.NOT_FOUND

        if (ss.spec.replicas == 0) return PodStatus.NOT_FOUND

        val pod = client.pods()
            .inNamespace(namespace)
            .withName("$statefulSetName-0")
            .get()
            ?: return PodStatus.PENDING

        return when (pod.status?.phase) {
            "Running" -> if (Readiness.isPodReady(pod))
                PodStatus.RUNNING else PodStatus.PENDING
            "Pending" -> PodStatus.PENDING
            "Failed" -> PodStatus.FAILED
            else -> PodStatus.UNKNOWN
        }
    }

    fun destroy(keepData: Boolean = true) {
        client.apps().statefulSets()
            .inNamespace(namespace)
            .withName(statefulSetName)
            .delete()

        client.services()
            .inNamespace(namespace)
            .withName(statefulSetName)
            .delete()

        if (!keepData) {
            client.persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(pvcName)
                .delete()
        }
    }

    fun connectionString(): String {
        return "jdbc:postgresql://$statefulSetName.$namespace.svc.cluster.local" +
            ":$pgPort/$pgDatabase?user=postgres&password=$pgPassword"
    }

    private fun createStatefulSet() {
        val ss = StatefulSetBuilder()
            .withNewMetadata()
                .withName(statefulSetName)
                .withNamespace(namespace)
                .withLabels<String, String>(labels)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withServiceName(statefulSetName)
                .withNewSelector()
                    .withMatchLabels<String, String>(labels)
                .endSelector()
                .withNewTemplate()
                    .withNewMetadata()
                        .withLabels<String, String>(labels)
                    .endMetadata()
                    .withNewSpec()
                        .addNewContainer()
                            .withName("postgres")
                            .withImage(pgImage)
                            .addNewPort()
                                .withContainerPort(pgPort)
                                .withName("postgres")
                            .endPort()
                            .addNewEnv()
                                .withName("POSTGRES_PASSWORD")
                                .withValue(pgPassword)
                            .endEnv()
                            .addNewEnv()
                                .withName("POSTGRES_DB")
                                .withValue(pgDatabase)
                            .endEnv()
                            .addNewVolumeMount()
                                .withName("pgdata")
                                .withMountPath("/var/lib/postgresql/data")
                            .endVolumeMount()
                            .withNewResources()
                                .addToRequests("memory", Quantity("64Mi"))
                                .addToRequests("cpu", Quantity("100m"))
                                .addToLimits("memory", Quantity("256Mi"))
                                .addToLimits("cpu", Quantity("500m"))
                            .endResources()
                            .withNewReadinessProbe()
                                .withNewExec()
                                    .withCommand("pg_isready", "-U", "postgres")
                                .endExec()
                                .withInitialDelaySeconds(5)
                                .withPeriodSeconds(5)
                            .endReadinessProbe()
                        .endContainer()
                        .addNewVolume()
                            .withName("pgdata")
                            .withNewPersistentVolumeClaim()
                                .withClaimName(pvcName)
                            .endPersistentVolumeClaim()
                        .endVolume()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build()

        client.apps().statefulSets()
            .inNamespace(namespace)
            .resource(ss)
            .create()
    }

    private fun ensurePvc() {
        val existing = client.persistentVolumeClaims()
            .inNamespace(namespace)
            .withName(pvcName)
            .get()

        if (existing == null) {
            val pvc = PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName)
                    .withNamespace(namespace)
                    .withLabels<String, String>(labels)
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withNewResources()
                        .addToRequests("storage", Quantity("1Gi"))
                    .endResources()
                .endSpec()
                .build()

            client.persistentVolumeClaims()
                .inNamespace(namespace)
                .resource(pvc)
                .create()
        }
    }

    private fun ensureService() {
        val existing = client.services()
            .inNamespace(namespace)
            .withName(statefulSetName)
            .get()

        if (existing == null) {
            val svc = ServiceBuilder()
                .withNewMetadata()
                    .withName(statefulSetName)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .withSelector<String, String>(labels)
                    .addNewPort()
                        .withPort(pgPort)
                        .withName("postgres")
                    .endPort()
                    .withClusterIP("None")
                .endSpec()
                .build()

            client.services()
                .inNamespace(namespace)
                .resource(svc)
                .create()
        }
    }

    private fun waitForReady(timeoutSeconds: Long): PodStatus {
        val podName = "$statefulSetName-0"
        return try {
            client.pods()
                .inNamespace(namespace)
                .withName(podName)
                .waitUntilReady(timeoutSeconds, TimeUnit.SECONDS)
            PodStatus.RUNNING
        } catch (e: Exception) {
            status()
        }
    }

    override fun close() {
        client.close()
    }
}
