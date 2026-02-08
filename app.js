
/*
  Minimal Carcassonne simulator (base A–X) — UI + polygon highlight areas.

  Fix:
  - Meeple placement points are NOT rotated in data, because the tile element is rotated in CSS.

  Added:
  - Loads carcassonne_base_A-X_areas.json (approx polygons per feature) and uses them for hover highlighting.
*/

const APP_VERSION = "3.8.0";
console.log(`Carcassonne sim ${APP_VERSION}`);

const OVERRIDES_STORAGE_KEY = "carc_areas_overrides_v1";
const OVERRIDES_FILE_NAME = "everrides.json";
const OVERRIDES_API_PATH = "/api/overrides";

const EDGE_OPP = { N:"S", E:"W", S:"N", W:"E" };

const PORT_ROT_CW = {
  // field edge-halves
  Nw: "En", Ne: "Es", En: "Se", Es: "Sw", Se: "Ws", Sw: "Wn", Ws: "Nw", Wn: "Ne"
};
const PORT_DISPLAY_ORDER = ["Nw","N","Ne","Wn","W","Ws","En","E","Es","Sw","S","Se"];
const PORT_GRID_5X5 = [
  [null, "Nw", "N", "Ne", null],
  ["Wn", null, null, null, "En"],
  ["W",  null, null, null, "E"],
  ["Ws", null, null, null, "Es"],
  [null, "Sw", "S", "Se", null]
];
const PORTS_BY_FEATURE_TYPE = {
  field: ["Nw","Ne","En","Es","Se","Sw","Ws","Wn"],
  road: ["N","E","S","W"],
  city: ["N","E","S","W"],
  cloister: []
};
const REFINE_TYPE_PRIORITY = { cloister: 0, road: 1, city: 2, field: 3 };
const EDGE_TO_FIELD_HALVES = {
  N: ["Nw","Ne"],
  E: ["En","Es"],
  S: ["Sw","Se"],
  W: ["Wn","Ws"]
};
const CITY_EDGE_TO_ADJ_FIELD_PORTS = {
  N: ["Nw","Ne","Wn","En"],
  E: ["En","Es","Ne","Se"],
  S: ["Sw","Se","Ws","Es"],
  W: ["Wn","Ws","Nw","Sw"]
};
const SIDEBAR_WIDTH_STORAGE_KEY = "carc_sidebar_width_px_v1";
function allowedPortsForType(type){
  return PORTS_BY_FEATURE_TYPE[type] ? [...PORTS_BY_FEATURE_TYPE[type]] : [];
}
function normalizePortsForType(ports, type){
  const allowed = new Set(allowedPortsForType(type));
  if(allowed.size===0 || !Array.isArray(ports)) return [];
  const input = new Set(ports);
  const out = [];
  for(const p of PORT_DISPLAY_ORDER){
    if(allowed.has(p) && input.has(p)) out.push(p);
  }
  for(const p of ports){
    if(allowed.has(p) && !out.includes(p)) out.push(p);
  }
  return out;
}
function rotPort(p, rotDeg){
  let steps = ((rotDeg % 360) + 360) % 360 / 90;
  let q = p;
  for(let i=0;i<steps;i++){
    if(q in PORT_ROT_CW) q = PORT_ROT_CW[q];
    else if(q==="N") q="E";
    else if(q==="E") q="S";
    else if(q==="S") q="W";
    else if(q==="W") q="N";
    else throw new Error("Unknown port: "+q);
  }
  return q;
}

class UnionFind {
  constructor(){ this.parent=new Map(); this.rank=new Map(); }
  add(x){ if(!this.parent.has(x)){ this.parent.set(x,x); this.rank.set(x,0); } }
  find(x){ let p=this.parent.get(x); if(p===x) return x; const r=this.find(p); this.parent.set(x,r); return r; }
  union(a,b){
    this.add(a); this.add(b);
    let ra=this.find(a), rb=this.find(b);
    if(ra===rb) return;
    let rka=this.rank.get(ra), rkb=this.rank.get(rb);
    if(rka<rkb){ [ra,rb]=[rb,ra]; [rka,rkb]=[rkb,rka]; }
    this.parent.set(rb,ra);
    if(rka===rkb) this.rank.set(ra,rka+1);
  }
}

function keyXY(x,y){ return `${x},${y}`; }
function parseXY(k){ const [x,y]=k.split(",").map(Number); return {x,y}; }
function deepCopy(x){ return JSON.parse(JSON.stringify(x)); }
function arraysEqual(a,b){
  if(a===b) return true;
  if(!Array.isArray(a) || !Array.isArray(b)) return false;
  if(a.length!==b.length) return false;
  for(let i=0;i<a.length;i++){
    if(a[i]!==b[i]) return false;
  }
  return true;
}
const $ = (sel)=>document.querySelector(sel);

const state = {
  tileset: null,
  tileById: new Map(),
  counts: null,
  remaining: null,
  useCounts: true,

  areasBase: null,      // base polygons from carcassonne_base_A-X_areas.json
  areasOverride: null,  // manual overrides (localStorage / imported)

  board: new Map(),   // "x,y" -> {instId, tileId, rotDeg, meeples: [{player, featureLocalId}]}
  instSeq: 1,

  selectedTileId: "A",
  selectedRot: 0,
  selectedPlayer: 1,
  ignoreMeepleRule: false,
  simplifiedGraphics: false,

  scoredKeys: new Set(),
  score: {1:0, 2:0},
  undoStack: [],

  selectedCell: null,
  hoverCell: null,
  boardHot: false,
  boardPanning: false,
  sidebarCollapsed: false,

  hoverFeature: null, // { type, tiles:Set(cellKey), markers:[{cellKey,type,pt}], featureIdsByCell: Map(cellKey -> Set(localId)) }
  hoverMarkerKey: null, // "x,y:featureId" marker currently driving hoverFeature
  selectedScoreKey: null, // group key currently selected in score UI
  selectedScoreFeature: null, // {key,type,tiles,featureIdsByCell,nodeKeys,cityFeatureIdsByCell?,cityLabelsByCell?}
  selectedScoreTone: null, // null|"neutral"|"p1"|"p2"|"tie"
  scoreDetailsOpen: { city: true, road: false, cloister: false, field: true },
  onlineFeatureSectionsOpen: { open: true, closed: false },
  activeTab: "online",
  refine: { list: [], idx: 0, N: 256, brushR: 10, down: false, mode: "add", mask: null, tileId:null, featureId:null, featureType:null, canvas:null, ctx:null, baseImg:null, maskHashLoaded: 0, lastMouse: {x:0.5,y:0.5, has:false} },
  online: {
    connected: false,
    token: null,
    userId: null,
    userName: "",
    lobby: null,
    match: null,
    pollTimer: null,
    hbTimer: null,
    pollBusy: false,
    pendingTile: null, // {x,y,rotDeg,tileId}
    tileLocked: false,
    pendingMeepleFeatureId: null,
    localSnapshot: null,
    chatRenderedLastId: null,
    matchRenderSig: null,
    intentInFlight: false,
    intentQueued: false,
    lastIntentSig: ""
  }
};

function clearHoverFeatureState(){
  state.hoverFeature = null;
  state.hoverMarkerKey = null;
}
function clearSelectedScoreState(){
  state.selectedScoreKey = null;
  state.selectedScoreFeature = null;
  state.selectedScoreTone = null;
}

function snapshot(){
  return {
    board: Array.from(state.board.entries()).map(([k,v])=>[k, deepCopy(v)]),
    instSeq: state.instSeq,
    scoredKeys: Array.from(state.scoredKeys),
    score: deepCopy(state.score),
    remaining: deepCopy(state.remaining),
    selectedTileId: state.selectedTileId,
    selectedRot: state.selectedRot,
    selectedPlayer: state.selectedPlayer
  };
}
function restore(snap){
  state.board = new Map(snap.board);
  state.instSeq = snap.instSeq;
  state.scoredKeys = new Set(snap.scoredKeys);
  state.score = snap.score;
  state.remaining = snap.remaining;
  state.selectedTileId = snap.selectedTileId;
  state.selectedRot = snap.selectedRot;
  state.selectedPlayer = snap.selectedPlayer;
  state.selectedCell = null;
  state.hoverCell = null;
  clearHoverFeatureState();
  clearSelectedScoreState();
}
function pushUndo(){ state.undoStack.push(snapshot()); if(state.undoStack.length>120) state.undoStack.shift(); }

function setStatus(msg){ $("#status").textContent = msg; }
function tileImageUrl(tileId){ return `images/tile_${tileId}.png`; }

const SIMPLE_COLORS = {
  field: "#7dac45",
  city: "#d2b059",
  road: "#ffffff",
  roadOutline: "#111111",
  outline: "#111111",
  cloister: "#d53636",
  crest: "#3f78d9"
};
const SIMPLE_EDGE_ANCHOR = {
  N: [50, 0],
  E: [100, 50],
  S: [50, 100],
  W: [0, 50]
};
const SIMPLE_FIELD_PORT_SEED = {
  Nw: [25, 8],
  Ne: [75, 8],
  En: [92, 25],
  Es: [92, 75],
  Se: [75, 92],
  Sw: [25, 92],
  Ws: [8, 75],
  Wn: [8, 25]
};
const tileImgCache = new Map();

function svgEl(tag){
  return document.createElementNS("http://www.w3.org/2000/svg", tag);
}

function smoothRingPath(poly){
  if(!Array.isArray(poly) || poly.length<3) return "";
  const pts = poly.map(([x,y])=>[x*100, y*100]);
  const n = pts.length;
  const pLast = pts[n-1];
  const p0 = pts[0];
  const start = [(pLast[0]+p0[0])/2, (pLast[1]+p0[1])/2];

  let d = `M ${start[0].toFixed(2)} ${start[1].toFixed(2)}`;
  for(let i=0;i<n;i++){
    const p = pts[i];
    const q = pts[(i+1)%n];
    const m = [(p[0]+q[0])/2, (p[1]+q[1])/2];
    d += ` Q ${p[0].toFixed(2)} ${p[1].toFixed(2)} ${m[0].toFixed(2)} ${m[1].toFixed(2)}`;
  }
  d += " Z";
  return d;
}

function edgePortsOfFeature(feat){
  return (feat.ports || []).filter(p=>p==="N" || p==="E" || p==="S" || p==="W");
}

function normalizedEdgePorts(ports){
  const keep = new Set((ports || []).filter(p=>p==="N"||p==="E"||p==="S"||p==="W"));
  return ["N","E","S","W"].filter(p=>keep.has(p));
}

function rotatePointCW(pt, steps){
  let [x,y] = pt;
  for(let i=0;i<steps;i++){
    [x,y] = [100-y, x];
  }
  return [x,y];
}

function rotatePointsCW(points, steps){
  return points.map(p=>rotatePointCW(p, steps));
}

function smoothPathFromPoints(points){
  return smoothRingPath((points||[]).map(([x,y])=>[x/100, y/100]));
}

function polygonPathFromPoints(points){
  if(!Array.isArray(points) || points.length<3) return "";
  let d = `M ${points[0][0].toFixed(2)} ${points[0][1].toFixed(2)}`;
  for(let i=1;i<points.length;i++){
    d += ` L ${points[i][0].toFixed(2)} ${points[i][1].toFixed(2)}`;
  }
  d += " Z";
  return d;
}

function featureCenterPx(feat){
  const mp = Array.isArray(feat?.meeple_placement) ? feat.meeple_placement : [0.5,0.5];
  return [mp[0]*100, mp[1]*100];
}

function cityOneEdgeFanPoints(edge, depth=30, samples=16){
  const base = [[0,0],[100,0]];
  for(let i=1;i<samples;i++){
    const x = 100 - (i*100/samples);
    const t = Math.sin(Math.PI*(x/100));
    const y = depth * Math.max(0, t);
    base.push([x, y]);
  }
  const steps = {N:0, E:1, S:2, W:3}[edge] ?? 0;
  return rotatePointsCW(base, steps);
}

function sampleQuadratic(p0, c, p1, n=14){
  const out = [];
  for(let i=1;i<n;i++){
    const t = i / n;
    const mt = 1 - t;
    out.push([
      mt*mt*p0[0] + 2*mt*t*c[0] + t*t*p1[0],
      mt*mt*p0[1] + 2*mt*t*c[1] + t*t*p1[1]
    ]);
  }
  return out;
}

function cityAdjacentEdgesPoints(ports){
  const s = new Set(ports || []);
  // Base orientation: N+W city. Curve from (100,0) to (0,100), bent toward corner (0,0).
  const base = [[0,0],[100,0]];
  base.push(...sampleQuadratic([100,0],[28,28],[0,100],16));
  base.push([0,100]);

  let steps = 0;
  if(s.has("N") && s.has("E")) steps = 1;
  else if(s.has("E") && s.has("S")) steps = 2;
  else if(s.has("S") && s.has("W")) steps = 3;
  return rotatePointsCW(base, steps);
}

function cityThreeEdgesPoints(missing){
  // Base orientation: missing S (city on N+E+W).
  // Smooth inward boundary only on missing edge.
  const base = [[0,0],[100,0],[100,100]];
  base.push(...sampleQuadratic([100,100],[50,72],[0,100],18));
  base.push([0,100]);
  const steps = {S:0, W:1, N:2, E:3}[missing] ?? 0;
  return rotatePointsCW(base, steps);
}

function cityPointsFromPorts(ports){
  const p = normalizedEdgePorts(ports);
  const s = new Set(p);

  if(p.length===0) return [[34,34],[66,34],[66,66],[34,66]];
  if(p.length===4) return [[0,0],[100,0],[100,100],[0,100]];
  if(p.length===1) return cityOneEdgeFanPoints(p[0]);

  if(p.length===2){
    const a = p[0], b = p[1];
    const opposite = (a==="N"&&b==="S") || (a==="S"&&b==="N") || (a==="E"&&b==="W") || (a==="W"&&b==="E");
    if(opposite){
      const baseNS = [[0,0],[100,0],[82,46],[82,54],[100,100],[0,100],[18,54],[18,46]];
      const steps = (a==="N" || a==="S") ? 0 : 1;
      return rotatePointsCW(baseNS, steps);
    }
    return cityAdjacentEdgesPoints(p);
  }

  const missing = ["N","E","S","W"].find(e=>!s.has(e));
  return cityThreeEdgesPoints(missing);
}

function cityPathFromPorts(ports){
  const pts = cityPointsFromPorts(ports || []);
  return polygonPathFromPoints(pts);
}

function roadSplitsFieldsAtEdge(roadFeat, feats, edge){
  const fieldOwner = {};
  for(const f of feats){
    if(f.type!=="field") continue;
    for(const hp of (f.ports || [])){
      if(hp==="Nw"||hp==="Ne"||hp==="En"||hp==="Es"||hp==="Se"||hp==="Sw"||hp==="Ws"||hp==="Wn"){
        fieldOwner[hp] = f.id;
      }
    }
  }
  const hs = EDGE_TO_FIELD_HALVES[edge] || [];
  if(hs.length!==2) return false;
  return !!fieldOwner[hs[0]] && !!fieldOwner[hs[1]] && fieldOwner[hs[0]] !== fieldOwner[hs[1]];
}

function roadDeadEndTarget(roadFeat, feats){
  const port = edgePortsOfFeature(roadFeat)[0];
  const split = roadSplitsFieldsAtEdge(roadFeat, feats, port);

  const clo = feats.find(f=>f.type==="cloister");
  if(clo){
    return {pt: [50,50], dot:false};
  }

  const cities = feats.filter(f=>f.type==="city");
  if(cities.length){
    if(port==="N") return {pt:[50,52], dot:false};
    if(port==="S") return {pt:[50,48], dot:false};
    if(port==="E") return {pt:[48,50], dot:false};
    if(port==="W") return {pt:[52,50], dot:false};
  }

  if(port==="N") return {pt:[50, split ? 36 : 28], dot:true};
  if(port==="S") return {pt:[50, split ? 64 : 72], dot:true};
  if(port==="E") return {pt:[split ? 64 : 72, 50], dot:true};
  if(port==="W") return {pt:[split ? 36 : 28, 50], dot:true};
  return {pt:[50,50], dot:true};
}

function edgeInwardControl(edge, dist=24){
  if(edge==="N") return [50, dist];
  if(edge==="S") return [50, 100-dist];
  if(edge==="E") return [100-dist, 50];
  return [dist, 50];
}

function drawRoadStroke(svg, d){
  const pOutline = svgEl("path");
  pOutline.setAttribute("d", d);
  pOutline.setAttribute("fill", "none");
  pOutline.setAttribute("stroke", SIMPLE_COLORS.roadOutline);
  pOutline.setAttribute("stroke-width", "10");
  pOutline.setAttribute("stroke-linecap", "round");
  pOutline.setAttribute("stroke-linejoin", "round");
  svg.appendChild(pOutline);

  const pRoad = svgEl("path");
  pRoad.setAttribute("d", d);
  pRoad.setAttribute("fill", "none");
  pRoad.setAttribute("stroke", SIMPLE_COLORS.road);
  pRoad.setAttribute("stroke-width", "6.2");
  pRoad.setAttribute("stroke-linecap", "round");
  pRoad.setAttribute("stroke-linejoin", "round");
  svg.appendChild(pRoad);
}

function roadFeatureDrawData(feat, feats, roadCtx=null){
  const out = { paths: [], dots: [] };
  const ports = edgePortsOfFeature(feat);
  if(ports.length===0) return out;

  if(ports.length===1){
    const edge = ports[0];
    const a = SIMPLE_EDGE_ANCHOR[edge];
    const sharedJunction = roadCtx?.singleToJunction?.get(feat.id) || null;
    if(sharedJunction){
      out.paths.push(`M ${a[0]} ${a[1]} L ${sharedJunction[0].toFixed(2)} ${sharedJunction[1].toFixed(2)}`);
      return out;
    }

    const tgt = roadDeadEndTarget(feat, feats);
    out.paths.push(`M ${a[0]} ${a[1]} L ${tgt.pt[0].toFixed(2)} ${tgt.pt[1].toFixed(2)}`);
    if(tgt.dot){
      out.dots.push({cx:tgt.pt[0], cy:tgt.pt[1], r:3.2});
    }
    return out;
  }

  if(ports.length===2){
    const [p0,p1] = ports;
    const a = SIMPLE_EDGE_ANCHOR[p0], b = SIMPLE_EDGE_ANCHOR[p1];
    const c1 = edgeInwardControl(p0, 24);
    const c2 = edgeInwardControl(p1, 24);
    out.paths.push(`M ${a[0]} ${a[1]} C ${c1[0]} ${c1[1]} ${c2[0]} ${c2[1]} ${b[0]} ${b[1]}`);
    return out;
  }

  const anchors = ports.map(p=>SIMPLE_EDGE_ANCHOR[p]).filter(Boolean);
  const mp = featureCenterPx(feat);
  const jx = (anchors.reduce((a,p)=>a+p[0],0) + mp[0]) / (anchors.length + 1);
  const jy = (anchors.reduce((a,p)=>a+p[1],0) + mp[1]) / (anchors.length + 1);
  const junction = [jx, jy];

  for(const a of anchors){
    const c = [(a[0]*2 + junction[0])/3, (a[1]*2 + junction[1])/3];
    out.paths.push(`M ${a[0]} ${a[1]} Q ${c[0].toFixed(2)} ${c[1].toFixed(2)} ${junction[0].toFixed(2)} ${junction[1].toFixed(2)}`);
  }
  out.dots.push({cx:junction[0], cy:junction[1], r:3.3});
  return out;
}

function buildRoadRenderContext(roads, feats){
  const onePortRoads = roads.filter(r=>edgePortsOfFeature(r).length===1);
  const multiPortRoads = roads.filter(r=>edgePortsOfFeature(r).length>1);
  const singleToJunction = new Map();
  let junction = null;

  // Crossroads can be encoded as separate one-port roads, including tiles with a city.
  if(multiPortRoads.length===0 && onePortRoads.length>=3){
    junction = [50,50];
    for(const r of onePortRoads) singleToJunction.set(r.id, junction);
  }

  return {singleToJunction, junction};
}

function drawRoadFeature(svg, feat, feats, roadCtx=null){
  const draw = roadFeatureDrawData(feat, feats, roadCtx);
  for(const d of draw.paths) drawRoadStroke(svg, d);
  for(const dot of draw.dots){
    const cc = svgEl("circle");
    cc.setAttribute("cx", dot.cx.toFixed(2));
    cc.setAttribute("cy", dot.cy.toFixed(2));
    cc.setAttribute("r", String(dot.r));
    cc.setAttribute("fill", SIMPLE_COLORS.roadOutline);
    svg.appendChild(cc);
  }
}

function drawCloisterCross(svg, feat){
  const mp = Array.isArray(feat.meeple_placement) ? feat.meeple_placement : [0.5,0.5];
  const cx = mp[0]*100, cy = mp[1]*100;

  const vert0 = svgEl("line");
  vert0.setAttribute("x1", cx);
  vert0.setAttribute("y1", cy-16);
  vert0.setAttribute("x2", cx);
  vert0.setAttribute("y2", cy+14);
  vert0.setAttribute("stroke", SIMPLE_COLORS.outline);
  vert0.setAttribute("stroke-width", "9");
  vert0.setAttribute("stroke-linecap", "round");
  svg.appendChild(vert0);

  const hor0 = svgEl("line");
  hor0.setAttribute("x1", cx-12);
  hor0.setAttribute("y1", cy-3);
  hor0.setAttribute("x2", cx+12);
  hor0.setAttribute("y2", cy-3);
  hor0.setAttribute("stroke", SIMPLE_COLORS.outline);
  hor0.setAttribute("stroke-width", "7");
  hor0.setAttribute("stroke-linecap", "round");
  svg.appendChild(hor0);

  const vert1 = svgEl("line");
  vert1.setAttribute("x1", cx);
  vert1.setAttribute("y1", cy-16);
  vert1.setAttribute("x2", cx);
  vert1.setAttribute("y2", cy+14);
  vert1.setAttribute("stroke", SIMPLE_COLORS.cloister);
  vert1.setAttribute("stroke-width", "5");
  vert1.setAttribute("stroke-linecap", "round");
  svg.appendChild(vert1);

  const hor1 = svgEl("line");
  hor1.setAttribute("x1", cx-12);
  hor1.setAttribute("y1", cy-3);
  hor1.setAttribute("x2", cx+12);
  hor1.setAttribute("y2", cy-3);
  hor1.setAttribute("stroke", SIMPLE_COLORS.cloister);
  hor1.setAttribute("stroke-width", "3.6");
  hor1.setAttribute("stroke-linecap", "round");
  svg.appendChild(hor1);
}

function drawShield(svg, cx, cy, s=5.8){
  const pts = [
    [cx, cy-s*1.2],
    [cx+s*0.9, cy-s*0.7],
    [cx+s*0.8, cy+s*0.5],
    [cx, cy+s*1.5],
    [cx-s*0.8, cy+s*0.5],
    [cx-s*0.9, cy-s*0.7]
  ];
  const pg = svgEl("polygon");
  pg.setAttribute("points", pts.map(p=>`${p[0].toFixed(2)},${p[1].toFixed(2)}`).join(" "));
  pg.setAttribute("fill", SIMPLE_COLORS.crest);
  pg.setAttribute("stroke", SIMPLE_COLORS.outline);
  pg.setAttribute("stroke-width", "1.1");
  pg.setAttribute("stroke-linejoin", "round");
  svg.appendChild(pg);
}

function drawCityCrests(svg, feat){
  const cnt = Math.max(0, Number(feat?.tags?.pennants || 0)|0);
  if(cnt<=0) return;
  const mp = Array.isArray(feat.meeple_placement) ? feat.meeple_placement : [0.5,0.5];
  const cx = mp[0]*100, cy = mp[1]*100;
  if(cnt===1){
    drawShield(svg, cx, cy);
    return;
  }
  for(let i=0;i<cnt;i++){
    const a = (Math.PI*2*i)/cnt;
    drawShield(svg, cx + Math.cos(a)*7.2, cy + Math.sin(a)*7.2, 5.2);
  }
}

function fieldSeedFromPorts(feat){
  const ports = normalizePortsForType(feat?.ports || [], "field");
  if(!ports.length) return [50,50];
  let sx = 0, sy = 0, c = 0;
  for(const p of ports){
    const pt = SIMPLE_FIELD_PORT_SEED[p];
    if(!pt) continue;
    sx += pt[0];
    sy += pt[1];
    c++;
  }
  if(c<=0) return [50,50];
  return [sx/c, sy/c];
}

function findNearestMaskComponent(mask, comp, N, sx, sy){
  const clamp = (v)=>Math.max(0, Math.min(N-1, v|0));
  sx = clamp(sx); sy = clamp(sy);
  const start = sy*N + sx;
  if(mask[start]) return comp[start];

  const lim = Math.max(N, N);
  for(let r=1; r<lim; r++){
    const x0 = Math.max(0, sx-r), x1 = Math.min(N-1, sx+r);
    const y0 = Math.max(0, sy-r), y1 = Math.min(N-1, sy+r);

    for(let x=x0; x<=x1; x++){
      const iTop = y0*N + x;
      if(mask[iTop]) return comp[iTop];
      const iBot = y1*N + x;
      if(mask[iBot]) return comp[iBot];
    }
    for(let y=y0+1; y<y1; y++){
      const iL = y*N + x0;
      if(mask[iL]) return comp[iL];
      const iR = y*N + x1;
      if(mask[iR]) return comp[iR];
    }
  }
  return -1;
}

function connectedComponents(mask, N){
  const comp = new Int32Array(N*N);
  comp.fill(-1);
  const components = [];
  const q = new Int32Array(N*N);

  for(let i=0;i<N*N;i++){
    if(!mask[i] || comp[i]!==-1) continue;
    const cid = components.length;
    const cells = [];
    let qs = 0, qe = 0;
    q[qe++] = i;
    comp[i] = cid;

    while(qs<qe){
      const cur = q[qs++];
      cells.push(cur);
      const x = cur % N;
      const y = (cur / N) | 0;

      const tryPush = (nx, ny)=>{
        if(nx<0 || ny<0 || nx>=N || ny>=N) return;
        const ni = ny*N + nx;
        if(!mask[ni] || comp[ni]!==-1) return;
        comp[ni] = cid;
        q[qe++] = ni;
      };
      tryPush(x-1, y);
      tryPush(x+1, y);
      tryPush(x, y-1);
      tryPush(x, y+1);
    }
    components.push(cells);
  }

  return {comp, components};
}

