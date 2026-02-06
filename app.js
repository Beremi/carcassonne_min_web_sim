
/*
  Minimal Carcassonne simulator (base A–X) — UI + polygon highlight areas.

  Fix:
  - Meeple placement points are NOT rotated in data, because the tile element is rotated in CSS.

  Added:
  - Loads carcassonne_base_A-X_areas.json (approx polygons per feature) and uses them for hover highlighting.
*/

const APP_VERSION = "3.7.3";
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
const PORTS_BY_FEATURE_TYPE = {
  field: ["Nw","Ne","En","Es","Se","Sw","Ws","Wn"],
  road: ["N","E","S","W"],
  city: ["N","E","S","W"],
  cloister: []
};
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

  scoredKeys: new Set(),
  score: {1:0, 2:0},
  undoStack: [],

  selectedCell: null,
  hoverCell: null,
  boardHot: false,

  hoverFeature: null, // { type, tiles:Set(cellKey), markers:[{cellKey,type,pt}], featureIdsByCell: Map(cellKey -> Set(localId)) }
  activeTab: "play",
  refine: { list: [], idx: 0, N: 256, brushR: 10, down: false, mode: "add", mask: null, tileId:null, featureId:null, featureType:null, canvas:null, ctx:null, baseImg:null, maskHashLoaded: 0, lastMouse: {x:0.5,y:0.5, has:false} }
};

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
  state.hoverFeature = null;
}
function pushUndo(){ state.undoStack.push(snapshot()); if(state.undoStack.length>120) state.undoStack.shift(); }

function setStatus(msg){ $("#status").textContent = msg; }
function tileImageUrl(tileId){ return `images/tile_${tileId}.png`; }

function makeTileImg(tileId){
  const img = document.createElement("img");
  img.className = "tileImg";
  img.src = tileImageUrl(tileId);
  img.alt = `Tile ${tileId}`;
  img.draggable = false;
  return img;
}



function getAreaFeature(tileId, featureId){
  const o = state.areasOverride?.tiles?.[tileId]?.features?.[featureId];
  if(o) return o;
  const b = state.areasBase?.tiles?.[tileId]?.features?.[featureId];
  return b || null;
}


function setActiveTab(tab){
  state.activeTab = tab;
  const tp = $("#tabPlay");
  const tr = $("#tabRefine");
  const pp = $("#playPane");
  const rp = $("#refinePane");

  if(tp) tp.classList.toggle("active", tab==="play");
  if(tr) tr.classList.toggle("active", tab==="refine");
  if(pp) pp.classList.toggle("hidden", tab!=="play");
  if(rp) rp.classList.toggle("hidden", tab!=="refine");

  if(tab==="refine"){
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
  }else{
    state.hoverFeature = null;
    render();
  }
}
function isRefineTab(){ return state.activeTab === "refine"; }

let overridesSaveTimer = null;
let overridesSaveChain = Promise.resolve();
let overridesSaveWarned = false;

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
  }catch(e){
    console.warn("Failed to store overrides:", e);
  }
  scheduleOverridesSaveToServer();
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

      out.tiles[tileId].features[f.id] = { type: f.type, polygons: rings };
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
  el.style.backgroundImage = `url(${tileImageUrl(state.selectedTileId)})`;
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
  updateRotLabel();
  render();
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
  for(const f of merged){
    const rf = deepCopy(f);
    rf.ports = (rf.ports||[]).map(p=>rotPort(p, rotDeg));
    // rf.meeple_placement left unchanged
    out.features.push(rf);
  }
  return out;
}

