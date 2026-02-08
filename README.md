# Carcassonne Minimal Web Simulator

This repository is a browser-based Carcassonne simulator/editor for the base game tile set `A` to `X`.

It combines:
- Rules simulation (tile placement legality, feature connectivity, scoring).
- Visual feature overlays (city/road/field/cloister polygons).
- A polygon refinement workflow that writes per-feature overrides.

## What Is In This Repo

- `index.html`: UI shell for `Play`, `Refine polygons`, and `Online` modes.
- `app.js`: all client logic (rules engine, rendering, overlays, refine tools, import/export).
- `style.css`: styles.
- `dev_server.py`: static server + JSON APIs for overrides + online lobby/match play.
- `carcassonne_base_A-X.json`: canonical base tile/rules data.
- `carcassonne_base_A-X_areas.json`: authored polygon areas for each tile feature.
- `everrides.json`: active override file loaded/saved by the app/server.
- `everrides2.json`, `everrides3.json`: example snapshots of richer overrides.
- `images/tile_*.png`: one image per tile type.

## Quick Start

```bash
python3 dev_server.py --host 127.0.0.1 --port 8000
```

Then open `http://127.0.0.1:8000`.

Using `dev_server.py` is important if you want auto-save of overrides to disk via `POST /api/overrides`.

## Modes and Multiplayer

### Local demo modes (existing functionality)

1. Start the server:
   ```bash
   python3 dev_server.py --host 127.0.0.1 --port 8000
   ```
2. Open `http://127.0.0.1:8000`.
3. Use tabs:
   - `Play`: local simulator/test board (existing demo behavior).
   - `Refine polygons`: polygon editing workflow (existing demo behavior).

### Online mode (lobby, chat, invite, match)

1. Start server bound to LAN:
   ```bash
   python3 dev_server.py --host 0.0.0.0 --port 8000
   ```
2. On the host machine, find your local IP (example `192.168.1.23`).
3. Other devices on the same network open:
   - `http://192.168.1.23:8000`
4. In the app, open the `Online` tab.
5. Enter a name and click `Connect`.
6. Use `Lobby` to invite an available player.
7. Accepted invite starts a match:
   - start tile is pre-placed,
   - random starting player,
   - drawn tile each turn (unplaceable draws are burned),
   - 7 meeples per player with returns on completed scored features.
8. Turn flow in UI:
   - place tile on board, click `1) Submit Tile`,
   - optionally click one marker on that tile,
   - click `2) Submit Meeple + End` or `2) Skip Meeple + End`.

## JSON Files: Structure And Purpose

## 1) `carcassonne_base_A-X.json` (core game model)

Top-level shape:

```json
{
  "meta": {},
  "tile_counts": { "A": 2, "...": 0 },
  "tiles": [ ... ]
}
```

What it contains:
- `meta`: documentation and coordinate/port conventions.
- `tile_counts`: deck multiplicity by tile ID.
- `tiles`: 24 unique tile definitions (`A`..`X`), each with edges/features/tags.

Tile object shape:

```json
{
  "id": "A",
  "count": 2,
  "is_start_tile_type": false,
  "image": "images/tile_A.png",
  "edges": {
    "N": {
      "primary": "field|road|city",
      "feature": "featureId-or-null",
      "halves": {
        "left|right|top|bottom": { "type": "field|road|city", "feature": "featureId-or-null" }
      }
    },
    "E": {},
    "S": {},
    "W": {}
  },
  "features": [
    {
      "id": "field1|road1|city1|cloister1",
      "type": "field|road|city|cloister",
      "ports": ["N","E","S","W","Nw","Ne","En","Es","Se","Sw","Ws","Wn"],
      "tags": {},
      "meeple_placement": [0.5, 0.5]
    }
  ],
  "tags": {}
}
```

Why this exists:
- This is the single source of truth for rules topology:
  - Edge matching (`edges.*.primary`).
  - Feature graph connectivity (`features[].ports`).
  - Meeple marker anchor points (`meeple_placement`).
  - Tile counts and selectable types.