function cloisterLineSpecs(feat){
  const mp = Array.isArray(feat?.meeple_placement) ? feat.meeple_placement : [0.5,0.5];
  const cx = mp[0]*100, cy = mp[1]*100;
  return [
    {x1:cx, y1:cy-16, x2:cx, y2:cy+14, width:9},
    {x1:cx-12, y1:cy-3, x2:cx+12, y2:cy-3, width:7},
    {x1:cx, y1:cy-16, x2:cx, y2:cy+14, width:5},
    {x1:cx-12, y1:cy-3, x2:cx+12, y2:cy-3, width:3.6}
  ];
}

function buildSimplifiedFeatureGeometry(tileId){
  if(simplifiedFeatureGeomCache.has(tileId)) return simplifiedFeatureGeomCache.get(tileId);

  const feats = mergedFeaturesForTile(tileId).filter(f=>["city","road","field","cloister"].includes(f.type));
  const roads = feats.filter(f=>f.type==="road");
  const cities = feats.filter(f=>f.type==="city");
  const cloisters = feats.filter(f=>f.type==="cloister");
  const fields = feats.filter(f=>f.type==="field");
  const roadCtx = buildRoadRenderContext(roads, feats);

  const byFeature = new Map();
  for(const c of cities){
    const d = cityPathFromPorts(c.ports || []);
    byFeature.set(c.id, {
      type: "city",
      fillPaths: d ? [d] : [],
      strokePaths: [],
      circles: [],
      polygons: []
    });
  }
  for(const r of roads){
    const rd = roadFeatureDrawData(r, feats, roadCtx);
    byFeature.set(r.id, {
      type: "road",
      fillPaths: [],
      strokePaths: rd.paths.map(d=>({d, width:10, linecap:"round", linejoin:"round"})),
      circles: rd.dots.map(dot=>({cx:dot.cx, cy:dot.cy, r:dot.r})),
      polygons: []
    });
  }
  for(const m of cloisters){
    const lines = cloisterLineSpecs(m);
    byFeature.set(m.id, {
      type: "cloister",
      fillPaths: [],
      strokePaths: lines.map(ln=>({
        d: `M ${ln.x1.toFixed(2)} ${ln.y1.toFixed(2)} L ${ln.x2.toFixed(2)} ${ln.y2.toFixed(2)}`,
        width: ln.width,
        linecap: "round",
        linejoin: "round"
      })),
      circles: [],
      polygons: []
    });
  }

  // Build field regions from the same simplified city/road geometry.
  if(fields.length){
    const N = 112;
    const occ = new Uint8Array(N*N);
    const off = document.createElement("canvas");
    off.width = N;
    off.height = N;
    const ctx = off.getContext("2d");
    ctx.clearRect(0,0,N,N);
    ctx.fillStyle = "#fff";
    ctx.strokeStyle = "#fff";
    ctx.lineCap = "round";
    ctx.lineJoin = "round";

    const scale = (N-1) / 100;
    ctx.save();
    ctx.scale(scale, scale);

    for(const c of cities){
      const cg = byFeature.get(c.id);
      for(const d of (cg?.fillPaths || [])){
        try{
          ctx.fill(new Path2D(d), "nonzero");
        }catch(_err){}
      }
    }
    for(const r of roads){
      const rg = byFeature.get(r.id);
      for(const sp of (rg?.strokePaths || [])){
        try{
          ctx.lineWidth = sp.width;
          ctx.stroke(new Path2D(sp.d));
        }catch(_err){}
      }
      for(const cc of (rg?.circles || [])){
        ctx.beginPath();
        ctx.arc(cc.cx, cc.cy, cc.r, 0, Math.PI*2);
        ctx.fill();
      }
    }
    ctx.restore();

    const img = ctx.getImageData(0,0,N,N).data;
    for(let i=0;i<N*N;i++){
      occ[i] = img[i*4+3] > 0 ? 1 : 0;
    }
    const fieldMask = new Uint8Array(N*N);
    for(let i=0;i<N*N;i++) fieldMask[i] = occ[i] ? 0 : 1;

    const {comp, components} = connectedComponents(fieldMask, N);
    const compPolys = new Map();

    for(const ff of fields){
      const mp = Array.isArray(ff?.meeple_placement) ? ff.meeple_placement : [0.5,0.5];
      let sx = Math.round((mp[0] || 0.5) * (N-1));
      let sy = Math.round((mp[1] || 0.5) * (N-1));
      let cid = findNearestMaskComponent(fieldMask, comp, N, sx, sy);
      if(cid<0){
        const [fx,fy] = fieldSeedFromPorts(ff);
        sx = Math.round((fx/100) * (N-1));
        sy = Math.round((fy/100) * (N-1));
        cid = findNearestMaskComponent(fieldMask, comp, N, sx, sy);
      }
      if(cid<0 || !components[cid]?.length) continue;

      if(!compPolys.has(cid)){
        const cm = new Uint8Array(N*N);
        for(const idx of components[cid]) cm[idx] = 1;
        compPolys.set(cid, maskToPolygons(cm, N));
      }
      byFeature.set(ff.id, {
        type: "field",
        fillPaths: [],
        strokePaths: [],
        circles: [],
        polygons: compPolys.get(cid) || []
      });
    }
  }

  const out = { byFeature };
  simplifiedFeatureGeomCache.set(tileId, out);
  return out;
}

function addFeatureAreaOverlayFromSimplified(tileDiv, tileId, localIds, mode="hover", tone=null){
  const geom = buildSimplifiedFeatureGeometry(tileId);
  if(!geom || !geom.byFeature) return false;

  const svg = document.createElementNS("http://www.w3.org/2000/svg","svg");
  svg.setAttribute("viewBox","0 0 100 100");
  svg.setAttribute("class", `areaSvg ${mode==="score" ? "score" : "hover"}`);
  svg.style.position="absolute";
  svg.style.inset="0";
  svg.style.pointerEvents="none";

  const fillBase = highlightTint(mode==="score" ? 0.48 : 0.40, tone);
  const lineCol  = highlightTint(mode==="score" ? 0.98 : 0.92, tone);
  const stroke   = highlightTint(mode==="score" ? 1.00 : 0.96, tone);

  const defs = document.createElementNS("http://www.w3.org/2000/svg","defs");
  const pid = `hatch_simple_${mode}_${tileId}_${state.selectedPlayer}_${tone || "player"}`;
  const pat = document.createElementNS("http://www.w3.org/2000/svg","pattern");
  pat.setAttribute("id", pid);
  pat.setAttribute("patternUnits","userSpaceOnUse");
  pat.setAttribute("width","5");
  pat.setAttribute("height","5");
  pat.setAttribute("patternTransform","rotate(45)");
  const line = document.createElementNS("http://www.w3.org/2000/svg","line");
  line.setAttribute("x1","0"); line.setAttribute("y1","0");
  line.setAttribute("x2","0"); line.setAttribute("y2","5");
  line.setAttribute("stroke", lineCol);
  line.setAttribute("stroke-width","2.4");
  pat.appendChild(line);
  defs.appendChild(pat);
  svg.appendChild(defs);

  let drew = false;
  for(const lid of localIds){
    const fg = geom.byFeature.get(lid);
    if(!fg) continue;

    for(const d of (fg.fillPaths || [])){
      const p0 = document.createElementNS("http://www.w3.org/2000/svg","path");
      p0.setAttribute("d", d);
      p0.setAttribute("fill", fillBase);
      p0.setAttribute("stroke", stroke);
      p0.setAttribute("stroke-width", "1.2");
      svg.appendChild(p0);

      const p1 = document.createElementNS("http://www.w3.org/2000/svg","path");
      p1.setAttribute("d", d);
      p1.setAttribute("fill", `url(#${pid})`);
      p1.setAttribute("stroke", "none");
      svg.appendChild(p1);
      drew = true;
    }

    for(const poly of (fg.polygons || [])){
      const pts = (poly||[]).map(p=>`${p[0]*100},${p[1]*100}`).join(" ");
      const pg0 = document.createElementNS("http://www.w3.org/2000/svg","polygon");
      pg0.setAttribute("points", pts);
      pg0.setAttribute("fill", fillBase);
      pg0.setAttribute("stroke", stroke);
      pg0.setAttribute("stroke-width", "1.2");
      svg.appendChild(pg0);

      const pg1 = document.createElementNS("http://www.w3.org/2000/svg","polygon");
      pg1.setAttribute("points", pts);
      pg1.setAttribute("fill", `url(#${pid})`);
      pg1.setAttribute("stroke", "none");
      svg.appendChild(pg1);
      drew = true;
    }

    for(const sp of (fg.strokePaths || [])){
      const p0 = document.createElementNS("http://www.w3.org/2000/svg","path");
      p0.setAttribute("d", sp.d);
      p0.setAttribute("fill", "none");
      p0.setAttribute("stroke", fillBase);
      p0.setAttribute("stroke-width", String(sp.width));
      p0.setAttribute("stroke-linecap", sp.linecap || "round");
      p0.setAttribute("stroke-linejoin", sp.linejoin || "round");
      svg.appendChild(p0);

      const p1 = document.createElementNS("http://www.w3.org/2000/svg","path");
      p1.setAttribute("d", sp.d);
      p1.setAttribute("fill", "none");
      p1.setAttribute("stroke", lineCol);
      p1.setAttribute("stroke-width", String(Math.max(2.4, sp.width*0.46)));
      p1.setAttribute("stroke-linecap", sp.linecap || "round");
      p1.setAttribute("stroke-linejoin", sp.linejoin || "round");
      p1.setAttribute("stroke-dasharray", "4 2");
      svg.appendChild(p1);
      drew = true;
    }

    for(const cc of (fg.circles || [])){
      const c0 = document.createElementNS("http://www.w3.org/2000/svg","circle");
      c0.setAttribute("cx", cc.cx.toFixed(2));
      c0.setAttribute("cy", cc.cy.toFixed(2));
      c0.setAttribute("r", String(cc.r));
      c0.setAttribute("fill", fillBase);
      c0.setAttribute("stroke", stroke);
      c0.setAttribute("stroke-width", "1.0");
      svg.appendChild(c0);

      const c1 = document.createElementNS("http://www.w3.org/2000/svg","circle");
      c1.setAttribute("cx", cc.cx.toFixed(2));
      c1.setAttribute("cy", cc.cy.toFixed(2));
      c1.setAttribute("r", String(cc.r));
      c1.setAttribute("fill", `url(#${pid})`);
      c1.setAttribute("stroke", "none");
      svg.appendChild(c1);
      drew = true;
    }
  }

  if(!drew) return false;
  tileDiv.appendChild(svg);
  return true;
}

function addCityBoundaryOverlayFromSimplified(tileDiv, tileId, localIds){
  const geom = buildSimplifiedFeatureGeometry(tileId);
  if(!geom || !geom.byFeature || !localIds || localIds.size===0) return false;

  const svg = document.createElementNS("http://www.w3.org/2000/svg","svg");
  svg.setAttribute("viewBox","0 0 100 100");
  svg.setAttribute("class","areaSvg cityBoundarySvg");
  svg.style.position = "absolute";
  svg.style.inset = "0";
  svg.style.pointerEvents = "none";

  let drew = false;
  for(const lid of localIds){
    const fg = geom.byFeature.get(lid);
    if(!fg) continue;

    for(const d of (fg.fillPaths || [])){
      const p = document.createElementNS("http://www.w3.org/2000/svg","path");
      p.setAttribute("d", d);
      p.setAttribute("fill", "none");
      p.setAttribute("stroke", "rgba(0,0,0,0.90)");
      p.setAttribute("stroke-width", "1.35");
      p.setAttribute("stroke-dasharray", "4 3");
      p.setAttribute("stroke-linejoin", "round");
      svg.appendChild(p);
      drew = true;
    }
    for(const poly of (fg.polygons || [])){
      const pts = (poly||[]).map(p=>`${p[0]*100},${p[1]*100}`).join(" ");
      const pg = document.createElementNS("http://www.w3.org/2000/svg","polygon");
      pg.setAttribute("points", pts);
      pg.setAttribute("fill", "none");
      pg.setAttribute("stroke", "rgba(0,0,0,0.90)");
      pg.setAttribute("stroke-width", "1.35");
      pg.setAttribute("stroke-dasharray", "4 3");
      pg.setAttribute("stroke-linejoin", "round");
      svg.appendChild(pg);
      drew = true;
    }
  }

  if(!drew) return false;
  tileDiv.appendChild(svg);
  return true;
}

function makeSimplifiedTileSvg(tileId){
  const svg = svgEl("svg");
  svg.setAttribute("viewBox", "0 0 100 100");
  svg.setAttribute("class", "simpleTileSvg");

  const bg = svgEl("rect");
  // Slight overdraw avoids sub-pixel edge fringing that can look like tile borders.
  bg.setAttribute("x", "-1");
  bg.setAttribute("y", "-1");
  bg.setAttribute("width", "102");
  bg.setAttribute("height", "102");
  bg.setAttribute("fill", SIMPLE_COLORS.field);
  svg.appendChild(bg);

  const feats = mergedFeaturesForTile(tileId).filter(f=>["city","road","field","cloister"].includes(f.type));
  const cities = feats.filter(f=>f.type==="city");
  const roads = feats.filter(f=>f.type==="road");
  const cloisters = feats.filter(f=>f.type==="cloister");
  const roadCtx = buildRoadRenderContext(roads, feats);

  for(const r of roads){
    drawRoadFeature(svg, r, feats, roadCtx);
  }
  if(roadCtx.junction){
    const jDot = svgEl("circle");
    jDot.setAttribute("cx", roadCtx.junction[0].toFixed(2));
    jDot.setAttribute("cy", roadCtx.junction[1].toFixed(2));
    jDot.setAttribute("r", "3.3");
    jDot.setAttribute("fill", SIMPLE_COLORS.roadOutline);
    svg.appendChild(jDot);
  }

  for(const c of cities){
    const d = cityPathFromPorts(c.ports || []);
    if(!d) continue;
    const p = svgEl("path");
    p.setAttribute("d", d);
    p.setAttribute("fill", SIMPLE_COLORS.city);
    p.setAttribute("stroke", SIMPLE_COLORS.outline);
    p.setAttribute("stroke-width", "1.35");
    p.setAttribute("stroke-linejoin", "round");
    svg.appendChild(p);
  }

  for(const m of cloisters) drawCloisterCross(svg, m);
  for(const c of cities) drawCityCrests(svg, c);

  return svg;
}

function makeTileImg(tileId){
  if(state.simplifiedGraphics){
    const wrap = document.createElement("div");
    wrap.className = "tileSimpleWrap";
    wrap.appendChild(makeSimplifiedTileSvg(tileId));
    return wrap;
  }

  let proto = tileImgCache.get(tileId);
  if(!proto){
    proto = new Image();
    proto.className = "tileImg";
    proto.src = tileImageUrl(tileId);
    proto.alt = `Tile ${tileId}`;
    proto.draggable = false;
    tileImgCache.set(tileId, proto);
  }
  const img = new Image();
  img.className = "tileImg";
  img.draggable = false;
  img.alt = `Tile ${tileId}`;
  img.loading = "eager";
  img.decoding = "sync";
  img.src = proto.currentSrc || proto.src || tileImageUrl(tileId);
  return img;
}

function preloadTileImage(tileId){
  let proto = tileImgCache.get(tileId);
  if(!proto){
    proto = new Image();
    proto.className = "tileImg";
    proto.alt = `Tile ${tileId}`;
    proto.draggable = false;
    tileImgCache.set(tileId, proto);
  }

  const src = tileImageUrl(tileId);
  if(proto.src !== src) proto.src = src;
  if(proto.complete && proto.naturalWidth > 0) return Promise.resolve();

  return new Promise((resolve)=>{
    const done = ()=>resolve();
    proto.addEventListener("load", done, {once:true});
    proto.addEventListener("error", done, {once:true});
  });
}



function getAreaFeature(tileId, featureId){
  const o = state.areasOverride?.tiles?.[tileId]?.features?.[featureId];
  if(o) return o;
  const b = state.areasBase?.tiles?.[tileId]?.features?.[featureId];
  return b || null;
}

function isOnlineTab(){ return state.activeTab === "online"; }
function onlineMatchIsActive(){ return !!(state.online.match && state.online.match.status==="active"); }

function captureLocalStateForOnline(){
  if(state.online.localSnapshot) return;
  state.online.localSnapshot = snapshot();
}

function restoreLocalStateFromOnline(){
  if(!state.online.localSnapshot) return;
  restore(state.online.localSnapshot);
  state.online.localSnapshot = null;
  updateRotLabel();
  renderTileSelect();
  renderTilePalette();
}

function clearOnlinePendingTurn(){
  state.online.pendingTile = null;
  state.online.tileLocked = false;
  state.online.pendingMeepleFeatureId = null;
  state.online.lastIntentSig = "";
}

function onlinePreviewTileForCursor(matchObj){
  const m = matchObj || state.online.match;
  if(!m || m.status!=="active") return null;
  if(m.can_act) return m.current_turn?.tile_id || null;
  return m.your_next_tile || null;
}

function onlineOpponentIntent(matchObj){
  const m = matchObj || state.online.match;
  if(!m || m.status!=="active") return null;
  const intent = m.turn_intent;
  if(!intent) return null;
  const you = Number(m.you_player || 0);
  const ip = Number(intent.player || 0);
  if(ip<=0 || ip===you) return null;
  return intent;
}

function applyOnlineMatchToBoardState(){
  if(!isOnlineTab()) return;

  const m = state.online.match;
  const prevSelectedCell = state.selectedCell;
  const prevHoverCell = state.hoverCell;
  if(!m){
    state.board = new Map();
    state.instSeq = 1;
    state.score = {1:0, 2:0};
    state.scoredKeys = new Set();
    state.selectedCell = null;
    state.hoverCell = null;
    clearHoverFeatureState();
    clearSelectedScoreState();
    render();
    return;
  }

  state.board = new Map(m.board || []);
  const maxInst = Math.max(0, ...Array.from(state.board.values()).map(v=>Number(v.instId)||0));
  state.instSeq = Number(m.inst_seq) || (maxInst + 1);
  state.score = {
    1: Number(m.score?.["1"] ?? m.score?.[1] ?? 0),
    2: Number(m.score?.["2"] ?? m.score?.[2] ?? 0)
  };
  state.scoredKeys = new Set(m.scored_keys || []);
  if(m.remaining) state.remaining = deepCopy(m.remaining);

  const youPlayer = Number(m.you_player || 0);
  if(youPlayer===1 || youPlayer===2) setPlayer(youPlayer);

  const previewTile = onlinePreviewTileForCursor(m) || m.current_turn?.tile_id;
  if(previewTile) state.selectedTileId = previewTile;

  state.selectedCell = (prevSelectedCell && state.board.has(prevSelectedCell)) ? prevSelectedCell : null;
  const keepHover =
    m.status==="active" &&
    !!m.can_act &&
    !state.online.pendingTile &&
    !!prevHoverCell &&
    !state.board.has(prevHoverCell);
  state.hoverCell = keepHover ? prevHoverCell : null;
  clearHoverFeatureState();
  renderTileSelect();
  renderTilePalette();
  updateTilePreview();
  render();
}

function setActiveTab(tab){
  const prevTab = state.activeTab;
  if(prevTab==="online" && tab!=="online"){
    restoreLocalStateFromOnline();
  }

  state.activeTab = tab;
  const tp = $("#tabPlay");
  const tr = $("#tabRefine");
  const to = $("#tabOnline");
  const pp = $("#playPane");
  const rp = $("#refinePane");
  const op = $("#onlinePane");
  const bw = $("#boardWrap");
  const rm = $("#refineMain");

  if(tp) tp.classList.toggle("active", tab==="play");
  if(tr) tr.classList.toggle("active", tab==="refine");
  if(to) to.classList.toggle("active", tab==="online");
  if(pp) pp.classList.toggle("hidden", tab!=="play");
  if(rp) rp.classList.toggle("hidden", tab!=="refine");
  if(op) op.classList.toggle("hidden", tab!=="online");
  if(bw) bw.classList.toggle("hidden", tab==="refine");
  if(rm) rm.classList.toggle("hidden", tab!=="refine");

  if(tab==="refine"){
    state.boardHot = false;
    if(state.tileById.size===0){
      const prog = $("#rpProgress");
      if(prog) prog.textContent = "Tileset is still loading…";
      setStatus("Tileset is still loading…");
      return;
    }
    try{
      if(!state.refine.list.length) initRefinePolygons();
      renderRefine();
      renderRefineAll();
    }catch(err){
      console.error(err);
      setStatus("Refine mode error: " + (err?.message || err));
      const prog = $("#rpProgress");
      if(prog) prog.textContent = "Refine mode error: " + (err?.message || err);
    }
  }else if(tab==="online"){
    captureLocalStateForOnline();
    if(!state.online.connected){
      clearOnlinePendingTurn();
      state.online.match = null;
    }
    if(state.online.connected){
      onlinePoll(true).catch(()=>{});
    }
    applyOnlineMatchToBoardState();
    renderOnlineSidebar();
    renderOnlineScorePanel();
  }else{
    clearHoverFeatureState();
    render();
  }
}

function isRefineTab(){ return state.activeTab === "refine"; }

const fieldCityAdjCache = new Map(); // tileId -> Map(fieldId -> Set(cityId))
const simplifiedFeatureGeomCache = new Map(); // tileId -> simplified overlay geometry

function clearFieldCityAdjCache(){
  fieldCityAdjCache.clear();
}
function clearSimplifiedFeatureGeomCache(){
  simplifiedFeatureGeomCache.clear();
}

let overridesSaveTimer = null;
let overridesSaveChain = Promise.resolve();
let overridesSaveWarned = false;
const SIDEBAR_COLLAPSED_STORAGE_KEY = "carc_sidebar_collapsed_v1";

function emptyOverridesPayload(){
  return { schema:{coords:"normalized_0_1", fillRule:"evenodd"}, tiles:{} };
}

function normalizeOverridesPayload(raw){
  const out = (raw && typeof raw==="object") ? raw : emptyOverridesPayload();
  if(!out.schema || typeof out.schema!=="object") out.schema = {};
  if(!out.schema.coords) out.schema.coords = "normalized_0_1";
  if(!out.schema.fillRule) out.schema.fillRule = "evenodd";
  if(!out.tiles || typeof out.tiles!=="object") out.tiles = {};
  for(const [tileId, tileEntry] of Object.entries(out.tiles)){
    if(!tileEntry || typeof tileEntry!=="object"){
      out.tiles[tileId] = {features:{}};
      continue;
    }
    if(!tileEntry.features || typeof tileEntry.features!=="object"){
      tileEntry.features = {};
    }
  }
  return out;
}

function loadOverridesFromLocalStorage(){
  try{
    const raw = localStorage.getItem(OVERRIDES_STORAGE_KEY);
    if(!raw) return null;
    return normalizeOverridesPayload(JSON.parse(raw));
  }catch(e){
    console.warn("Failed to parse overrides from localStorage:", e);
    return null;
  }
}

async function loadOverridesFromServer(){
  try{
    const res = await fetch(`${OVERRIDES_API_PATH}?ts=${Date.now()}`, {cache:"no-store"});
    if(res.ok){
      return normalizeOverridesPayload(await res.json());
    }
  }catch(e){}

  // Fallback if static hosting serves the file but no write API exists.
  try{
    const res = await fetch(`${OVERRIDES_FILE_NAME}?ts=${Date.now()}`, {cache:"no-store"});
    if(res.ok){
      return normalizeOverridesPayload(await res.json());
    }
  }catch(e){}

  return null;
}

async function saveOverridesToServer(payload){
  try{
    const res = await fetch(OVERRIDES_API_PATH, {
      method: "POST",
      headers: {"Content-Type":"application/json"},
      body: JSON.stringify(payload)
    });
    if(!res.ok) throw new Error(`HTTP ${res.status}`);
    overridesSaveWarned = false;
    return true;
  }catch(e){
    if(!overridesSaveWarned){
      console.warn(`Auto-save disabled (could not write ${OVERRIDES_FILE_NAME}):`, e);
      overridesSaveWarned = true;
    }
    return false;
  }
}

function scheduleOverridesSaveToServer(){
  if(overridesSaveTimer) clearTimeout(overridesSaveTimer);
  overridesSaveTimer = setTimeout(()=>{
    overridesSaveTimer = null;
    const payload = normalizeOverridesPayload(deepCopy(state.areasOverride || emptyOverridesPayload()));
    overridesSaveChain = overridesSaveChain
      .then(()=>saveOverridesToServer(payload))
      .catch(()=>{});
  }, 160);
}

function persistOverridesToLocalStorage(){
  try{
    state.areasOverride = normalizeOverridesPayload(state.areasOverride || emptyOverridesPayload());
    localStorage.setItem(OVERRIDES_STORAGE_KEY, JSON.stringify(state.areasOverride));
    clearFieldCityAdjCache();
    clearSimplifiedFeatureGeomCache();
  }catch(e){
    console.warn("Failed to store overrides:", e);
  }
  scheduleOverridesSaveToServer();
}

function repairLegacyOverridePorts(){
  if(!state.areasOverride?.tiles) return 0;
  let repaired = 0;

  for(const [tileId, tileEntry] of Object.entries(state.areasOverride.tiles)){
    const features = tileEntry?.features;
    if(!features || typeof features!=="object") continue;

    for(const [featureId, ov] of Object.entries(features)){
      if(!ov || typeof ov!=="object" || ov.deleted) continue;
      const baseFeat = getBaseTileFeature(tileId, featureId);
      if(!baseFeat) continue;

      const type = ov.type || baseFeat.type || "field";
      if(Array.isArray(ov.ports)){
        const normalized = normalizePortsForType(ov.ports, type);
        if(!arraysEqual(normalized, ov.ports)){
          ov.ports = normalized;
          repaired++;
        }
      }

      const basePortsForType = normalizePortsForType(baseFeat.ports || [], type);
      const sameTypeAsBase = !ov.type || ov.type===baseFeat.type;
      if(Array.isArray(ov.ports) && ov.ports.length===0 && basePortsForType.length>0 && sameTypeAsBase){
        // Legacy bug: polygon edits could write empty ports for base features.
        delete ov.ports;
        repaired++;
      }
    }
  }

  return repaired;
}


