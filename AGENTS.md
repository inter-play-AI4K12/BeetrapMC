# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

**BeeTrap** is a Minecraft Fabric mod (1.21.4, Java 21) — an educational game using bee pollination as an analogy to teach kids about AI filter bubbles in recommendation systems. As players pollinate flowers, similar ones grow, mirroring algorithmic echo chambers; players learn diversification to break the cycle.

**BeeCurious** extends BeeTrap with an embodied conversational AI agent named **Bip** — a bee-like character that talks with players, asks reflective questions, and makes AI concepts more relatable. Agent decisions are provided by the sibling `BeeCuriousService` Python project. Fabric executes Bip's physical actions and optionally uses Typecast for voice.

*Research project — Interplay Lab, CS Dept, University of Rochester.*

## Build & Run Commands

```bash
./gradlew runClient     # Launch Minecraft client with mod loaded
./gradlew runServer     # Launch a dedicated development server
./gradlew build         # Compile and package the mod (output: build/libs/)
```

No test suite exists — testing is manual via the game client.

## World Setup (first-time)

1. Run `./gradlew runClient` once to create the `run/saves/` directory.
2. Download the world `.zip` from the [GitHub Releases page](https://github.com/farhadi-erfan/beetrap-fabricmc-1.21.4/releases) and extract its contents into `run/saves/` (no redundant parent folders).
3. Download the sample `.env` from the same releases page, place it at `run/.env`, and fill in your API keys.
4. Relaunch with `./gradlew runClient`, choose the **beetrap** world in singleplayer.

## Required Configuration

Place a `.env` file at `run/.env` (the Minecraft working directory, not the project root):

```
BEECURIOUS_SERVICE_URL=http://127.0.0.1:8765
TYPECAST_API_KEY=...
# Optional: client-side speech-to-text only
OPENAI_API_KEY=...
```

Loaded in `Beetrapfabricmc.onInitialize()` before any game logic runs.

## In-Game Commands

```
/game new <ai_level>    # Start a new game (ai_level 3 = PhysicalAgent with Bip)
/game destroy           # Clean up the world for the next session
```

## Logs

Runtime logs (including remote agent events and decisions) are written to:

```
run/beetrap/logs/beetrap-fabricmc-[timestamp].log
```

## Architecture

### Mod Entry Points

- **Server:** `Beetrapfabricmc.java` — `ModInitializer`, loads env vars and registers all handlers
- **Client:** `BeetrapfabricmcClient.java` — `ClientModInitializer`, registers renderers, screens, and C2S/S2C networking

Source sets are split: `src/main/java/` (server-side) and `src/client/java/` (client-side), enforced by Fabric Loom.

### Layered Architecture

```
Fabric Events / Commands
        ↓
   handler/          ← Event wiring layer (BeetrapGameHandler, CommandHandler, NetworkHandler, EntityHandler)
        ↓
   BeetrapGame       ← Central game orchestrator; owns all subsystems
        ↓
  ┌─────────────────────────────────────────────────┐
  │  BeetrapStateManager  │  Agent  │  FlowerManager │
  │  (state machine)      │  (LLM)  │  (garden)      │
  └─────────────────────────────────────────────────┘
```

### Key Classes

| Class | Role |
|---|---|
| `BeetrapGame` | Creates and holds all subsystems; entry points for tick and chat events |
| `BeetrapGameHandler` | Singleton routing Fabric events to the active `BeetrapGame` instance |
| `BeetrapStateManager` | High-level game state machine; orchestrates flowers, bee nest, UI, and agent |
| `Agent` (abstract) | LLM-backed agent base with `AgentState` machine and command queue |
| `FlowerManager` | Spawns and tracks flower entities (using falling blocks) in the bounded garden |
| `BeeNestController` | Spawns and animates the bee nest block toward pollination targets |
| `RemoteAgentClient` | HTTP client for BeeCuriousService sessions and events |
| `NetworkingService` | Broadcasts S2C packets to all connected players |

### Game Lifecycle

1. `/game new <ai_level>` (Brigadier command) → creates `BeetrapGame`, sets players to ADVENTURE + flight
2. **Tick loop:** `ServerTickEvents.START_WORLD_TICK` → `Agent.tick()` + `BeetrapStateManager.tick()`
3. **Chat:** `ServerMessageEvents.CHAT_MESSAGE` → `Agent.onChatMessageReceived()`
4. **Packets:** C2S payloads routed by `BeetrapGameHandler` to game handlers; S2C sent via `NetworkingService`

### Networking (Custom Payloads)

- **C2S (client→server):** `PlayerTarget`, `PlayerPollinate`, `PlayerTimeTravel`, `PollinationCircleRadiusIncrease`, `MultipleChoiceSelection`, `TextInputResult`, `EndSubActivity`, `RestartGame`
- **S2C (server→client):** `EntityPositionUpdate`, `ShowTextScreen`, `ShowMultipleChoiceScreen`, `ShowTextInputScreen`, `BeginSubActivity`, `BeetrapLog`

All payload types live under `networking/`; registration happens in `NetworkHandler`.

### Agent System

`Agent` is an abstract base with an `AgentState` state machine and a command queue. Three concrete subtypes exist under `agent/`:

- `physical/PhysicalAgent` — owns Bip's Minecraft bee entity and physical command execution
- `remote/RemotePhysicalAgent` — AI level 3; sends events to BeeCuriousService and queues returned `AgentCommand` objects
- `chatonly/` — communicates through chat only, no physical presence
- `empty/EmptyAgent` — no-op placeholder for games without AI

### Utility Packages

- `util/ClassicalMDS` — multidimensional scaling for flower layout
- `util/AlgorithmOfFloyd` — shortest-path computation across flowers
- `tts/` — Typecast TTS integration for agent voice
- `config/` — Configuration POJOs loaded from env vars
