#!/usr/bin/env python3
import asyncio
import json
import os
import re
import sys
import time

import aiohttp

TOKEN_ENDPOINT = os.environ.get(
    "POCKET_DEEPGRAM_TOKEN_ENDPOINT",
    "https://fe-voice-ind.vercel.app/api/deepgram-token",
)
TTS_BASE = "wss://api.deepgram.com/v1/speak"
STT_URL = (
    "wss://api.deepgram.com/v2/listen"
    "?model=flux-general-en&encoding=linear16&sample_rate=16000"
)
AGENT_TEXT = (
    "I am going to explain this carefully and in enough detail that the response keeps "
    "playing for several seconds. First, I will describe the background. Then I will "
    "walk through the reasoning step by step. Finally, I will summarize the result and "
    "repeat the important points so there is plenty of speech left to interrupt."
)
INTERRUPTION_TEXT = "Stop. Give me only the final answer."


def auth_header(payload):
    if payload.get("type") == "access_token" and payload.get("token"):
        return {"Authorization": "Bearer " + payload["token"]}
    if payload.get("type") == "api_key" and payload.get("key"):
        return {"Authorization": "Token " + payload["key"]}
    raise RuntimeError("Deepgram token endpoint returned no usable credential")


async def get_auth(session):
    async with session.get(TOKEN_ENDPOINT, timeout=aiohttp.ClientTimeout(total=15)) as response:
        if response.status >= 400:
            raise RuntimeError(f"Deepgram token endpoint failed with HTTP {response.status}")
        return auth_header(await response.json())


async def synthesize_pcm(session, headers, text, sample_rate):
    url = (
        f"{TTS_BASE}?model=aura-2-thalia-en"
        f"&encoding=linear16&sample_rate={sample_rate}"
    )
    audio = bytearray()
    async with session.ws_connect(url, headers=headers, heartbeat=20, timeout=15) as ws:
        await ws.send_json({"type": "Speak", "text": text})
        await ws.send_json({"type": "Flush"})
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            message = await asyncio.wait_for(ws.receive(), timeout=8)
            if message.type == aiohttp.WSMsgType.BINARY:
                audio.extend(message.data)
            elif message.type == aiohttp.WSMsgType.TEXT:
                payload = json.loads(message.data)
                kind = payload.get("type")
                if kind == "Flushed":
                    break
                if kind == "Error":
                    raise RuntimeError("Deepgram TTS returned an error")
            elif message.type in {
                aiohttp.WSMsgType.CLOSED,
                aiohttp.WSMsgType.CLOSE,
                aiohttp.WSMsgType.ERROR,
            }:
                break
    if not audio:
        raise RuntimeError("Deepgram TTS returned no interruption audio")
    return bytes(audio)


async def sleep_interruptibly(seconds, interrupted):
    deadline = time.monotonic() + seconds
    while not interrupted.is_set():
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return True
        try:
            await asyncio.wait_for(interrupted.wait(), timeout=min(remaining, 0.02))
        except asyncio.TimeoutError:
            pass
    return False


