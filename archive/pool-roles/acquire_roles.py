# Archived from clients/python/tap/service.py and pytest_plugin.py (commit 3afba99): role/constraint acquire.

    def acquire(self, roles: dict[str, dict], timeout: float) -> dict[str, DeviceFacts]:
        """All-or-none lease of one device per role. Constraint keys: serial, min_api, max_api,
        emulator, model_contains."""
        request = pb.AcquireRequest(run_id=self.id, timeout_ms=int(timeout * 1000))
        for role, constraints in roles.items():
            request.roles.append(pb.RoleRequest(role=role, constraints=pb.DeviceConstraints(**constraints)))
        with mapped_errors():
            response = self.service.pool.Acquire(request, timeout=timeout + 30)
        return {a.role: DeviceFacts.of(a.device) for a in response.assignments}


# pytest_plugin.tap_devices built the constraints from the configured serials:
    constraints = {
        role: ({"serial": tap_config.serials[i]} if tap_config.serials else {})
        for i, role in enumerate(roles)
    }
    facts = tap_run.acquire(constraints, tap_config.acquire_timeout)
    assignment = {role: facts[role].serial for role in roles}
