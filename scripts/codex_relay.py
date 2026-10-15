#!/usr/bin/env python3
"""Development-only inference relay: loopback, private bearer, no agent/tool execution.
Requires aiohttp. Local Codex account tokens never leave this process except to
OpenAI's fixed HTTPS endpoint; they are never returned to the emulator.
"""
from __future__ import annotations

import argparse
import asyncio
import hmac
import json
import os
from pathlib import Path
import secrets
import stat
from typing import Any

from aiohttp import ClientSession, ClientTimeout, web

UPSTREAM = "https://chatgpt.com/backend-api/codex/responses"
MAX_REQUEST = 4 * 1024 * 1024
MAX_STREAM = 16 * 1024 * 1024
TOKEN_FILE = Path.home() / ".local/state/pocket-agent/relay.token"
AUTH_FILE = Path.home() / ".codex/auth.json"


def local_bearer(path: Path) -> str:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(fd, "w") as file:
            file.write(secrets.token_urlsafe(48) + "\n")
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError("Relay bearer file must be owned by this user with mode 0600")
    token = path.read_text().strip()
    if len(token) < 32:
        raise ValueError("Relay bearer file has an invalid value")
    return token


def account_headers(path: Path) -> dict[str, str]:
    # Read on each request so a normal Codex sign-in/refresh is picked up.
    auth = json.loads(path.read_text())
    tokens = auth.get("tokens") or {}
    access, account = tokens.get("access_token"), tokens.get("account_id")
    if not isinstance(access, str) or not isinstance(account, str) or not access or not account:
        raise ValueError("Codex account sign-in is unavailable")
    return {
        "Authorization": f"Bearer {access}",
        "chatgpt-account-id": account,
        "OpenAI-Beta": "responses=experimental",
        "originator": "codex_cli_rs",
        "User-Agent": "pocket-agent-dev-relay/0.1",
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
    }


def normalize_request(body: Any, model: str) -> dict[str, Any]:
    if not isinstance(body, dict) or body.get("model") != model:
        raise ValueError("Requested model is not enabled on this relay")
    if body.get("stream") is not True or not isinstance(body.get("input"), list):
        raise ValueError("A streaming Responses input array is required")
    # Keep the accepted surface narrow. In particular, callers cannot request
    # server-side tools, URLs, stored responses or arbitrary upstream routes.
    allowed = {"model", "input", "tools", "tool_choice", "parallel_tool_calls", "reasoning", "text", "include", "prompt_cache_key"}
    result = {key: value for key, value in body.items() if key in allowed}
    instructions = body.get("instructions") or "You are a helpful assistant."
    if not isinstance(instructions, str):
        raise ValueError("Instructions must be text")
    remaining = []
    for item in result["input"]:
        if isinstance(item, dict) and item.get("role") in ("system", "developer"):
            content = item.get("content", "")
            if isinstance(content, str):
                instructions += "\n" + content
            elif isinstance(content, list):
                instructions += "\n" + "\n".join(c.get("text", "") for c in content if isinstance(c, dict) and isinstance(c.get("text"), str))
        else:
            remaining.append(item)
    result["input"] = remaining
    tools = result.get("tools", [])
    if not isinstance(tools, list) or any(not isinstance(tool, dict) or tool.get("type") != "function" for tool in tools):
        raise ValueError("Only caller-executed function tools are allowed")
    result.update(instructions=instructions, store=False, stream=True)
    result.setdefault("include", ["reasoning.encrypted_content"])
    return result


def error(status: int, message: str) -> web.Response:
    return web.json_response({"error": {"message": message, "type": "relay_error"}}, status=status)


def make_app(bearer: str, auth_file: Path, model: str, *, upstream: str = UPSTREAM) -> web.Application:
    app = web.Application(client_max_size=MAX_REQUEST)

    async def relay(request: web.Request) -> web.StreamResponse:
        if request.headers.get("Origin") or not hmac.compare_digest(request.headers.get("Authorization", ""), f"Bearer {bearer}"):
            return error(401, "Local relay authorization required")
        try:
            body = normalize_request(await request.json(), model)
        except (ValueError, TypeError, json.JSONDecodeError):
            return error(400, "Invalid relay request or model")
        try:
            headers = account_headers(auth_file)
        except (OSError, ValueError, TypeError):
            return error(503, "Local Codex account credentials unavailable; sign in with Codex on the host")
        # No automatic retries. Disconnect cancellation closes the upstream request.
        output = None
        try:
            timeout = ClientTimeout(total=180, sock_connect=20, sock_read=90)
            async with ClientSession(timeout=timeout, trust_env=True) as session:
                async with session.post(upstream, json=body, headers=headers, allow_redirects=False) as response:
                    if response.status != 200:
                        # Never return remote error bodies: they may contain account data.
                        status = response.status if response.status in (400, 401, 403, 429) else 502
                        return error(status, f"Codex inference rejected the request (HTTP {response.status}); no retry was attempted")
                    content_type = response.headers.get("Content-Type", "")
                    if content_type and "text/event-stream" not in content_type:
                        return error(502, "Codex returned an unexpected response format")
                    # Some Codex gateways omit Content-Type. Validate a bounded SSE
                    # prefix instead of accepting arbitrary HTML/JSON as a stream.
                    prefix = await response.content.readexactly(6)
                    if not prefix.startswith((b"event:", b"data: ", b": ")):
                        return error(502, "Codex returned an invalid event stream")
                    output = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-store", "X-Accel-Buffering": "no"})
                    await output.prepare(request)
                    count = len(prefix)
                    await output.write(prefix)
                    async for chunk in response.content.iter_any():
                        count += len(chunk)
                        if count > MAX_STREAM:
                            raise ValueError("Response limit")
                        await output.write(chunk)
                    await output.write_eof()
                    return output
        except asyncio.CancelledError:
            raise
        except Exception:
            if output is not None:
                request.transport.close() if request.transport else None
                return output
            return error(502, "Codex inference connection failed; no retry was attempted")

    app.router.add_post("/v1/responses", relay)
    return app


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--token-file", type=Path, default=TOKEN_FILE)
    parser.add_argument("--auth-file", type=Path, default=AUTH_FILE)
    parser.add_argument("--model", default="gpt-6-luna")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use an unprivileged port between 1024 and 65535")
    if "astra" in args.model.lower():
        parser.error("Astra models are disabled for this development relay")
    bearer = local_bearer(args.token_file)
    print(f"Private bearer file: {args.token_file}; model: {args.model}", flush=True)
    # Bind is deliberately not configurable. Never tunnel or publish this relay.
    web.run_app(make_app(bearer, args.auth_file, args.model), host="127.0.0.1", port=args.port,
                access_log=None, handler_cancellation=True)


if __name__ == "__main__":
    main()