async def run_probe():
    timeout = aiohttp.ClientTimeout(total=60)
    async with aiohttp.ClientSession(timeout=timeout) as session:
        headers = await get_auth(session)
        interruption_pcm = await synthesize_pcm(
            session, headers, INTERRUPTION_TEXT, sample_rate=16000
        )
        interruption_duration_ms = round(len(interruption_pcm) / (16000 * 2) * 1000)

        tts_url = (
            f"{TTS_BASE}?model=aura-2-thalia-en"
            "&encoding=linear16&sample_rate=24000"
        )
        async with session.ws_connect(
            tts_url, headers=headers, heartbeat=20, timeout=15
        ) as agent_ws, session.ws_connect(
            STT_URL, headers=headers, heartbeat=20, timeout=15
        ) as stt_ws:
            playback_queue = asyncio.Queue()
            first_agent_audio = asyncio.Event()
            interrupted = asyncio.Event()
            playback_stopped = asyncio.Event()
            clear_ack = asyncio.Event()
            final_turn = asyncio.Event()
            shutdown = asyncio.Event()

            state = {
                "agent_received_bytes": 0,
                "agent_played_bytes": 0,
                "played_bytes_at_interrupt": None,
                "buffered_bytes_at_interrupt": None,
                "start_of_turn_at": None,
                "interruption_send_started_at": None,
                "playback_stopped_at": None,
                "transcript": None,
                "tts_flushed_before_interrupt": False,
            }

            async def receive_agent_tts():
                while not shutdown.is_set():
                    message = await agent_ws.receive()
                    if message.type == aiohttp.WSMsgType.BINARY:
                        state["agent_received_bytes"] += len(message.data)
                        if not first_agent_audio.is_set():
                            first_agent_audio.set()
                        if not interrupted.is_set():
                            await playback_queue.put(bytes(message.data))
                    elif message.type == aiohttp.WSMsgType.TEXT:
                        payload = json.loads(message.data)
                        kind = payload.get("type")
                        if kind == "Cleared":
                            clear_ack.set()
                        elif kind == "Flushed" and not interrupted.is_set():
                            state["tts_flushed_before_interrupt"] = True
                        elif kind == "Error":
                            raise RuntimeError("Deepgram agent TTS returned an error")
                    elif message.type in {
                        aiohttp.WSMsgType.CLOSED,
                        aiohttp.WSMsgType.CLOSE,
                        aiohttp.WSMsgType.ERROR,
                    }:
                        return

            async def simulate_playback():
                while not shutdown.is_set():
                    if interrupted.is_set():
                        while not playback_queue.empty():
                            try:
                                playback_queue.get_nowait()
                            except asyncio.QueueEmpty:
                                break
                        state["playback_stopped_at"] = time.monotonic()
                        playback_stopped.set()
                        return
                    try:
                        chunk = await asyncio.wait_for(playback_queue.get(), timeout=0.1)
                    except asyncio.TimeoutError:
                        continue
                    duration = len(chunk) / (24000 * 2)
                    completed = await sleep_interruptibly(duration, interrupted)
                    if completed:
                        state["agent_played_bytes"] += len(chunk)
                    else:
                        state["playback_stopped_at"] = time.monotonic()
                        playback_stopped.set()
                        return

            async def receive_flux():
                while not shutdown.is_set():
                    message = await stt_ws.receive()
                    if message.type == aiohttp.WSMsgType.TEXT:
                        payload = json.loads(message.data)
                        if payload.get("type") == "Error":
                            raise RuntimeError("Deepgram Flux returned an error")
                        if payload.get("type") != "TurnInfo":
                            continue
                        event = payload.get("event")
                        if event == "StartOfTurn" and not interrupted.is_set():
                            state["start_of_turn_at"] = time.monotonic()
                            state["played_bytes_at_interrupt"] = state["agent_played_bytes"]
                            state["buffered_bytes_at_interrupt"] = max(
                                0,
                                state["agent_received_bytes"] - state["agent_played_bytes"],
                            )
                            interrupted.set()
                            await agent_ws.send_json({"type": "Clear"})
                        elif event == "EndOfTurn":
                            transcript = (payload.get("transcript") or "").strip()
                            if transcript:
                                state["transcript"] = transcript
                                final_turn.set()
                                return
                    elif message.type in {
                        aiohttp.WSMsgType.CLOSED,
                        aiohttp.WSMsgType.CLOSE,
                        aiohttp.WSMsgType.ERROR,
                    }:
                        return

            tasks = [
                asyncio.create_task(receive_agent_tts()),
                asyncio.create_task(simulate_playback()),
                asyncio.create_task(receive_flux()),
            ]
            try:
                await agent_ws.send_json({"type": "Speak", "text": AGENT_TEXT})
                await agent_ws.send_json({"type": "Flush"})
                await asyncio.wait_for(first_agent_audio.wait(), timeout=10)

                playback_deadline = time.monotonic() + 5
                while state["agent_played_bytes"] < 24000 * 2 // 2:
                    if time.monotonic() >= playback_deadline:
                        raise RuntimeError("Agent playback did not advance before interruption")
                    await asyncio.sleep(0.02)
                    if interrupted.is_set():
                        break

                state["interruption_send_started_at"] = time.monotonic()
                for offset in range(0, len(interruption_pcm), 2560):
                    chunk = interruption_pcm[offset : offset + 2560]
                    if len(chunk) % 2:
                        chunk = chunk[:-1]
                    if chunk:
                        await stt_ws.send_bytes(chunk)
                        await asyncio.sleep(0.08)

                quiet = bytes(2560)
                for _ in range(45):
                    await stt_ws.send_bytes(quiet)
                    if final_turn.is_set():
                        break
                    await asyncio.sleep(0.08)

                await asyncio.wait_for(interrupted.wait(), timeout=6)
                await asyncio.wait_for(playback_stopped.wait(), timeout=1)
                await asyncio.wait_for(clear_ack.wait(), timeout=4)
                await asyncio.wait_for(final_turn.wait(), timeout=10)

                normalized = set(re.findall(r"[a-z]+", (state["transcript"] or "").lower()))
                if "stop" not in normalized or "answer" not in normalized:
                    raise AssertionError(
                        "Flux did not recover the expected interruption phrase: "
                        + repr(state["transcript"])
                    )

                if not state["played_bytes_at_interrupt"]:
                    raise AssertionError("Interruption arrived before agent playback began")
                if not state["buffered_bytes_at_interrupt"]:
                    raise AssertionError(
                        "No queued agent audio remained when interruption arrived; "
                        "the probe did not interrupt mid-response"
                    )
                if state["agent_played_bytes"] != state["played_bytes_at_interrupt"]:
                    raise AssertionError("Agent playback advanced after the interruption")

                start_latency_ms = round(
                    (state["start_of_turn_at"] - state["interruption_send_started_at"]) * 1000
                )
                stop_latency_ms = round(
                    (state["playback_stopped_at"] - state["start_of_turn_at"]) * 1000
                )
                played_ms = round(
                    state["played_bytes_at_interrupt"] / (24000 * 2) * 1000
                )
                buffered_ms = round(
                    state["buffered_bytes_at_interrupt"] / (24000 * 2) * 1000
                )
                return {
                    "success": True,
                    "provider": "Deepgram Aura-2 Thalia + Flux v2",
                    "interruption_transcript": state["transcript"],
                    "interruption_audio_ms": interruption_duration_ms,
                    "agent_played_before_interrupt_ms": played_ms,
                    "agent_buffered_at_interrupt_ms": buffered_ms,
                    "flux_start_of_turn_ms_after_interrupt_audio_started": start_latency_ms,
                    "local_playback_stop_ms_after_start_of_turn": stop_latency_ms,
                    "deepgram_clear_acknowledged": clear_ack.is_set(),
                    "tts_flush_arrived_before_interrupt": state["tts_flushed_before_interrupt"],
                }
            finally:
                shutdown.set()
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                await agent_ws.close(code=1000, message=b"probe complete")
                await stt_ws.close(code=1000, message=b"probe complete")


def main():
    try:
        result = asyncio.run(run_probe())
    except Exception as error:
        print(
            json.dumps(
                {
                    "success": False,
                    "error": type(error).__name__,
                    "detail": str(error),
                },
                sort_keys=True,
            )
        )
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
