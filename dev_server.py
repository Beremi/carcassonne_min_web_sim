#!/usr/bin/env python3
"""Static server + overrides API + lightweight multiplayer lobby/match API.

Usage:
  python3 dev_server.py --host 127.0.0.1 --port 8000

Endpoints:
  GET  /api/overrides
  POST /api/overrides

  POST /api/session/join
  POST /api/session/heartbeat
  POST /api/session/leave

  GET  /api/lobby?token=...
  POST /api/chat
  POST /api/invite
  POST /api/invite/respond

  GET  /api/match?token=...
  POST /api/match/intent
  POST /api/match/submit_turn
  POST /api/match/resign
"""

from __future__ import annotations

import argparse
import copy
import json
import random
import secrets
import threading
import time
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
OVERRIDES_FILE = ROOT / "everrides.json"
TILESET_FILE = ROOT / "carcassonne_base_A-X.json"

DEFAULT_OVERRIDES = {
    "schema": {"coords": "normalized_0_1", "fillRule": "evenodd"},
    "tiles": {},
}

EDGE_OPP = {"N": "S", "E": "W", "S": "N", "W": "E"}
PORT_ROT_CW = {
    "Nw": "En",
    "Ne": "Es",
    "En": "Se",
    "Es": "Sw",
    "Se": "Ws",
    "Sw": "Wn",
    "Ws": "Nw",
    "Wn": "Ne",
}
CITY_EDGE_TO_ADJ_FIELD_PORTS = {
    "N": ["Nw", "Ne", "Wn", "En"],
    "E": ["En", "Es", "Ne", "Se"],
    "S": ["Sw", "Se", "Ws", "Es"],
    "W": ["Wn", "Ws", "Nw", "Sw"],
}

BOARD_HALF_SPAN = 12
SESSION_TIMEOUT_SEC = 60
INVITE_TIMEOUT_SEC = 120
MAX_CHAT_MESSAGES = 160


class UnionFind:
    def __init__(self):
        self.parent = {}
        self.rank = {}

    def add(self, item):
        if item not in self.parent:
            self.parent[item] = item
            self.rank[item] = 0

    def find(self, item):
        parent = self.parent[item]
        if parent == item:
            return item
        root = self.find(parent)
        self.parent[item] = root
        return root

    def union(self, a, b):
        self.add(a)
        self.add(b)
        ra = self.find(a)
        rb = self.find(b)
        if ra == rb:
            return
        rka = self.rank[ra]
        rkb = self.rank[rb]
        if rka < rkb:
            ra, rb = rb, ra
            rka, rkb = rkb, rka
        self.parent[rb] = ra
        if rka == rkb:
            self.rank[ra] = rka + 1


