package dev.k8.pgmanager

enum class PodStatus {
    RUNNING,
    PENDING,
    NOT_FOUND,
    FAILED,
    TERMINATING,
    UNKNOWN
}
