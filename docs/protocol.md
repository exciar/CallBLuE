# BLuE wire protocol

BLuE speaks the protocol used by Xiaomi's "Offline Communication" feature, as observed through interoperability analysis. It adds a **SECURE** mode for BLuE-to-BLuE calls. Nothing here is Xiaomi code; it describes bytes on the air.

## Modes

| | App mode (BLuE ↔ BLuE) | Xiaomi mode (BLuE ↔ stock Xiaomi, Phase 2) |
|---|---|---|
| Discovery | Legacy advert + scan response (any BLE phone) | Extended advert on LE Coded PHY, service data `0xFD2D` |
| GATT service | `7b1e0001-3c2f-4d8a-9e51-0f6b2a9d4c10` | `0000fd2d-0000-1000-8000-00805f9b34fb` |
| Encryption | AES-GCM, fresh nonce per message, audio encrypted | AES-GCM with one IV per session, audio in the clear |

## Discovery: app mode

- **Advert:** the 128-bit service UUID above, connectable.
- **Scan response:** manufacturer data, company ID `0xFFFF` (the Bluetooth SIG test ID).

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | version (`1`) |
| 1 | 1 | capabilities: bit 0 Coded PHY, bit 1 extended advertising |
| 2 | 4 | random per-install id (big-endian), stable while the BLE address rotates |
| 6 | ≤21 | display name, UTF-8, cut on a character boundary |

On phones with LE Coded PHY and extended advertising, the same service UUID and manufacturer data are also sent in an extended, connectable advert on Coded PHY (primary and secondary) for long range. Scanners use `setLegacy(false)` with all PHYs so they receive both.

## Discovery: Xiaomi mode

Service data under `0xFD2D`, 84 bytes:

| Offset | Bytes | Meaning |
|---|---|---|
| 0–6 | `03 05 01 01 00 02 00` | magic; scanners filter on this prefix |
| 7–8 | `03 02` | unknown, copied verbatim |
| 9 | `01` | protocol version |
| 10 | `C0` base, `+0x20` visible to everyone | feature flags; bit 6 = supports app-layer encryption |
| 11–12 | `33 03` | unknown, copied verbatim |
| 13 / 14–37 | length / bytes | SIM 1 number, XOR-ed with ASCII `miuiVirtualCall` (repeating) |
| 38 / 39–62 | length / bytes | SIM 2 number, same XOR |
| 63–64 | `14 04` | location block marker |
| 65 / 66–74 | length / ASCII | longitude (optional) |
| 75 / 76–83 | length / ASCII | latitude (optional) |

The advert is extended, connectable, and uses Coded PHY for both primary and secondary PHY. The XOR hides nothing: anyone nearby can read these numbers, which is why app mode doesn't broadcast numbers at all.

## GATT

Four characteristics in both modes:

| Role | App UUID | Xiaomi UUID | Properties |
|---|---|---|---|
| audio, caller → callee | `7b1e0005-…` | `0000BF05-0000-0000-0000-000000000000` | write without response |
| audio, callee → caller | `7b1e0006-…` | `0000BF06-…` | notify |
| commands, caller → callee | `7b1e0007-…` | `0000BF07-…` | write |
| commands, callee → caller | `7b1e0008-…` | `0000BF08-…` | notify |

- **Notify descriptors:** app mode uses the standard CCCD `0x2902`. Xiaomi uses `11200000-0000-0000-0000-000000000000` (audio) and `22300000-0000-0000-0000-000000000000` (commands).
- **Connection setup:** the caller requests MTU 512. In Xiaomi mode it also requests Coded PHY with S=8 coding.

## Frames

```
CC BB AA | len_hi len_lo | type | seq | opcode | md | payload
type   0 notify, 1 command (no response), 2 command, 3 response
seq    1..254 for commands (wraps to 0), 0xFF marks audio
md     0 last chunk, 1 more chunks follow, 2 discard what was buffered
```

- **Chunking:** payloads longer than 480 bytes are split into chunks that share a `seq`. The receiver concatenates chunks until `md = 0`.
- **Length field:** receivers ignore it and treat the rest of the packet as payload.
- **Audio:** `CC BB AA <len> 00 FF FF 00 <payload>`.

## Opcodes

| Opcode | Name | Payload | Sent as |
|---|---|---|---|
| 41 | QUERY_CAPACITY_LIST | `"0,2,0"` (Speex); entries are separated by `:` (`"0,2,0:1,2,2"` adds Xiaomi's AI codec) | plain, type 2 |
| 42 | REPLY_CAPACITY_LIST | same format | plain, type 2 |
| 43 | SET_CODEC | `"SPEEX,VERSION=1"` | plain, type 2 |
| 40 | QUERY_BLACK_LIST | `"number1:number2"` (the callee checks it against its block list) | plain, type 2 (Xiaomi mode only) |
| 35 | QUERY_AES_KEY | caller's RSA-2048 public key, X.509 DER, Base64 without line breaks | plain, type 2 |
| 36 | NOTIFY_AES_KEY | Base64(RSA-PKCS1(`{"key": b64(16 bytes), "iv": b64(12 bytes)}`)); `/` may arrive escaped as `\/` | plain, type 0 |
| 101 | CALL_REQUEST | 50 bytes: `[len][number ≤24]` at offset 0 and again at offset 25 | encrypted, type 0 |
| 102 | CALL_ANSWER | `11 11` | encrypted, type 0 |
| 103 | CALL_REJECT | reason bytes (BLuE sends `11 11`) | encrypted, type 0 |
| 104 | DISCONNECT_THIRD | callee is busy with another peer | encrypted, type 0 |
| 105 | NOTIFY_DISCONNECT | `11 11` | encrypted, type 0 |

## Call sequence

```
caller                                   callee
QUERY_CAPACITY_LIST  ───────────────▶
                     ◀───────────────   REPLY_CAPACITY_LIST
SET_CODEC            ───────────────▶
(QUERY_BLACK_LIST    ───────────────▶   Xiaomi mode only)
QUERY_AES_KEY        ───────────────▶
                     ◀───────────────   NOTIFY_AES_KEY        both sides now encrypted
CALL_REQUEST         ───────────────▶   rings
                     ◀───────────────   CALL_ANSWER / CALL_REJECT
audio                ◀──────────────▶   audio
NOTIFY_DISCONNECT    ◀──────────────▶   either side
```

Both apps show a 4-digit **check code**: the first 32 bits of SHA-256(session key), mod 10000. If the codes differ, someone relayed the key exchange.

## Encryption

- **SECURE (app mode):** each message is `nonce(12) | AES-128-GCM ciphertext+tag`, with a fresh random nonce. Audio packets are encrypted the same way.
- **XIAOMI:** each message is AES-128-GCM under the session IV with no nonce prefix. The same IV is reused for every message. This is required for compatibility and is weak. Audio is not encrypted.

## Audio

- **Codec:** Speex narrowband, 8 kHz mono, quality 1. One 20 ms frame (160 samples) encodes to 10 bytes.
- **Packets:** 9 frames (90 bytes, 180 ms of audio) per audio packet.
