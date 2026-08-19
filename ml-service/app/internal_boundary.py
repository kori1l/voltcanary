import hmac
import logging
from collections.abc import Callable

from app.config.settings import Settings, settings
from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

TOKEN_HEADER = "X-Internal-Service-Token"
SOURCE_SERVICE_HEADER = "X-Source-Service"
OPEN_PROBE_PATHS = {"/health", "/health/artifacts"}
BODY_LIMIT_PATHS = {"/train", "/inference", "/ml/train", "/ml/inference"}
BODY_LIMIT_METHODS = {"POST", "PUT", "PATCH"}

logger = logging.getLogger("voltcanary.ml.internal_boundary")


class InternalBoundaryMiddleware:
    def __init__(
        self,
        app: ASGIApp,
        settings_provider: Callable[[], Settings] | None = None,
    ) -> None:
        self.app = app
        self.settings_provider = settings_provider or (lambda: settings)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        current_settings = self.settings_provider()
        path = scope.get("path", "")
        method = scope.get("method", "GET").upper()
        headers = Headers(scope=scope)

        if path not in OPEN_PROBE_PATHS and current_settings.ml_internal_auth_required:
            rejection = self._auth_rejection(path, headers, current_settings)
            if rejection is not None:
                status_code, code, message = rejection
                await self._reject(scope, receive, send, status_code, code, message)
                return

        if method in BODY_LIMIT_METHODS and path in BODY_LIMIT_PATHS:
            max_bytes = max(current_settings.ml_max_request_bytes, 0)
            if max_bytes > 0:
                limited_receive, rejection = await self._limit_request_body(path, headers, receive, max_bytes)
                if rejection is not None:
                    status_code, code, message = rejection
                    await self._reject(
                        scope,
                        receive,
                        send,
                        status_code,
                        code,
                        message,
                    )
                    return
                receive = limited_receive

        await self.app(scope, receive, send)

    def _auth_rejection(
        self,
        path: str,
        headers: Headers,
        current_settings: Settings,
    ) -> tuple[int, str, str] | None:
        configured_token = current_settings.internal_service_token
        if not configured_token:
            self._log_rejection(path, 403, "internal_auth_not_configured", headers)
            return 403, "internal_auth_not_configured", "Internal service authentication is not configured."

        provided_token = headers.get(TOKEN_HEADER)
        if not provided_token:
            self._log_rejection(path, 401, "missing_internal_service_token", headers)
            return 401, "missing_internal_service_token", "Internal service token is required."

        if not hmac.compare_digest(provided_token, configured_token):
            self._log_rejection(path, 403, "invalid_internal_service_token", headers)
            return 403, "invalid_internal_service_token", "Internal service token is not valid."

        allowed_sources = current_settings.allowed_source_service_names
        source_service = (headers.get(SOURCE_SERVICE_HEADER) or "").strip()
        if allowed_sources and source_service not in allowed_sources:
            self._log_rejection(path, 403, "source_service_forbidden", headers)
            return 403, "source_service_forbidden", "Source service is not allowed."

        return None

    async def _limit_request_body(
        self,
        path: str,
        headers: Headers,
        receive: Receive,
        max_bytes: int,
    ) -> tuple[Receive, tuple[int, str, str] | None]:
        content_length = headers.get("content-length")
        if content_length:
            try:
                if int(content_length) > max_bytes:
                    logger.warning(
                        "mlRequestRejected path=%s status=413 reason=request_body_too_large bytes=%s maxBytes=%s",
                        path,
                        content_length,
                        max_bytes,
                    )
                    return receive, (
                        413,
                        "request_body_too_large",
                        "Request body exceeds the configured maximum size.",
                    )
            except ValueError:
                logger.warning("mlRequestRejected path=%s status=400 reason=invalid_content_length", path)
                return receive, (
                    400,
                    "invalid_content_length",
                    "Content-Length header is not valid.",
                )

        buffered: list[Message] = []
        total_bytes = 0

        while True:
            message = await receive()
            buffered.append(message)
            if message["type"] != "http.request":
                break
            total_bytes += len(message.get("body", b""))
            if total_bytes > max_bytes:
                logger.warning(
                    "mlRequestRejected path=%s status=413 reason=request_body_too_large bytes=%s maxBytes=%s",
                    path,
                    total_bytes,
                    max_bytes,
                )
                return receive, (
                    413,
                    "request_body_too_large",
                    "Request body exceeds the configured maximum size.",
                )
            if not message.get("more_body", False):
                break

        async def replay_receive() -> Message:
            if buffered:
                return buffered.pop(0)
            return await receive()

        return replay_receive, None

    async def _reject(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
        status_code: int,
        code: str,
        message: str,
    ) -> None:
        response = JSONResponse(
            status_code=status_code,
            content={"detail": {"code": code, "message": message}},
        )
        await response(scope, receive, send)

    def _log_rejection(self, path: str, status_code: int, reason: str, headers: Headers) -> None:
        source_service = (headers.get(SOURCE_SERVICE_HEADER) or "missing").strip() or "missing"
        logger.warning(
            "mlInternalAuthRejected path=%s status=%s reason=%s sourceService=%s",
            path,
            status_code,
            reason,
            source_service,
        )