Field semantics:
- `edges.*.primary`: used for placement legality when comparing touching edges.
- `features[].ports`:
  - `road` and `city` features use edge ports `N/E/S/W`.
  - `field` features use edge-half ports `Nw/Ne/En/Es/Se/Sw/Ws/Wn`.
- `features[].tags`:
  - `city.tags.pennants` is used for city scoring.
  - Other expansion-oriented tags are present but mostly unused in current scoring.
- `is_start_tile_type`: present in data; currently not used by startup placement logic.
- `image`: present in data; current renderer builds image URL from tile ID instead.

## 2) `carcassonne_base_A-X_areas.json` (authored visual polygons)

Top-level shape:

```json
{
  "schema": {
    "coords": "normalized_0_1",
    "method": "...",
    "note": "..."
  },
  "tiles": {
    "A": {
      "features": {
        "field1": {
          "type": "field",
          "polygons": [
            [[x,y],[x,y],[x,y]]
          ]
        }
      }
    }
  }
}
```

What it contains:
- Per tile + per feature polygon rings in normalized coordinates (0..1).
- Area polygons are for visual highlighting/editing, not scoring.

Why this exists:
- Gives better-looking and more accurate area masks than coarse procedural masks.
- Useful as base authored geometry for refinement workflows.

Current behavior note:
- The current `app.js` generates `areasBase` procedurally from feature ports and does not fetch this file directly.
- Overrides still work and are merged on top of generated base areas.

## 3) `everrides.json` (runtime area overrides)

Top-level shape:

```json
{
  "schema": { "coords": "normalized_0_1", "fillRule": "evenodd" },
  "tiles": {
    "A": {
      "features": {
        "field1": {
          "type": "field",
          "polygons": [ [[x,y],[x,y],[x,y]] ],
          "ports": ["Nw","Ne","..."],
          "tags": {},
          "meeple_placement": [0.5, 0.5],
          "deleted": false
        }
      }
    }
  }
}
```

What it contains:
- Deltas/overrides for any tile feature:
  - Replace polygons.
  - Override feature type/ports/tags/meeple point.
  - Mark feature as `deleted` (including base features).
  - Add brand new features not in base tileset.

Why this exists:
- Lets you edit geometry and topology without modifying the base tileset JSON.
- Used by Refine mode and imported/exported from the UI.

Note on naming:
- Filename is intentionally spelled `everrides.json` in this repo and code.

## 4) Exported game state JSON (`Export JSON` button)

Shape:

```json
{
  "version": 3,
  "board": [
    ["x,y", {"instId": 1, "tileId": "A", "rotDeg": 0, "meeples": [{"player":1,"featureLocalId":"road1"}]}]
  ],
  "score": {"1": 0, "2": 0},
  "scoredKeys": ["..."],
  "remaining": {"A": 1, "B": 4, "...": 0}
}
```

Purpose:
- Save/load a play session state (board layout, scores, remaining counts).

## Port Vocabulary (important for connectivity)

- Edge ports: `N`, `E`, `S`, `W`.
- Field half-ports: `Nw`, `Ne`, `En`, `Es`, `Se`, `Sw`, `Ws`, `Wn`.

These drive graph connectivity and completion checks.

## How The Code Interacts With JSON

Key entry points in code:
- Startup: `main()` in `app.js`.
- Base area generation: `generateProceduralAreas()` in `app.js`.
- Override load/save: `loadOverridesFromServer()`, `saveOverridesToServer()`, `persistOverridesToLocalStorage()` in `app.js`.
- Rule rotation and legality: `rotatedTile()`, `canPlaceAt()` in `app.js`.
- Connectivity/scoring: `analyzeBoard()`, `recomputeAndScore()`, `renderScores()` in `app.js`.
- Geometry lookup and merge: `getAreaFeature()`, `mergedFeaturesForTile()` in `app.js`.
- API implementation: `CarcassonneHandler` in `dev_server.py`.