class CarcassonneEngine:
    def __init__(self, tileset_payload):
        self.tileset = tileset_payload
        self.tile_by_id = {t["id"]: t for t in tileset_payload.get("tiles", [])}
        self.counts = {
            tile_id: int(count)
            for tile_id, count in (tileset_payload.get("tile_counts") or {}).items()
        }
        self.start_tile_id = self._pick_start_tile_id()
        self.field_city_adjacency = self._build_field_city_adjacency()

    def _pick_start_tile_id(self):
        tiles = self.tileset.get("tiles") or []
        for tile in tiles:
            tile_id = tile.get("id")
            if tile.get("is_start_tile_type") and (self.counts.get(tile_id, 0) > 0):
                return tile_id
        ids = sorted([tid for tid, cnt in self.counts.items() if cnt > 0])
        return ids[0] if ids else None

    def _build_field_city_adjacency(self):
        out = {}
        for tile_id, tile in self.tile_by_id.items():
            feats = tile.get("features") or []
            fields = [f for f in feats if f.get("type") == "field"]
            cities = [f for f in feats if f.get("type") == "city"]
            tile_map = {}
            for field in fields:
                fports = set(field.get("ports") or [])
                if not fports:
                    continue
                hits = set()
                for city in cities:
                    adjacent = False
                    for edge in city.get("ports") or []:
                        for candidate in CITY_EDGE_TO_ADJ_FIELD_PORTS.get(edge, []):
                            if candidate in fports:
                                adjacent = True
                                break
                        if adjacent:
                            break
                    if adjacent:
                        hits.add(city.get("id"))
                if hits:
                    tile_map[field.get("id")] = hits
            out[tile_id] = tile_map
        return out

    @staticmethod
    def key_xy(x, y):
        return f"{x},{y}"

    @staticmethod
    def parse_xy(k):
        sx, sy = k.split(",", 1)
        return int(sx), int(sy)

    @staticmethod
    def rot_port(port, rot_deg):
        steps = ((rot_deg % 360) + 360) % 360 // 90
        q = port
        for _ in range(steps):
            if q in PORT_ROT_CW:
                q = PORT_ROT_CW[q]
            elif q == "N":
                q = "E"
            elif q == "E":
                q = "S"
            elif q == "S":
                q = "W"
            elif q == "W":
                q = "N"
            else:
                raise ValueError(f"Unknown port: {q}")
        return q

    def rotate_tile(self, tile_id, rot_deg):
        base = self.tile_by_id[tile_id]
        inv = (360 - rot_deg) % 360
        out = {"id": tile_id, "edges": {}, "features": []}

        for edge in ["N", "E", "S", "W"]:
            src_edge = self.rot_port(edge, inv)
            be = base["edges"][src_edge]
            out["edges"][edge] = {
                "primary": be.get("primary"),
                "feature": be.get("feature"),
                "halves": be.get("halves"),
            }

        for feat in base.get("features") or []:
            rf = copy.deepcopy(feat)
            rf["ports"] = [self.rot_port(p, rot_deg) for p in (rf.get("ports") or [])]
            out["features"].append(rf)
        return out

    def _within_bounds(self, x, y):
        return abs(x) <= BOARD_HALF_SPAN and abs(y) <= BOARD_HALF_SPAN

    def can_place_at(self, board, tile_id, rot_deg, x, y):
        if not self._within_bounds(x, y):
            return False, "Out of board bounds."

        key = self.key_xy(x, y)
        if key in board:
            return False, "Cell occupied."

        has_any = bool(board)
        touches = False
        tile = self.rotate_tile(tile_id, rot_deg)

        neighbors = [
            (0, -1, "N"),
            (1, 0, "E"),
            (0, 1, "S"),
            (-1, 0, "W"),
        ]

        for dx, dy, edge in neighbors:
            nk = self.key_xy(x + dx, y + dy)
            n_inst = board.get(nk)
            if not n_inst:
                continue
            touches = True
            n_tile = self.rotate_tile(n_inst["tileId"], n_inst["rotDeg"])
            opp = EDGE_OPP[edge]
            a = tile["edges"][edge]["primary"]
            b = n_tile["edges"][opp]["primary"]
            if a != b:
                return False, f"Edge mismatch {edge}: {a} vs neighbor {opp}: {b}"

        if has_any and not touches:
            return False, "Tile must touch at least one placed tile."
        return True, "OK"

    def build_frontier(self, board):
        frontier = set()
        if not board:
            frontier.add((0, 0))
            return frontier

        for cell_key in board:
            x, y = self.parse_xy(cell_key)
            for nx, ny in ((x, y - 1), (x + 1, y), (x, y + 1), (x - 1, y)):
                if not self._within_bounds(nx, ny):
                    continue
                nk = self.key_xy(nx, ny)
                if nk not in board:
                    frontier.add((nx, ny))
        return frontier

    def has_any_placement(self, board, tile_id):
        for x, y in self.build_frontier(board):
            for rot in (0, 90, 180, 270):
                ok, _ = self.can_place_at(board, tile_id, rot, x, y)
                if ok:
                    return True
        return False

    @staticmethod
    def _score_feature(group, completed):
        gtype = group["type"]
        if gtype == "road":
            return len(group["tiles"])
        if gtype == "city":
            tiles = len(group["tiles"])
            pennants = group["pennants"]
            return (2 * tiles + 2 * pennants) if completed else (tiles + pennants)
        if gtype == "cloister":
            return 9 if completed else (1 + group["adjacent_count"])
        if gtype == "field":
            return 3 * len(group["adj_completed_cities"])
        return 0

    @classmethod
    def score_end_now_value(cls, group):
        gtype = group["type"]
        if gtype == "city":
            return cls._score_feature(group, group["complete"])
        if gtype == "road":
            return cls._score_feature(group, True)
        if gtype == "cloister":
            return cls._score_feature(group, False)
        if gtype == "field":
            return cls._score_feature(group, False)
        return 0

    @staticmethod
    def stable_group_key(group):
        return f"{group['type']}|{'/'.join(sorted(group['nodes']))}"

    @staticmethod
    def winners_of_group(group):
        m1 = group["meeples_by_player"].get(1, 0)
        m2 = group["meeples_by_player"].get(2, 0)
        mx = max(m1, m2)
        if mx <= 0:
            return []
        winners = []
        if m1 == mx:
            winners.append(1)
        if m2 == mx:
            winners.append(2)
        return winners

    @staticmethod
    def _edge_delta(edge):
        if edge == "N":
            return 0, -1
        if edge == "E":
            return 1, 0
        if edge == "S":
            return 0, 1
        if edge == "W":
            return -1, 0
        raise ValueError(f"Bad edge: {edge}")

    def analyze_board(self, board):
        uf = UnionFind()
        node_meta = {}
        per_tile_lookup = {}
        inst_by_id = {}

        for cell_key, inst in board.items():
            inst_id = inst["instId"]
            inst_by_id[inst_id] = {"cell_key": cell_key, "inst": inst}
            tile = self.rotate_tile(inst["tileId"], inst["rotDeg"])

            road_edge = {}
            city_edge = {}
            field_half = {}

            for feat in tile.get("features") or []:
                node_key = f"{inst_id}:{feat['id']}"
                uf.add(node_key)
                node_meta[node_key] = {
                    "type": feat.get("type"),
                    "ports": list(feat.get("ports") or []),
                    "tags": feat.get("tags") or {},
                    "meeple_placement": feat.get("meeple_placement") or [0.5, 0.5],
                    "inst_id": inst_id,
                    "cell_key": cell_key,
                    "local_id": feat.get("id"),
                }

                if feat.get("type") == "road":
                    for p in feat.get("ports") or []:
                        road_edge[p] = feat.get("id")
                elif feat.get("type") == "city":
                    for p in feat.get("ports") or []:
                        city_edge[p] = feat.get("id")
                elif feat.get("type") == "field":
                    for p in feat.get("ports") or []:
                        field_half[p] = feat.get("id")

            per_tile_lookup[inst_id] = {
                "road_edge": road_edge,
                "city_edge": city_edge,
                "field_half": field_half,
            }

        for cell_key, inst in board.items():
            x, y = self.parse_xy(cell_key)
            look_a = per_tile_lookup[inst["instId"]]

            for nx, ny, edge_a, edge_b, half_pairs in (
                (x + 1, y, "E", "W", (("En", "Wn"), ("Es", "Ws"))),
                (x, y + 1, "S", "N", (("Sw", "Nw"), ("Se", "Ne"))),
            ):
                nk = self.key_xy(nx, ny)
                inst_b = board.get(nk)
                if not inst_b:
                    continue
                look_b = per_tile_lookup[inst_b["instId"]]

                if edge_a in look_a["road_edge"] and edge_b in look_b["road_edge"]:
                    uf.union(
                        f"{inst['instId']}:{look_a['road_edge'][edge_a]}",
                        f"{inst_b['instId']}:{look_b['road_edge'][edge_b]}",
                    )
                if edge_a in look_a["city_edge"] and edge_b in look_b["city_edge"]:
                    uf.union(
                        f"{inst['instId']}:{look_a['city_edge'][edge_a]}",
                        f"{inst_b['instId']}:{look_b['city_edge'][edge_b]}",
                    )

                for half_a, half_b in half_pairs:
                    if half_a in look_a["field_half"] and half_b in look_b["field_half"]:
                        uf.union(
                            f"{inst['instId']}:{look_a['field_half'][half_a]}",
                            f"{inst_b['instId']}:{look_b['field_half'][half_b]}",
                        )

        groups = {}
        for node_key, meta in node_meta.items():
            root = uf.find(node_key)
            if root not in groups:
                groups[root] = {
                    "id": root,
                    "type": meta["type"],
                    "nodes": set(),
                    "tiles": set(),
                    "meeples_by_player": {1: 0, 2: 0},
                    "pennants": 0,
                    "complete": False,
                    "open_ports": set(),
                    "adjacent_count": 0,
                    "adj_completed_cities": set(),
                    "key": "",
                }
            g = groups[root]
            g["nodes"].add(node_key)
            g["tiles"].add(meta["inst_id"])
            if meta["type"] == "city":
                g["pennants"] += int(meta["tags"].get("pennants", 0) or 0)

        for cell_key, inst in board.items():
            for meeple in inst.get("meeples") or []:
                node_key = f"{inst['instId']}:{meeple['featureLocalId']}"
                if node_key not in node_meta:
                    continue
                root = uf.find(node_key)
                g = groups.get(root)
                if not g:
                    continue
                player = int(meeple.get("player", 0) or 0)
                if player in (1, 2):
                    g["meeples_by_player"][player] = g["meeples_by_player"].get(player, 0) + 1

        for g in groups.values():
            g["key"] = self.stable_group_key(g)

        for g in groups.values():
            if g["type"] in ("road", "city"):
                open_ports = set()
                for node_key in g["nodes"]:
                    meta = node_meta[node_key]
                    x, y = self.parse_xy(meta["cell_key"])
                    for edge in meta["ports"]:
                        dx, dy = self._edge_delta(edge)
                        nk = self.key_xy(x + dx, y + dy)
                        n_inst = board.get(nk)
                        if not n_inst:
                            open_ports.add(f"{meta['cell_key']}:{edge}")
                            continue
                        n_lookup = per_tile_lookup[n_inst["instId"]]
                        opp = EDGE_OPP[edge]
                        if g["type"] == "road":
                            if opp not in n_lookup["road_edge"]:
                                open_ports.add(f"{meta['cell_key']}:{edge}")
                        else:
                            if opp not in n_lookup["city_edge"]:
                                open_ports.add(f"{meta['cell_key']}:{edge}")
                g["open_ports"] = open_ports
                g["complete"] = len(open_ports) == 0
            elif g["type"] == "cloister":
                only_inst_id = next(iter(g["tiles"]), None)
                cell_key = (inst_by_id.get(only_inst_id) or {}).get("cell_key")
                if cell_key is None:
                    g["adjacent_count"] = 0
                    g["complete"] = False
                else:
                    x, y = self.parse_xy(cell_key)
                    cnt = 0
                    for dy in (-1, 0, 1):
                        for dx in (-1, 0, 1):
                            if dx == 0 and dy == 0:
                                continue
                            if self.key_xy(x + dx, y + dy) in board:
                                cnt += 1
                    g["adjacent_count"] = cnt
                    g["complete"] = cnt == 8

        for g in groups.values():
            if g["type"] != "field":
                continue
            for node_key in g["nodes"]:
                meta = node_meta[node_key]
                inst = board.get(meta["cell_key"])
                if not inst:
                    continue
                tile_adj = self.field_city_adjacency.get(inst["tileId"], {})
                city_locals = tile_adj.get(meta["local_id"], set())
                for city_local in city_locals:
                    city_node = f"{meta['inst_id']}:{city_local}"
                    if city_node not in node_meta:
                        continue
                    city_root = uf.find(city_node)
                    city_group = groups.get(city_root)
                    if city_group and city_group["type"] == "city" and city_group["complete"]:
                        g["adj_completed_cities"].add(city_group["key"])

        return {"uf": uf, "node_meta": node_meta, "groups": groups}


