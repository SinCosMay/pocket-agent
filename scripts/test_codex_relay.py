#!/usr/bin/env python3
"""Local relay protocol/security tests; no account or network API is needed."""
import asyncio
import json
from pathlib import Path
import tempfile
import unittest

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from codex_relay import local_bearer, make_app, normalize_request


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.auth = Path(self.temp.name) / 'auth.json'
        self.auth.write_text(json.dumps({'tokens': {'access_token': 'account-secret', 'account_id': 'account-id'}}))
        self.seen = []
        self.upstream_status = 200
        self.waiting = asyncio.Event()
        self.cancelled = asyncio.Event()
        self.block = False

        async def upstream(request):
            self.seen.append((dict(request.headers), await request.json()))
            if self.block:
                self.waiting.set()
                try:
                    await asyncio.Event().wait()
                finally:
                    self.cancelled.set()
            if self.upstream_status != 200:
                return web.Response(status=self.upstream_status, text='account-secret private failure details')
            return web.Response(text='event: response.output_text.delta\ndata: {"type":"response.output_text.delta","delta":"ok"}\n\n', content_type='text/event-stream')

        upstream_app = web.Application()
        upstream_app.router.add_post('/responses', upstream)
        self.upstream = TestServer(upstream_app, handler_cancellation=True)
        await self.upstream.start_server()
        self.client = TestClient(TestServer(make_app('local-secret', self.auth, 'gpt-6-sol', upstream=str(self.upstream.make_url('/responses'))), handler_cancellation=True))
        await self.client.start_server()
        self.headers = {'Authorization': 'Bearer local-secret'}
        self.body = {'model': 'gpt-6-sol', 'stream': True, 'input': [{'role': 'user', 'content': 'hello'}]}

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()
        self.temp.cleanup()

    async def test_auth_and_origin_checked_before_upstream(self):
        for headers in ({}, {'Authorization': 'Bearer bad'}, {**self.headers, 'Origin': 'https://evil.test'}):
            response = await self.client.post('/v1/responses', json=self.body, headers=headers)
            self.assertEqual(response.status, 401)
        self.assertEqual(self.seen, [])

    async def test_stream_and_private_upstream_credential(self):
        response = await self.client.post('/v1/responses', json=self.body, headers=self.headers)
        self.assertEqual(response.status, 200)
        self.assertIn('"delta":"ok"', await response.text())
        headers, body = self.seen[0]
        self.assertEqual(headers['Authorization'], 'Bearer account-secret')
        self.assertEqual(headers['chatgpt-account-id'], 'account-id')
        self.assertFalse(body['store'])
        self.assertTrue(body['stream'])
        self.assertNotIn('account-secret', str(response.headers))

    async def test_errors_bounded_and_never_retried(self):
        self.upstream_status = 429
        response = await self.client.post('/v1/responses', json=self.body, headers=self.headers)
        self.assertEqual(response.status, 429)
        self.assertNotIn('account-secret', await response.text())
        self.assertEqual(len(self.seen), 1)

    async def test_invalid_model_and_server_tools_rejected(self):
        for changes in ({'model': 'gpt-6-astra'}, {'tools': [{'type': 'web_search'}]}, {'stream': False}):
            response = await self.client.post('/v1/responses', json={**self.body, **changes}, headers=self.headers)
            self.assertEqual(response.status, 400)
        self.assertEqual(self.seen, [])

    async def test_disconnect_cancels_inference_before_first_byte(self):
        self.block = True
        task = asyncio.create_task(self.client.post('/v1/responses', json=self.body, headers=self.headers))
        await asyncio.wait_for(self.waiting.wait(), 2)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        await asyncio.wait_for(self.cancelled.wait(), 2)

    def test_normalization_preserves_function_calls_and_results(self):
        items = [{'role': 'system', 'content': 'Use phone tools.'}, {'type': 'function_call', 'call_id': 'c1', 'name': 'phone_status', 'arguments': '{}'}, {'type': 'function_call_output', 'call_id': 'c1', 'output': '{"ready":true}'}]
        body = normalize_request({**self.body, 'input': items, 'max_output_tokens': 50, 'tools': [{'type': 'function', 'name': 'phone_status', 'parameters': {'type': 'object'}}]}, 'gpt-6-sol')
        self.assertEqual(body['input'], items[1:])
        self.assertIn('Use phone tools.', body['instructions'])
        self.assertNotIn('max_output_tokens', body)

    def test_token_file_private_and_reused(self):
        path = Path(self.temp.name) / 'secret'
        token = local_bearer(path)
        self.assertEqual(token, local_bearer(path))
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            local_bearer(path)


if __name__ == '__main__':
    unittest.main()