function generateProceduralAreas(){
  // Creates default highlight rings purely from ports, so every feature has a usable area
  // and areas touch their edge ports. This is intentionally coarse; overrides refine it.
  const out = { schema: { coords: "normalized_0_1", fillRule: "evenodd" }, tiles: {} };

  const cityEdgeBand = 0.30;
  const fieldEdgeBand = 0.28;
  const roadW = 0.14; // corridor width

  function clamp01(v){ return Math.max(0, Math.min(1, v)); }

  const PORT = {
    N:  [0.50, 0.00],
    E:  [1.00, 0.50],
    S:  [0.50, 1.00],
    W:  [0.00, 0.50],
    Nw: [0.25, 0.00],
    Ne: [0.75, 0.00],
    Sw: [0.25, 1.00],
    Se: [0.75, 1.00],
    Wn: [0.00, 0.25],
    Ws: [0.00, 0.75],
    En: [1.00, 0.25],
    Es: [1.00, 0.75],
  };

  function portPointsForCity(p){
    // triangle band touching the edge
    if(p==="N") return [[0,0],[1,0],[0.5,cityEdgeBand]];
    if(p==="S") return [[0,1],[1,1],[0.5,1-cityEdgeBand]];
    if(p==="W") return [[0,0],[0,1],[cityEdgeBand,0.5]];
    if(p==="E") return [[1,0],[1,1],[1-cityEdgeBand,0.5]];
    return [[PORT[p]?.[0] ?? 0.5, PORT[p]?.[1] ?? 0.5]];
  }

  function portPointsForField(p){
    // wedge touching the relevant corner/edge half
    if(p==="Nw") return [[0,0],[0.5,0],[0,0.5],[0.25,0.25]];
    if(p==="Ne") return [[0.5,0],[1,0],[1,0.5],[0.75,0.25]];
    if(p==="Sw") return [[0,0.5],[0,1],[0.5,1],[0.25,0.75]];
    if(p==="Se") return [[0.5,1],[1,0.5],[1,1],[0.75,0.75]];
    if(p==="Wn") return [[0,0],[0,0.5],[fieldEdgeBand,0.25],[0.25,0.25]];
    if(p==="Ws") return [[0,0.5],[0,1],[fieldEdgeBand,0.75],[0.25,0.75]];
    if(p==="En") return [[1,0],[1,0.5],[1-fieldEdgeBand,0.25],[0.75,0.25]];
    if(p==="Es") return [[1,0.5],[1,1],[1-fieldEdgeBand,0.75],[0.75,0.75]];
    if(p==="N")  return [[0,0],[1,0],[0.5,fieldEdgeBand],[0.5,0.2]];
    if(p==="S")  return [[0,1],[1,1],[0.5,1-fieldEdgeBand],[0.5,0.8]];
    if(p==="W")  return [[0,0],[0,1],[fieldEdgeBand,0.5],[0.2,0.5]];
    if(p==="E")  return [[1,0],[1,1],[1-fieldEdgeBand,0.5],[0.8,0.5]];
    return [[PORT[p]?.[0] ?? 0.5, PORT[p]?.[1] ?? 0.5]];
  }

  function circleRing(cx, cy, r, n=20){
    const ring = [];
    for(let i=0;i<n;i++){
      const a = i/n * Math.PI*2;
      ring.push([clamp01(cx + r*Math.cos(a)), clamp01(cy + r*Math.sin(a))]);
    }
    return ring;
  }

  function segmentRect(p, q, w){
    const [x1,y1]=p, [x2,y2]=q;
    const dx=x2-x1, dy=y2-y1;
    const L=Math.hypot(dx,dy) || 1e-9;
    const ux=dx/L, uy=dy/L;
    const nx=-uy, ny=ux;
    const hw=w/2;
    return [
      [clamp01(x1+nx*hw), clamp01(y1+ny*hw)],
      [clamp01(x1-nx*hw), clamp01(y1-ny*hw)],
      [clamp01(x2-nx*hw), clamp01(y2-ny*hw)],
      [clamp01(x2+nx*hw), clamp01(y2+ny*hw)],
    ];
  }

  // Convex hull (Monotonic chain) for coarse regions
  function hull(points){
    if(points.length<=3) return points;
    const pts = points
      .map(p=>({x:p[0],y:p[1]}))
      .sort((a,b)=>a.x===b.x ? a.y-b.y : a.x-b.x);

    function cross(o,a,b){ return (a.x-o.x)*(b.y-o.y)-(a.y-o.y)*(b.x-o.x); }

    const lower=[];
    for(const p of pts){
      while(lower.length>=2 && cross(lower[lower.length-2], lower[lower.length-1], p) <= 0) lower.pop();
      lower.push(p);
    }
    const upper=[];
    for(let i=pts.length-1;i>=0;i--){
      const p=pts[i];
      while(upper.length>=2 && cross(upper[upper.length-2], upper[upper.length-1], p) <= 0) upper.pop();
      upper.push(p);
    }
    upper.pop(); lower.pop();
    const H = lower.concat(upper).map(p=>[p.x,p.y]);
    return H.length>=3 ? H : points.slice(0,3);
  }

  for(const [tileId, t] of state.tileById.entries()){
    out.tiles[tileId] = { features: {} };
    for(const f of t.features){
      const ports = (f.ports || []);
      let rings = [];

      if(f.type==="cloister"){
        rings = [ circleRing(0.5,0.5,0.22, 24) ];
      }else if(f.type==="road"){
        const edgePorts = ports.filter(p=>["N","E","S","W"].includes(p));
        const anchors = edgePorts.map(p=>PORT[p]).filter(Boolean);
        let j = [0.5,0.5];
        if(anchors.length===1){
          j = [0.5,0.5];
        }else if(anchors.length===2){
          j = [(anchors[0][0]+anchors[1][0])/2, (anchors[0][1]+anchors[1][1])/2];
        }else if(anchors.length>=3){
          j = [0.5,0.5];
        }
        // multiple corridor rectangles + a junction disk (ring)
        for(const a of anchors){
          rings.push(segmentRect(a, j, roadW));
        }
        rings.push(circleRing(j[0], j[1], roadW*0.45, 18));
      }else if(f.type==="city"){
        const pts=[];
        for(const p of ports){
          for(const pp of portPointsForCity(p)) pts.push(pp);
        }
        // always include a small inward anchor so hull doesn't collapse
        pts.push([0.5,0.35]);
        pts.push([0.5,0.65]);
        rings = [ hull(pts) ];
      }else if(f.type==="field"){
        const pts=[];
        for(const p of ports){
          for(const pp of portPointsForField(p)) pts.push(pp);
        }
        // add multiple gentle anchors so hull spans the correct side(s)
        pts.push([0.5,0.5]);
        pts.push([0.2,0.5]); pts.push([0.8,0.5]);
        pts.push([0.5,0.2]); pts.push([0.5,0.8]);
        rings = [ hull(pts) ];
      }else{
        // fallback
        rings = [ circleRing(0.5,0.5,0.18, 18) ];
      }

      out.tiles[tileId].features[f.id] = {
        type: f.type,
        polygons: rings,
        ports: normalizePortsForType(f.ports || [], f.type),
        tags: deepCopy(f.tags || {}),
        meeple_placement: Array.isArray(f.meeple_placement) ? deepCopy(f.meeple_placement) : featureCentroid(rings)
      };
    }
  }
  return out;
}

// Expose helpers to avoid scope/caching issues
window.loadOverridesFromLocalStorage = loadOverridesFromLocalStorage;
window.persistOverridesToLocalStorage = persistOverridesToLocalStorage;


function updateTilePreview(){
  const el = $("#tilePreview");
  if(!el) return;
  el.innerHTML = "";
  if(state.simplifiedGraphics){
    el.style.backgroundImage = "none";
    el.appendChild(makeTileImg(state.selectedTileId));
  }else{
    el.style.backgroundImage = `url(${tileImageUrl(state.selectedTileId)})`;
  }
  el.style.transform = `rotate(${state.selectedRot}deg)`;
}
function updateRotLabel(){
  $("#rotLabel").textContent = `Rotation: ${state.selectedRot}°`;
  updateTilePreview();
}

function setPlayer(p){
  state.selectedPlayer = p;
  $("#p1btn").classList.toggle("active", p===1);
  $("#p2btn").classList.toggle("active", p===2);
}

function cellStepPx(){
  const root = getComputedStyle(document.documentElement);
  const cell = parseFloat(root.getPropertyValue("--cell"));
  const gap = parseFloat(root.getPropertyValue("--gap"));
  return cell + gap;
}

function rotateSelected(delta){
  state.selectedRot = (state.selectedRot + delta + 360) % 360;
  if(isOnlineTab() && state.online.pendingTile && !state.online.tileLocked){
    state.online.pendingTile.rotDeg = state.selectedRot;
  }
  updateRotLabel();
  render();
  if(isOnlineTab()) onlineQueueIntentPush();
}

function rotatedTile(tileId, rotDeg){
  // IMPORTANT: meeple_placement is NOT rotated, because the tile div is rotated in CSS.
  // Ports and edges are rotated for connectivity.
  const base = state.tileById.get(tileId);
  const out = { id: base.id, edges: {}, features: [] };
  const inv = (360 - rotDeg) % 360;

  for(const e of ["N","E","S","W"]){
    const srcE = rotPort(e, inv);
    const be = base.edges[srcE];
    out.edges[e] = { primary: be.primary, feature: be.feature, halves: be.halves };
  }
  const merged = mergedFeaturesForTile(tileId);
  const hasCloister = merged.some((f)=>f.type==="cloister");
  for(const f of merged){
    const rf = deepCopy(f);
    rf.ports = (rf.ports||[]).map(p=>rotPort(p, rotDeg));
    if(
      hasCloister &&
      rf.type==="field" &&
      Array.isArray(rf.meeple_placement) &&
      rf.meeple_placement.length>=2
    ){
      const px = Number(rf.meeple_placement[0]);
      const py = Number(rf.meeple_placement[1]);
      rf.meeple_placement = [
        Number.isFinite(px) ? px : 0.5,
        Math.max(0.02, Math.min(0.98, (Number.isFinite(py) ? py : 0.5) - 0.25))
      ];
    }
    // meeple_placement stays unrotated because tile DOM is rotated in CSS
    out.features.push(rf);
  }
  return out;
}

function canPlaceAtOnBoard(boardMap, tileId, rotDeg, x, y){
  const k = keyXY(x,y);
  if(boardMap.has(k)) return {ok:false, reason:"Cell occupied."};

  const hasAny = boardMap.size>0;
  let touches = false;

  const tile = rotatedTile(tileId, rotDeg);

  const neigh = [
    {dx:0, dy:-1, edge:"N"},
    {dx:1, dy:0, edge:"E"},
    {dx:0, dy:1, edge:"S"},
    {dx:-1, dy:0, edge:"W"},
  ];
  for(const n of neigh){
    const nk = keyXY(x+n.dx, y+n.dy);
    if(!boardMap.has(nk)) continue;
    touches = true;
    const nInst = boardMap.get(nk);
    const nTile = rotatedTile(nInst.tileId, nInst.rotDeg);
    const e = n.edge;
    const oe = EDGE_OPP[e];
    const a = tile.edges[e].primary;
    const b = nTile.edges[oe].primary;
    if(a !== b){
      return {ok:false, reason:`Edge mismatch ${e}: ${a} vs neighbor ${oe}: ${b}`};
    }
  }
  if(hasAny && !touches){
    return {ok:false, reason:"Tile must touch at least one placed tile."};
  }
  return {ok:true, reason:"OK"};
}

function canPlaceAt(tileId, rotDeg, x, y){
  return canPlaceAtOnBoard(state.board, tileId, rotDeg, x, y);
}

function boardHalfSpan(){
  const board = $("#board");
  if(!board) return 12;
  const size = Math.sqrt(board.children.length) | 0;
  return Math.max(1, Math.floor(size/2));
}

function withinBoardBounds(x, y, half){
  return Math.abs(x)<=half && Math.abs(y)<=half;
}

function shuffleInPlace(arr){
  for(let i=arr.length-1;i>0;i--){
    const j = (Math.random()*(i+1))|0;
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

function boardNeighbors4(x, y){
  return [
    {x, y:y-1},
    {x:x+1, y},
    {x, y:y+1},
    {x:x-1, y},
  ];
}

function buildFrontier(boardMap, half){
  const frontier = new Set();
  for(const [k] of boardMap){
    const {x,y} = parseXY(k);
    for(const n of boardNeighbors4(x,y)){
      if(!withinBoardBounds(n.x, n.y, half)) continue;
      const nk = keyXY(n.x, n.y);
      if(!boardMap.has(nk)) frontier.add(nk);
    }
  }
  return frontier;
}

function pickStartTileType(){
  const ts = state.tileset?.tiles || [];
  const explicit = ts.find(t=>t.is_start_tile_type && (state.counts?.[t.id] ?? 0) > 0);
  if(explicit) return explicit.id;
  const ids = Array.from(state.tileById.keys()).sort();
  return ids[0] || null;
}

function buildDeckFromCounts(remaining){
  const deck = [];
  for(const [tileId, cnt] of Object.entries(remaining || {})){
    for(let i=0;i<(cnt|0);i++) deck.push(tileId);
  }
  return deck;
}

function autoFillBoardWithAllTiles(){
  const startTileId = pickStartTileType();
  if(!startTileId){
    setStatus("Cannot auto-fill: no tile types loaded.");
    return;
  }
  if((state.counts?.[startTileId] ?? 0) <= 0){
    setStatus(`Cannot auto-fill: start tile type ${startTileId} has no count.`);
    return;
  }

  const totalTiles = Object.values(state.counts || {}).reduce((a,b)=>a+(b|0), 0);
  if(totalTiles<=0){
    setStatus("Cannot auto-fill: tile counts are empty.");
    return;
  }

  const half = boardHalfSpan();
  const rotations = [0,90,180,270];
  const maxAttempts = 240;
  let best = null;

  for(let attempt=1; attempt<=maxAttempts; attempt++){
    const boardMap = new Map();
    const remaining = deepCopy(state.counts);
    let instSeq = 1;

    const startRot = rotations[(Math.random()*4)|0];
    boardMap.set("0,0", {instId: instSeq++, tileId: startTileId, rotDeg: startRot, meeples: []});
    remaining[startTileId] -= 1;

    const deck = shuffleInPlace(buildDeckFromCounts(remaining));
    const frontier = buildFrontier(boardMap, half);
    let failed = false;

    while(deck.length){
      const startIdx = (Math.random()*deck.length)|0;
      let move = null;
      let deckIdx = -1;

      for(let off=0; off<deck.length; off++){
        const idx = (startIdx + off) % deck.length;
        const tileId = deck[idx];
        const frontierArr = Array.from(frontier);
        if(frontierArr.length===0) continue;
        const fStart = (Math.random()*frontierArr.length)|0;

        for(let j=0; j<frontierArr.length; j++){
          const fk = frontierArr[(fStart + j) % frontierArr.length];
          const {x,y} = parseXY(fk);
          const rStart = (Math.random()*4)|0;
          for(let r=0; r<4; r++){
            const rot = rotations[(rStart + r) % 4];
            const ok = canPlaceAtOnBoard(boardMap, tileId, rot, x, y);
            if(!ok.ok) continue;
            move = {tileId, x, y, rot};
            deckIdx = idx;
            break;
          }
          if(move) break;
        }
        if(move) break;
      }

      if(!move){
        failed = true;
        break;
      }

      const mk = keyXY(move.x, move.y);
      boardMap.set(mk, {
        instId: instSeq++,
        tileId: move.tileId,
        rotDeg: move.rot,
        meeples: []
      });

      deck.splice(deckIdx, 1);
      remaining[move.tileId] -= 1;
      frontier.delete(mk);

      for(const n of boardNeighbors4(move.x, move.y)){
        if(!withinBoardBounds(n.x, n.y, half)) continue;
        const nk = keyXY(n.x, n.y);
        if(!boardMap.has(nk)) frontier.add(nk);
      }
    }

    const placed = boardMap.size;
    if(!best || placed > best.placed){
      best = {boardMap, instSeq, remaining, placed, attempt};
    }
    if(!failed && placed===totalTiles){
      best = {boardMap, instSeq, remaining, placed, attempt};
      break;
    }
  }

  if(!best || best.placed<=0){
    setStatus("Auto-fill failed.");
    return;
  }

  pushUndo();
  state.board = best.boardMap;
  state.instSeq = best.instSeq;
  state.remaining = best.remaining;
  state.scoredKeys.clear();
  state.score = {1:0, 2:0};
  state.selectedCell = null;
  state.hoverCell = null;
  clearHoverFeatureState();
  clearSelectedScoreState();
  recomputeAndScore(true);
  renderTileSelect();
  renderTilePalette();
  render();

  if(best.placed===totalTiles){
    setStatus(`Auto-filled board with all ${best.placed} tiles (attempt ${best.attempt}/${maxAttempts}).`);
  }else{
    setStatus(`Auto-fill partial: placed ${best.placed}/${totalTiles} tiles (best attempt ${best.attempt}/${maxAttempts}).`);
  }
}

function randomizeMeeplesOnBoard(perPlayer=5){
  if(state.board.size===0){
    setStatus("Board is empty. Auto-fill or place tiles first.");
    return;
  }

  pushUndo();
  for(const inst of state.board.values()) inst.meeples = [];

  const candidates = [];
  for(const [cellKey, inst] of state.board.entries()){
    const tile = rotatedTile(inst.tileId, inst.rotDeg);
    for(const f of tile.features){
      if(!["road","city","field","cloister"].includes(f.type)) continue;
      candidates.push({cellKey, inst, featureId: f.id});
    }
  }
  if(candidates.length===0){
    state.scoredKeys.clear();
    state.score = {1:0, 2:0};
    recomputeAndScore(true);
    render();
    setStatus("No feature candidates available for meeple randomization.");
    return;
  }

  const placed = {1:0, 2:0};
  for(const player of [1,2]){
    let attempts = 0;
    const maxAttempts = candidates.length * 10;
    while(placed[player] < perPlayer && attempts < maxAttempts){
      const cand = candidates[(Math.random()*candidates.length)|0];
      attempts++;
      if(!cand) break;
      const {cellKey, inst, featureId} = cand;
      if(inst.meeples.some(m=>m.featureLocalId===featureId)) continue;

      const g = computeGlobalFeatureForLocal(cellKey, featureId);
      if(!g) continue;
      const occupied = (g.meeplesByPlayer[1]||0) + (g.meeplesByPlayer[2]||0) > 0;
      if(occupied) continue;
      if(g.type!=="field" && g.complete) continue;

      inst.meeples.push({player, featureLocalId: featureId});
      placed[player] += 1;
    }
  }

  state.scoredKeys.clear();
  state.score = {1:0, 2:0};
  clearHoverFeatureState();
  clearSelectedScoreState();
  recomputeAndScore(true);
  render();
  setStatus(`Random meeples placed — P1 ${placed[1]}/${perPlayer}, P2 ${placed[2]}/${perPlayer}.`);
}

function applySidebarCollapsed(collapsed){
  state.sidebarCollapsed = !!collapsed;
  document.body.classList.toggle("sidebar-collapsed", state.sidebarCollapsed);
  const btn = $("#toggleSidebarBtn");
  if(btn) btn.textContent = state.sidebarCollapsed ? "Panel +" : "Panel -";
  try{
    localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, state.sidebarCollapsed ? "1" : "0");
  }catch(_err){}
}

function initUI(){
  $("#rotL").addEventListener("click", ()=>rotateSelected(-90));
  $("#rotR").addEventListener("click", ()=>rotateSelected(90));
  $("#toggleSidebarBtn")?.addEventListener("click", ()=>{
    applySidebarCollapsed(!state.sidebarCollapsed);
  });
  try{
    const raw = localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY);
    applySidebarCollapsed(raw==="1");
  }catch(_err){
    applySidebarCollapsed(false);
  }

  $("#useCounts").addEventListener("change", (e)=>{
    state.useCounts = e.target.checked;
    renderTileSelect();
    renderTilePalette();
    render();
  });
  $("#simplifiedGraphics")?.addEventListener("change", (e)=>{
    state.simplifiedGraphics = !!e.target.checked;
    updateTilePreview();
    renderTilePalette();
    render();
  });

  $("#ignoreMeepleRule").addEventListener("change", (e)=>{
    state.ignoreMeepleRule = e.target.checked;
    render();
  });

  $("#p1btn").addEventListener("click", ()=>setPlayer(1));
  $("#p2btn").addEventListener("click", ()=>setPlayer(2));
  setPlayer(1);

  $("#undoBtn").addEventListener("click", ()=>{
    const snap = state.undoStack.pop();
    if(!snap) return;
    restore(snap);
    updateRotLabel();
    renderTileSelect();
    renderTilePalette();
    render();
  });

  $("#resetBtn").addEventListener("click", ()=>{
    pushUndo();
    state.board.clear();
    state.instSeq = 1;
    state.scoredKeys.clear();
    state.score = {1:0, 2:0};
    state.remaining = deepCopy(state.counts);
    state.selectedCell = null;
    state.hoverCell = null;
    clearHoverFeatureState();
    clearSelectedScoreState();
    renderTileSelect();
    renderTilePalette();
    render();
  });

  $("#autoFillBoardBtn")?.addEventListener("click", ()=>{
    autoFillBoardWithAllTiles();
  });
  $("#autoMeeplesBtn")?.addEventListener("click", ()=>{
    randomizeMeeplesOnBoard(5);
  });

  $("#exportBtn").addEventListener("click", ()=>{
    const payload = {
      version: 3,
      board: Array.from(state.board.entries()),
      score: state.score,
      scoredKeys: Array.from(state.scoredKeys),
      remaining: state.remaining
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], {type:"application/json"});
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "carcassonne_sim_state.json";
    a.click();
    URL.revokeObjectURL(a.href);
  });

  $("#importBtn").addEventListener("click", ()=>$("#importFile").click());
  $("#importFile").addEventListener("change", async (e)=>{
    const f = e.target.files?.[0];
    if(!f) return;
    const txt = await f.text();
    const payload = JSON.parse(txt);
    pushUndo();
    state.board = new Map(payload.board);
    state.score = payload.score || {1:0, 2:0};
    state.scoredKeys = new Set(payload.scoredKeys || []);
    state.remaining = payload.remaining || deepCopy(state.counts);
    state.instSeq = 1 + Math.max(0, ...Array.from(state.board.values()).map(v=>v.instId||0));
    state.selectedCell = null;
    state.hoverCell = null;
    clearHoverFeatureState();
    clearSelectedScoreState();
    renderTileSelect();
    renderTilePalette();
    render();
    e.target.value = "";
  });

  // Board: wheel rotates, arrows scroll
  const wrap = $("#boardWrap");
  const pan = {active:false, sx:0, sy:0, sl:0, st:0};
  wrap.addEventListener("mouseenter", ()=>{
    state.boardHot = true;
    wrap.focus({preventScroll:true});
  });
  wrap.addEventListener("mouseleave", ()=>{
    state.boardHot = false;
    state.hoverCell = null;
    render();
  });

  wrap.addEventListener("wheel", (e)=>{
    if(!state.boardHot) return;
    if(isRefineTab()) return;
    e.preventDefault();
    const dir = (e.deltaY>0) ? 90 : -90;
    rotateSelected(dir);
  }, {passive:false});

  wrap.addEventListener("keydown", (e)=>{
    if(!state.boardHot) return;
    const step = cellStepPx();
    if(e.key==="ArrowLeft"){ wrap.scrollLeft -= step; e.preventDefault(); }
    else if(e.key==="ArrowRight"){ wrap.scrollLeft += step; e.preventDefault(); }
    else if(e.key==="ArrowUp"){ wrap.scrollTop -= step; e.preventDefault(); }
    else if(e.key==="ArrowDown"){ wrap.scrollTop += step; e.preventDefault(); }
  });

  wrap.addEventListener("contextmenu", (e)=>{
    if(state.boardPanning || isOnlineTab()) e.preventDefault();
  });
  wrap.addEventListener("mousedown", (e)=>{
    if(e.button!==2) return;
    pan.active = true;
    pan.sx = e.clientX;
    pan.sy = e.clientY;
    pan.sl = wrap.scrollLeft;
    pan.st = wrap.scrollTop;
    state.boardPanning = true;
    wrap.classList.add("panning");
    e.preventDefault();
  });
  window.addEventListener("mousemove", (e)=>{
    if(!pan.active) return;
    wrap.scrollLeft = pan.sl - (e.clientX - pan.sx);
    wrap.scrollTop = pan.st - (e.clientY - pan.sy);
  });
  window.addEventListener("mouseup", ()=>{
    if(!pan.active) return;
    pan.active = false;
    state.boardPanning = false;
    wrap.classList.remove("panning");
  });

  // Tabs
  const tp = $("#tabPlay");
  const tr = $("#tabRefine");
  const to = $("#tabOnline");
  if(tp) tp.addEventListener("click", ()=>setActiveTab("play"));
  if(tr) tr.addEventListener("click", ()=>setActiveTab("refine"));
  if(to) to.addEventListener("click", ()=>setActiveTab("online"));
  initAppSplitter();
  initOnlineUI();

  updateRotLabel();
  updateTilePreview();
}

function initAppSplitter(){
  const app = $("#app");
  const sidebar = $("#sidebar");
  const splitter = $("#appSplitter");
  if(!app || !sidebar || !splitter) return;

  const minWidth = 260;
  const maxWidth = 760;

  function clampSidebarWidth(width){
    const maxByViewport = Math.max(minWidth, window.innerWidth - 280);
    return Math.max(minWidth, Math.min(Math.min(maxWidth, maxByViewport), width));
  }

  function applySidebarWidth(width){
    const clamped = Math.round(clampSidebarWidth(width));
    app.style.setProperty("--sidebar-width", `${clamped}px`);
    try{
      localStorage.setItem(SIDEBAR_WIDTH_STORAGE_KEY, String(clamped));
    }catch(_err){}
  }

  try{
    const raw = Number(localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY));
    if(Number.isFinite(raw) && raw > 0) applySidebarWidth(raw);
  }catch(_err){}

  let dragging = false;
  let startX = 0;
  let startWidth = 0;

  splitter.addEventListener("mousedown", (e)=>{
    if(e.button !== 0) return;
    dragging = true;
    startX = e.clientX;
    startWidth = sidebar.getBoundingClientRect().width;
    document.body.classList.add("resizing");
    e.preventDefault();
  });

  window.addEventListener("mousemove", (e)=>{
    if(!dragging) return;
    applySidebarWidth(startWidth + (e.clientX - startX));
  });

  window.addEventListener("mouseup", ()=>{
    if(!dragging) return;
    dragging = false;
    document.body.classList.remove("resizing");
  });

  splitter.addEventListener("dblclick", ()=>{
    applySidebarWidth(340);
  });

  window.addEventListener("resize", ()=>{
    const raw = parseFloat(getComputedStyle(app).getPropertyValue("--sidebar-width"));
    if(Number.isFinite(raw) && raw > 0) applySidebarWidth(raw);
  });
}