function canPlaceAt(tileId, rotDeg, x, y){
  const k = keyXY(x,y);
  if(state.board.has(k)) return {ok:false, reason:"Cell occupied."};

  const hasAny = state.board.size>0;
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
    if(!state.board.has(nk)) continue;
    touches = true;
    const nInst = state.board.get(nk);
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

function initUI(){
  $("#rotL").addEventListener("click", ()=>rotateSelected(-90));
  $("#rotR").addEventListener("click", ()=>rotateSelected(90));

  $("#useCounts").addEventListener("change", (e)=>{
    state.useCounts = e.target.checked;
    renderTileSelect();
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
    state.hoverFeature = null;
    renderTileSelect();
    renderTilePalette();
    render();
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
    state.hoverFeature = null;
    renderTileSelect();
    renderTilePalette();
    render();
    e.target.value = "";
  });

  // Board: wheel rotates, arrows scroll
  const wrap = $("#boardWrap");
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

  // Tabs
  const tp = $("#tabPlay");
  const tr = $("#tabRefine");
  if(tp) tp.addEventListener("click", ()=>setActiveTab("play"));
  if(tr) tr.addEventListener("click", ()=>setActiveTab("refine"));

  updateRotLabel();
  updateTilePreview();
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
    img.style.backgroundImage = `url(${tileImageUrl(id)})`;
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
    }
  });

  board.addEventListener("mouseleave", ()=>{
    state.hoverCell = null;
    render();
  });

  board.addEventListener("click", (e)=>{
    if(e.target.classList.contains("cell")) clearSelection();
  });
}

function clearSelection(){
  state.selectedCell = null;
  state.hoverFeature = null;
  render();
}

