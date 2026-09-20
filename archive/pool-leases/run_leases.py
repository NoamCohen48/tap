# Archived from clients/python/tap/service.py and pytest_plugin.py (commit e7d6004): serial lease calls.

    def acquire(self, serials: list[str], timeout: float) -> list[DeviceFacts]:
        """Lease every one of ``serials`` for this run, all or none, waiting up to ``timeout``
        seconds for them to be free at the same time. Returns their facts in request order.
        The pool only knows serials; naming devices (roles) is up to the caller — see
        :meth:`free_serials` to pick some."""
        request = pb.AcquireRequest(run_id=self.id, serials=list(serials), timeout_ms=int(timeout * 1000))
        with mapped_errors():
            response = self.service.pool.Acquire(request, timeout=timeout + 30)
        return [DeviceFacts.of(d) for d in response.devices]

    def release(self, serials: list[str] | None = None) -> int:
        """Release the given serials (default: every device of this run); returns how many were released."""
        with mapped_errors():
            return self.service.pool.Release(pb.ReleaseRequest(run_id=self.id, serials=serials or []), timeout=30).released

# pytest_plugin.tap_devices: tap_run.acquire(serials, timeout) before opening (parallel), tap_run.release(serials) on failure and after the test.
def _open_all(run: Run, config: TapConfig, assignment: dict[str, str]) -> dict[str, Device]:
    with ThreadPoolExecutor(max_workers=len(assignment)) as pool:
        futures = {role: pool.submit(run.open_device, serial, config.aut) for role, serial in assignment.items()}
        opened: dict[str, Device] = {}
        failure: BaseException | None = None
        for role, future in futures.items():
            try:
                opened[role] = future.result()
            except BaseException as error:  # noqa: BLE001 - re-raised after cleanup
                failure = failure or error
        if failure is not None:
            for device in opened.values():
                try:
                    device.close()
                except Exception:  # noqa: BLE001
                    pass
            raise failure
        return opened