function renderTileSelect(){
  const sel = $("#tileSelect");
  sel.innerHTML = "";
  const ids = Array.from(state.tileById.keys()).sort();
  for(const id of ids){
    const rem = state.remaining[id] ?? 0;
    const opt = document.createElement("option");
    opt.value = id;
    opt.textContent = state.useCounts ? `${id} (remaining: ${rem})` : `${id}`;
    if(state.useCounts && rem<=0) opt.disabled = true;
    sel.appendChild(opt);
  }
  sel.value = state.selectedTileId;
  sel.onchange = (e)=>{
    state.selectedTileId = e.target.value;
    updateTilePreview();
    renderTilePalette();
    render();
  };
}

function renderTilePalette(){
  const pal = $("#tilePalette");
  if(!pal) return;
  pal.innerHTML = "";
  const ids = Array.from(state.tileById.keys()).sort();
  for(const id of ids){
    const rem = state.remaining[id] ?? 0;
    const disabled = state.useCounts && rem<=0;

    const d = document.createElement("div");
    d.className = "thumb";
    if(disabled) d.classList.add("disabled");
    if(id===state.selectedTileId) d.classList.add("selected");
    d.title = state.useCounts ? `${id} (remaining: ${rem})` : `${id}`;

    const img = document.createElement("div");
    img.className = "img";
    if(state.simplifiedGraphics){
      img.classList.add("simpleGraphic");
      img.appendChild(makeTileImg(id));
    }else{
      img.style.backgroundImage = `url(${tileImageUrl(id)})`;
    }
    d.appendChild(img);

    const badge = document.createElement("div");
    badge.className="badge";
    badge.textContent = state.useCounts ? rem : "∞";
    d.appendChild(badge);

    const lab = document.createElement("div");
    lab.className="id";
    lab.textContent = id;
    d.appendChild(lab);

    d.addEventListener("click", ()=>{
      if(disabled) return;
      state.selectedTileId = id;
      $("#tileSelect").value = id;
      updateTilePreview();
      renderTilePalette();
      render();
    });

    pal.appendChild(d);
  }
}

function buildBoardGrid(size=25){
  const board = $("#board");
  board.style.gridTemplateColumns = `repeat(${size}, var(--cell))`;
  board.style.gridTemplateRows = `repeat(${size}, var(--cell))`;
  board.innerHTML = "";
  const half = Math.floor(size/2);

  for(let y=-half; y<=half; y++){
    for(let x=-half; x<=half; x++){
      const cell = document.createElement("div");
      cell.className = "cell";
      cell.dataset.x = x;
      cell.dataset.y = y;

      cell.addEventListener("click", (e)=>onCellClick(e, x, y));
      cell.addEventListener("contextmenu", (e)=>onCellRightClick(e, x, y));

      board.appendChild(cell);
    }
  }

  // Hover tracking for ghost tile
  board.addEventListener("mousemove", (e)=>{
    const cell = e.target.closest(".cell");
    if(!cell) return;
    const x = Number(cell.dataset.x), y = Number(cell.dataset.y);
    const k = keyXY(x,y);
    if(state.hoverCell !== k){
      state.hoverCell = k;
      render();
      if(isOnlineTab()) onlineQueueIntentPush();
    }
  });

  board.addEventListener("mouseleave", ()=>{
    state.hoverCell = null;
    render();
    if(isOnlineTab()) onlineQueueIntentPush();
  });

  board.addEventListener("click", (e)=>{
    if(e.target.classList.contains("cell")) clearSelection();
  });
}

function clearSelection(){
  state.selectedCell = null;
  clearHoverFeatureState();
  render();
}

function onOnlineBoardCellClick(x, y){
  if(!state.online.connected){
    setStatus("Connect to the online server first.");
    return;
  }
  const k = keyXY(x,y);

  if(state.board.has(k)){
    state.selectedCell = k;
    clearHoverFeatureState();
    render();
    setStatus("Tile selected. Tap a marker to inspect that connected feature.");
    return;
  }

  if(!onlineMatchIsActive()){
    setStatus("No active match. Invite a player from the lobby.");
    return;
  }
  if(!state.online.match?.can_act){
    setStatus("Wait for your turn.");
    return;
  }

  const drawnTileId = state.online.match?.current_turn?.tile_id;
  if(!drawnTileId){
    setStatus("No tile assigned yet.");
    return;
  }

  const rot = state.selectedRot;
  const ok = canPlaceAt(drawnTileId, rot, x, y);
  if(!ok.ok){
    setStatus(ok.reason);
    return;
  }

  state.online.pendingTile = {x, y, rotDeg: rot, tileId: drawnTileId};
  state.online.tileLocked = true;
  state.online.pendingMeepleFeatureId = null;
  state.selectedCell = keyXY(x,y);
  render();
  renderOnlineSidebar();
  renderBoardTopInfo();
  onlineQueueIntentPush(true);
  setStatus(`Prepared ${drawnTileId} at (${x},${y}) rot ${rot}°. Select meeple if wanted, then Confirm/Revert on top bar.`);
}

function onCellClick(e, x, y){
  if(isOnlineTab()){
    onOnlineBoardCellClick(x, y);
    return;
  }

  const k = keyXY(x,y);

  if(state.board.has(k)){
    state.selectedCell = k;
    clearHoverFeatureState();
    render();
    return;
  }

  if(state.useCounts && (state.remaining[state.selectedTileId] ?? 0) <= 0){
    setStatus(`No remaining tiles of type ${state.selectedTileId}.`);
    return;
  }

  const ok = canPlaceAt(state.selectedTileId, state.selectedRot, x, y);
  if(!ok.ok){
    setStatus(ok.reason);
    return;
  }

  pushUndo();

  const inst = { instId: state.instSeq++, tileId: state.selectedTileId, rotDeg: state.selectedRot, meeples: [] };
  state.board.set(k, inst);
  if(state.useCounts) state.remaining[state.selectedTileId] -= 1;

  recomputeAndScore();

  renderTileSelect();
  renderTilePalette();
  render();
  setStatus(`Placed ${inst.tileId} at (${x},${y}) rot ${inst.rotDeg}°.`);
}

function onCellRightClick(e, x, y){
  e.preventDefault();
  if(state.boardPanning) return;
  if(isOnlineTab()){
    return;
  }
  const k = keyXY(x,y);
  if(!state.board.has(k)) return;

  pushUndo();

  const inst = state.board.get(k);
  state.board.delete(k);
  if(state.useCounts) state.remaining[inst.tileId] += 1;

  state.scoredKeys.clear();
  state.score = {1:0, 2:0};

  recomputeAndScore(true);
  if(state.selectedCell===k) state.selectedCell=null;
  clearHoverFeatureState();

  renderTileSelect();
  renderTilePalette();
  render();
  setStatus(`Removed tile at (${x},${y}). Scores recomputed.`);
}

function playerTintById(player, alpha){
  if(player===2) return `rgba(255,70,70,${alpha})`;   // P2 red
  return `rgba(40,155,255,${alpha})`;                 // P1 blue
}

function playerTint(alpha){
  // Color the highlight by currently selected player (placement intent)
  return playerTintById(state.selectedPlayer, alpha);
}

function highlightTint(alpha, tone=null){
  if(tone==="neutral") return `rgba(255,216,92,${alpha})`;
  if(tone==="tie") return `rgba(168,112,255,${alpha})`;
  if(tone==="p1") return playerTintById(1, alpha);
  if(tone==="p2") return playerTintById(2, alpha);
  return playerTint(alpha);
}

function featureColor(type){
  // Keep signature, but color by player per user request.
  return playerTint(0.28);
}



function addFeatureAreaOverlay(tileDiv, tileId, localIds, type, mode="hover", tone=null){
  if(state.simplifiedGraphics){
    const ok = addFeatureAreaOverlayFromSimplified(tileDiv, tileId, localIds, mode, tone);
    if(ok) return;
  }
  if(!state.areasBase && !state.areasOverride) return;

  const svg = document.createElementNS("http://www.w3.org/2000/svg","svg");
  svg.setAttribute("viewBox","0 0 100 100");
  svg.setAttribute("class", `areaSvg ${mode==="score" ? "score" : "hover"}`);
  svg.style.position="absolute";
  svg.style.inset="0";
  svg.style.pointerEvents="none";

  const fillBase = highlightTint(mode==="score" ? 0.48 : 0.40, tone);
  const lineCol  = highlightTint(mode==="score" ? 0.98 : 0.92, tone);
  const stroke   = highlightTint(mode==="score" ? 1.00 : 0.96, tone);

  const defs = document.createElementNS("http://www.w3.org/2000/svg","defs");

  const pid = `hatch_${mode}_${tileId}_${state.selectedPlayer}_${tone || "player"}`;
  const pat = document.createElementNS("http://www.w3.org/2000/svg","pattern");
  pat.setAttribute("id", pid);
  pat.setAttribute("patternUnits","userSpaceOnUse");
  pat.setAttribute("width","5");
  pat.setAttribute("height","5");
  pat.setAttribute("patternTransform","rotate(45)");

  const line = document.createElementNS("http://www.w3.org/2000/svg","line");
  line.setAttribute("x1","0"); line.setAttribute("y1","0");
  line.setAttribute("x2","0"); line.setAttribute("y2","5");
  line.setAttribute("stroke", lineCol);
  line.setAttribute("stroke-width","2.4");
  pat.appendChild(line);

  defs.appendChild(pat);
  svg.appendChild(defs);

  for(const lid of localIds){
    const feat = getAreaFeature(tileId, lid);

    if(!feat) continue;
    for(const poly of (feat.polygons || [])){
      const pts = (poly||[]).map(p=>`${p[0]*100},${p[1]*100}`).join(" ");

      // base translucent fill
      const pg0 = document.createElementNS("http://www.w3.org/2000/svg","polygon");
      pg0.setAttribute("points", pts);
      pg0.setAttribute("fill", fillBase);
      pg0.setAttribute("stroke", stroke);
      pg0.setAttribute("stroke-width", "1.2");
      svg.appendChild(pg0);

      // hatching overlay
      const pg1 = document.createElementNS("http://www.w3.org/2000/svg","polygon");
      pg1.setAttribute("points", pts);
      pg1.setAttribute("fill", `url(#${pid})`);
      pg1.setAttribute("stroke", "none");
      svg.appendChild(pg1);
    }
  }

  tileDiv.appendChild(svg);
}

function addCityBoundaryOverlay(tileDiv, tileId, localIds){
  if(state.simplifiedGraphics){
    const ok = addCityBoundaryOverlayFromSimplified(tileDiv, tileId, localIds);
    if(ok) return;
  }
  if(!state.areasBase && !state.areasOverride) return;
  if(!localIds || localIds.size===0) return;

  const svg = document.createElementNS("http://www.w3.org/2000/svg","svg");
  svg.setAttribute("viewBox","0 0 100 100");
  svg.setAttribute("class","areaSvg cityBoundarySvg");
  svg.style.position = "absolute";
  svg.style.inset = "0";
  svg.style.pointerEvents = "none";

  for(const lid of localIds){
    const feat = getAreaFeature(tileId, lid);
    if(!feat) continue;
    for(const poly of (feat.polygons || [])){
      const pts = (poly||[]).map(p=>`${p[0]*100},${p[1]*100}`).join(" ");
      const pg = document.createElementNS("http://www.w3.org/2000/svg","polygon");
      pg.setAttribute("points", pts);
      pg.setAttribute("fill", "none");
      pg.setAttribute("stroke", "rgba(0,0,0,0.90)");
      pg.setAttribute("stroke-width", "1.35");
      pg.setAttribute("stroke-dasharray", "4 3");
      pg.setAttribute("stroke-linejoin", "round");
      svg.appendChild(pg);
    }
  }

  tileDiv.appendChild(svg);
}


function render(){
  const boardEl = $("#board");
  const size = Math.sqrt(boardEl.children.length) | 0;
  const half = Math.floor(size/2);
  const analysisForScore = analyzeBoard();
  syncSelectedScoreFeatureFromAnalysis(analysisForScore);

  for(const cell of boardEl.children) cell.innerHTML = "";

  const tileDivByCell = new Map();

  for(const [k, inst] of state.board.entries()){
    const {x,y} = parseXY(k);
    const col = x + half;
    const row = y + half;
    const idx = row*size + col;
    const cell = boardEl.children[idx];

    const tileDiv = document.createElement("div");
    tileDiv.className = "tile";
    tileDiv.style.transform = `rotate(${inst.rotDeg}deg)`;
    tileDiv.appendChild(makeTileImg(inst.tileId));
    cell.appendChild(tileDiv);
    tileDivByCell.set(k, tileDiv);

    if(state.selectedScoreFeature && state.selectedScoreFeature.tiles.has(k)){
      tileDiv.classList.add("scoreHl");
      if(state.selectedScoreTone){
        tileDiv.classList.add(`tone-${state.selectedScoreTone}`);
      }
      const scoreLocalIds = state.selectedScoreFeature.featureIdsByCell.get(k);
      if(scoreLocalIds){
        addFeatureAreaOverlay(
          tileDiv,
          inst.tileId,
          scoreLocalIds,
          state.selectedScoreFeature.type,
          "score",
          state.selectedScoreTone
        );
      }
    }
// Polygon area highlight (per feature local-id) + tile tint
    if(state.hoverFeature && state.hoverFeature.tiles.has(k)){
      tileDiv.classList.add("hl");
      const localIds = state.hoverFeature.featureIdsByCell.get(k);
      if(localIds){
        addFeatureAreaOverlay(tileDiv, inst.tileId, localIds, state.hoverFeature.type);
      }
    }

    // Meeples
    const tile = rotatedTile(inst.tileId, inst.rotDeg);
    for(const m of inst.meeples){
      const feat = tile.features.find(f=>f.id===m.featureLocalId);
      if(!feat) continue;
      const [px,py] = feat.meeple_placement;
      const mee = document.createElement("div");
      mee.className = `meeple ${m.player===1?"p1":"p2"}`;
      const nodeKey = `${inst.instId}:${m.featureLocalId}`;
      if(state.selectedScoreFeature?.nodeKeys?.has(nodeKey)){
        mee.classList.add("scoreSel");
      }
      mee.style.left = `${px*100}%`;
      mee.style.top = `${py*100}%`;
      tileDiv.appendChild(mee);
    }

    if(state.selectedScoreFeature?.type==="field"){
      const cityIds = state.selectedScoreFeature.cityFeatureIdsByCell?.get(k);
      if(cityIds && cityIds.size){
        addCityBoundaryOverlay(tileDiv, inst.tileId, cityIds);
      }

      const labels = state.selectedScoreFeature.cityLabelsByCell?.get(k) || [];
      for(const lbl of labels){
        const tag = document.createElement("div");
        tag.className = "cityRefLabel";
        tag.textContent = String(lbl.number);
        tag.style.left = `${lbl.pt[0]*100}%`;
        tag.style.top = `${lbl.pt[1]*100}%`;
        tileDiv.appendChild(tag);
      }
    }

    // Selection markers
    if(state.selectedCell === k){
      tileDiv.classList.add("selected");
      renderFeatureMarkers(tileDiv, k, inst, isOnlineTab());
    }
  }

  // Online pending tile (local pre-submit state).
  if(isOnlineTab() && state.online.pendingTile){
    const p = state.online.pendingTile;
    const pk = keyXY(p.x, p.y);
    if(!state.board.has(pk)){
      const col = p.x + half;
      const row = p.y + half;
      const idx = row*size + col;
      const cell = boardEl.children[idx];
      if(cell){
        const tileDiv = document.createElement("div");
        tileDiv.className = "tile pending";
        if(state.online.tileLocked) tileDiv.classList.add("locked");
        tileDiv.style.transform = `rotate(${p.rotDeg}deg)`;
        tileDiv.appendChild(makeTileImg(p.tileId));
        cell.appendChild(tileDiv);
        tileDivByCell.set(pk, tileDiv);
        if(state.online.tileLocked){
          tileDiv.classList.add("selected");
          renderOnlinePendingMeepleMarkers(tileDiv, p);
        }
      }
    }
  }

  if(isOnlineTab()){
    const intent = onlineOpponentIntent(state.online.match);
    if(intent){
      const ix = Number(intent.x);
      const iy = Number(intent.y);
      const irot = Number(intent.rot_deg) || 0;
      const ik = keyXY(ix, iy);
      if(Number.isFinite(ix) && Number.isFinite(iy) && !state.board.has(ik)){
        const col = ix + half;
        const row = iy + half;
        if(col>=0 && col<size && row>=0 && row<size){
          const idx = row*size + col;
          const cell = boardEl.children[idx];
          if(cell){
            const isLocked = !!intent.locked;
            const isValid = intent.valid !== false;
            const tileDiv = document.createElement("div");
            tileDiv.className = `tile remoteIntent ${Number(intent.player)===2 ? "p2" : "p1"}${isLocked ? " locked" : " cursor"}${isValid ? "" : " invalid"}`;
            tileDiv.style.transform = `rotate(${irot}deg)`;
            tileDiv.appendChild(makeTileImg(intent.tile_id));
            cell.appendChild(tileDiv);
            const mfid = intent.meeple_feature_id ? String(intent.meeple_feature_id) : "";
            if(mfid){
              const tile = rotatedTile(intent.tile_id, irot);
              const feat = tile.features.find((f)=>f.id===mfid);
              if(feat && Array.isArray(feat.meeple_placement) && feat.meeple_placement.length>=2){
                const mee = document.createElement("div");
                mee.className = `meeple ${Number(intent.player)===2 ? "p2" : "p1"}`;
                mee.style.left = `${feat.meeple_placement[0]*100}%`;
                mee.style.top = `${feat.meeple_placement[1]*100}%`;
                mee.style.opacity = "0.8";
                tileDiv.appendChild(mee);
              }
            }
          }
        }
      }
    }
  }

  // Highlight markers (placements across group)
  if(state.hoverFeature){
    for(const m of state.hoverFeature.markers){
      const tdiv = tileDivByCell.get(m.cellKey);
      if(!tdiv) continue;
      const mk = document.createElement("div");
      mk.className = "hlMarker";
      mk.dataset.type = m.type;
      mk.style.left = `${m.pt[0]*100}%`;
      mk.style.top  = `${m.pt[1]*100}%`;
      tdiv.appendChild(mk);
    }
  }

  // Ghost tile
  if(state.hoverCell && !(isOnlineTab() && state.online.tileLocked)){
    const {x,y} = parseXY(state.hoverCell);
    const pendingKey = state.online.pendingTile ? keyXY(state.online.pendingTile.x, state.online.pendingTile.y) : null;
    if(!state.board.has(state.hoverCell) && (!pendingKey || pendingKey!==state.hoverCell)){
      const col = x + half;
      const row = y + half;
      const idx = row*size + col;
      const cell = boardEl.children[idx];
      if(cell){
        const ghost = document.createElement("div");
        ghost.className = "tile ghost";
        ghost.style.transform = `rotate(${state.selectedRot}deg)`;
        ghost.appendChild(makeTileImg(state.selectedTileId));
const ok = canPlaceAt(state.selectedTileId, state.selectedRot, x, y);
        if(!ok.ok) ghost.classList.add("invalid");
        cell.appendChild(ghost);
      }
    }
  }

  renderScores(analysisForScore);
  if(isOnlineTab()) renderOnlineScorePanel(analysisForScore);
}

function renderOnlinePendingMeepleMarkers(tileDiv, pendingTile){
  const tile = rotatedTile(pendingTile.tileId, pendingTile.rotDeg);
  for(const f of tile.features){
    if(!["road","city","field","cloister"].includes(f.type)) continue;
    const [px,py] = f.meeple_placement;
    const mk = document.createElement("div");
    mk.className = "marker";
    mk.dataset.type = f.type;
    mk.title = `Select meeple on ${f.type} (${f.id})`;
    mk.style.left = `${px*100}%`;
    mk.style.top = `${py*100}%`;

    if(state.online.pendingMeepleFeatureId===f.id){
      mk.style.outline = "2px solid rgba(255,225,120,0.95)";
      mk.style.outlineOffset = "1px";
    }

    mk.addEventListener("click", (e)=>{
      e.stopPropagation();
      if(state.online.pendingMeepleFeatureId===f.id) state.online.pendingMeepleFeatureId = null;
      else state.online.pendingMeepleFeatureId = f.id;
      renderOnlineSidebar();
      render();
      onlineQueueIntentPush(true);
    });

    tileDiv.appendChild(mk);
  }

  if(state.online.pendingMeepleFeatureId){
    const feat = tile.features.find(f=>f.id===state.online.pendingMeepleFeatureId);
    if(feat){
      const [px,py] = feat.meeple_placement;
      const you = Number(state.online.match?.you_player || 1);
      const mee = document.createElement("div");
      mee.className = `meeple ${you===2?"p2":"p1"}`;
      mee.style.left = `${px*100}%`;
      mee.style.top = `${py*100}%`;
      tileDiv.appendChild(mee);
    }
  }
}

function renderFeatureMarkers(tileDiv, cellKey, inst, inspectOnly=false){
  const tile = rotatedTile(inst.tileId, inst.rotDeg);
  for(const f of tile.features){
    if(!["road","city","field","cloister"].includes(f.type)) continue;
    const [px,py] = f.meeple_placement;
    const mk = document.createElement("div");
    mk.className = "marker";
    mk.dataset.type = f.type;
    mk.title = `Place meeple on ${f.type} (${f.id})`;
    mk.style.left = `${px*100}%`;
    mk.style.top = `${py*100}%`;

    const markerKey = `${cellKey}:${f.id}`;

    mk.addEventListener("mouseenter", ()=>{
      // Prevent hover-enter render loops that detach markers before click lands.
      if(state.hoverMarkerKey===markerKey) return;
      state.hoverMarkerKey = markerKey;
      state.hoverFeature = computeHoverFeature(cellKey, f.id);
      render();
    });
    mk.addEventListener("mouseleave", (ev)=>{
      if(state.hoverMarkerKey!==markerKey) return;
      // If pointer moved to another marker, let that marker handler own hover.
      const nextMarker = ev.relatedTarget?.closest?.(".marker");
      if(nextMarker) return;
      clearHoverFeatureState();
      render();
    });

    mk.addEventListener("click", (e)=>{
      e.stopPropagation();
      if(inspectOnly) onlineInspectFeature(cellKey, f.id);
      else placeOrRemoveMeeple(cellKey, inst, f.id);
    });

    tileDiv.appendChild(mk);
  }
}

function onlineInspectFeature(cellKey, localFeatureId){
  const analysis = analyzeBoard();
  const inst = state.board.get(cellKey);
  if(!inst) return;
  const nodeKey = `${inst.instId}:${localFeatureId}`;
  if(!analysis.nodeMeta.has(nodeKey)) return;
  const root = analysis.uf.find(nodeKey);
  const group = analysis.groups.get(root);
  if(!group) return;
  const groupByKey = new Map();
  for(const g of analysis.groups.values()) groupByKey.set(g.key, g);
  state.selectedScoreKey = group.key;
  state.selectedScoreTone = toneFromGroup(group);
  state.selectedScoreFeature = buildScoreSelectionFeature(analysis, group, groupByKey);
  render();
  setStatus(`Inspecting ${group.type} feature ${localFeatureId}.`);
}

function placeOrRemoveMeeple(cellKey, inst, featureLocalId){
  if(isOnlineTab()) return;
  pushUndo();

  const existingIdx = inst.meeples.findIndex(m => m.player===state.selectedPlayer && m.featureLocalId===featureLocalId);
  if(existingIdx>=0){
    inst.meeples.splice(existingIdx, 1);
    recomputeAndScore();
    render();
    setStatus(`Removed meeple P${state.selectedPlayer} from ${inst.tileId}:${featureLocalId}.`);
    return;
  }

  if(!state.ignoreMeepleRule){
    const g = computeGlobalFeatureForLocal(cellKey, featureLocalId);
    if(g){
      const hasAnyMeeple = (g.meeplesByPlayer[1]||0) + (g.meeplesByPlayer[2]||0) > 0;
      if(hasAnyMeeple){
        setStatus("Meeple rule: that connected feature is already occupied.");
        state.undoStack.pop();
        return;
      }
    }
  }

  inst.meeples.push({player: state.selectedPlayer, featureLocalId});
  recomputeAndScore();
  render();
  setStatus(`Placed meeple P${state.selectedPlayer} on ${inst.tileId}:${featureLocalId}.`);
}

function computeHoverFeature(cellKey, localFeatureId){
  const analysis = analyzeBoard();
  const inst = state.board.get(cellKey);
  if(!inst) return null;

  const nodeKey = `${inst.instId}:${localFeatureId}`;
  const root = analysis.uf.find(nodeKey);
  const g = analysis.groups.get(root);
  if(!g) return null;

  const tiles = new Set();
  const markers = [];
  const featureIdsByCell = new Map();

  for(const nk of g.nodes){
    const meta = analysis.nodeMeta.get(nk);
    if(!meta) continue;
    tiles.add(meta.cellKey);

    if(!featureIdsByCell.has(meta.cellKey)) featureIdsByCell.set(meta.cellKey, new Set());
    featureIdsByCell.get(meta.cellKey).add(meta.localId);

    markers.push({cellKey: meta.cellKey, type: meta.type, pt: meta.meeplePlacement});
  }

  return { type: g.type, tiles, markers, featureIdsByCell };
}

function computeGlobalFeatureForLocal(cellKey, localFeatureId){
  const analysis = analyzeBoard();
  const inst = state.board.get(cellKey);
  if(!inst) return null;
  const nodeKey = `${inst.instId}:${localFeatureId}`;
  const root = analysis.uf.find(nodeKey);
  return analysis.groups.get(root) || null;
}

