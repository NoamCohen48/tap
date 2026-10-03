"""Bearer call details satisfy gRPC's nominal interface and preserve binary metadata."""
from types import SimpleNamespace
from unittest.mock import Mock

import grpc
import pytest
from tap_e2e.client import _BearerToken, _failure
from tap_e2e.proto import failure_pb2 as pb


def test_interceptor_details_preserve_fields_and_binary_metadata():
    details = Mock(spec=grpc.ClientCallDetails)
    details.method = "/tap.v1.WatchService/Watch"
    details.timeout = 2.0
    details.metadata = (("trace-bin", b"\x00\xff"),)
    details.credentials = None
    details.wait_for_ready = True
    details.compression = grpc.Compression.Gzip
    forwarded = _BearerToken("secret")._details(details)
    assert isinstance(forwarded, grpc.ClientCallDetails)
    assert forwarded.method == details.method
    assert forwarded.timeout == 2.0
    assert forwarded.credentials is None
    assert forwarded.wait_for_ready is True
    assert forwarded.compression == grpc.Compression.Gzip
    assert forwarded.metadata == (("trace-bin", b"\x00\xff"), ("authorization", "Bearer secret"))


@pytest.mark.parametrize("attributes", [False, True])
def test_failure_metadata_accepts_tuple_and_attribute_representations(attributes):
    failure = pb.Failure(reason=pb.FAILURE_REASON_NOT_OWNER, serial="serial")
    item = ("tap-failure-bin", failure.SerializeToString())
    metadata = SimpleNamespace(key=item[0], value=item[1]) if attributes else item
    # RpcError's base has no metadata method at runtime; its Call subclasses do.
    error = Mock(spec=grpc.Call)
    error.trailing_metadata.return_value = (metadata,)
    assert _failure(error) == failure