def normalize_overrides_payload(raw):
    out = raw if isinstance(raw, dict) else dict(DEFAULT_OVERRIDES)
    schema = out.get("schema")
    if not isinstance(schema, dict):
        schema = {}
    schema.setdefault("coords", "normalized_0_1")
    schema.setdefault("fillRule", "evenodd")
    out["schema"] = schema

    tiles = out.get("tiles")
    if not isinstance(tiles, dict):
        tiles = {}
    for tile_id, tile_entry in list(tiles.items()):
        if not isinstance(tile_entry, dict):
            tiles[tile_id] = {"features": {}}
            continue
        feats = tile_entry.get("features")
        if not isinstance(feats, dict):
            tile_entry["features"] = {}
    out["tiles"] = tiles
    return out


def load_overrides_file():
    if not OVERRIDES_FILE.exists():
        return dict(DEFAULT_OVERRIDES)
    with OVERRIDES_FILE.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    return normalize_overrides_payload(payload)


def write_overrides_file(payload):
    payload = normalize_overrides_payload(payload)
    with NamedTemporaryFile("w", delete=False, dir=ROOT, encoding="utf-8") as tmp:
        json.dump(payload, tmp, indent=2)
        tmp.write("\n")
        tmp_path = Path(tmp.name)
    tmp_path.replace(OVERRIDES_FILE)


def sanitize_name(raw):
    name = str(raw or "").strip()
    if not name:
        return "Player"
    return " ".join(name.split())[:28]


def format_clock(ts):
    return time.strftime("%H:%M:%S", time.localtime(ts))