function recomputeAndScore(reawardAll=false){
  const analysis = analyzeBoard();
  if(reawardAll){
    state.scoredKeys.clear();
    state.score = {1:0, 2:0};
  }

  const scoredNow = new Set();
  for(const g of analysis.groups.values()){
    if(g.type==="field") continue;
    if(!g.complete) continue;

    const key = g.key;
    if(state.scoredKeys.has(key)) continue;

    const m1 = g.meeplesByPlayer[1] || 0;
    const m2 = g.meeplesByPlayer[2] || 0;
    const max = Math.max(m1,m2);
    if(max<=0){
      state.scoredKeys.add(key);
      continue;
    }
    const winners = [];
    if(m1===max) winners.push(1);
    if(m2===max) winners.push(2);

    const pts = scoreFeature(g, true);
    for(const w of winners) state.score[w] += pts;

    state.scoredKeys.add(key);
    scoredNow.add(key);
  }

  if(scoredNow.size>0){
    for(const [cellK, inst] of state.board.entries()){
      inst.meeples = inst.meeples.filter(m=>{
        const nodeKey = `${inst.instId}:${m.featureLocalId}`;
        const gid = analysis.uf.find(nodeKey);
        const g = analysis.groups.get(gid);
        if(!g) return true;
        if(g.type==="field") return true;
        return !scoredNow.has(g.key);
      });
    }
  }
}

function scoreFeature(group, completed){
  if(group.type==="road") return group.tiles.size;
  if(group.type==="city"){
    const tiles = group.tiles.size;
    const pennants = group.pennants;
    return completed ? (2*tiles + 2*pennants) : (tiles + pennants);
  }
  if(group.type==="cloister"){
    if(completed) return 9;
    return 1 + group.adjacentCount;
  }
  if(group.type==="field"){
    return 3 * group.adjCompletedCities.size;
  }
  return 0;
}

function stableGroupKey(group){
  return `${group.type}|${Array.from(group.nodes).sort().join(";")}`;
}

function masksTouch(maskA, maskB, N, reach=1){
  for(let y=0; y<N; y++){
    const yoff = y*N;
    for(let x=0; x<N; x++){
      const i = yoff + x;
      if(!maskA[i]) continue;
      const y0 = Math.max(0, y-reach), y1 = Math.min(N-1, y+reach);
      const x0 = Math.max(0, x-reach), x1 = Math.min(N-1, x+reach);
      for(let yy=y0; yy<=y1; yy++){
        const yyoff = yy*N;
        for(let xx=x0; xx<=x1; xx++){
          if(maskB[yyoff + xx]) return true;
        }
      }
    }
  }
  return false;
}

function fieldTouchesCityByPorts(fieldPortsSet, cityPorts){
  for(const edge of (cityPorts||[])){
    const candidates = CITY_EDGE_TO_ADJ_FIELD_PORTS[edge];
    if(!candidates) continue;
    for(const p of candidates){
      if(fieldPortsSet.has(p)) return true;
    }
  }
  return false;
}

function getFieldCityAdjacencyForTile(tileId){
  if(fieldCityAdjCache.has(tileId)) return fieldCityAdjCache.get(tileId);

  const feats = mergedFeaturesForTile(tileId).filter(f=>f.type==="field" || f.type==="city");
  const fields = feats.filter(f=>f.type==="field");
  const cities = feats.filter(f=>f.type==="city");
  const out = new Map();

  if(fields.length===0 || cities.length===0){
    fieldCityAdjCache.set(tileId, out);
    return out;
  }

  const N = 96;
  const masksById = new Map();
  for(const f of feats){
    const area = getAreaFeature(tileId, f.id);
    const polys = Array.isArray(area?.polygons) ? area.polygons : [];
    if(!polys.length) continue;
    const mask = new Uint8Array(N*N);
    rasterizePolygonsToMask(polys, mask, N);
    masksById.set(f.id, mask);
  }

  for(const ff of fields){
    const fieldPorts = new Set(ff.ports || []);
    const hits = new Set();
    const fMask = masksById.get(ff.id);

    for(const cc of cities){
      let adjacent = false;
      const cMask = masksById.get(cc.id);

      if(fMask && cMask){
        adjacent = masksTouch(fMask, cMask, N, 1);
      }
      if(!adjacent){
        adjacent = fieldTouchesCityByPorts(fieldPorts, cc.ports || []);
      }
      if(adjacent) hits.add(cc.id);
    }

    if(hits.size) out.set(ff.id, hits);
  }

  fieldCityAdjCache.set(tileId, out);
  return out;
}

function analyzeBoard(){
  const uf = new UnionFind();
  const nodeMeta = new Map();
  const instById = new Map();
  const perTileLookup = new Map();

  for(const [k, inst] of state.board.entries()){
    instById.set(inst.instId, {cellKey:k, inst});
    const tile = rotatedTile(inst.tileId, inst.rotDeg);

    const roadEdge = {};
    const cityEdge = {};
    const fieldHalf = {};

    for(const f of tile.features){
      const nodeKey = `${inst.instId}:${f.id}`;
      uf.add(nodeKey);
      nodeMeta.set(nodeKey, {
        type: f.type,
        ports: f.ports || [],
        tags: f.tags || {},
        meeplePlacement: f.meeple_placement, // not rotated
        instId: inst.instId,
        cellKey: k,
        localId: f.id
      });

      if(f.type==="road") for(const p of f.ports) roadEdge[p] = f.id;
      else if(f.type==="city") for(const p of f.ports) cityEdge[p] = f.id;
      else if(f.type==="field") for(const p of f.ports) fieldHalf[p] = f.id;
    }
    perTileLookup.set(inst.instId, {roadEdge, cityEdge, fieldHalf});
  }

  for(const [k, inst] of state.board.entries()){
    const {x,y} = parseXY(k);
    const lookA = perTileLookup.get(inst.instId);

    const ke = keyXY(x+1,y);
    if(state.board.has(ke)){
      const instB = state.board.get(ke);
      const lookB = perTileLookup.get(instB.instId);
      connectEdgeFeatures(uf, inst, lookA, "E", instB, lookB, "W");
      connectFieldHalves(uf, inst, lookA, "En", instB, lookB, "Wn");
      connectFieldHalves(uf, inst, lookA, "Es", instB, lookB, "Ws");
    }

    const ks = keyXY(x,y+1);
    if(state.board.has(ks)){
      const instB = state.board.get(ks);
      const lookB = perTileLookup.get(instB.instId);
      connectEdgeFeatures(uf, inst, lookA, "S", instB, lookB, "N");
      connectFieldHalves(uf, inst, lookA, "Sw", instB, lookB, "Nw");
      connectFieldHalves(uf, inst, lookA, "Se", instB, lookB, "Ne");
    }
  }

  const groups = new Map();
  for(const [nodeKey, meta] of nodeMeta.entries()){
    const root = uf.find(nodeKey);
    if(!groups.has(root)){
      groups.set(root, {
        id: root,
        type: meta.type,
        nodes: new Set(),
        tiles: new Set(),
        meeplesByPlayer: {1:0, 2:0},
        pennants: 0,
        complete: false,
        openPorts: new Set(),
        adjacentCount: 0,
        adjCompletedCities: new Set(),
        key: ""
      });
    }
    const g = groups.get(root);
    g.nodes.add(nodeKey);
    g.tiles.add(meta.instId);
    if(meta.type==="city") g.pennants += (meta.tags?.pennants || 0);
  }

  for(const [k, inst] of state.board.entries()){
    for(const m of inst.meeples){
      const nodeKey = `${inst.instId}:${m.featureLocalId}`;
      if(!nodeMeta.has(nodeKey)) continue;
      const root = uf.find(nodeKey);
      const g = groups.get(root);
      if(!g) continue;
      g.meeplesByPlayer[m.player] = (g.meeplesByPlayer[m.player]||0) + 1;
    }
  }

  for(const g of groups.values()){
    g.key = stableGroupKey(g);
  }

  for(const g of groups.values()){
    if(g.type==="road" || g.type==="city"){
      const open = new Set();
      for(const nodeKey of g.nodes){
        const meta = nodeMeta.get(nodeKey);
        const {cellKey} = meta;
        const {x,y} = parseXY(cellKey);

        for(const edge of meta.ports){
          const {dx,dy} = edgeDelta(edge);
          const nk = keyXY(x+dx, y+dy);
          if(!state.board.has(nk)){
            open.add(`${cellKey}:${edge}`);
            continue;
          }
          const nInst = state.board.get(nk);
          const nLook = perTileLookup.get(nInst.instId);
          const opp = EDGE_OPP[edge];

          if(g.type==="road"){
            if(!(opp in nLook.roadEdge)) open.add(`${cellKey}:${edge}`);
          }else{
            if(!(opp in nLook.cityEdge)) open.add(`${cellKey}:${edge}`);
          }
        }
      }
      g.openPorts = open;
      g.complete = (open.size===0);
      continue;
    }

    if(g.type==="cloister"){
      const only = Array.from(g.tiles)[0];
      const cellKey = instById.get(only)?.cellKey;
      if(!cellKey){
        g.adjacentCount = 0;
        g.complete = false;
        continue;
      }
      const {x,y} = parseXY(cellKey);
      let cnt=0;
      for(let dy=-1; dy<=1; dy++){
        for(let dx=-1; dx<=1; dx++){
          if(dx===0 && dy===0) continue;
          if(state.board.has(keyXY(x+dx, y+dy))) cnt++;
        }
      }
      g.adjacentCount = cnt;
      g.complete = (cnt===8);
    }
  }

  for(const g of groups.values()){
    if(g.type!=="field") continue;

    // Farm adjacency to completed cities based on per-tile field/city area contact.
    for(const nodeKey of g.nodes){
      const meta = nodeMeta.get(nodeKey);
      const inst = state.board.get(meta.cellKey);
      if(!inst) continue;
      const tileAdj = getFieldCityAdjacencyForTile(inst.tileId);
      const cityLocals = tileAdj.get(meta.localId);
      if(!cityLocals) continue;

      for(const cityLocal of cityLocals){
        const cityNodeKey = `${meta.instId}:${cityLocal}`;
        const cityRoot = uf.find(cityNodeKey);
        const cityGroup = groups.get(cityRoot);
        if(cityGroup && cityGroup.type==="city" && cityGroup.complete){
          g.adjCompletedCities.add(cityGroup.key);
        }
      }
    }
  }

  return { uf, nodeMeta, groups };
}

function edgeDelta(edge){
  if(edge==="N") return {dx:0,dy:-1};
  if(edge==="E") return {dx:1,dy:0};
  if(edge==="S") return {dx:0,dy:1};
  if(edge==="W") return {dx:-1,dy:0};
  throw new Error("bad edge "+edge);
}

function connectEdgeFeatures(uf, instA, lookA, edgeA, instB, lookB, edgeB){
  if(edgeA in lookA.roadEdge && edgeB in lookB.roadEdge){
    uf.union(`${instA.instId}:${lookA.roadEdge[edgeA]}`, `${instB.instId}:${lookB.roadEdge[edgeB]}`);
  }
  if(edgeA in lookA.cityEdge && edgeB in lookB.cityEdge){
    uf.union(`${instA.instId}:${lookA.cityEdge[edgeA]}`, `${instB.instId}:${lookB.cityEdge[edgeB]}`);
  }
}

function connectFieldHalves(uf, instA, lookA, halfA, instB, lookB, halfB){
  if(halfA in lookA.fieldHalf && halfB in lookB.fieldHalf){
    uf.union(`${instA.instId}:${lookA.fieldHalf[halfA]}`, `${instB.instId}:${lookB.fieldHalf[halfB]}`);
  }
}

function winnersOfGroup(g){
  const m1 = g.meeplesByPlayer[1]||0;
  const m2 = g.meeplesByPlayer[2]||0;
  const max = Math.max(m1,m2);
  if(max<=0) return [];
  const ws=[];
  if(m1===max) ws.push(1);
  if(m2===max) ws.push(2);
  return ws;
}

function toneFromGroup(g){
  const ws = winnersOfGroup(g);
  if(ws.length===0) return "neutral";
  if(ws.length>=2) return "tie";
  return ws[0]===2 ? "p2" : "p1";
}

function addFeatureIdForCell(featureIdsByCell, cellKey, localId){
  if(!featureIdsByCell.has(cellKey)) featureIdsByCell.set(cellKey, new Set());
  featureIdsByCell.get(cellKey).add(localId);
}

function scoreEndNowValue(g){
  if(g.type==="city") return g.complete ? scoreFeature(g,true) : scoreFeature(g,false);
  if(g.type==="road") return scoreFeature(g,true);
  if(g.type==="cloister") return scoreFeature(g,false);
  if(g.type==="field") return scoreFeature(g,false);
  return 0;
}

function groupStatusText(g){
  if(g.type==="field") return `${g.adjCompletedCities.size} completed ${g.adjCompletedCities.size===1?"city":"cities"}`;
  if(g.type==="cloister") return `${g.adjacentCount}/8 neighbors`;
  if(g.complete) return "complete";
  return `${g.openPorts.size} open edge${g.openPorts.size===1?"":"s"}`;
}

function escHtml(str){
  return String(str).replace(/[&<>"']/g, (ch)=>(
    ch==="&" ? "&amp;" :
    ch==="<" ? "&lt;" :
    ch===">" ? "&gt;" :
    ch==="\"" ? "&quot;" : "&#39;"
  ));
}

function buildScoreSelectionFeature(analysis, group, groupByKey){
  if(!group) return null;

  const tiles = new Set();
  const markers = [];
  const featureIdsByCell = new Map();
  const nodeKeys = new Set();

  for(const nodeKey of group.nodes){
    const meta = analysis.nodeMeta.get(nodeKey);
    if(!meta) continue;
    nodeKeys.add(nodeKey);
    tiles.add(meta.cellKey);
    addFeatureIdForCell(featureIdsByCell, meta.cellKey, meta.localId);
    markers.push({cellKey: meta.cellKey, type: meta.type, pt: meta.meeplePlacement});
  }

  const out = {
    key: group.key,
    type: group.type,
    tiles,
    markers,
    featureIdsByCell,
    nodeKeys,
    cityFeatureIdsByCell: new Map(),
    cityLabelsByCell: new Map()
  };

  if(group.type!=="field") return out;

  const cityKeys = Array.from(group.adjCompletedCities).sort();
  let num = 1;
  for(const cityKey of cityKeys){
    const cityGroup = groupByKey.get(cityKey);
    if(!cityGroup || cityGroup.type!=="city") continue;

    const cityMetas = [];
    for(const nodeKey of cityGroup.nodes){
      const meta = analysis.nodeMeta.get(nodeKey);
      if(!meta) continue;
      cityMetas.push(meta);
      tiles.add(meta.cellKey);
      addFeatureIdForCell(out.cityFeatureIdsByCell, meta.cellKey, meta.localId);
    }
    if(!cityMetas.length) continue;

    let cx=0, cy=0;
    for(const meta of cityMetas){
      const pos = parseXY(meta.cellKey);
      cx += pos.x;
      cy += pos.y;
    }
    cx /= cityMetas.length;
    cy /= cityMetas.length;

    let best = cityMetas[0];
    let bestD = Infinity;
    for(const meta of cityMetas){
      const pos = parseXY(meta.cellKey);
      const d = (pos.x-cx)*(pos.x-cx) + (pos.y-cy)*(pos.y-cy);
      if(d<bestD){
        bestD = d;
        best = meta;
      }
    }

    if(!out.cityLabelsByCell.has(best.cellKey)) out.cityLabelsByCell.set(best.cellKey, []);
    out.cityLabelsByCell.get(best.cellKey).push({
      number: num,
      pt: Array.isArray(best.meeplePlacement) ? best.meeplePlacement : [0.5,0.5]
    });
    num++;
  }

  return out;
}

function syncSelectedScoreFeatureFromAnalysis(analysis){
  const groupByKey = new Map();
  for(const g of analysis.groups.values()) groupByKey.set(g.key, g);

  if(state.selectedScoreKey && !groupByKey.has(state.selectedScoreKey)){
    clearSelectedScoreState();
  }
  if(state.selectedScoreKey){
    state.selectedScoreFeature = buildScoreSelectionFeature(
      analysis,
      groupByKey.get(state.selectedScoreKey),
      groupByKey
    );
  }else{
    state.selectedScoreFeature = null;
  }
  return groupByKey;
}

function setSelectedScoreKey(scoreKey, tone=null){
  if(state.selectedScoreKey===scoreKey && state.selectedScoreTone===tone){
    clearSelectedScoreState();
  }else{
    state.selectedScoreKey = scoreKey;
    state.selectedScoreTone = tone;
  }
  render();
}

function renderScores(analysis){
  const boardAnalysis = analysis || analyzeBoard();
  if(!analysis) syncSelectedScoreFeatureFromAnalysis(boardAnalysis);

  const endNow = {1:0, 2:0};
  const ifCompleteNow = {1:0, 2:0};
  const entriesByType = { city: [], road: [], cloister: [], field: [] };
  const typeTotals = {
    city: {1:0,2:0},
    road: {1:0,2:0},
    cloister: {1:0,2:0},
    field: {1:0,2:0}
  };
  const typeSeq = {city:0, road:0, cloister:0, field:0};

  for(const g of boardAnalysis.groups.values()){
    const ws = winnersOfGroup(g);
    if(ws.length===0) continue;

    if(g.type==="road" || g.type==="city" || g.type==="cloister"){
      if(!g.complete){
        const ptsEnd = scoreEndNowValue(g);
        for(const w of ws) endNow[w] += ptsEnd;

        const ptsComp = (g.type==="cloister") ? 9 : scoreFeature(g,true);
        for(const w of ws) ifCompleteNow[w] += ptsComp;
      }
    }else if(g.type==="field"){
      const ptsFarm = scoreFeature(g,false);
      for(const w of ws) endNow[w] += ptsFarm;
    }

    if(!(g.type in entriesByType)) continue;
    typeSeq[g.type] += 1;

    const ptsEnd = scoreEndNowValue(g);
    const p1End = ws.includes(1) ? ptsEnd : 0;
    const p2End = ws.includes(2) ? ptsEnd : 0;
    typeTotals[g.type][1] += p1End;
    typeTotals[g.type][2] += p2End;

    let info = `tiles ${g.tiles.size}`;
    if(g.type==="city") info += `, pennants ${g.pennants}`;
    if(g.type==="field") info += `, completed cities ${g.adjCompletedCities.size}`;
    if(g.type==="cloister") info += `, neighbors ${g.adjacentCount}/8`;
    info += `, meeples ${g.meeplesByPlayer[1]||0}/${g.meeplesByPlayer[2]||0}`;

    entriesByType[g.type].push({
      key: g.key,
      idx: typeSeq[g.type],
      status: groupStatusText(g),
      info,
      p1End,
      p2End
    });
  }

  const finalNow = {
    1: state.score[1] + endNow[1],
    2: state.score[2] + endNow[2]
  };

  const TYPE_LABEL = { city:"City", road:"Road", cloister:"Cloister", field:"Field" };
  const typeSections = ["city","road","cloister","field"].map((type)=>{
    const entries = entriesByType[type];
    const openAttr = state.scoreDetailsOpen[type] ? " open" : "";

    const rows = entries.length===0
      ? `<tr><td colspan="6" class="muted">No claimed ${type} features on map.</td></tr>`
      : entries.map((entry)=>{
          const active = state.selectedScoreKey===entry.key ? " active" : "";
          return `
            <tr class="scoreEntryRow${active}" data-score-key="${escHtml(entry.key)}" tabindex="0">
              <td class="mono">${entry.idx}</td>
              <td>${escHtml(entry.status)}</td>
              <td class="mono">${entry.p1End ? "+"+entry.p1End : "0"}</td>
              <td class="mono">${entry.p2End ? "+"+entry.p2End : "0"}</td>
              <td>${escHtml(entry.info)}</td>
              <td class="mono">${escHtml(entry.key)}</td>
            </tr>`;
        }).join("");

    return `
      <details class="scoreTypeBlock" data-score-type="${type}"${openAttr}>
        <summary>${TYPE_LABEL[type]} (entries: ${entries.length}) — P1:+${typeTotals[type][1]} / P2:+${typeTotals[type][2]}</summary>
        <table class="scoreTable scoreDetailsTable">
          <thead>
            <tr><th>#</th><th>Status</th><th>P1 End</th><th>P2 End</th><th>Info</th><th>Key</th></tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </details>`;
  }).join("");

  $("#scoreBox").innerHTML = `
    <table class="scoreTable scoreSummaryTable">
      <thead>
        <tr><th></th><th>Current Score</th><th>End-if-now Bonus</th><th>If-complete-now Bonus</th><th>Final-if-now</th></tr>
      </thead>
      <tbody>
        <tr><td class="mono">P1 (Blue)</td><td class="mono">${state.score[1]}</td><td class="mono">+${endNow[1]}</td><td class="mono">+${ifCompleteNow[1]}</td><td class="mono">${finalNow[1]}</td></tr>
        <tr><td class="mono">P2 (Red)</td><td class="mono">${state.score[2]}</td><td class="mono">+${endNow[2]}</td><td class="mono">+${ifCompleteNow[2]}</td><td class="mono">${finalNow[2]}</td></tr>
      </tbody>
    </table>
    <div class="scoreTypeList">${typeSections}</div>`;

  const scoreBox = $("#scoreBox");
  if(!scoreBox) return;

  for(const det of scoreBox.querySelectorAll("details.scoreTypeBlock")){
    det.addEventListener("toggle", ()=>{
      const type = det.dataset.scoreType;
      if(type && type in state.scoreDetailsOpen){
        state.scoreDetailsOpen[type] = det.open;
      }
    });
  }
  for(const row of scoreBox.querySelectorAll(".scoreEntryRow")){
    const key = row.dataset.scoreKey;
    if(!key) continue;
    row.addEventListener("click", ()=>setSelectedScoreKey(key));
    row.addEventListener("keydown", (e)=>{
      if(e.key!=="Enter" && e.key!==" ") return;
      e.preventDefault();
      setSelectedScoreKey(key);
    });
  }
}

// -------------------- Online lobby/match --------------------
const ONLINE_NAME_STORAGE_KEY = "carc_online_name_v1";

async function onlineApiGet(path){
  const res = await fetch(path, {cache:"no-store"});
  let payload = null;
  try{ payload = await res.json(); }catch(_err){ payload = null; }
  if(!res.ok || !payload?.ok){
    throw new Error(payload?.error || `HTTP ${res.status}`);
  }
  return payload;
}

async function onlineApiPost(path, body){
  const res = await fetch(path, {
    method: "POST",
    headers: {"Content-Type":"application/json"},
    body: JSON.stringify(body || {})
  });
  let payload = null;
  try{ payload = await res.json(); }catch(_err){ payload = null; }
  if(!res.ok || !payload?.ok){
    throw new Error(payload?.error || `HTTP ${res.status}`);
  }
  return payload;
}

function onlineStopLoops(){
  if(state.online.pollTimer){
    clearInterval(state.online.pollTimer);
    state.online.pollTimer = null;
  }
  if(state.online.hbTimer){
    clearInterval(state.online.hbTimer);
    state.online.hbTimer = null;
  }
}

function onlineStartLoops(){
  onlineStopLoops();
  state.online.pollTimer = setInterval(()=>{
    onlinePoll().catch((err)=>{
      setStatus(`Online sync error: ${err?.message || err}`);
    });
  }, 1200);
  state.online.hbTimer = setInterval(()=>{
    onlineHeartbeat().catch(()=>{});
  }, 10000);
}

async function onlineHeartbeat(){
  if(!state.online.connected || !state.online.token) return;
  try{
    await onlineApiPost("/api/session/heartbeat", {token: state.online.token});
  }catch(err){
    if(/token|session/i.test(String(err?.message || ""))){
      await onlineDisconnect(false);
    }
  }
}

async function onlineConnect(){
  if(state.online.connected) return;
  const input = $("#onlineNameInput");
  const name = (input?.value || "").trim();
  if(!name){
    setStatus("Enter your name to connect.");
    return;
  }

  const payload = await onlineApiPost("/api/session/join", {name});
  state.online.connected = true;
  state.online.token = payload.token;
  state.online.userId = payload.user?.id || null;
  state.online.userName = payload.user?.name || name;
  state.online.lobby = payload.lobby || null;
  state.online.match = null;
  state.online.pollBusy = false;
  state.online.matchRenderSig = null;
  state.online.intentInFlight = false;
  state.online.intentQueued = false;
  state.online.lastIntentSig = "";
  clearOnlinePendingTurn();

  try{ localStorage.setItem(ONLINE_NAME_STORAGE_KEY, state.online.userName); }catch(_err){}

  if(isOnlineTab()) captureLocalStateForOnline();

  onlineStartLoops();
  await onlinePoll(true);
  renderOnlineSidebar();
  if(isOnlineTab()) renderOnlineScorePanel();
  setStatus(`Connected as ${state.online.userName}.`);
}

async function onlineDisconnect(sendLeave=true){
  const hadConnection = state.online.connected;
  const token = state.online.token;

  if(sendLeave && hadConnection && token){
    try{ await onlineApiPost("/api/session/leave", {token}); }catch(_err){}
  }

  onlineStopLoops();
  state.online.connected = false;
  state.online.token = null;
  state.online.userId = null;
  state.online.userName = "";
  state.online.lobby = null;
  state.online.match = null;
  state.online.pollBusy = false;
  state.online.matchRenderSig = null;
  state.online.intentInFlight = false;
  state.online.intentQueued = false;
  state.online.lastIntentSig = "";
  clearOnlinePendingTurn();

  if(isOnlineTab()){
    applyOnlineMatchToBoardState();
    renderOnlineSidebar();
    renderOnlineScorePanel();
  }
  if(hadConnection) setStatus("Disconnected from online server.");
}

async function onlinePoll(force=false){
  if(!state.online.connected || !state.online.token) return;
  if(state.online.pollBusy && !force) return;

  state.online.pollBusy = true;
  try{
    const lobby = await onlineApiGet(`/api/lobby?token=${encodeURIComponent(state.online.token)}`);
    state.online.lobby = lobby;

    const hasMatchContext = !!(lobby.current_match_id || lobby.last_match_id);
    if(hasMatchContext){
      const mres = await onlineApiGet(`/api/match?token=${encodeURIComponent(state.online.token)}`);
      state.online.match = mres.match || null;
    }else{
      state.online.match = null;
      clearOnlinePendingTurn();
    }

    if(state.online.match?.status!=="active"){
      clearOnlinePendingTurn();
    }else{
      const previewTile = onlinePreviewTileForCursor(state.online.match) || state.online.match?.current_turn?.tile_id;
      if(previewTile){
        state.selectedTileId = previewTile;
        updateTilePreview();
      }
      if(!state.online.match?.can_act){
        clearOnlinePendingTurn();
      }
    }

    const nextSig = onlineMatchSignature(state.online.match);
    const sigChanged = nextSig !== state.online.matchRenderSig;
    if(sigChanged) state.online.matchRenderSig = nextSig;

    const preserveLocalPending =
      state.online.match?.status==="active" &&
      state.online.match?.can_act &&
      (!!state.online.pendingTile || !!state.hoverCell);

    if(isOnlineTab() && (force || sigChanged) && !preserveLocalPending){
      applyOnlineMatchToBoardState();
    }
    renderOnlineSidebar();
    if(isOnlineTab()) renderOnlineScorePanel();
  }catch(err){
    const msg = String(err?.message || err || "unknown");
    if(/token|session|unauthorized|401/i.test(msg)){
      await onlineDisconnect(false);
      setStatus("Online session expired. Connect again.");
    }else{
      setStatus(`Online sync error: ${msg}`);
    }
  }finally{
    state.online.pollBusy = false;
  }
}

