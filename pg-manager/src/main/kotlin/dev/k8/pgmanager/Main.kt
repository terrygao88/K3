package dev.k8.pgmanager

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "status"
    val nameIndex = args.indexOf("--name")
    val instanceName = if (nameIndex >= 0 && nameIndex + 1 < args.size) args[nameIndex + 1] else null

    if (instanceName == null) {
        println("Error: --name <instance-name> is required (e.g., --name user1)")
        println("Usage: pg-manager <start|stop|status|destroy> --name <instance-name> [--keep-data]")
        return
    }

    PostgresManager(instanceName = instanceName).use { mgr ->
        when (command) {
            "start" -> {
                println("Starting PostgreSQL instance '$instanceName'...")
                val status = mgr.start()
                println("PostgreSQL status: $status")
                println("Connection: ${mgr.connectionString()}")
            }
            "stop" -> {
                println("Stopping PostgreSQL instance '$instanceName'...")
                mgr.stop()
                println("PostgreSQL stopped. Data is preserved.")
            }
            "status" -> {
                val status = mgr.status()
                println("PostgreSQL '$instanceName' status: $status")
            }
            "destroy" -> {
                val keepData = "--keep-data" in args
                println("Destroying PostgreSQL instance '$instanceName' (keepData=$keepData)...")
                mgr.destroy(keepData = keepData)
                println("Done.")
            }
            else -> {
                println("Usage: pg-manager <start|stop|status|destroy> --name <instance-name> [--keep-data]")
            }
        }
    }
}