class MultiplayerState:
    def __init__(self, root_dir):
        with (root_dir / TILESET_FILE.name).open("r", encoding="utf-8") as f:
            tileset_payload = json.load(f)

        self.engine = CarcassonneEngine(tileset_payload)
        self.lock = threading.Lock()

        self.users_by_id = {}
        self.user_by_token = {}
        self.invites = {}
        self.matches = {}
        self.chat = []

        self.next_user_id = 1
        self.next_invite_id = 1
        self.next_match_id = 1
        self.next_chat_id = 1

    def _new_user_id(self):
        uid = f"u{self.next_user_id}"
        self.next_user_id += 1
        return uid

    def _new_invite_id(self):
        iid = f"i{self.next_invite_id}"
        self.next_invite_id += 1
        return iid

    def _new_match_id(self):
        mid = f"m{self.next_match_id}"
        self.next_match_id += 1
        return mid

    def _new_chat_id(self):
        cid = f"c{self.next_chat_id}"
        self.next_chat_id += 1
        return cid

    def _push_chat_locked(self, text, from_user=None, system=False):
        now = int(time.time())
        msg = {
            "id": self._new_chat_id(),
            "ts": now,
            "time": format_clock(now),
            "system": bool(system),
            "text": str(text)[:240],
            "from": None,
        }
        if from_user is not None:
            msg["from"] = {"id": from_user["id"], "name": from_user["name"]}
        self.chat.append(msg)
        if len(self.chat) > MAX_CHAT_MESSAGES:
            self.chat = self.chat[-MAX_CHAT_MESSAGES:]

    def _user_match_status_locked(self, user):
        mid = user.get("match_id")
        if not mid:
            return "available"
        match = self.matches.get(mid)
        if not match:
            return "available"
        if match.get("status") == "active":
            return "unavailable"
        return "available"

    def _auth_user_locked(self, token):
        uid = self.user_by_token.get(token)
        if not uid:
            return None
        user = self.users_by_id.get(uid)
        if not user:
            self.user_by_token.pop(token, None)
            return None
        user["last_seen"] = time.time()
        return user

    def _unique_name_locked(self, wanted):
        existing = {u["name"].casefold() for u in self.users_by_id.values()}
        if wanted.casefold() not in existing:
            return wanted
        idx = 2
        while True:
            cand = f"{wanted} ({idx})"
            if cand.casefold() not in existing:
                return cand
            idx += 1

    def _cleanup_locked(self):
        now = time.time()

        stale_user_ids = [
            uid
            for uid, user in self.users_by_id.items()
            if (now - user.get("last_seen", now)) > SESSION_TIMEOUT_SEC
        ]
        for uid in stale_user_ids:
            self._remove_user_locked(uid, reason="timeout")

        for invite in self.invites.values():
            if invite.get("status") != "pending":
                continue
            if (now - invite.get("created_at", now)) > INVITE_TIMEOUT_SEC:
                invite["status"] = "expired"
                invite["responded_at"] = now

    def _remove_user_locked(self, user_id, reason="left"):
        user = self.users_by_id.pop(user_id, None)
        if not user:
            return
        token = user.get("token")
        if token:
            self.user_by_token.pop(token, None)

        for invite in self.invites.values():
            if invite.get("status") != "pending":
                continue
            if user_id in (invite.get("from_user_id"), invite.get("to_user_id")):
                invite["status"] = "expired"
                invite["responded_at"] = time.time()

        mid = user.get("match_id")
        if mid:
            self._abort_match_locked(mid, f"{user['name']} disconnected.")

        if reason == "timeout":
            self._push_chat_locked(f"{user['name']} disconnected.", system=True)
        else:
            self._push_chat_locked(f"{user['name']} left the lobby.", system=True)

    def _abort_match_locked(self, match_id, reason):
        match = self.matches.get(match_id)
        if not match:
            return
        if match.get("status") == "active":
            match["status"] = "aborted"
            match["finished_at"] = time.time()
            match["current_tile"] = None
            match["burned_turn"] = []
            match["turn_intent"] = None
            match["next_tiles"] = {1: None, 2: None}
            match["last_event"] = reason

        for uid in match["players"].values():
            user = self.users_by_id.get(uid)
            if user and user.get("match_id") == match_id:
                user["match_id"] = None
                user["last_match_id"] = match_id

    def _serialize_users_locked(self):
        users = []
        for user in sorted(self.users_by_id.values(), key=lambda u: u["name"].casefold()):
            users.append(
                {
                    "id": user["id"],
                    "name": user["name"],
                    "status": self._user_match_status_locked(user),
                }
            )
        return users

    def _serialize_invite_locked(self, invite):
        from_user = self.users_by_id.get(invite["from_user_id"])
        to_user = self.users_by_id.get(invite["to_user_id"])
        return {
            "id": invite["id"],
            "status": invite["status"],
            "created_at": invite["created_at"],
            "from_user_id": invite["from_user_id"],
            "to_user_id": invite["to_user_id"],
            "from_name": from_user["name"] if from_user else invite.get("from_name") or "Unknown",
            "to_name": to_user["name"] if to_user else invite.get("to_name") or "Unknown",
        }

    def _serialize_lobby_locked(self, user):
        uid = user["id"]
        invites_for_me = []
        invites_sent_by_me = []

        for invite in self.invites.values():
            if invite.get("status") != "pending":
                continue
            if invite.get("to_user_id") == uid:
                invites_for_me.append(self._serialize_invite_locked(invite))
            elif invite.get("from_user_id") == uid:
                invites_sent_by_me.append(self._serialize_invite_locked(invite))

        invites_for_me.sort(key=lambda x: x["created_at"], reverse=True)
        invites_sent_by_me.sort(key=lambda x: x["created_at"], reverse=True)

        return {
            "ok": True,
            "you": {
                "id": user["id"],
                "name": user["name"],
                "status": self._user_match_status_locked(user),
            },
            "users": self._serialize_users_locked(),
            "invites_for_me": invites_for_me,
            "invites_sent_by_me": invites_sent_by_me,
            "chat": self.chat[-90:],
            "current_match_id": user.get("match_id"),
            "last_match_id": user.get("last_match_id"),
        }

    def _pick_random_tile_locked(self, remaining):
        total = 0
        for cnt in remaining.values():
            total += max(0, int(cnt))
        if total <= 0:
            return None

        r = random.randint(1, total)
        acc = 0
        for tile_id in sorted(remaining.keys()):
            cnt = max(0, int(remaining.get(tile_id, 0)))
            if cnt <= 0:
                continue
            acc += cnt
            if r <= acc:
                return tile_id
        return None

    def _draw_reserved_tile_locked(self, match):
        tile_id = self._pick_random_tile_locked(match["remaining"])
        if not tile_id:
            return None
        match["remaining"][tile_id] = max(0, int(match["remaining"].get(tile_id, 0)) - 1)
        return tile_id

    def _ensure_next_tiles_locked(self, match):
        if match.get("status") != "active":
            return
        next_tiles = match.setdefault("next_tiles", {1: None, 2: None})
        turn_player = int(match.get("turn_player") or 1)
        for player in (1, 2):
            if player == turn_player:
                continue
            if next_tiles.get(player):
                continue
            tile_id = self._draw_reserved_tile_locked(match)
            if not tile_id:
                continue
            next_tiles[player] = tile_id

    def _draw_placeable_tile_for_match_locked(self, match):
        burned = []
        turn_player = int(match.get("turn_player") or 1)
        next_tiles = match.setdefault("next_tiles", {1: None, 2: None})
        while True:
            tile_id = next_tiles.get(turn_player)
            if tile_id:
                next_tiles[turn_player] = None
            else:
                tile_id = self._draw_reserved_tile_locked(match)
            if not tile_id:
                match["current_tile"] = None
                match["burned_turn"] = burned
                self._finalize_match_locked(match)
                return

            if self.engine.has_any_placement(match["board"], tile_id):
                match["current_tile"] = tile_id
                match["burned_turn"] = burned
                match["turn_intent"] = None
                self._ensure_next_tiles_locked(match)
                return

            burned.append(tile_id)

    def _new_match_locked(self, user_a_id, user_b_id):
        match_id = self._new_match_id()
        players = {1: user_a_id, 2: user_b_id}

        remaining = copy.deepcopy(self.engine.counts)
        start_tile_id = self.engine.start_tile_id
        if not start_tile_id:
            raise RuntimeError("Tileset has no start tile.")

        board = {
            "0,0": {
                "instId": 1,
                "tileId": start_tile_id,
                "rotDeg": 0,
                "meeples": [],
            }
        }
        remaining[start_tile_id] = max(0, int(remaining.get(start_tile_id, 0)) - 1)

        match = {
            "id": match_id,
            "created_at": time.time(),
            "finished_at": None,
            "status": "active",
            "players": players,
            "user_to_player": {user_a_id: 1, user_b_id: 2},
            "board": board,
            "inst_seq": 2,
            "remaining": remaining,
            "score": {1: 0, 2: 0},
            "scored_keys": set(),
            "meeples_available": {1: 7, 2: 7},
            "turn_player": random.choice([1, 2]),
            "turn_index": 1,
            "current_tile": None,
            "burned_turn": [],
            "next_tiles": {1: None, 2: None},
            "turn_intent": None,
            "last_event": "Match started.",
        }

        self.matches[match_id] = match
        for uid in players.values():
            user = self.users_by_id.get(uid)
            if user:
                user["match_id"] = match_id

        self._ensure_next_tiles_locked(match)
        self._draw_placeable_tile_for_match_locked(match)
        return match

    def _recompute_and_score_locked(self, match, reaward_all=False):
        analysis = self.engine.analyze_board(match["board"])

        if reaward_all:
            match["scored_keys"] = set()
            match["score"] = {1: 0, 2: 0}

        scored_now = set()
        for g in analysis["groups"].values():
            if g["type"] == "field" or not g["complete"]:
                continue
            if g["key"] in match["scored_keys"]:
                continue

            m1 = g["meeples_by_player"].get(1, 0)
            m2 = g["meeples_by_player"].get(2, 0)
            mx = max(m1, m2)
            if mx <= 0:
                match["scored_keys"].add(g["key"])
                continue

            winners = []
            if m1 == mx:
                winners.append(1)
            if m2 == mx:
                winners.append(2)

            pts = self.engine._score_feature(g, True)
            for winner in winners:
                match["score"][winner] += pts

            match["scored_keys"].add(g["key"])
            scored_now.add(g["key"])

        if scored_now:
            for inst in match["board"].values():
                kept = []
                for meeple in inst.get("meeples") or []:
                    node_key = f"{inst['instId']}:{meeple['featureLocalId']}"
                    if node_key not in analysis["node_meta"]:
                        kept.append(meeple)
                        continue
                    gid = analysis["uf"].find(node_key)
                    group = analysis["groups"].get(gid)
                    if not group:
                        kept.append(meeple)
                        continue
                    if group["type"] == "field" or group["key"] not in scored_now:
                        kept.append(meeple)
                        continue
                    player = int(meeple.get("player", 0) or 0)
                    if player in (1, 2):
                        match["meeples_available"][player] = min(
                            7, match["meeples_available"].get(player, 0) + 1
                        )
                inst["meeples"] = kept

    def _finalize_match_locked(self, match):
        if match.get("status") != "active":
            return

        analysis = self.engine.analyze_board(match["board"])
        for g in analysis["groups"].values():
            winners = self.engine.winners_of_group(g)
            if not winners:
                continue

            if g["type"] != "field" and g["complete"] and g["key"] in match["scored_keys"]:
                continue

            pts = self.engine.score_end_now_value(g)
            if pts <= 0:
                continue
            for winner in winners:
                match["score"][winner] += pts

        match["status"] = "finished"
        match["finished_at"] = time.time()
        match["current_tile"] = None
        match["burned_turn"] = []
        match["turn_intent"] = None
        match["next_tiles"] = {1: None, 2: None}
        match["last_event"] = "Match finished."

        p1 = match["score"].get(1, 0)
        p2 = match["score"].get(2, 0)
        u1 = self.users_by_id.get(match["players"][1])
        u2 = self.users_by_id.get(match["players"][2])
        n1 = u1["name"] if u1 else "P1"
        n2 = u2["name"] if u2 else "P2"

        if p1 > p2:
            summary = f"Match finished: {n1} won {p1}-{p2}."
        elif p2 > p1:
            summary = f"Match finished: {n2} won {p2}-{p1}."
        else:
            summary = f"Match finished: draw {p1}-{p2}."
        self._push_chat_locked(summary, system=True)

        for uid in match["players"].values():
            user = self.users_by_id.get(uid)
            if user and user.get("match_id") == match["id"]:
                user["match_id"] = None
                user["last_match_id"] = match["id"]

    def _serialize_match_locked(self, match, for_user):
        players = []
        for p in (1, 2):
            uid = match["players"][p]
            user = self.users_by_id.get(uid)
            players.append(
                {
                    "player": p,
                    "user_id": uid,
                    "name": user["name"] if user else f"Player {p}",
                    "score": int(match["score"].get(p, 0)),
                    "meeples_left": int(match["meeples_available"].get(p, 0)),
                }
            )

        board_pairs = list(match["board"].items())
        board_pairs.sort(key=lambda kv: (self.engine.parse_xy(kv[0])[1], self.engine.parse_xy(kv[0])[0]))

        remaining = {k: int(v) for k, v in match["remaining"].items()}
        remaining_total = sum(max(0, int(v)) for v in remaining.values())

        turn_player = match.get("turn_player")
        turn_uid = match["players"].get(turn_player) if turn_player else None
        turn_user = self.users_by_id.get(turn_uid) if turn_uid else None

        current_turn = None
        if match.get("status") == "active":
            current_turn = {
                "player": turn_player,
                "user_id": turn_uid,
                "name": turn_user["name"] if turn_user else f"Player {turn_player}",
                "tile_id": match.get("current_tile"),
                "burned": list(match.get("burned_turn") or []),
                "turn_index": int(match.get("turn_index") or 0),
            }

        your_player = match["user_to_player"].get(for_user["id"])
        next_tiles = match.get("next_tiles") or {}
        your_next_tile = next_tiles.get(your_player) if your_player in (1, 2) else None
        raw_intent = match.get("turn_intent")
        turn_intent = None
        if isinstance(raw_intent, dict):
            turn_intent = {
                "player": int(raw_intent.get("player") or 0),
                "user_id": raw_intent.get("user_id"),
                "tile_id": raw_intent.get("tile_id"),
                "x": int(raw_intent.get("x") or 0),
                "y": int(raw_intent.get("y") or 0),
                "rot_deg": int(raw_intent.get("rot_deg") or 0),
                "meeple_feature_id": raw_intent.get("meeple_feature_id"),
                "locked": bool(raw_intent.get("locked")),
                "valid": bool(raw_intent.get("valid", True)),
            }

        payload = {
            "ok": True,
            "match": {
                "id": match["id"],
                "status": match["status"],
                "created_at": match["created_at"],
                "finished_at": match["finished_at"],
                "players": players,
                "you_player": your_player,
                "can_act": bool(match["status"] == "active" and your_player == turn_player),
                "your_next_tile": your_next_tile,
                "board": board_pairs,
                "inst_seq": int(match["inst_seq"]),
                "remaining": remaining,
                "remaining_total": remaining_total,
                "score": {"1": int(match["score"].get(1, 0)), "2": int(match["score"].get(2, 0))},
                "meeples_available": {
                    "1": int(match["meeples_available"].get(1, 0)),
                    "2": int(match["meeples_available"].get(2, 0)),
                },
                "current_turn": current_turn,
                "turn_intent": turn_intent,
                "scored_keys": sorted(list(match["scored_keys"])),
                "last_event": match.get("last_event") or "",
            },
        }
        return payload

    def join(self, name_raw):
        with self.lock:
            self._cleanup_locked()
            name = sanitize_name(name_raw)
            name = self._unique_name_locked(name)
            user_id = self._new_user_id()
            token = secrets.token_urlsafe(24)
            now = time.time()
            user = {
                "id": user_id,
                "token": token,
                "name": name,
                "joined_at": now,
                "last_seen": now,
                "match_id": None,
                "last_match_id": None,
            }
            self.users_by_id[user_id] = user
            self.user_by_token[token] = user_id
            self._push_chat_locked(f"{name} joined the lobby.", system=True)
            lobby = self._serialize_lobby_locked(user)
            return {
                "ok": True,
                "token": token,
                "user": {"id": user_id, "name": name},
                "lobby": lobby,
            }

    def heartbeat(self, token):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."
            return {"ok": True, "ts": int(time.time())}, None

    def leave(self, token):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."
            self._remove_user_locked(user["id"], reason="left")
            return {"ok": True}, None

    def lobby(self, token):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."
            return self._serialize_lobby_locked(user), None

    def chat_send(self, token, text):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."
            msg = str(text or "").strip()
            if not msg:
                return None, "Message is empty."
            self._push_chat_locked(msg[:220], from_user=user, system=False)
            return {"ok": True}, None

    def invite(self, token, to_user_id):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."

            if self._user_match_status_locked(user) != "available":
                return None, "You are currently unavailable."

            if to_user_id == user["id"]:
                return None, "Cannot invite yourself."

            other = self.users_by_id.get(to_user_id)
            if not other:
                return None, "User not found."
            if self._user_match_status_locked(other) != "available":
                return None, "That player is unavailable."

            for inv in self.invites.values():
                if inv.get("status") != "pending":
                    continue
                pair = {inv.get("from_user_id"), inv.get("to_user_id")}
                if pair == {user["id"], to_user_id}:
                    return None, "There is already a pending invite between these players."

            invite = {
                "id": self._new_invite_id(),
                "from_user_id": user["id"],
                "to_user_id": to_user_id,
                "from_name": user["name"],
                "to_name": other["name"],
                "status": "pending",
                "created_at": time.time(),
                "responded_at": None,
            }
            self.invites[invite["id"]] = invite
            self._push_chat_locked(f"{user['name']} invited {other['name']}.", system=True)
            return {"ok": True, "invite": self._serialize_invite_locked(invite)}, None

    def invite_respond(self, token, invite_id, action):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."

            invite = self.invites.get(invite_id)
            if not invite:
                return None, "Invite not found."
            if invite.get("status") != "pending":
                return None, "Invite is no longer pending."
            if invite.get("to_user_id") != user["id"]:
                return None, "Only the invited user can respond."

            act = str(action or "").strip().lower()
            if act not in ("accept", "decline"):
                return None, "Action must be 'accept' or 'decline'."

            invite["responded_at"] = time.time()

            from_user = self.users_by_id.get(invite["from_user_id"])
            to_user = self.users_by_id.get(invite["to_user_id"])
            if not from_user or not to_user:
                invite["status"] = "expired"
                return None, "One of the users is no longer connected."

            if act == "decline":
                invite["status"] = "declined"
                self._push_chat_locked(
                    f"{to_user['name']} declined an invite from {from_user['name']}.",
                    system=True,
                )
                return {"ok": True, "invite": self._serialize_invite_locked(invite)}, None

            if self._user_match_status_locked(from_user) != "available":
                invite["status"] = "expired"
                return None, "Inviting player is no longer available."
            if self._user_match_status_locked(to_user) != "available":
                invite["status"] = "expired"
                return None, "You are currently unavailable."

            invite["status"] = "accepted"

            for other_inv in self.invites.values():
                if other_inv.get("status") != "pending":
                    continue
                pair = {other_inv.get("from_user_id"), other_inv.get("to_user_id")}
                if from_user["id"] in pair or to_user["id"] in pair:
                    other_inv["status"] = "canceled"
                    other_inv["responded_at"] = time.time()

            match = self._new_match_locked(from_user["id"], to_user["id"])
            self._push_chat_locked(
                f"Match started: {from_user['name']} vs {to_user['name']}.",
                system=True,
            )
            return {"ok": True, "match": self._serialize_match_locked(match, user)["match"]}, None

    def match_get(self, token):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."

            match_id = user.get("match_id") or user.get("last_match_id")
            if not match_id:
                return {"ok": True, "match": None}, None

            match = self.matches.get(match_id)
            if not match:
                if user.get("match_id") == match_id:
                    user["match_id"] = None
                if user.get("last_match_id") == match_id:
                    user["last_match_id"] = None
                return {"ok": True, "match": None}, None

            return self._serialize_match_locked(match, user), None

    def match_intent(self, token, x, y, rot_deg, meeple_feature_id, clear=False, locked=False):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."

            match_id = user.get("match_id")
            if not match_id:
                return None, "You are not in an active match."

            match = self.matches.get(match_id)
            if not match:
                user["match_id"] = None
                return None, "Match not found."

            player = match["user_to_player"].get(user["id"])
            if clear:
                current_intent = match.get("turn_intent")
                if not current_intent or current_intent.get("user_id") == user["id"]:
                    match["turn_intent"] = None
                return self._serialize_match_locked(match, user), None

            if match.get("status") != "active":
                return None, "Match is not active."
            if player != match.get("turn_player"):
                return None, "Only the active player can publish placement intent."

            tile_id = match.get("current_tile")
            if not tile_id:
                return None, "No tile is currently assigned for this turn."

            try:
                rx = int(x)
                ry = int(y)
                rrot = int(rot_deg)
            except Exception:
                return None, "Invalid placement intent coordinates or rotation."

            rrot = ((rrot % 360) + 360) % 360
            if rrot not in (0, 90, 180, 270):
                return None, "Rotation must be one of 0, 90, 180, 270."

            cell_key = self.engine.key_xy(rx, ry)
            if cell_key in match["board"]:
                return None, "Cell occupied."

            ok, reason = self.engine.can_place_at(match["board"], tile_id, rrot, rx, ry)
            locked_intent = bool(locked)
            if locked_intent and not ok:
                return None, reason

            selected_meeple_feature = None
            if meeple_feature_id is not None:
                fid = str(meeple_feature_id).strip()
                if fid:
                    selected_meeple_feature = fid

            if selected_meeple_feature:
                tile_rot = self.engine.rotate_tile(tile_id, rrot)
                feature_by_id = {f.get("id"): f for f in (tile_rot.get("features") or [])}
                feat = feature_by_id.get(selected_meeple_feature)
                if not feat:
                    return None, "Meeple feature id is invalid for the placed tile."
                if feat.get("type") not in ("road", "city", "field", "cloister"):
                    return None, "Meeple cannot be placed on that feature type."

            match["turn_intent"] = {
                "user_id": user["id"],
                "player": int(player or 0),
                "tile_id": tile_id,
                "x": rx,
                "y": ry,
                "rot_deg": rrot,
                "meeple_feature_id": selected_meeple_feature,
                "locked": locked_intent,
                "valid": bool(ok),
            }
            return self._serialize_match_locked(match, user), None

    def match_submit_turn(self, token, x, y, rot_deg, meeple_feature_id):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."

            match_id = user.get("match_id")
            if not match_id:
                return None, "You are not in an active match."

            match = self.matches.get(match_id)
            if not match:
                user["match_id"] = None
                return None, "Match not found."
            if match.get("status") != "active":
                return None, "Match is not active."

            player = match["user_to_player"].get(user["id"])
            if player != match.get("turn_player"):
                return None, "It is not your turn."

            tile_id = match.get("current_tile")
            if not tile_id:
                return None, "No tile is currently assigned for this turn."

            try:
                rx = int(x)
                ry = int(y)
                rrot = int(rot_deg)
            except Exception:
                return None, "Invalid placement coordinates or rotation."

            rrot = ((rrot % 360) + 360) % 360
            if rrot not in (0, 90, 180, 270):
                return None, "Rotation must be one of 0, 90, 180, 270."

            ok, reason = self.engine.can_place_at(match["board"], tile_id, rrot, rx, ry)
            if not ok:
                return None, reason

            cell_key = self.engine.key_xy(rx, ry)
            inst_id = int(match["inst_seq"])
            inst = {
                "instId": inst_id,
                "tileId": tile_id,
                "rotDeg": rrot,
                "meeples": [],
            }
            match["board"][cell_key] = inst
            match["inst_seq"] = inst_id + 1

            selected_meeple_feature = None
            if meeple_feature_id is not None:
                fid = str(meeple_feature_id).strip()
                if fid:
                    selected_meeple_feature = fid

            if selected_meeple_feature:
                if match["meeples_available"].get(player, 0) <= 0:
                    match["board"].pop(cell_key, None)
                    match["inst_seq"] = inst_id
                    return None, "No meeples remaining for this player."

                tile_rot = self.engine.rotate_tile(tile_id, rrot)
                feature_by_id = {f.get("id"): f for f in (tile_rot.get("features") or [])}
                feat = feature_by_id.get(selected_meeple_feature)
                if not feat:
                    match["board"].pop(cell_key, None)
                    match["inst_seq"] = inst_id
                    return None, "Meeple feature id is invalid for the placed tile."

                if feat.get("type") not in ("road", "city", "field", "cloister"):
                    match["board"].pop(cell_key, None)
                    match["inst_seq"] = inst_id
                    return None, "Meeple cannot be placed on that feature type."

                analysis = self.engine.analyze_board(match["board"])
                node_key = f"{inst_id}:{selected_meeple_feature}"
                if node_key not in analysis["node_meta"]:
                    match["board"].pop(cell_key, None)
                    match["inst_seq"] = inst_id
                    return None, "Failed to analyze selected feature."

                gid = analysis["uf"].find(node_key)
                group = analysis["groups"].get(gid)
                if group:
                    occ = group["meeples_by_player"].get(1, 0) + group["meeples_by_player"].get(2, 0)
                    if occ > 0:
                        match["board"].pop(cell_key, None)
                        match["inst_seq"] = inst_id
                        return None, "Meeple rule: that connected feature is already occupied."

                inst["meeples"].append({"player": player, "featureLocalId": selected_meeple_feature})
                match["meeples_available"][player] = max(0, match["meeples_available"].get(player, 0) - 1)

            self._recompute_and_score_locked(match)

            next_player = 1 if player == 2 else 2
            match["turn_player"] = next_player
            match["turn_index"] = int(match.get("turn_index", 0)) + 1
            match["current_tile"] = None
            match["burned_turn"] = []
            match["turn_intent"] = None
            match["last_event"] = (
                f"{user['name']} placed {tile_id} at ({rx},{ry}) r{rrot}"
                + (f" + meeple {selected_meeple_feature}." if selected_meeple_feature else ".")
            )

            self._draw_placeable_tile_for_match_locked(match)

            return self._serialize_match_locked(match, user), None

    def match_resign(self, token):
        with self.lock:
            self._cleanup_locked()
            user = self._auth_user_locked(token)
            if not user:
                return None, "Invalid session token."

            match_id = user.get("match_id")
            if not match_id:
                return None, "You are not in a match."

            match = self.matches.get(match_id)
            if not match:
                user["match_id"] = None
                return None, "Match not found."

            if match.get("status") == "active":
                self._abort_match_locked(match_id, f"{user['name']} resigned.")
                self._push_chat_locked(
                    f"Match ended early: {user['name']} resigned.",
                    system=True,
                )

            return {"ok": True}, None