async function onlineSendInvite(toUserId){
  if(!state.online.connected) return;
  try{
    await onlineApiPost("/api/invite", {token: state.online.token, to_user_id: toUserId});
    await onlinePoll(true);
  }catch(err){
    setStatus(`Invite failed: ${err?.message || err}`);
  }
}

async function onlineRespondInvite(inviteId, action){
  if(!state.online.connected) return;
  try{
    await onlineApiPost("/api/invite/respond", {
      token: state.online.token,
      invite_id: inviteId,
      action
    });
    await onlinePoll(true);
  }catch(err){
    setStatus(`Invite response failed: ${err?.message || err}`);
  }
}

async function onlineSendChat(){
  if(!state.online.connected) return;
  const input = $("#onlineChatInput");
  const text = (input?.value || "").trim();
  if(!text) return;
  input.value = "";
  try{
    await onlineApiPost("/api/chat", {token: state.online.token, text});
    await onlinePoll(true);
  }catch(err){
    setStatus(`Chat failed: ${err?.message || err}`);
  }
}

function onlineMatchCanAct(){
  return !!(state.online.connected && state.online.match?.status==="active" && state.online.match?.can_act);
}

function onlineLocalIntentCandidate(){
  if(!onlineMatchCanAct()) return null;
  const turnTile = state.online.match?.current_turn?.tile_id;
  if(!turnTile) return null;

  if(state.online.pendingTile){
    const p = state.online.pendingTile;
    return {
      x: p.x,
      y: p.y,
      rotDeg: p.rotDeg,
      meepleFeatureId: state.online.pendingMeepleFeatureId || null,
      locked: !!state.online.tileLocked,
      valid: true
    };
  }

  if(!state.hoverCell) return null;
  const {x, y} = parseXY(state.hoverCell);
  const cellKey = keyXY(x, y);
  if(state.board.has(cellKey)) return null;
  const rot = state.selectedRot;
  const ok = canPlaceAt(turnTile, rot, x, y);
  return {
    x,
    y,
    rotDeg: rot,
    meepleFeatureId: null,
    locked: false,
    valid: !!ok.ok
  };
}

function onlineCurrentIntentSignature(){
  const c = onlineLocalIntentCandidate();
  if(!c) return "none";
  return JSON.stringify({
    x: c.x,
    y: c.y,
    r: c.rotDeg,
    m: c.meepleFeatureId || null,
    l: !!c.locked,
    v: !!c.valid
  });
}

async function onlinePushIntentNow(){
  if(!state.online.connected || !state.online.token || !state.online.match) return;
  const payload = { token: state.online.token };
  const c = onlineLocalIntentCandidate();
  if(c){
    payload.x = c.x;
    payload.y = c.y;
    payload.rot_deg = c.rotDeg;
    payload.meeple_feature_id = c.meepleFeatureId || null;
    payload.locked = !!c.locked;
  }else{
    payload.clear = true;
  }
  try{
    await onlineApiPost("/api/match/intent", payload);
  }catch(_err){
    // Best-effort preview sync only.
  }
}

function onlineQueueIntentPush(force=false){
  if(!state.online.connected || !state.online.match) return;
  const sig = onlineCurrentIntentSignature();
  if(!force && sig===state.online.lastIntentSig) return;
  state.online.lastIntentSig = sig;
  if(state.online.intentInFlight){
    state.online.intentQueued = true;
    return;
  }
  state.online.intentInFlight = true;
  onlinePushIntentNow()
    .catch(()=>{})
    .finally(()=>{
      state.online.intentInFlight = false;
      if(state.online.intentQueued){
        state.online.intentQueued = false;
        onlineQueueIntentPush(true);
      }
    });
}

function onlineMatchSignature(match){
  if(!match) return "none";
  const turn = match.current_turn || {};
  const score = match.score || {};
  const intent = match.turn_intent || null;
  const players = Array.isArray(match.players) ? match.players.map(p=>({
    p: p.player,
    s: p.score,
    m: p.meeples_left
  })) : [];
  return JSON.stringify({
    id: match.id,
    st: match.status,
    ti: turn.turn_index || 0,
    tu: turn.user_id || "",
    tt: turn.tile_id || "",
    b: Array.isArray(match.board) ? match.board.length : 0,
    rt: match.remaining_total || 0,
    s1: Number(score["1"] ?? score[1] ?? 0),
    s2: Number(score["2"] ?? score[2] ?? 0),
    p: players,
    yt: match.you_next_tile || "",
    it: intent ? [intent.player, intent.user_id, intent.tile_id, intent.x, intent.y, intent.rot_deg, intent.meeple_feature_id || "", intent.locked ? 1 : 0, intent.valid ? 1 : 0] : null,
    e: match.last_event || ""
  });
}

function onlineSubmitTileLocal(){
  if(!onlineMatchCanAct()){
    setStatus("It is not your turn.");
    return;
  }
  if(!state.online.pendingTile){
    setStatus("Place the drawn tile on the board first.");
    return;
  }
  const p = state.online.pendingTile;
  const ok = canPlaceAt(p.tileId, p.rotDeg, p.x, p.y);
  if(!ok.ok){
    setStatus(ok.reason);
    return;
  }
  state.online.tileLocked = true;
  state.selectedCell = keyXY(p.x, p.y);
  renderOnlineSidebar();
  render();
}

function onlineResetTileLocal(){
  clearOnlinePendingTurn();
  state.selectedCell = null;
  clearHoverFeatureState();
  renderOnlineSidebar();
  renderBoardTopInfo();
  render();
  onlineQueueIntentPush(true);
}

async function onlineSubmitTurn(skipMeeple){
  if(!onlineMatchCanAct()){
    setStatus("It is not your turn.");
    return;
  }
  if(!state.online.pendingTile || !state.online.tileLocked){
    setStatus("Place tile first.");
    return;
  }

  const p = state.online.pendingTile;
  const meepleFeature = skipMeeple ? null : (state.online.pendingMeepleFeatureId || null);
  try{
    const payload = await onlineApiPost("/api/match/submit_turn", {
      token: state.online.token,
      x: p.x,
      y: p.y,
      rot_deg: p.rotDeg,
      meeple_feature_id: meepleFeature
    });
    state.online.match = payload.match || null;
    clearOnlinePendingTurn();
    if(isOnlineTab()) applyOnlineMatchToBoardState();
    renderOnlineSidebar();
    if(isOnlineTab()) renderOnlineScorePanel();
    renderBoardTopInfo();
    setStatus("Turn submitted.");
  }catch(err){
    renderBoardTopInfo();
    setStatus(`Turn rejected: ${err?.message || err}`);
  }
}

function onlineComputeProjection(analysis){
  const endNow = {1:0, 2:0};
  const ifCompleteNow = {1:0, 2:0};
  for(const g of analysis.groups.values()){
    const ws = winnersOfGroup(g);
    if(ws.length===0) continue;

    if(g.type==="road" || g.type==="city" || g.type==="cloister"){
      if(!g.complete){
        const ptsEnd = scoreEndNowValue(g);
        for(const w of ws) endNow[w] += ptsEnd;
        const ptsComp = (g.type==="cloister") ? 9 : scoreFeature(g, true);
        for(const w of ws) ifCompleteNow[w] += ptsComp;
      }
    }else if(g.type==="field"){
      const ptsFarm = scoreFeature(g, false);
      for(const w of ws) endNow[w] += ptsFarm;
    }
  }
  return {
    endNow,
    ifCompleteNow,
    finalNow: {
      1: state.score[1] + endNow[1],
      2: state.score[2] + endNow[2]
    }
  };
}

function renderBoardTopInfo(analysis){
  const infoEl = $("#boardTopInfo");
  const actWrap = $("#onlineBoardActions");
  const confirmBtn = $("#onlineConfirmBtn");
  const revertBtn = $("#onlineRevertBtn");
  if(!infoEl) return;

  if(!state.online.connected || !state.online.match){
    infoEl.textContent = "No active match.";
    if(actWrap) actWrap.classList.add("hidden");
    return;
  }

  const m = state.online.match;
  const p1 = m.players?.find(p=>p.player===1);
  const p2 = m.players?.find(p=>p.player===2);
  const a = analysis || analyzeBoard();
  const proj = onlineComputeProjection(a);
  const now = `Now ${p1?.name || "P1"} ${state.score[1]} - ${state.score[2]} ${p2?.name || "P2"}`;
  const fin = `If end now ${proj.finalNow[1]} - ${proj.finalNow[2]}`;
  if(m.status==="active"){
    const t = m.current_turn;
    const nextMine = (!m.can_act && m.your_next_tile) ? ` | Next for you ${m.your_next_tile}` : "";
    infoEl.textContent = `Turn ${t?.turn_index || 0}: ${t?.name || "?"} | Draw ${t?.tile_id || "?"}${nextMine} | ${now} | ${fin}`;
  }else if(m.status==="finished"){
    infoEl.textContent = "Finished.";
  }else{
    infoEl.textContent = `Match ${m.status}.`;
  }

  const showActions = onlineMatchCanAct() && !!state.online.pendingTile;
  if(actWrap) actWrap.classList.toggle("hidden", !showActions);
  if(confirmBtn) confirmBtn.disabled = !showActions;
  if(revertBtn) revertBtn.disabled = !showActions;
}

function renderOnlineScorePanel(analysis){
  const summaryEl = $("#onlineScoreSummary");
  const listEl = $("#onlineFeatureList");
  if(!summaryEl || !listEl) return;

  if(!state.online.connected || !state.online.match){
    summaryEl.innerHTML = `<div class="hint">No active online match.</div>`;
    listEl.innerHTML = "";
    renderBoardTopInfo();
    return;
  }

  const m = state.online.match;
  const a = analysis || analyzeBoard();
  const proj = (m.status==="active")
    ? onlineComputeProjection(a)
    : {
        endNow: {1:0, 2:0},
        ifCompleteNow: {1:0, 2:0},
        finalNow: {1: state.score[1], 2: state.score[2]}
      };
  renderBoardTopInfo(a);
  summaryEl.innerHTML = `
    <table class="scoreTable scoreSummaryTable">
      <thead>
        <tr><th></th><th>Current</th><th>End-if-now</th><th>Final-if-now</th></tr>
      </thead>
      <tbody>
        <tr><td class="mono">P1</td><td class="mono">${state.score[1]}</td><td class="mono">+${proj.endNow[1]}</td><td class="mono">${proj.finalNow[1]}</td></tr>
        <tr><td class="mono">P2</td><td class="mono">${state.score[2]}</td><td class="mono">+${proj.endNow[2]}</td><td class="mono">${proj.finalNow[2]}</td></tr>
      </tbody>
    </table>`;

  const typeLabel = {city:"City", road:"Road", cloister:"Cloister", field:"Field"};
  const typeCount = {city:0, road:0, cloister:0, field:0};
  const openEntries = [];
  const closedEntries = [];

  for(const g of a.groups.values()){
    const ws = winnersOfGroup(g);
    if(ws.length===0) continue;
    if(!(g.type in typeCount)) continue;
    typeCount[g.type] += 1;
    const pts = scoreEndNowValue(g);
    const p1 = ws.includes(1) ? pts : 0;
    const p2 = ws.includes(2) ? pts : 0;
    const entry = {
      key: g.key,
      order: {city:0, road:1, cloister:2, field:3}[g.type],
      idx: typeCount[g.type],
      tone: toneFromGroup(g),
      label: `${typeLabel[g.type]} #${typeCount[g.type]} | ${groupStatusText(g)} | P1:+${p1} P2:+${p2}`
    };
    const isClosed = (g.type!=="field") && !!g.complete;
    if(isClosed) closedEntries.push(entry);
    else openEntries.push(entry);
  }
  openEntries.sort((x,y)=>x.order-y.order || x.idx-y.idx);
  closedEntries.sort((x,y)=>x.order-y.order || x.idx-y.idx);

  const renderEntryRows = (entries)=>{
    if(entries.length===0){
      return `<div class="hint">No entries.</div>`;
    }
    return entries.map((entry)=>{
      const active = state.selectedScoreKey===entry.key ? " active" : "";
      const toneCls = entry.tone ? ` tone-${entry.tone}` : "";
      return `<button type="button" class="onlineFeatureRow${active}${toneCls}" data-score-key="${escHtml(entry.key)}" data-score-tone="${escHtml(entry.tone || "neutral")}">${escHtml(entry.label)}</button>`;
    }).join("");
  };

  listEl.innerHTML = `
    <details class="onlineFeatureSection"${state.onlineFeatureSectionsOpen.open ? " open" : ""} data-online-feature-section="open">
      <summary>Open / In-progress (${openEntries.length})</summary>
      <div class="onlineFeatureRows">${renderEntryRows(openEntries)}</div>
    </details>
    <details class="onlineFeatureSection"${state.onlineFeatureSectionsOpen.closed ? " open" : ""} data-online-feature-section="closed">
      <summary>Closed (${closedEntries.length})</summary>
      <div class="onlineFeatureRows">${renderEntryRows(closedEntries)}</div>
    </details>
  `;

  for(const det of listEl.querySelectorAll(".onlineFeatureSection")){
    det.addEventListener("toggle", ()=>{
      const key = det.getAttribute("data-online-feature-section");
      if(!key) return;
      if(key==="open" || key==="closed"){
        state.onlineFeatureSectionsOpen[key] = det.open;
      }
    });
  }
  for(const btn of listEl.querySelectorAll(".onlineFeatureRow")){
    const key = btn.dataset.scoreKey;
    if(!key) continue;
    const tone = btn.dataset.scoreTone || null;
    btn.addEventListener("click", ()=>setSelectedScoreKey(key, tone));
  }
}

function renderOnlineSidebar(){
  const connEl = $("#onlineConnStatus");
  const usersEl = $("#onlineUsersList");
  const inviteEl = $("#onlineInviteList");
  const chatEl = $("#onlineChatLog");
  const matchEl = $("#onlineMatchStatus");
  const meepleEl = $("#onlineMeepleStatus");

  const connectBtn = $("#onlineConnectBtn");
  const disconnectBtn = $("#onlineDisconnectBtn");

  if(connectBtn) connectBtn.disabled = state.online.connected;
  if(disconnectBtn) disconnectBtn.disabled = !state.online.connected;

  if(connEl){
    connEl.textContent = state.online.connected
      ? `Connected as ${state.online.userName || "?"}.`
      : "Disconnected.";
  }

  if(!state.online.connected){
    if(usersEl) usersEl.innerHTML = `<div class="hint">Connect to see players.</div>`;
    if(inviteEl) inviteEl.innerHTML = `<div class="hint">No invites.</div>`;
    if(chatEl) chatEl.innerHTML = `<div class="hint">No chat.</div>`;
    if(matchEl) matchEl.textContent = "No active match.";
    if(meepleEl) meepleEl.textContent = "Meeple selection: none.";
    renderBoardTopInfo();
    return;
  }

  const lobby = state.online.lobby;
  const users = lobby?.users || [];
  const sent = new Set((lobby?.invites_sent_by_me || []).map(inv=>inv.to_user_id));
  const meStatus = lobby?.you?.status || "available";
  const canInviteAnyone = meStatus==="available" && !lobby?.current_match_id;

  if(usersEl){
    const rows = users.map((u)=>{
      const isSelf = u.id===state.online.userId;
      const status = u.status || "available";
      const btn = (!isSelf && canInviteAnyone && status==="available" && !sent.has(u.id))
        ? `<button type="button" data-online-invite="${escHtml(u.id)}">Invite</button>`
        : "";
      return `
        <div class="onlineRow">
          <div class="onlineRowMain">
            <span class="onlineName">${escHtml(u.name)}${isSelf ? " (you)" : ""}</span>
            <span class="onlineStatus">${escHtml(status)}</span>
          </div>
          <div class="onlineButtons">${btn}</div>
        </div>`;
    }).join("");
    usersEl.innerHTML = rows || `<div class="hint">No other users connected.</div>`;
    for(const b of usersEl.querySelectorAll("[data-online-invite]")){
      const uid = b.getAttribute("data-online-invite");
      b.addEventListener("click", ()=>onlineSendInvite(uid));
    }
  }

  if(inviteEl){
    const incoming = lobby?.invites_for_me || [];
    const outgoing = lobby?.invites_sent_by_me || [];
    const incRows = incoming.map((inv)=>`
      <div class="onlineRow">
        <div class="onlineRowMain">
          <span>${escHtml(inv.from_name)} invited you</span>
        </div>
        <div class="onlineButtons">
          <button type="button" data-online-accept="${escHtml(inv.id)}">Accept</button>
          <button type="button" data-online-decline="${escHtml(inv.id)}">Decline</button>
        </div>
      </div>`).join("");
    const outRows = outgoing.map((inv)=>`
      <div class="onlineRow">
        <div class="onlineRowMain">
          <span>Invite sent to ${escHtml(inv.to_name)}</span>
        </div>
        <div class="onlineButtons"></div>
      </div>`).join("");

    inviteEl.innerHTML = (incRows + outRows) || `<div class="hint">No pending invites.</div>`;
    for(const b of inviteEl.querySelectorAll("[data-online-accept]")){
      b.addEventListener("click", ()=>onlineRespondInvite(b.getAttribute("data-online-accept"), "accept"));
    }
    for(const b of inviteEl.querySelectorAll("[data-online-decline]")){
      b.addEventListener("click", ()=>onlineRespondInvite(b.getAttribute("data-online-decline"), "decline"));
    }
  }

  if(chatEl){
    const chat = lobby?.chat || [];
    const stayBottom = (chatEl.scrollTop + chatEl.clientHeight) >= (chatEl.scrollHeight - 30);
    chatEl.innerHTML = chat.map((m)=>{
      const sender = m.system ? "system" : (m.from?.name || "unknown");
      const klass = m.system ? "onlineChatMsg onlineChatSystem" : "onlineChatMsg";
      return `<div class="${klass}"><span class="onlineChatMeta">[${escHtml(m.time || "--:--:--")}] ${escHtml(sender)}:</span>${escHtml(m.text || "")}</div>`;
    }).join("") || `<div class="hint">No messages yet.</div>`;
    const latestId = chat.length ? chat[chat.length-1].id : null;
    if(stayBottom || latestId!==state.online.chatRenderedLastId){
      chatEl.scrollTop = chatEl.scrollHeight;
    }
    state.online.chatRenderedLastId = latestId;
  }

  if(matchEl){
    const m = state.online.match;
    if(!m){
      matchEl.textContent = "No active match.";
    }else if(m.status==="active"){
      const p1 = m.players?.find(p=>p.player===1);
      const p2 = m.players?.find(p=>p.player===2);
      const t = m.current_turn;
      const nextMine = (!m.can_act && m.your_next_tile) ? `<br/>Your next tile: <b>${escHtml(m.your_next_tile)}</b>.` : "";
      matchEl.innerHTML = `
        P1 ${escHtml(p1?.name || "P1")} (meeples ${p1?.meeples_left ?? 0}) vs
        P2 ${escHtml(p2?.name || "P2")} (meeples ${p2?.meeples_left ?? 0})<br/>
        Turn ${t?.turn_index || 0}: ${escHtml(t?.name || "?" )} draws <b>${escHtml(t?.tile_id || "?")}</b>.
        ${Array.isArray(t?.burned) && t.burned.length ? `Burned before draw: ${escHtml(t.burned.join(", "))}.` : ""}
        ${nextMine}
      `;
    }else if(m.status==="finished"){
      matchEl.textContent = "Finished.";
    }else{
      matchEl.textContent = `Match ${escHtml(m.status)}.`;
    }
  }

  if(meepleEl){
    const sel = state.online.pendingMeepleFeatureId;
    meepleEl.textContent = sel ? `Meeple selection: ${sel}.` : "Meeple selection: none.";
  }
  renderBoardTopInfo();
}

function initOnlineUI(){
  const nameInput = $("#onlineNameInput");
  if(nameInput){
    try{
      const saved = localStorage.getItem(ONLINE_NAME_STORAGE_KEY) || "";
      if(saved) nameInput.value = saved;
    }catch(_err){}
    nameInput.addEventListener("keydown", (e)=>{
      if(e.key!=="Enter") return;
      e.preventDefault();
      onlineConnect().catch((err)=>setStatus(`Connect failed: ${err?.message || err}`));
    });
  }

  $("#onlineConnectBtn")?.addEventListener("click", ()=>{
    onlineConnect().catch((err)=>setStatus(`Connect failed: ${err?.message || err}`));
  });
  $("#onlineDisconnectBtn")?.addEventListener("click", ()=>{
    onlineDisconnect(true).catch(()=>{});
  });

  $("#onlineChatSend")?.addEventListener("click", ()=>{
    onlineSendChat().catch(()=>{});
  });
  $("#onlineChatInput")?.addEventListener("keydown", (e)=>{
    if(e.key!=="Enter") return;
    e.preventDefault();
    onlineSendChat().catch(()=>{});
  });

  $("#onlineConfirmBtn")?.addEventListener("click", ()=>{
    onlineSubmitTurn(false).catch(()=>{});
  });
  $("#onlineRevertBtn")?.addEventListener("click", ()=>onlineResetTileLocal());

  window.addEventListener("beforeunload", ()=>{
    if(!state.online.connected || !state.online.token) return;
    try{
      const blob = new Blob([JSON.stringify({token: state.online.token})], {type:"application/json"});
      navigator.sendBeacon("/api/session/leave", blob);
    }catch(_err){}
  });

  renderOnlineSidebar();
  renderOnlineScorePanel();
  renderBoardTopInfo();
}
// ------------------ end Online lobby/match ------------------


// -------------------- Manual Area Editor --------------------
const editor = {
  tileId: "A",
  featureId: null,
  featureType: null,
  polys: [],          // [[{x,y},...], ...] in 0..100
  selectedPoly: 0,
  dragging: null      // {poly, pt}
};

function ensureOverrideRoot(){
  if(!state.areasOverride){
    state.areasOverride = { schema: { coords:"normalized_0_1", note:"Manual overrides created in simulator" }, tiles: {} };
  }
}


function getOverrideEntry(tileId, featureId){
  const t = state.areasOverride?.tiles?.[tileId];
  if(!t) return null;
  return t.features?.[featureId] || null;
}
function ensureOverrideEntry(tileId, featureId, baseFallback){
  if(!state.areasOverride) state.areasOverride = { schema:{coords:"normalized_0_1", fillRule:"evenodd"}, tiles:{} };
  if(!state.areasOverride.tiles) state.areasOverride.tiles = {};
  if(!state.areasOverride.tiles[tileId]) state.areasOverride.tiles[tileId] = {features:{}};
  if(!state.areasOverride.tiles[tileId].features) state.areasOverride.tiles[tileId].features = {};
  if(!state.areasOverride.tiles[tileId].features[featureId]){
    const entry = baseFallback ? deepCopy(baseFallback) : { type:"field", polygons:[], ports:[], tags:{} };
    entry.type = entry.type || "field";
    entry.ports = normalizePortsForType(entry.ports || [], entry.type);
    state.areasOverride.tiles[tileId].features[featureId] = entry;
  }
  const ent = state.areasOverride.tiles[tileId].features[featureId];
  ent.type = ent.type || "field";
  ent.ports = normalizePortsForType(ent.ports || [], ent.type);
  return ent;
}

function featureCentroid(polys){
  if(!polys || !polys.length) return [0.5,0.5];
  // centroid of largest polygon ring
  const poly = polys[0];
  let A=0, Cx=0, Cy=0;
  for(let i=0;i<poly.length;i++){
    const [x1,y1]=poly[i];
    const [x2,y2]=poly[(i+1)%poly.length];
    const cross = x1*y2 - y1*x2;
    A += cross;
    Cx += (x1+x2)*cross;
    Cy += (y1+y2)*cross;
  }
  A *= 0.5;
  if(Math.abs(A) < 1e-9) return [poly[0][0], poly[0][1]];
  Cx /= (6*A); Cy /= (6*A);
  return [Math.max(0,Math.min(1,Cx)), Math.max(0,Math.min(1,Cy))];
}

function mergedFeaturesForTile(tileId){
  const base = state.tileById.get(tileId);
  if(!base || !base.features) return [];
  const baseById = new Map();
  for(const f of base.features) baseById.set(f.id, f);

  const out = [];
  const overrides = state.areasOverride?.tiles?.[tileId]?.features || {};

  // base features (possibly edited or deleted)
  for(const f of base.features){
    const ov = overrides[f.id];
    if(ov && ov.deleted) continue;
    const mf = deepCopy(f);
    mf.ports = normalizePortsForType(mf.ports || [], mf.type);
    if(ov){
      if(ov.type) mf.type = ov.type;
      if(Array.isArray(ov.ports)) mf.ports = normalizePortsForType(deepCopy(ov.ports), mf.type);
      else mf.ports = normalizePortsForType(mf.ports || [], mf.type);
      if(ov.tags) mf.tags = deepCopy(ov.tags);
      // polygons are handled by getAreaFeature, not here
      if(Array.isArray(ov.meeple_placement)) mf.meeple_placement = deepCopy(ov.meeple_placement);
    }
    out.push(mf);
  }

  // added features (present only in overrides)
  for(const [fid, ov] of Object.entries(overrides)){
    if(baseById.has(fid)) continue;
    if(ov && ov.deleted) continue;
    const mf = {
      id: fid,
      type: ov.type || "field",
      ports: normalizePortsForType(Array.isArray(ov.ports) ? deepCopy(ov.ports) : [], ov.type || "field"),
      tags: ov.tags ? deepCopy(ov.tags) : {},
      meeple_placement: Array.isArray(ov.meeple_placement) ? deepCopy(ov.meeple_placement) : featureCentroid(ov.polygons || [])
    };
    out.push(mf);
  }

  // stable order: city, road, cloister, field then id
  const rank = {city:0, road:1, cloister:2, field:3};
  out.sort((a,b)=>{
    const ra = rank[a.type] ?? 9, rb = rank[b.type] ?? 9;
    if(ra!==rb) return ra-rb;
    return a.id.localeCompare(b.id);
  });
  return out;
}