function onCellClick(e, x, y){
  const k = keyXY(x,y);

  if(state.board.has(k)){
    state.selectedCell = k;
    state.hoverFeature = null;
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
  state.hoverFeature = null;

  renderTileSelect();
  renderTilePalette();
  render();
  setStatus(`Removed tile at (${x},${y}). Scores recomputed.`);
}

function playerTint(alpha){
  // Color the highlight by currently selected player (placement intent)
  return (state.selectedPlayer===1)
    ? `rgba(80,160,255,${alpha})`   // P1 blue
    : `rgba(255,90,90,${alpha})`;   // P2 red
}

function featureColor(type){
  // Keep signature, but color by player per user request.
  return playerTint(0.28);
}



function addFeatureAreaOverlay(tileDiv, tileId, localIds, type){
  if(!state.areasBase && !state.areasOverride) return;

  const svg = document.createElementNS("http://www.w3.org/2000/svg","svg");
  svg.setAttribute("viewBox","0 0 100 100");
  svg.setAttribute("class","areaSvg");
  svg.style.position="absolute";
  svg.style.inset="0";
  svg.style.pointerEvents="none";

  const fillBase = playerTint(0.16);
  const lineCol  = playerTint(0.55);
  const stroke   = playerTint(0.70);

  const defs = document.createElementNS("http://www.w3.org/2000/svg","defs");

  const pid = `hatch_${tileId}_${state.selectedPlayer}`;
  const pat = document.createElementNS("http://www.w3.org/2000/svg","pattern");
  pat.setAttribute("id", pid);
  pat.setAttribute("patternUnits","userSpaceOnUse");
  pat.setAttribute("width","6");
  pat.setAttribute("height","6");
  pat.setAttribute("patternTransform","rotate(45)");

  const line = document.createElementNS("http://www.w3.org/2000/svg","line");
  line.setAttribute("x1","0"); line.setAttribute("y1","0");
  line.setAttribute("x2","0"); line.setAttribute("y2","6");
  line.setAttribute("stroke", lineCol);
  line.setAttribute("stroke-width","2");
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
      pg0.setAttribute("stroke-width", "0.9");
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


function render(){
  const boardEl = $("#board");
  const size = Math.sqrt(boardEl.children.length) | 0;
  const half = Math.floor(size/2);

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
      mee.style.left = `${px*100}%`;
      mee.style.top = `${py*100}%`;
      tileDiv.appendChild(mee);
    }

    // Selection markers
    if(state.selectedCell === k){
      tileDiv.classList.add("selected");
      renderFeatureMarkers(tileDiv, k, inst);
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
  if(state.hoverCell){
    const {x,y} = parseXY(state.hoverCell);
    if(!state.board.has(state.hoverCell)){
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

  renderScores();
}

function renderFeatureMarkers(tileDiv, cellKey, inst){
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

    mk.addEventListener("mouseenter", ()=>{
      state.hoverFeature = computeHoverFeature(cellKey, f.id);
      render();
    });
    mk.addEventListener("mouseleave", ()=>{
      state.hoverFeature = null;
      render();
    });

    mk.addEventListener("click", (e)=>{
      e.stopPropagation();
      placeOrRemoveMeeple(cellKey, inst, f.id);
    });

    tileDiv.appendChild(mk);
  }
}

function placeOrRemoveMeeple(cellKey, inst, featureLocalId){
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
    if(g.type==="road" || g.type==="city"){
      const open = new Set();
      for(const nodeKey of g.nodes){
        const meta = nodeMeta.get(nodeKey);
        const {cellKey, instId} = meta;
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
      g.key = `${g.type}|${Array.from(g.tiles).sort((a,b)=>a-b).join(",")}`;
    }
    else if(g.type==="cloister"){
      const only = Array.from(g.tiles)[0];
      const cellKey = instById.get(only)?.cellKey;
      g.key = `cloister|${cellKey}`;
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
    else if(g.type==="field"){
      g.key = `field|${Array.from(g.tiles).sort((a,b)=>a-b).join(",")}|${g.nodes.size}`;
      // Approx farm adjacency to completed cities using edge-city corners
      for(const nodeKey of g.nodes){
        const meta = nodeMeta.get(nodeKey);
        const inst = state.board.get(meta.cellKey);
        if(!inst) continue;
        const tile = rotatedTile(inst.tileId, inst.rotDeg);
        const look = perTileLookup.get(meta.instId);

        for(const fp of meta.ports){
          const edge = fp[0];
          if(tile.edges[edge]?.primary==="city"){
            const cityLocal = look.cityEdge[edge];
            if(!cityLocal) continue;
            const cityNodeKey = `${meta.instId}:${cityLocal}`;
            const cityRoot = uf.find(cityNodeKey);
            const cityGroup = groups.get(cityRoot);
            if(cityGroup && cityGroup.type==="city" && cityGroup.complete){
              g.adjCompletedCities.add(cityGroup.key);
            }
          }
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

function renderScores(){
  const analysis = analyzeBoard();

  const endNow = {1:0, 2:0};
  const ifCompleteNow = {1:0, 2:0};

  function winnersOf(g){
    const m1 = g.meeplesByPlayer[1]||0;
    const m2 = g.meeplesByPlayer[2]||0;
    const max = Math.max(m1,m2);
    if(max<=0) return [];
    const ws=[];
    if(m1===max) ws.push(1);
    if(m2===max) ws.push(2);
    return ws;
  }

  for(const g of analysis.groups.values()){
    const ws = winnersOf(g);
    if(ws.length===0) continue;

    if(g.type==="road" || g.type==="city" || g.type==="cloister"){
      if(!g.complete){
        const ptsEnd = (g.type==="city") ? scoreFeature(g,false) :
                       (g.type==="cloister") ? scoreFeature(g,false) :
                       scoreFeature(g,true);
        for(const w of ws) endNow[w] += ptsEnd;

        let ptsComp = 0;
        if(g.type==="city") ptsComp = scoreFeature(g,true);
        else if(g.type==="road") ptsComp = scoreFeature(g,true);
        else if(g.type==="cloister") ptsComp = 9;
        for(const w of ws) ifCompleteNow[w] += ptsComp;
      }
    }else if(g.type==="field"){
      const ptsFarm = scoreFeature(g,false);
      for(const w of ws) endNow[w] += ptsFarm;
    }
  }

  $("#scoreBox").innerHTML = `
    <table class="scoreTable">
      <thead>
        <tr><th></th><th>Score</th><th>End-if-now</th><th>If-complete-now</th></tr>
      </thead>
      <tbody>
        <tr><td class="mono">P1 (Blue)</td><td class="mono">${state.score[1]}</td><td class="mono">+${endNow[1]}</td><td class="mono">+${ifCompleteNow[1]}</td></tr>
        <tr><td class="mono">P2 (Red)</td><td class="mono">${state.score[2]}</td><td class="mono">+${endNow[2]}</td><td class="mono">+${ifCompleteNow[2]}</td></tr>
      </tbody>
    </table>`;
}



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

function getOverrideFeature(tileId, featureId){
  return state.areasOverride?.tiles?.[tileId]?.features?.[featureId] || null;
}

function setOverrideFeature(tileId, featureId, type, polygons){
  const baseFeat = getBaseFeature(tileId, featureId);
  const fallback = baseFeat ? { type: baseFeat.type, polygons: [], ports: baseFeat.ports||[], tags: baseFeat.tags||{}, meeple_placement: baseFeat.meeple_placement||[0.5,0.5] }
                           : { type: type||"field", polygons: [], ports: [], tags: {}, meeple_placement: [0.5,0.5] };
  const entry = ensureOverrideEntry(tileId, featureId, fallback);
  entry.type = type || entry.type || "field";
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
  const baseFeat = getBaseFeature(tileId, featureId);
  const fallback = baseFeat ? { type: baseFeat.type, polygons: [], ports: baseFeat.ports||[], tags: baseFeat.tags||{}, meeple_placement: baseFeat.meeple_placement||[0.5,0.5] }
                           : { type: "field", polygons: [], ports: [], tags: {}, meeple_placement:[0.5,0.5] };
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

function refinePrev(){
  if(!saveCurrentRefineWork()) return;
  state.refine.list = buildRefineList();
  state.refine.idx = Math.max(0, state.refine.idx - 1);
  refineResetToCurrent();
  renderRefine();
    renderRefineAll();
}

function refineNext(){
  if(!saveCurrentRefineWork()) return;
  state.refine.list = buildRefineList();
  state.refine.idx = Math.min(state.refine.list.length - 1, state.refine.idx + 1);
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
  const baseFeat = getBaseFeature(tileId, featureId);
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
  const allowed = allowedPortsForType(featType);
  if(allowed.length===0){
    box.textContent = "No boundary edges for cloister.";
    return;
  }
  const ports = new Set(normalizePortsForType(feat?.ports || [], featType));

  for(const p of PORT_DISPLAY_ORDER){
    if(!allowed.includes(p)) continue;
    const b = document.createElement("button");
    b.type="button";
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
    box.appendChild(b);
  }
}

function togglePort(tileId, fid, port){
  // Write ports into override entry for this feature (even if base feature)
  const base = getBaseFeature(tileId, fid);
  const baseFallback = base ? { type: base.type, polygons: getAreaFeature(tileId,fid)?.polygons || [], ports: base.ports||[], tags: base.tags||{}, meeple_placement: base.meeple_placement||[0.5,0.5] }
                           : { type: "field", polygons: getAreaFeature(tileId,fid)?.polygons || [], ports: [], tags: {}, meeple_placement:[0.5,0.5] };
  const ent = ensureOverrideEntry(tileId, fid, baseFallback);
  if(ent.deleted) ent.deleted_tf = true; // noop marker; keep deleted for deleteFeature
  ent.deleted = false;
  const allowed = new Set(allowedPortsForType(ent.type || baseFallback.type || "field"));
  if(!allowed.has(port)) return;
  if(!Array.isArray(ent.ports)) ent.ports = deepCopy(baseFallback.ports || []);
  ent.ports = normalizePortsForType(ent.ports, ent.type || baseFallback.type || "field");
  const s = new Set(ent.ports);
  if(s.has(port)) s.delete(port);
  else s.add(port);
  ent.ports = normalizePortsForType(Array.from(s), ent.type || baseFallback.type || "field");
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
    inst.meeples = inst.meeples.filter(m=>m.featureId !== fid);
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

  initUI();
  renderTileSelect();
  renderTilePalette();
  buildBoardGrid(25);
  updateTilePreview();
  render();
  setStatus("Ready. Hover board for ghost. Wheel rotates. Arrows scroll. Hover meeple markers to highlight feature polygons.");
}

main().catch(err=>{
  console.error(err);
  setStatus("Failed to load: " + err.message);
});