STATE = MultiplayerState(ROOT)


class CarcassonneHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def _write_json(self, payload, status=HTTPStatus.OK):
        body = json.dumps(payload, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self):
        try:
            raw_len = self.headers.get("Content-Length", "0")
            content_len = int(raw_len)
        except ValueError:
            return None, "Invalid Content-Length"

        body = self.rfile.read(content_len)
        try:
            payload = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            return None, "Invalid JSON payload"

        if not isinstance(payload, dict):
            return None, "Payload must be a JSON object"
        return payload, None

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/overrides":
            try:
                payload = load_overrides_file()
            except Exception as exc:  # pragma: no cover
                self._write_json(
                    {"ok": False, "error": f"Failed to read overrides: {exc}"},
                    status=HTTPStatus.INTERNAL_SERVER_ERROR,
                )
                return
            self._write_json(payload)
            return

        if path == "/api/lobby":
            token = (parse_qs(parsed.query).get("token") or [""])[0]
            payload, err = STATE.lobby(token)
            if err:
                self._write_json({"ok": False, "error": err}, status=HTTPStatus.UNAUTHORIZED)
                return
            self._write_json(payload)
            return

        if path == "/api/match":
            token = (parse_qs(parsed.query).get("token") or [""])[0]
            payload, err = STATE.match_get(token)
            if err:
                self._write_json({"ok": False, "error": err}, status=HTTPStatus.UNAUTHORIZED)
                return
            self._write_json(payload)
            return

        return super().do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/overrides":
            payload, err = self._read_json_body()
            if err:
                self._write_json({"ok": False, "error": err}, status=HTTPStatus.BAD_REQUEST)
                return
            try:
                write_overrides_file(payload)
            except Exception as exc:  # pragma: no cover
                self._write_json(
                    {"ok": False, "error": f"Failed to save overrides: {exc}"},
                    status=HTTPStatus.INTERNAL_SERVER_ERROR,
                )
                return
            self._write_json({"ok": True, "file": OVERRIDES_FILE.name})
            return

        payload, err = self._read_json_body()
        if err:
            self._write_json({"ok": False, "error": err}, status=HTTPStatus.BAD_REQUEST)
            return

        if path == "/api/session/join":
            name = payload.get("name")
            self._write_json(STATE.join(name))
            return

        if path == "/api/session/heartbeat":
            res, msg = STATE.heartbeat(payload.get("token"))
            if msg:
                self._write_json({"ok": False, "error": msg}, status=HTTPStatus.UNAUTHORIZED)
                return
            self._write_json(res)
            return

        if path == "/api/session/leave":
            res, msg = STATE.leave(payload.get("token"))
            if msg:
                self._write_json({"ok": False, "error": msg}, status=HTTPStatus.UNAUTHORIZED)
                return
            self._write_json(res)
            return

        if path == "/api/chat":
            res, msg = STATE.chat_send(payload.get("token"), payload.get("text"))
            if msg:
                code = HTTPStatus.UNAUTHORIZED if "token" in msg.lower() else HTTPStatus.BAD_REQUEST
                self._write_json({"ok": False, "error": msg}, status=code)
                return
            self._write_json(res)
            return

        if path == "/api/invite":
            res, msg = STATE.invite(payload.get("token"), payload.get("to_user_id"))
            if msg:
                code = HTTPStatus.UNAUTHORIZED if "token" in msg.lower() else HTTPStatus.BAD_REQUEST
                self._write_json({"ok": False, "error": msg}, status=code)
                return
            self._write_json(res)
            return

        if path == "/api/invite/respond":
            res, msg = STATE.invite_respond(payload.get("token"), payload.get("invite_id"), payload.get("action"))
            if msg:
                code = HTTPStatus.UNAUTHORIZED if "token" in msg.lower() else HTTPStatus.BAD_REQUEST
                self._write_json({"ok": False, "error": msg}, status=code)
                return
            self._write_json(res)
            return

        if path == "/api/match/intent":
            res, msg = STATE.match_intent(
                payload.get("token"),
                payload.get("x"),
                payload.get("y"),
                payload.get("rot_deg"),
                payload.get("meeple_feature_id"),
                payload.get("clear"),
                payload.get("locked"),
            )
            if msg:
                code = HTTPStatus.UNAUTHORIZED if "token" in msg.lower() else HTTPStatus.BAD_REQUEST
                self._write_json({"ok": False, "error": msg}, status=code)
                return
            self._write_json(res)
            return

        if path == "/api/match/submit_turn":
            res, msg = STATE.match_submit_turn(
                payload.get("token"),
                payload.get("x"),
                payload.get("y"),
                payload.get("rot_deg"),
                payload.get("meeple_feature_id"),
            )
            if msg:
                code = HTTPStatus.UNAUTHORIZED if "token" in msg.lower() else HTTPStatus.BAD_REQUEST
                self._write_json({"ok": False, "error": msg}, status=code)
                return
            self._write_json(res)
            return

        if path == "/api/match/resign":
            res, msg = STATE.match_resign(payload.get("token"))
            if msg:
                code = HTTPStatus.UNAUTHORIZED if "token" in msg.lower() else HTTPStatus.BAD_REQUEST
                self._write_json({"ok": False, "error": msg}, status=code)
                return
            self._write_json(res)
            return

        self._write_json({"ok": False, "error": "Not found"}, status=HTTPStatus.NOT_FOUND)


def main():
    parser = argparse.ArgumentParser(
        description="Serve Carcassonne app with override + multiplayer APIs"
    )
    parser.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8000, help="Bind port (default: 8000)")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), CarcassonneHandler)
    print(f"Serving {ROOT} at http://{args.host}:{args.port}")
    print(f"Overrides file: {OVERRIDES_FILE}")
    print("Multiplayer API enabled: /api/session/*, /api/lobby, /api/chat, /api/invite/*, /api/match/* (including /api/match/intent)")
    server.serve_forever()


if __name__ == "__main__":
    main()
