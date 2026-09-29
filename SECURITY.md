# Security

Please report vulnerabilities privately through GitHub: **Security → Report a vulnerability**
on this repository. Do not open a public issue for them.

Tap's threat model is a trusted host: the `tap` server binds loopback only and checks a bearer
token from `~/.tap/daemon.json` (mode 0600); anyone who can run commands as your user, or as
shell/root on an attached device, is inside that boundary. Reports about crossing it — another
local user reaching the server, an app on the device driving the driver, secrets leaking into
logs or artifacts — are the ones that matter most.