function getBaseFeature(tileId, featureId){
  return state.areasBase?.tiles?.[tileId]?.features?.[featureId] || null;
}

function getBaseTileFeature(tileId, featureId){
  const tile = state.tileById.get(tileId);
  if(!tile || !Array.isArray(tile.features)) return null;
  return tile.features.find(f=>f.id===featureId) || null;
}

function getOverrideFeature(tileId, featureId){
  return state.areasOverride?.tiles?.[tileId]?.features?.[featureId] || null;
}

function setOverrideFeature(tileId, featureId, type, polygons){
  const baseAreaFeat = getBaseFeature(tileId, featureId);
  const baseTileFeat = getBaseTileFeature(tileId, featureId);
  const mergedFeat = mergedFeaturesForTile(tileId).find(f=>f.id===featureId);
  const fallback = {
    type: type || mergedFeat?.type || baseTileFeat?.type || baseAreaFeat?.type || "field",
    polygons: baseAreaFeat?.polygons || [],
    ports: mergedFeat?.ports || baseTileFeat?.ports || [],
    tags: mergedFeat?.tags || baseTileFeat?.tags || {},
    meeple_placement: mergedFeat?.meeple_placement || baseTileFeat?.meeple_placement || [0.5,0.5]
  };
  const entry = ensureOverrideEntry(tileId, featureId, fallback);
  entry.type = type || entry.type || fallback.type || "field";
  if(!Array.isArray(entry.ports)) entry.ports = deepCopy(fallback.ports || []);
  entry.ports = normalizePortsForType(entry.ports, entry.type);
  entry.polygons = polygons || [];
  // if this is an added feature or placement should follow polygon, update meeple placement
  entry.meeple_placement = featureCentroid(entry.polygons);
  entry.deleted = false;
  persistOverridesToLocalStorage();
}


function deleteOverrideFeature(tileId, featureId){
  // Mark as deleted so it can hide base features too
  const baseAreaFeat = getBaseFeature(tileId, featureId);
  const baseTileFeat = getBaseTileFeature(tileId, featureId);
  const mergedFeat = mergedFeaturesForTile(tileId).find(f=>f.id===featureId);
  const fallback = {
    type: mergedFeat?.type || baseTileFeat?.type || baseAreaFeat?.type || "field",
    polygons: baseAreaFeat?.polygons || [],
    ports: mergedFeat?.ports || baseTileFeat?.ports || [],
    tags: mergedFeat?.tags || baseTileFeat?.tags || {},
    meeple_placement: mergedFeat?.meeple_placement || baseTileFeat?.meeple_placement || [0.5,0.5]
  };
  const entry = ensureOverrideEntry(tileId, featureId, fallback);
  entry.deleted = true;
  persistOverridesToLocalStorage();
}


function normToEditor(poly){
  return (poly||[]).map(p=>({x: p[0]*100, y: p[1]*100}));
}
function editorToNorm(poly){
  return (poly||[]).map(p=>[p.x/100, p.y/100]);
}

function editorLoadCurrent(){
  const of = getOverrideFeature(editor.tileId, editor.featureId);
  const bf = getBaseFeature(editor.tileId, editor.featureId);
  const src = of || bf;
  editor.featureType = (src?.type) || editor.featureType;
  editor.polys = (src?.polygons || []).map(normToEditor);
  if(editor.polys.length===0) editor.polys = [[]];
  editor.selectedPoly = 0;
  editor.dragging = null;
  renderAreaEditor();
}

function renderAreaEditor(){
  const stage = $("#aeStage");
  if(!stage) return;
  stage.innerHTML = "";

  const svg = document.createElementNS("http://www.w3.org/2000/svg","svg");
  svg.setAttribute("viewBox","0 0 100 100");
  svg.setAttribute("class","aeSvg");

  // tile image
  const img = document.createElementNS("http://www.w3.org/2000/svg","image");
  img.setAttributeNS("http://www.w3.org/1999/xlink", "href", tileImageUrl(editor.tileId));
  img.setAttribute("x","0"); img.setAttribute("y","0");
  img.setAttribute("width","100"); img.setAttribute("height","100");
  img.setAttribute("preserveAspectRatio","none");
  svg.appendChild(img);

  // hatch defs for preview
  const defs = document.createElementNS("http://www.w3.org/2000/svg","defs");
  const pid = `aeHatch_${state.selectedPlayer}`;
  const pat = document.createElementNS("http://www.w3.org/2000/svg","pattern");
  pat.setAttribute("id", pid);
  pat.setAttribute("patternUnits","userSpaceOnUse");
  pat.setAttribute("width","6");
  pat.setAttribute("height","6");
  pat.setAttribute("patternTransform","rotate(45)");
  const line = document.createElementNS("http://www.w3.org/2000/svg","line");
  line.setAttribute("x1","0"); line.setAttribute("y1","0");
  line.setAttribute("x2","0"); line.setAttribute("y2","6");
  line.setAttribute("stroke", playerTint(0.75));
  line.setAttribute("stroke-width","2");
  pat.appendChild(line);
  defs.appendChild(pat);
  svg.appendChild(defs);

  // polygons
  editor.polys.forEach((poly, pi)=>{
    const pg = document.createElementNS("http://www.w3.org/2000/svg","polygon");
    pg.setAttribute("class", "aePoly" + (pi===editor.selectedPoly ? " selected":""));
    pg.setAttribute("points", poly.map(p=>`${p.x},${p.y}`).join(" "));
    pg.setAttribute("stroke", playerTint(0.9));
    pg.setAttribute("fill", `url(#${pid})`);
    pg.setAttribute("fill-opacity","0.22");
    pg.addEventListener("mousedown", (e)=>{
      e.stopPropagation();
      editor.selectedPoly = pi;
      renderAreaEditor();
    });
    svg.appendChild(pg);

    // points
    poly.forEach((p, pj)=>{
      const c = document.createElementNS("http://www.w3.org/2000/svg","circle");
      c.setAttribute("class","aePt");
      c.setAttribute("r","1.6");
      c.setAttribute("cx", p.x);
      c.setAttribute("cy", p.y);
      c.setAttribute("fill", playerTint(0.95));
      c.addEventListener("mousedown", (e)=>{
        e.stopPropagation();
        editor.selectedPoly = pi;
        editor.dragging = {poly: pi, pt: pj};
        c.classList.add("dragging");
      });
      c.addEventListener("contextmenu", (e)=>{
        e.preventDefault();
        e.stopPropagation();
        // delete point
        editor.polys[pi].splice(pj,1);
        renderAreaEditor();
      });
      svg.appendChild(c);
    });
  });

  // add point on click
  svg.addEventListener("mousedown", (e)=>{
    // ignore if dragging already started on point
    const pt = svgPoint(svg, e);
    if(!editor.polys[editor.selectedPoly]) editor.polys[editor.selectedPoly]=[];
    editor.polys[editor.selectedPoly].push({x: pt.x, y: pt.y});
    renderAreaEditor();
  });

  svg.addEventListener("mousemove", (e)=>{
    if(!editor.dragging) return;
    const pt = svgPoint(svg, e);
    const {poly, pt:pj} = editor.dragging;
    if(editor.polys[poly] && editor.polys[poly][pj]){
      editor.polys[poly][pj].x = pt.x;
      editor.polys[poly][pj].y = pt.y;
      renderAreaEditor();
    }
  });
  svg.addEventListener("mouseup", ()=>{
    editor.dragging = null;
  });
  svg.addEventListener("mouseleave", ()=>{
    editor.dragging = null;
  });

  stage.appendChild(svg);
}

function svgPoint(svg, evt){
  const rect = svg.getBoundingClientRect();
  const x = (evt.clientX - rect.left) / rect.width * 100;
  const y = (evt.clientY - rect.top) / rect.height * 100;
  return {x: Math.max(0, Math.min(100, x)), y: Math.max(0, Math.min(100, y))};
}

function initAreaEditor(){
  const aeTile = $("#aeTile");
  const aeFeat = $("#aeFeature");
  if(!aeTile || !aeFeat) return;

  // tile dropdown
  aeTile.innerHTML = "";
  const ids = Array.from(state.tileById.keys()).sort();
  for(const id of ids){
    const opt = document.createElement("option");
    opt.value = id;
    opt.textContent = id;
    aeTile.appendChild(opt);
  }

  function fillFeatures(tileId){
    aeFeat.innerHTML = "";
    const base = state.tileById.get(tileId);
    const feats = (base?.features || []).filter(f=>["road","city","field","cloister"].includes(f.type));
    for(const f of feats){
      const opt = document.createElement("option");
      opt.value = f.id;
      opt.textContent = `${f.id} (${f.type})`;
      aeFeat.appendChild(opt);
    }
    editor.featureId = feats[0]?.id || null;
    editor.featureType = feats[0]?.type || null;
    aeFeat.value = editor.featureId || "";
  }

  editor.tileId = ids[0] || "A";
  aeTile.value = editor.tileId;
  fillFeatures(editor.tileId);
  editorLoadCurrent();

  aeTile.addEventListener("change", ()=>{
    editor.tileId = aeTile.value;
    fillFeatures(editor.tileId);
    editorLoadCurrent();
  });

  aeFeat.addEventListener("change", ()=>{
    editor.featureId = aeFeat.value;
    // derive type from tileset
    const base = state.tileById.get(editor.tileId);
    const f = base.features.find(x=>x.id===editor.featureId);
    editor.featureType = f?.type || editor.featureType;
    editorLoadCurrent();
  });

  $("#aeNewPoly").addEventListener("click", ()=>{
    editor.polys.push([]);
    editor.selectedPoly = editor.polys.length-1;
    renderAreaEditor();
  });

  $("#aeClear").addEventListener("click", ()=>{
    editor.polys = [[]];
    editor.selectedPoly = 0;
    renderAreaEditor();
  });

  $("#aeReset").addEventListener("click", ()=>{
    deleteOverrideFeature(editor.tileId, editor.featureId);
    editorLoadCurrent();
    render(); // update highlight usage
    setStatus(`Reverted override for ${editor.tileId}:${editor.featureId}`);
  });

  $("#aeSave").addEventListener("click", ()=>{
    // drop degenerate polys (<3 points)
    const polysNorm = editor.polys
      .filter(p=>p.length>=3)
      .map(editorToNorm);

    setOverrideFeature(editor.tileId, editor.featureId, editor.featureType, polysNorm);
    render(); // update highlight usage
    setStatus(`Saved override for ${editor.tileId}:${editor.featureId} (${polysNorm.length} polygon(s)).`);
  });

  $("#aeExport").addEventListener("click", ()=>{
    const payload = state.areasOverride || { schema:{coords:"normalized_0_1"}, tiles:{} };
    const blob = new Blob([JSON.stringify(payload, null, 2)], {type:"application/json"});
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = OVERRIDES_FILE_NAME;
    a.click();
    URL.revokeObjectURL(a.href);
  });

  $("#aeImport").addEventListener("click", ()=>$("#aeImportFile").click());
  $("#aeImportFile").addEventListener("change", async (e)=>{
    const f = e.target.files?.[0];
    if(!f) return;
    try{
      const txt = await f.text();
      state.areasOverride = JSON.parse(txt);
      persistOverridesToLocalStorage();
      editorLoadCurrent();
      render();
      setStatus("Overrides imported.");
    }catch(err){
      setStatus("Failed to import overrides: " + err.message);
    }
    e.target.value = "";
  });
}
// ------------------ end Manual Area Editor ------------------


// -------------------- Refine polygons (paint-to-mask) --------------------
function buildRefineList(){
  const list = [];
  const ids = Array.from(state.tileById.keys()).sort();
  for(const id of ids){
    const feats = mergedFeaturesForTile(id).filter(f=>["road","city","field","cloister"].includes(f.type));
    for(const f of feats){
      list.push({tileId:id, featureId:f.id, featureType:f.type});
    }
  }
  return list;
}


function initRefinePolygons(){
  state.areasOverride = normalizeOverridesPayload(state.areasOverride || emptyOverridesPayload());

  state.refine.N = 256;
  state.refine.brushR = 10;
  state.refine.mask = new Uint8Array(state.refine.N * state.refine.N);
  state.refine.down = false;
  state.refine.mode = "add";
  state.refine.list = buildRefineList();
  state.refine.idx = Math.min(state.refine.idx||0, Math.max(0, state.refine.list.length-1));

  state.refine.baseImg = new Image();
  state.refine.baseImg.decoding = "async";
  state.refine.baseImg.onload = ()=>{ if(isRefineTab()) { renderRefine(); renderRefineAll(); } };

  // Build canvases
  const stage = $("#rpStage");
  if(stage){
    const c = document.createElement("canvas");
    c.className = "rpCanvas";
    c.width = 512;
    c.height = 512;
    stage.innerHTML = "";
    stage.appendChild(c);
    state.refine.canvas = c;
    state.refine.ctx = c.getContext("2d");
    c.addEventListener("contextmenu", (e)=>e.preventDefault());
    c.addEventListener("mousedown", (e)=>{
      if(!isRefineTab()) return;
      const rect = c.getBoundingClientRect();
      state.refine.lastMouse = {x:(e.clientX-rect.left)/rect.width, y:(e.clientY-rect.top)/rect.height, has:true};
      state.refine.down = true;
      state.refine.mode = (e.button===2) ? "erase" : "add";
      paintAtEvent(e);
    });
    window.addEventListener("mouseup", ()=>{
      if(!state.refine.down) return;
      state.refine.down = false;
      renderRefine();
    renderRefineAll();
      renderRefineAll();
    });
    c.addEventListener("mousemove", (e)=>{
      const rect = c.getBoundingClientRect();
      const x = (e.clientX - rect.left) / rect.width;
      const y = (e.clientY - rect.top) / rect.height;
      state.refine.lastMouse = {x, y, has:true};
      if(state.refine.down) paintAtEvent(e);
      else { renderRefine();
    renderRefineAll(); }
    });
    c.addEventListener("wheel", (e)=>{
      if(!isRefineTab()) return;
      e.preventDefault();
      const d = (e.deltaY>0) ? -1 : +1;
      state.refine.brushR = Math.max(2, Math.min(60, state.refine.brushR + d));
      updateBrushLabel();
      renderRefine();
    renderRefineAll();
    }, {passive:false});
  }

  const stageAll = $("#rpStageAll");
  if(stageAll){
    const c2 = document.createElement("canvas");
    c2.className = "rpCanvas";
    c2.width = 512;
    c2.height = 512;
    stageAll.innerHTML = "";
    stageAll.appendChild(c2);
    state.refine.canvasAll = c2;
    state.refine.ctxAll = c2.getContext("2d");
  }

  // Wire buttons
  $("#rpPrev")?.addEventListener("click", ()=>refinePrev());
  $("#rpSkip")?.addEventListener("click", ()=>refineNext());
  $("#rpClear")?.addEventListener("click", ()=>{ refineClear(); renderRefine();
    renderRefineAll(); renderRefineAll(); });
  $("#rpResetToCurrent")?.addEventListener("click", ()=>{ refineResetToCurrent(); renderRefine();
    renderRefineAll(); renderRefineAll(); });

  $("#rpExport")?.addEventListener("click", ()=>{
    const payload = state.areasOverride || { schema:{coords:"normalized_0_1", fillRule:"evenodd"}, tiles:{} };
    const blob = new Blob([JSON.stringify(payload, null, 2)], {type:"application/json"});
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = OVERRIDES_FILE_NAME;
    a.click();
    URL.revokeObjectURL(a.href);
  });
  $("#rpImport")?.addEventListener("click", ()=>$("#rpImportFile")?.click());
  $("#rpImportFile")?.addEventListener("change", async (e)=>{
    const f = e.target.files?.[0];
    if(!f) return;
    try{
      const txt = await f.text();
      state.areasOverride = JSON.parse(txt);
      persistOverridesToLocalStorage();
      setStatus("Overrides imported.");
      state.refine.list = buildRefineList();
      state.refine.list = buildRefineList();
  state.refine.idx = Math.min(state.refine.idx, Math.max(0, state.refine.list.length-1));
      populateRefineSelectors();
      refineResetToCurrent();
      renderRefine();
    renderRefineAll();
      renderRefineAll();
      render();
    }catch(err){
      setStatus("Failed to import overrides: " + err.message);
    }
    e.target.value = "";
  });

  // Add/delete feature
  $("#rpAddFeature")?.addEventListener("click", ()=>refineAddFeature());
  $("#rpDeleteFeature")?.addEventListener("click", ()=>refineDeleteFeature());

  // Selectors
  $("#rpTileSelect")?.addEventListener("change", ()=>{
    if(!saveCurrentRefineWork()) return;
    const tid = $("#rpTileSelect").value;
    // jump to first feature of that tile in list
    state.refine.list = buildRefineList();
    const idx = state.refine.list.findIndex(it=>it.tileId===tid);
    if(idx>=0) state.refine.idx = idx;
    refineResetToCurrent();
    renderRefine();
    renderRefineAll();
    renderRefineAll();
    render();
  });
  $("#rpFeatureSelect")?.addEventListener("change", ()=>{
    if(!saveCurrentRefineWork()) return;
    const tid = $("#rpTileSelect").value;
    const fid = $("#rpFeatureSelect").value;
    state.refine.list = buildRefineList();
    const idx = state.refine.list.findIndex(it=>it.tileId===tid && it.featureId===fid);
    if(idx>=0) state.refine.idx = idx;
    refineResetToCurrent();
    renderRefine();
    renderRefineAll();
    renderRefineAll();
    render();
  });

  // Keyboard
  window.addEventListener("keydown", (e)=>{
    if(!isRefineTab()) return;
    if(e.key === "Enter"){
      e.preventDefault();
      refineNext();
    }
  });

  updateBrushLabel();
  populateRefineSelectors();
  refineResetToCurrent();
  renderRefine();
  renderRefineAll();
}



function updateBrushLabel(){
  const el = $("#rpBrushLabel");
  if(el) el.textContent = `${state.refine.brushR}px`;
}

function pickPreferredFeatureOwner(features, preferredFeatureId){
  if(preferredFeatureId){
    const keep = features.find(f=>f.id===preferredFeatureId);
    if(keep) return keep;
  }
  return [...features].sort((a,b)=>{
    const pa = REFINE_TYPE_PRIORITY[a.type] ?? 99;
    const pb = REFINE_TYPE_PRIORITY[b.type] ?? 99;
    if(pa !== pb) return pa - pb;
    return a.id.localeCompare(b.id);
  })[0];
}

function ensureOverrideFromMergedFeature(tileId, feat){
  const area = getAreaFeature(tileId, feat.id);
  return ensureOverrideEntry(tileId, feat.id, {
    type: feat.type,
    polygons: area?.polygons || [],
    ports: feat.ports || [],
    tags: feat.tags || {},
    meeple_placement: feat.meeple_placement || [0.5,0.5]
  });
}

function enforceRefinePortExclusivity(tileId, preferredFeatureId){
  const feats = mergedFeaturesForTile(tileId).filter(f=>["road","city","field","cloister"].includes(f.type));
  if(!feats.length) return false;

  const nextPorts = new Map(feats.map(f=>[f.id, new Set(normalizePortsForType(f.ports || [], f.type))]));
  const originalPorts = new Map(feats.map(f=>[f.id, normalizePortsForType(f.ports || [], f.type)]));

  // Road/city edges are exclusive to a single road/city feature per edge.
  for(const edge of ["N","E","S","W"]){
    const owners = feats.filter(f=>["road","city"].includes(f.type) && nextPorts.get(f.id)?.has(edge));
    if(owners.length <= 1) continue;
    const keep = pickPreferredFeatureOwner(owners, preferredFeatureId);
    for(const f of owners){
      if(f.id !== keep.id) nextPorts.get(f.id).delete(edge);
    }
  }

  // City ownership of an edge removes both field halves on that edge from all fields.
  const fields = feats.filter(f=>f.type==="field");
  for(const city of feats.filter(f=>f.type==="city")){
    const cityPorts = nextPorts.get(city.id);
    for(const edge of ["N","E","S","W"]){
      if(!cityPorts?.has(edge)) continue;
      for(const h of EDGE_TO_FIELD_HALVES[edge]){
        for(const ff of fields){
          nextPorts.get(ff.id).delete(h);
        }
      }
    }
  }

  // Field halves are exclusive between field features.
  for(const half of ["Nw","Ne","En","Es","Se","Sw","Ws","Wn"]){
    const owners = fields.filter(f=>nextPorts.get(f.id)?.has(half));
    if(owners.length <= 1) continue;
    const keep = pickPreferredFeatureOwner(owners, preferredFeatureId);
    for(const f of owners){
      if(f.id !== keep.id) nextPorts.get(f.id).delete(half);
    }
  }

  let changed = false;
  for(const feat of feats){
    const normalized = normalizePortsForType(Array.from(nextPorts.get(feat.id) || []), feat.type);
    const prev = originalPorts.get(feat.id) || [];
    if(JSON.stringify(prev) === JSON.stringify(normalized)) continue;
    const ent = ensureOverrideFromMergedFeature(tileId, feat);
    ent.deleted = false;
    ent.type = feat.type;
    ent.ports = normalized;
    changed = true;
  }

  if(changed) persistOverridesToLocalStorage();
  return changed;
}

function enforceRefinePolygonExclusivity(tileId, preferredFeatureId){
  const N = state.refine?.N || 256;
  const feats = mergedFeaturesForTile(tileId).filter(f=>["road","city","field","cloister"].includes(f.type));
  if(!feats.length) return false;

  const items = feats.map(feat=>{
    const area = getAreaFeature(tileId, feat.id);
    const polys = Array.isArray(area?.polygons) ? area.polygons : [];
    const mask = new Uint8Array(N * N);
    rasterizePolygonsToMask(polys, mask, N);
    return { feat, mask };
  });

  items.sort((a,b)=>{
    if(a.feat.id===preferredFeatureId) return -1;
    if(b.feat.id===preferredFeatureId) return 1;
    const pa = REFINE_TYPE_PRIORITY[a.feat.type] ?? 99;
    const pb = REFINE_TYPE_PRIORITY[b.feat.type] ?? 99;
    if(pa !== pb) return pa - pb;
    return a.feat.id.localeCompare(b.feat.id);
  });

  const occupied = new Uint8Array(N * N);
  const changed = new Set();
  for(const item of items){
    const m = item.mask;
    let touched = false;
    for(let i=0;i<m.length;i++){
      if(m[i] && occupied[i]){
        m[i] = 0;
        touched = true;
      }
    }
    if(touched) changed.add(item.feat.id);
    for(let i=0;i<m.length;i++){
      if(m[i]) occupied[i] = 1;
    }
  }

  if(changed.size===0) return false;

  for(const item of items){
    if(!changed.has(item.feat.id)) continue;
    const polys = maskToPolygons(item.mask, N);
    const ent = ensureOverrideFromMergedFeature(tileId, item.feat);
    ent.deleted = false;
    ent.type = item.feat.type;
    ent.polygons = polys;
    ent.ports = normalizePortsForType(ent.ports || item.feat.ports || [], ent.type);
    ent.meeple_placement = featureCentroid(polys);
  }
  persistOverridesToLocalStorage();
  return true;
}

function applyRefineAutoAdjustments(tileId, featureId){
  if(!tileId || !featureId) return;
  const portsChanged = enforceRefinePortExclusivity(tileId, featureId);
  const polysChanged = enforceRefinePolygonExclusivity(tileId, featureId);
  if(portsChanged || polysChanged){
    setStatus(`Auto-adjusted ${tileId}:${featureId} to keep ports/polygons exclusive.`);
  }
}

function refinePrev(){
  const cur = state.refine.list[state.refine.idx];
  if(!saveCurrentRefineWork()) return;
  applyRefineAutoAdjustments(cur?.tileId, cur?.featureId);
  state.refine.list = buildRefineList();
  const curIdx = cur ? state.refine.list.findIndex(it=>it.tileId===cur.tileId && it.featureId===cur.featureId) : state.refine.idx;
  const from = curIdx>=0 ? curIdx : state.refine.idx;
  state.refine.idx = Math.max(0, from - 1);
  refineResetToCurrent();
  renderRefine();
    renderRefineAll();
}

function refineNext(){
  const cur = state.refine.list[state.refine.idx];
  if(!saveCurrentRefineWork()) return;
  applyRefineAutoAdjustments(cur?.tileId, cur?.featureId);
  state.refine.list = buildRefineList();
  const curIdx = cur ? state.refine.list.findIndex(it=>it.tileId===cur.tileId && it.featureId===cur.featureId) : state.refine.idx;
  const from = curIdx>=0 ? curIdx : state.refine.idx;
  state.refine.idx = Math.min(state.refine.list.length - 1, from + 1);
  refineResetToCurrent();
  renderRefine();
    renderRefineAll();
  render();
}

function saveCurrentRefineWork(){
  const item = state.refine.list[state.refine.idx];
  if(!item || !state.refine.mask) return true;
  const curHash = maskChecksum(state.refine.mask);
  if(curHash === state.refine.maskHashLoaded) return true;
  try{
    refineSaveCurrentMaskAsOverride();
    return true;
  }catch(err){
    console.error(err);
    setStatus("Save failed in Refine: " + (err?.message || err));
    return false;
  }
}

function refineClear(){
  state.refine.mask.fill(0);
  }

function maskChecksum(mask){
  if(!mask || !mask.length) return 0;
  // FNV-1a over mask bytes; stable enough for change detection.
  let h = 2166136261 >>> 0;
  for(let i=0;i<mask.length;i++){
    h ^= mask[i];
    h = Math.imul(h, 16777619) >>> 0;
  }
  return h >>> 0;
}