## Startup flow

1. Load base tileset from `carcassonne_base_A-X.json`.
2. Initialize `tileById`, `counts`, and `remaining`.
3. Build `areasBase` procedurally from base feature ports.
4. Load overrides with this precedence:
   - `GET /api/overrides` (dev server API).
   - `GET /everrides.json` (static file fallback).
   - browser `localStorage`.
   - legacy fallback file `carcassonne_areas_overrides.json` if present.

## Placement and rotation

- Placement checks compare touching edges via `edges.*.primary`.
- A placed tile must touch at least one existing tile (except first placement).
- Rotation rotates ports/edges for logic.
- `meeple_placement` is not rotated in data because the tile DOM element itself is rotated.

## Feature graph + scoring

- Every placed local feature becomes a graph node (`instId:featureId`).
- Union-find merges nodes across neighboring tiles:
  - roads/cities connect by opposite edge ports.
  - fields connect by matching half-ports.
- Completion logic:
  - roads/cities complete when no open ports remain.
  - cloister complete at 8 surrounding tiles.
  - field end-game value uses adjacent completed city groups.
- City scoring uses `tags.pennants`.

## Overlay rendering

- Hovering feature markers computes the full connected feature group.
- For each local feature in that group, polygon rings are looked up by:
  - override first,
  - base areas second.
- Polygons are rendered as SVG overlays on top of tile images.

## Refine mode and persistence

- Refine mode raster-paints masks and converts them back to polygons.
- Saving writes into override entries (`tiles -> features -> featureId`).
- Port toggles also update override `ports` arrays (connectivity edits).
- Persistence:
  - immediate localStorage write,
  - debounced API POST to `/api/overrides` (if dev server available).

## Merge Rules Between Base Data And Overrides

Feature list merge for each tile:
- Start from base `tiles[].features`.
- If override feature has `deleted: true`, drop it.
- For non-deleted base features, apply override fields (`type`, `ports`, `tags`, `meeple_placement`) when present.
- Add extra override-only features that do not exist in base.

Area geometry merge:
- `getAreaFeature(tileId, featureId)` returns override geometry if available.
- Otherwise returns base area geometry.

Important:
- Base rule topology comes from `carcassonne_base_A-X.json`.
- Area geometry comes from `areasBase` + overrides.
- You can accidentally diverge topology and geometry if ports and polygons are edited inconsistently.

## What Is Used Vs Present-But-Unused

Used in current runtime:
- `tile_counts`.
- `tiles[].edges.*.primary`.
- `tiles[].features[].ports`.
- `tiles[].features[].meeple_placement`.
- `tiles[].features[].tags.pennants`.
- Override fields including `deleted`.

Present but currently not central:
- `tiles[].image` (renderer uses deterministic `images/tile_${id}.png` path).
- `is_start_tile_type` (not used to auto-seed board).
- Most expansion-related tag fields.
- Authored `carcassonne_base_A-X_areas.json` file (not directly fetched by current startup code).

## Server API (`dev_server.py`)

- `GET /api/overrides`:
  - returns normalized override payload from `everrides.json`,
  - returns default empty payload if file does not exist.
- `POST /api/overrides`:
  - validates body is a JSON object,
  - normalizes schema/tiles/features container shape,
  - atomically writes to `everrides.json` via temp file replace.

## Known Practical Gotchas

- The override filename is `everrides.json` (typo kept consistently in code and server).
- A comment in `app.js` mentions loading `carcassonne_base_A-X_areas.json`, but startup currently uses procedural areas instead.
- There is a legacy fallback fetch for `carcassonne_areas_overrides.json`; this file is optional and not required for normal flow.

## Recommended Workflow For Data Edits

1. Keep `carcassonne_base_A-X.json` as canonical topology/rules.
2. Use Refine mode to edit polygons and ports.
3. Persist changes into `everrides.json`.
4. Periodically export override JSON for versioned backups.
5. Re-import override snapshots to compare geometry variants.
