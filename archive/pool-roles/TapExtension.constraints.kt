// Archived from clients/kotlin/junit5/src/main/kotlin/com/company/tap/junit5/TapExtension.kt (commit 3afba99): roles → DeviceConstraints.

    /**
     * Roles are pinned explicitly (`tap.device.<role>`), then to `tap.serials` in declaration
     * order; with no serials configured the service pool assigns any free device.
     */
    private fun constraints(roles: List<String>, config: TapConfig): Map<String, DeviceConstraints> {
        val free = config.serials.filter { it !in config.pinnedRoles.values }.toMutableList()
        return roles.associateWith { role ->
            val pinned = config.pinnedRoles[role] ?: free.removeFirstOrNull()
            if (pinned != null) DeviceConstraints.serial(pinned) else DeviceConstraints.ANY
        }
    }