function refineResetToCurrent(){
  const item = state.refine.list[state.refine.idx];
  if(!item) return;
  state.refine.tileId = item.tileId;
  state.refine.featureId = item.featureId;
  state.refine.featureType = item.featureType;

  // sync selectors
  const tileSel = $("#rpTileSelect");
  const featSel = $("#rpFeatureSelect");
  if(tileSel && tileSel.value !== item.tileId) tileSel.value = item.tileId;
  if(featSel){
    // rebuild features list for this tile (in case it changed)
    const feats = mergedFeaturesForTile(item.tileId).filter(f=>["road","city","field","cloister"].includes(f.type));
    featSel.innerHTML = feats.map(f=>`<option value="${f.id}">${f.id} (${f.type})</option>`).join("");
    featSel.value = item.featureId;
    if(!featSel.value && feats.length) featSel.value = feats[0].id;
  }
  renderPortsButtons();

  // rasterize current polygons (override->base) into mask
  const feat = getAreaFeature(item.tileId, item.featureId);
  const polys = feat?.polygons || [];
  rasterizePolygonsToMask(polys, state.refine.mask, state.refine.N);
  state.refine.maskHashLoaded = maskChecksum(state.refine.mask);

  // load base image
  state.refine.baseImg.src = tileImageUrl(item.tileId);
}


function refineSaveCurrentMaskAsOverride(){
  const {tileId, featureId, mask, N} = state.refine;
  const mergedFeat = mergedFeaturesForTile(tileId).find(f=>f.id===featureId);
  const baseFeat = getBaseTileFeature(tileId, featureId) || getBaseFeature(tileId, featureId);
  const featureType = state.refine.featureType || mergedFeat?.type || baseFeat?.type || "field";
  state.refine.featureType = featureType;
  const polys = maskToPolygons(mask, N);
  setOverrideFeature(tileId, featureId, featureType, polys);
  state.refine.maskHashLoaded = maskChecksum(mask);
  setStatus(`Saved override for ${tileId}:${featureId} (${polys.length} polygon(s)).`);
  // refresh list to reflect any added/deleted areas
  state.refine.list = buildRefineList();
  populateRefineSelectors();
  renderRefineAll();
}

function paintAtEvent(e){
  const c = state.refine.canvas;
  if(!c) return;
  const rect = c.getBoundingClientRect();
  const x = (e.clientX - rect.left) / rect.width;
  const y = (e.clientY - rect.top) / rect.height;
  const N = state.refine.N;
  const px = Math.floor(x * N);
  const py = Math.floor(y * N);
  applyBrush(state.refine.mask, N, px, py, state.refine.brushR, state.refine.mode==="add");
  renderRefine();
    renderRefineAll();
}

function applyBrush(mask, N, cx, cy, r, add){
  const r2 = r*r;
  const x0 = Math.max(0, cx-r), x1 = Math.min(N-1, cx+r);
  const y0 = Math.max(0, cy-r), y1 = Math.min(N-1, cy+r);
  for(let y=y0; y<=y1; y++){
    const dy = y - cy;
    for(let x=x0; x<=x1; x++){
      const dx = x - cx;
      if(dx*dx + dy*dy <= r2){
        mask[y*N + x] = add ? 1 : 0;
      }
    }
  }
}

function rasterizePolygonsToMask(polys, outMask, N){
  outMask.fill(0);
  if(!polys || polys.length===0) return;
  const off = document.createElement("canvas");
  off.width = N; off.height = N;
  const ctx = off.getContext("2d");
  ctx.clearRect(0,0,N,N);
  ctx.fillStyle = "#fff";
  ctx.beginPath();
  let any = false;
  for(const poly of polys){
    if(!poly || poly.length<3) continue;
    any = true;
    ctx.moveTo(poly[0][0]*(N-1), poly[0][1]*(N-1));
    for(let i=1;i<poly.length;i++){
      ctx.lineTo(poly[i][0]*(N-1), poly[i][1]*(N-1));
    }
    ctx.closePath();
  }
  if(any) ctx.fill("evenodd");
  const img = ctx.getImageData(0,0,N,N).data;
  for(let i=0;i<N*N;i++){
    outMask[i] = img[i*4+3] > 0 ? 1 : 0;
  }
}

function fillHolesInMask(mask, N){
  const visited = new Uint8Array(N*N);
  const q = new Int32Array(N*N*2);
  let qs=0, qe=0;

  function push(x,y){
    const idx=y*N+x;
    if(visited[idx]) return;
    if(mask[idx]===1) return;
    visited[idx]=1;
    q[qe*2]=x; q[qe*2+1]=y; qe++;
  }

  for(let x=0;x<N;x++){ push(x,0); push(x,N-1); }
  for(let y=0;y<N;y++){ push(0,y); push(N-1,y); }

  while(qs<qe){
    const x=q[qs*2], y=q[qs*2+1]; qs++;
    if(x>0) push(x-1,y);
    if(x<N-1) push(x+1,y);
    if(y>0) push(x,y-1);
    if(y<N-1) push(x,y+1);
  }

  for(let i=0;i<N*N;i++){
    if(mask[i]===0 && !visited[i]) mask[i]=1;
  }
}

function maskToPolygons(mask, N){
  // marching squares -> loops -> simplified polygons
  // Use a 1px zero-padding border so contours touching tile edges close correctly.
  const M = N + 2;
  const segs = [];
  function inside(x,y){
    if(x<=0 || y<=0 || x>=M-1 || y>=M-1) return false;
    return mask[(y-1)*N + (x-1)]===1;
  }
  function addSeg(ax,ay,bx,by){ segs.push([ax,ay,bx,by]); }

  for(let y=0; y<M-1; y++){
    for(let x=0; x<M-1; x++){
      const a = inside(x,y)?1:0, b=inside(x+1,y)?1:0, c=inside(x+1,y+1)?1:0, d=inside(x,y+1)?1:0;
      const code = a | (b<<1) | (c<<2) | (d<<3);
      if(code===0||code===15) continue;
      const top=[x+0.5,y], right=[x+1,y+0.5], bottom=[x+0.5,y+1], left=[x,y+0.5];
      switch(code){
        case 1:  addSeg(left[0],left[1], top[0],top[1]); break;
        case 2:  addSeg(top[0],top[1], right[0],right[1]); break;
        case 3:  addSeg(left[0],left[1], right[0],right[1]); break;
        case 4:  addSeg(right[0],right[1], bottom[0],bottom[1]); break;
        case 5:  addSeg(left[0],left[1], top[0],top[1]); addSeg(right[0],right[1], bottom[0],bottom[1]); break;
        case 6:  addSeg(top[0],top[1], bottom[0],bottom[1]); break;
        case 7:  addSeg(left[0],left[1], bottom[0],bottom[1]); break;
        case 8:  addSeg(bottom[0],bottom[1], left[0],left[1]); break;
        case 9:  addSeg(top[0],top[1], bottom[0],bottom[1]); break;
        case 10: addSeg(top[0],top[1], right[0],right[1]); addSeg(bottom[0],bottom[1], left[0],left[1]); break;
        case 11: addSeg(right[0],right[1], bottom[0],bottom[1]); break;
        case 12: addSeg(right[0],right[1], left[0],left[1]); break;
        case 13: addSeg(top[0],top[1], right[0],right[1]); break;
        case 14: addSeg(left[0],left[1], top[0],top[1]); break;
      }
    }
  }

  // adjacency map
  const adj = new Map();
  const pts = new Map();
  const edgeUsed = new Set();
  function key(x,y){ return `${x.toFixed(2)},${y.toFixed(2)}`; }
  function ekey(a,b){ return a<b ? `${a}|${b}` : `${b}|${a}`; }

  function addEdge(ax,ay,bx,by){
    const ka=key(ax,ay), kb=key(bx,by);
    pts.set(ka, {x:ax,y:ay}); pts.set(kb,{x:bx,y:by});
    if(!adj.has(ka)) adj.set(ka, []);
    if(!adj.has(kb)) adj.set(kb, []);
    adj.get(ka).push(kb);
    adj.get(kb).push(ka);
  }
  for(const s of segs) addEdge(s[0],s[1],s[2],s[3]);

  const loops = [];
  for(const [k, nbs] of adj.entries()){
    for(const nb of nbs){
      const ek = ekey(k,nb);
      if(edgeUsed.has(ek)) continue;
      let curr=k, prev=null;
      const path=[];
      while(true){
        path.push(curr);
        const ns=adj.get(curr)||[];
        let next=null;
        if(prev===null) next=ns[0];
        else next = (ns.length===1) ? ns[0] : (ns[0]===prev ? ns[1] : ns[0]);
        if(!next) break;
        edgeUsed.add(ekey(curr,next));
        prev=curr; curr=next;
        if(curr===k) break;
        if(path.length>50000) break;
      }
      if(curr===k && path.length>=6){
        loops.push(path.map(pk=>pts.get(pk)));
      }
    }
  }

  function polyAreaPts(poly){
    let s=0;
    for(let i=0;i<poly.length;i++){
      const p=poly[i], q=poly[(i+1)%poly.length];
      s += p.x*q.y - p.y*q.x;
    }
    return Math.abs(s)*0.5;
  }

  // Iterative RDP to avoid call stack overflows
  function rdpIter(points, eps){
    const n = points.length;
    if(n<=3) return points;
    const keep = new Uint8Array(n);
    keep[0]=1; keep[n-1]=1;
    const stack = [[0, n-1]];
    while(stack.length){
      const [i0, i1] = stack.pop();
      const a = points[i0], b = points[i1];
      const vx = b.x - a.x, vy = b.y - a.y;
      const den = vx*vx + vy*vy || 1e-9;
      let idx=-1, dmax=-1;
      for(let i=i0+1;i<i1;i++){
        const p = points[i];
        const t = ((p.x-a.x)*vx + (p.y-a.y)*vy)/den;
        const tt = Math.max(0, Math.min(1, t));
        const px = a.x + tt*vx, py = a.y + tt*vy;
        const d = Math.hypot(p.x-px, p.y-py);
        if(d>dmax){ dmax=d; idx=i; }
      }
      if(dmax>eps && idx>0){
        keep[idx]=1;
        stack.push([i0, idx], [idx, i1]);
      }
    }
    const out=[];
    for(let i=0;i<n;i++) if(keep[i]) out.push(points[i]);
    return out;
  }

  function decimate(points, target=1200){
    if(points.length<=target) return points;
    const step = Math.ceil(points.length/target);
    const out=[];
    for(let i=0;i<points.length;i+=step) out.push(points[i]);
    // ensure last
    if(out[out.length-1] !== points[points.length-1]) out.push(points[points.length-1]);
    return out;
  }

  const polys = loops.map(loop=>{
    // close
    const closed = loop.concat([loop[0]]);
    const reduced = decimate(closed, 1500);
    const simp = rdpIter(reduced, 1.0);
    // remove closure for polygon array
    const ring = simp.slice(0, -1);
    if(ring.length<3) return null;
    return ring.map(p=>[
      Math.max(0, Math.min(1, (p.x - 1) / (N-1))),
      Math.max(0, Math.min(1, (p.y - 1) / (N-1)))
    ]);
  }).filter(Boolean);

  // keep more loops so holes are preserved; sort by area desc
  polys.sort((p,q)=>{
    const ap = polyAreaPts(p.map(([x,y])=>({x:x*(N-1), y:y*(N-1)})));
    const aq = polyAreaPts(q.map(([x,y])=>({x:x*(N-1), y:y*(N-1)})));
    return aq - ap;
  });

  return polys.slice(0, 12);
}


function renderRefine(){
  if(!isRefineTab()) return;
  const {idx, list, tileId, featureId, featureType, canvas, ctx, baseImg, mask, N} = state.refine;
  const prog = $("#rpProgress");
  if(prog) prog.textContent = `${idx+1}/${list.length} — Tile ${tileId} — ${featureId} (${featureType})`;
  updateBrushLabel();
  if(!canvas || !ctx) return;

  // base image
  ctx.clearRect(0,0,canvas.width, canvas.height);
  if(baseImg && baseImg.complete){
    ctx.drawImage(baseImg, 0,0, canvas.width, canvas.height);
  }else{
    ctx.fillStyle="rgba(255,255,255,0.06)";
    ctx.fillRect(0,0,canvas.width, canvas.height);
  }

  // ports of selected area (red)
  const cur = state.refine.list[state.refine.idx];
  if(cur){
    const feat = mergedFeaturesForTile(cur.tileId).find(f=>f.id===cur.featureId);
    if(feat) drawPortsOnCanvas(ctx, feat.ports||[], canvas.width, canvas.height);
  }

// overlay mask with hatch
  const img = ctx.getImageData(0,0,canvas.width, canvas.height);
  const data = img.data;
  const w = canvas.width, h = canvas.height;
  const isP1 = (state.selectedPlayer===1);
  const col = isP1 ? {r:80,g:160,b:255} : {r:255,g:90,b:90};
  const alphaFill = 0.30;
  const alphaHatch = 0.45;

  for(let y=0;y<h;y++){
    const my = Math.floor(y / h * N);
    for(let x=0;x<w;x++){
      const mx = Math.floor(x / w * N);
      if(!mask[my*N + mx]) continue;
      const i = (y*w + x)*4;

      data[i]   = Math.round(data[i]   * (1-alphaFill) + col.r * alphaFill);
      data[i+1] = Math.round(data[i+1] * (1-alphaFill) + col.g * alphaFill);
      data[i+2] = Math.round(data[i+2] * (1-alphaFill) + col.b * alphaFill);

      if(((x + y) % 10) < 2){
        data[i]   = Math.round(data[i]   * (1-alphaHatch) + col.r * alphaHatch);
        data[i+1] = Math.round(data[i+1] * (1-alphaHatch) + col.g * alphaHatch);
        data[i+2] = Math.round(data[i+2] * (1-alphaHatch) + col.b * alphaHatch);
      }
    }
  }
  ctx.putImageData(img, 0, 0);

  // Boundary from mask (visible) — pixel outline (no recursion)
  try{
    const N = state.refine.N;
    const mask = state.refine.mask;
    const w = canvas.width, h = canvas.height;
    const sx = w / N, sy = h / N;

    ctx.save();
    ctx.globalAlpha = 1.0;
    ctx.fillStyle = isP1 ? "rgba(80,160,255,0.95)" : "rgba(255,90,90,0.95)";
    // draw small rects where boundary exists
    for(let y=0; y<N; y++){
      const yoff = y*N;
      for(let x=0; x<N; x++){
        if(!mask[yoff + x]) continue;
        const up    = (y===0)     ? 0 : mask[(y-1)*N + x];
        const down  = (y===N-1)   ? 0 : mask[(y+1)*N + x];
        const left  = (x===0)     ? 0 : mask[yoff + (x-1)];
        const right = (x===N-1)   ? 0 : mask[yoff + (x+1)];
        if(!up || !down || !left || !right){
          ctx.fillRect(x*sx, y*sy, sx, sy);
        }
      }
    }
    ctx.restore();
  }catch(e){}

  // Brush shadow
  // Brush shadow
  if(state.refine?.lastMouse?.has){
    const bx = state.refine.lastMouse.x * canvas.width;
    const by = state.refine.lastMouse.y * canvas.height;
    const br = (state.refine.brushR / state.refine.N) * canvas.width;
    ctx.save();
    ctx.beginPath();
    ctx.arc(bx, by, br, 0, Math.PI*2);
    ctx.fillStyle = 'rgba(0,0,0,0.15)';
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = 'rgba(255,255,255,0.55)';
    ctx.stroke();
    ctx.restore();
  }
}



function populateRefineSelectors(){
  const tileSel = $("#rpTileSelect");
  const featSel = $("#rpFeatureSelect");
  if(!tileSel || !featSel) return;

  const tileIds = Array.from(state.tileById.keys()).sort();
  if(tileIds.length===0){
    const prog = $("#rpProgress");
    if(prog) prog.textContent = "Tileset is still loading…";
    tileSel.innerHTML = "";
    featSel.innerHTML = "";
    return;
  }
  tileSel.innerHTML = tileIds.map(t=>`<option value="${t}">${t}</option>`).join("");

  const cur = state.refine.list[state.refine.idx] || state.refine.list[0];
  if(cur) tileSel.value = cur.tileId;

  const feats = mergedFeaturesForTile(tileSel.value).filter(f=>["road","city","field","cloister"].includes(f.type));
  featSel.innerHTML = feats.map(f=>`<option value="${f.id}">${f.id} (${f.type})</option>`).join("");
  if(cur){
    featSel.value = cur.featureId;
    if(!featSel.value && feats.length) featSel.value = feats[0].id;
  }

  renderPortsButtons();
}

function renderPortsButtons(){
  const box = $("#rpPorts");
  if(!box) return;
  box.innerHTML = "";

  const cur = state.refine.list[state.refine.idx];
  if(!cur) return;
  const tileId = cur.tileId;
  const fid = cur.featureId;

  const feat = mergedFeaturesForTile(tileId).find(f=>f.id===fid);
  const featType = feat?.type || cur.featureType || "field";
  const allowed = new Set(allowedPortsForType(featType));
  const ports = new Set(normalizePortsForType(feat?.ports || [], featType));

  for(const row of PORT_GRID_5X5){
    for(const p of row){
      const slot = document.createElement("div");
      slot.className = "rpPortSlot";

      if(!p || !allowed.has(p)){
        box.appendChild(slot);
        continue;
      }

      const b = document.createElement("button");
      b.type = "button";
      b.className = "rpPortBtn" + (ports.has(p) ? " on" : "");
      b.textContent = p;
      b.addEventListener("click", ()=>{
        togglePort(tileId, fid, p);
        renderPortsButtons();
        renderRefine();
        renderRefineAll();
        renderRefineAll();
        render();
      });

      slot.appendChild(b);
      box.appendChild(slot);
    }
  }
}

function togglePort(tileId, fid, port){
  // Toggle against the currently effective feature ports so clicks do not reset previous selections.
  const curFeat = mergedFeaturesForTile(tileId).find(f=>f.id===fid);
  const featType = curFeat?.type || "field";
  const allowed = new Set(allowedPortsForType(featType));
  if(!allowed.has(port)) return;

  const area = getAreaFeature(tileId, fid);
  const fallback = {
    type: featType,
    polygons: area?.polygons || [],
    ports: normalizePortsForType(curFeat?.ports || [], featType),
    tags: curFeat?.tags || {},
    meeple_placement: curFeat?.meeple_placement || [0.5,0.5]
  };

  const ent = ensureOverrideEntry(tileId, fid, fallback);
  ent.deleted = false;
  ent.type = featType;

  const nextPorts = new Set(normalizePortsForType(curFeat?.ports || ent.ports || [], featType));
  if(nextPorts.has(port)) nextPorts.delete(port);
  else nextPorts.add(port);
  ent.ports = normalizePortsForType(Array.from(nextPorts), featType);
  persistOverridesToLocalStorage();
}

function nextFeatureId(tileId, type){
  const base = state.tileById.get(tileId);
  const used = new Set(base.features.map(f=>f.id));
  const ov = state.areasOverride?.tiles?.[tileId]?.features || {};
  for(const k of Object.keys(ov)) used.add(k);
  let i=1;
  while(true){
    const cand = `${type}_custom_${i}`;
    if(!used.has(cand)) return cand;
    i++;
  }
}

function refineAddFeature(){
  const tileId = $("#rpTileSelect")?.value || (state.refine.list[state.refine.idx]?.tileId);
  if(!tileId) return;
  const type = $("#rpNewType")?.value || "field";
  const fid = nextFeatureId(tileId, type);

  const ent = ensureOverrideEntry(tileId, fid, { type, polygons: [], ports: [], tags: {}, meeple_placement:[0.5,0.5] });
  ent.type = type;
  ent.polygons = ent.polygons || [];
  ent.ports = normalizePortsForType(ent.ports || [], ent.type);
  ent.tags = ent.tags || {};
  ent.deleted = false;

  // Put a tiny default polygon so it is visible; user will paint anyway
  if(!ent.polygons.length){
    ent.polygons = [[[0.45,0.45],[0.55,0.45],[0.55,0.55],[0.45,0.55]]];
    ent.meeple_placement = featureCentroid(ent.polygons);
  }
  persistOverridesToLocalStorage();

  // rebuild list and jump to new feature
  state.refine.list = buildRefineList();
  const idx = state.refine.list.findIndex(it=>it.tileId===tileId && it.featureId===fid);
  if(idx>=0) state.refine.idx = idx;
  populateRefineSelectors();
  refineResetToCurrent();
  renderRefine();
    renderRefineAll();
  renderRefineAll();
  render();
}

function refineDeleteFeature(){
  const cur = state.refine.list[state.refine.idx];
  if(!cur) return;
  const tileId = cur.tileId;
  const fid = cur.featureId;

  deleteOverrideFeature(tileId, fid);

  // remove meeples on this local feature for all instances of this tile on board
  for(const [k, inst] of state.board.entries()){
    if(inst.tileId !== tileId) continue;
    if(!inst.meeples) continue;
    inst.meeples = inst.meeples.filter(m=>m.featureLocalId !== fid);
  }

  state.refine.list = buildRefineList();
  state.refine.list = buildRefineList();
  state.refine.idx = Math.min(state.refine.idx, Math.max(0, state.refine.list.length-1));
  populateRefineSelectors();
  refineResetToCurrent();
  renderRefine();
    renderRefineAll();
  renderRefineAll();
  render();
}

function portRects(){
  // normalized rectangles for port segments (thirds)
  const t = 0.06; // thickness
  const a = 1/3;
  const b = 2/3;
  return {
    Nw:[0,0,a,t], N:[a,0,b,t], Ne:[b,0,1,t],
    Sw:[0,1-t,a,1], S:[a,1-t,b,1], Se:[b,1-t,1,1],
    Wn:[0,0,t,a], W:[0,a,t,b], Ws:[0,b,t,1],
    En:[1-t,0,1,a], E:[1-t,a,1,b], Es:[1-t,b,1,1],
  };
}

function drawPortsOnCanvas(ctx, ports, w, h){
  const rects = portRects();
  ctx.save();
  ctx.fillStyle = "rgba(255, 60, 60, 0.85)";
  for(const p of ports){
    const r = rects[p];
    if(!r) continue;
    ctx.fillRect(r[0]*w, r[1]*h, (r[2]-r[0])*w, (r[3]-r[1])*h);
  }
  ctx.restore();
}

function renderRefineAll(){
  if(!isRefineTab()) return;
  const cur = state.refine.list[state.refine.idx];
  if(!cur) return;
  const tileId = cur.tileId;

  const c = state.refine.canvasAll;
  const ctx = state.refine.ctxAll;
  if(!c || !ctx) return;

  ctx.clearRect(0,0,c.width,c.height);

  // base image
  if(state.refine.baseImg && state.refine.baseImg.complete){
    ctx.drawImage(state.refine.baseImg, 0,0,c.width,c.height);
  }else{
    ctx.fillStyle="rgba(255,255,255,0.06)";
    ctx.fillRect(0,0,c.width,c.height);
  }

  const feats = mergedFeaturesForTile(tileId).filter(f=>["road","city","field","cloister"].includes(f.type));
  for(let i=0;i<feats.length;i++){
    const f = feats[i];
    const area = getAreaFeature(tileId, f.id);
    const rings = area?.polygons || [];
    if(!rings.length) continue;

    // color per feature
    const hue = (i * 47) % 360;
    ctx.save();
    ctx.globalAlpha = 0.22;
    ctx.fillStyle = `hsl(${hue} 90% 60%)`;
    ctx.strokeStyle = `hsl(${hue} 95% 55%)`;
    ctx.lineWidth = 3;

    // even-odd: draw all rings in one path
    ctx.beginPath();
    for(const ring of rings){
      if(!ring || ring.length<3) continue;
      ctx.moveTo(ring[0][0]*c.width, ring[0][1]*c.height);
      for(let k=1;k<ring.length;k++){
        ctx.lineTo(ring[k][0]*c.width, ring[k][1]*c.height);
      }
      ctx.closePath();
    }
    ctx.fill("evenodd");
    ctx.globalAlpha = 0.85;
    ctx.stroke();

    // ports in red for this area
    drawPortsOnCanvas(ctx, f.ports||[], c.width, c.height);

    ctx.restore();
  }

  // Highlight currently selected feature boundary stronger
  const sel = mergedFeaturesForTile(tileId).find(f=>f.id===cur.featureId);
  const areaSel = getAreaFeature(tileId, cur.featureId);
  const ringsSel = areaSel?.polygons || [];
  if(ringsSel.length){
    ctx.save();
    ctx.globalAlpha = 1.0;
    ctx.strokeStyle = "rgba(255,255,255,0.95)";
    ctx.lineWidth = 4;
    ctx.beginPath();
    for(const ring of ringsSel){
      if(!ring || ring.length<3) continue;
      ctx.moveTo(ring[0][0]*c.width, ring[0][1]*c.height);
      for(let k=1;k<ring.length;k++) ctx.lineTo(ring[k][0]*c.width, ring[k][1]*c.height);
      ctx.closePath();
    }
    ctx.stroke();
    ctx.restore();
  }
}
// ------------------ end Refine polygons --------------------
async function main(){
  setStatus(`Loading tileset… (app ${APP_VERSION})`);
  const res = await fetch("carcassonne_base_A-X.json");
  const tileset = await res.json();
  state.tileset = tileset;
  state.counts = tileset.tile_counts;
  state.remaining = deepCopy(state.counts);

  for(const t of tileset.tiles) state.tileById.set(t.id, t);
  await Promise.allSettled(Array.from(state.tileById.keys()).map((tileId)=>preloadTileImage(tileId)));

  // Default highlight areas: procedurally generated from edge ports (coarse, always connected)
  try{
    state.areasBase = generateProceduralAreas();
  }catch(err){
    console.warn("Procedural areas failed:", err);
    state.areasBase = null;
  }

  // Load overrides (write-through file/API first; local cache + legacy fallback)
  state.areasOverride = await loadOverridesFromServer();
  if(!state.areasOverride){
    state.areasOverride = loadOverridesFromLocalStorage();
  }
  if(!state.areasOverride){
    try{
      const ores = await fetch("carcassonne_areas_overrides.json", {cache:"no-store"});
      if(ores.ok) state.areasOverride = normalizeOverridesPayload(await ores.json());
    }catch(err){
      state.areasOverride = null;
    }
  }

  const repaired = repairLegacyOverridePorts();
  if(repaired>0){
    console.info(`Repaired ${repaired} legacy override port entr${repaired===1?"y":"ies"}.`);
    persistOverridesToLocalStorage();
  }
  clearFieldCityAdjCache();
  clearSimplifiedFeatureGeomCache();

  initUI();
  renderTileSelect();
  renderTilePalette();
  buildBoardGrid(25);
  updateTilePreview();
  setActiveTab("online");
  render();
  setStatus("Ready. Connect in Settings, invite a player, then play directly on the board.");
}

main().catch(err=>{
  console.error(err);
  setStatus("Failed to load: " + err.message);
});
