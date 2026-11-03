"""gRPC client configuration."""

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class GrpcSettings:
    host: str = "127.0.0.1"
    port: int = 9090
    timeout_seconds: float = 5.0
    use_tls: bool = False
    # Shared secret sent as the `x-internal-service-key` metadata header.
    # Excluded from repr so it never leaks through logs or tracebacks.
    service_key: str = field(default="", repr=False)


def load_grpc_settings() -> GrpcSettings:
    host = os.getenv("CASHIER_GRPC_HOST") or os.getenv("GRPC_HOST") or "127.0.0.1"

    port_raw = os.getenv("CASHIER_GRPC_PORT") or os.getenv("GRPC_PORT")
    try:
        port = int(port_raw.strip()) if port_raw and port_raw.strip() else 9090
    except ValueError:
        port = 9090

    timeout_raw = os.getenv("CASHIER_GRPC_TIMEOUT_SECONDS") or os.getenv("GRPC_TIMEOUT_SECONDS")
    try:
        timeout_seconds = float(timeout_raw.strip()) if timeout_raw and timeout_raw.strip() else 5.0
    except ValueError:
        timeout_seconds = 5.0

    tls_raw = os.getenv("CASHIER_GRPC_USE_TLS") or os.getenv("GRPC_USE_TLS") or ""
    use_tls = tls_raw.strip().lower() in {"true", "1", "yes"}

    service_key = (os.getenv("CASHIER_GRPC_SERVICE_KEY") or "").strip()

    return GrpcSettings(
        host=host.strip(),
        port=port,
        timeout_seconds=timeout_seconds,
        use_tls=use_tls,
        service_key=service_key,
    )

