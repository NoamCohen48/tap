"""The server API's ``tap.v1`` messages, for tools that store or exchange them.

Recordings, event logs and other documents embed ``tap.v1`` messages (a ``Selector``, a
``Command``, an ``Error``); this module is their public home. Import the per-file modules to
generate code against them (``from tap_e2e.proto import selector_pb2``: protobuf registers each
``.proto`` file once per process, so a tool's own generated code must import these rather than
generate ``tap.v1`` again), or the messages by name (``tap_e2e.proto.Command``).

``tap_e2e.proto.Selector`` is the message; ``tap_e2e.Selector`` is the builder that wraps one
(``Selector.from_proto`` / ``to_proto``). Experimental: it follows the server API's ``tap.v1``
schema and may change in any release.
"""

from ._gen import *  # noqa: F401,F403
from ._gen import (  # noqa: F401
    app_pb2,
    client_connection_pb2,
    command_pb2,
    device_pb2,
    event_log_pb2,
    failure_pb2,
    selector_pb2,
)
