# Recipe: stream-resume

Demonstrates **streaming result chunks** and **graceful resume handling** using
a custom lightweight server (RFC v1.1 §8.4, §12).

```
Client
  └── streamer@1.0.0
        ├── job.submit            → job.accepted
        ├── result_chunk seq=0    data="The"
        ├── result_chunk seq=1    data=" quick"
        ├── result_chunk seq=2    data=" brown"
        ├── result_chunk seq=3    data=" fox"
        ├── result_chunk seq=4    data=" jumps"  more=false
        ├── [assembled]           "The quick brown fox jumps"
        └── resume                → Nack(UNIMPLEMENTED) — handled gracefully
```

The recipe shows two key RFC §8.4 / §12 patterns:

1. **Streaming result chunks** — the server sends five `result_chunk` envelopes
   with consecutive `chunkSeq` values; `more=false` on the last chunk signals
   the end of stream.  The client uses `ResultChunkAssembler` to collect and
   reassemble the chunks into a single UTF-8 string.

2. **Graceful Resume handling** — the client sends a `resume` after streaming
   completes, demonstrating that a server which does not maintain an EventLog
   can Nack the request with `UNIMPLEMENTED` and the client continues cleanly.

> **Why a custom server?**
> `ARCPRuntime` Nacks `result_chunk` messages as `UNIMPLEMENTED` (the runtime
> manages job state but does not relay streaming payloads).  This recipe uses a
> hand-rolled server coroutine that dispatches `result_chunk` envelopes directly
> over the transport, bypassing the runtime dispatcher (RFC v1.1 §8.4).

## API keys

No external services are used — everything runs in-process with
`MemoryTransport`.

## Running

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :recipes:runStreamResume
```

## What to look for

- `[client] session opened …` — handshake completed with the lightweight server.
- `[client] job accepted …` — server accepted the job before streaming.
- `[client] chunk  seq=0  more=true  data="The"` through
  `[client] chunk  seq=4  more=false  data=" jumps"` — five UTF-8 chunks
  arriving in order.
- `[client] assembled  "The quick brown fox jumps"` — `ResultChunkAssembler`
  concatenated all chunks into the final sentence.
- `[client] resume  → nack(UNIMPLEMENTED) …` — server Nacked the resume
  request; client logged the response and exited cleanly.
- `[client] done` — clean shutdown with no unhandled errors.
