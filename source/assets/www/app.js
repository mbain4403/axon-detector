"use strict";
/* Axon Detector UI. Native (Java) pushes scan state via window.__onScan and
   location via window.__onLoc. This file renders; it stores nothing. */

var $ = function (id) { return document.getElementById(id); };
var scanState = { btSupported: true, btOn: true, hasPerm: false, scanning: false, hits: [] };
var userLoc = null;
var mapInited = false;
var locAsked = false;

/* ---------------- detector ---------------- */

function bandClass(rssi) {
  if (rssi >= -60) return "strong";
  if (rssi >= -80) return "hit";
  return "hit";
}

var crashShown = "";
function renderCrash() {
  var cb = $("crashBox");
  var text = scanState.lastCrash || "";
  if (text === crashShown) return;
  crashShown = text;
  if (!text) { cb.classList.add("hidden"); cb.innerHTML = ""; return; }
  cb.innerHTML = "<strong>The app crashed last time it ran.</strong> " +
    "Please send Torque the error below (a screenshot is fine):<pre></pre>" +
    '<button type="button" id="crashDismiss" class="btn">Dismiss</button>';
  cb.querySelector("pre").textContent = text;
  cb.classList.remove("hidden");
  $("crashDismiss").addEventListener("click", function () {
    crashShown = "";
    cb.classList.add("hidden");
    if (window.Bridge && Bridge.dismissCrash) Bridge.dismissCrash();
  });
}

function renderScan() {
  renderCrash();
  var ring = $("ring"), rt = $("ringText"), rs = $("ringSub");
  var hits = scanState.hits || [];
  var alert = $("alertBox");

  $("permBtn").classList.toggle("hidden", scanState.hasPerm);
  $("btBtn").classList.toggle("hidden", !(scanState.hasPerm && !scanState.btOn));
  var sb = $("scanBtn");
  sb.textContent = scanState.scanning ? "Stop scanning" : "Start scanning";
  sb.className = "btn " + (scanState.scanning ? "stop" : "primary");

  alert.classList.add("hidden");
  ring.className = "ring standby";

  if (!scanState.btSupported) {
    rt.textContent = "NO BLUETOOTH LE";
    rs.textContent = "this device cannot scan";
    return;
  }
  if (!scanState.hasPerm) {
    rt.textContent = "SETUP NEEDED";
    if (scanState.permBlocked) {
      rs.textContent = "permission was turned off";
      $("permBtn").textContent = "Open app settings to allow it";
      alert.textContent = "Android has stopped showing the permission prompt, so the button can't ask again. Fix it in system settings: Apps → Axon Detector → Permissions → Nearby devices → Allow. Then come back here — scanning starts on its own.";
    } else {
      rs.textContent = "tap Enable Bluetooth access";
      $("permBtn").textContent = "Enable Bluetooth access";
      alert.textContent = "The detector needs Bluetooth permission to listen for Axon signals. The prompt calls it “Nearby devices” — that's the one to allow. It's Bluetooth only: the scan never uses your location (location is a separate ask, only for the camera map).";
    }
    alert.classList.remove("hidden");
    renderHits(hits);
    return;
  }
  if (!scanState.btOn) {
    rt.textContent = "BLUETOOTH OFF";
    rs.textContent = "turn Bluetooth on to scan";
    renderHits(hits);
    return;
  }
  if (!scanState.scanning) {
    rt.textContent = "STANDBY";
    rs.textContent = "scanner idle";
    renderHits(hits);
    return;
  }
  if (hits.length === 0) {
    ring.className = "ring clear";
    rt.textContent = "CLEAR";
    rs.textContent = "scanning — no Axon signals";
  } else {
    var strongest = Math.max.apply(null, hits.map(function (h) { return h.rssi; }));
    ring.className = "ring " + (strongest >= -60 ? "strong" : "hit");
    rt.textContent = hits.length === 1 ? "AXON SIGNAL" : hits.length + " AXON SIGNALS";
    rs.textContent = "gear nearby — rough range only";
  }
  renderHits(hits);
}

function renderHits(hits) {
  var wrap = $("hits");
  wrap.innerHTML = "";
  var sorted = hits.slice().sort(function (a, b) { return b.rssi - a.rssi; });
  sorted.forEach(function (h) {
    var card = document.createElement("div");
    card.className = "hit-card";
    var ago = h.ago <= 1 ? "just now" : h.ago + "s ago";
    card.innerHTML =
      '<div class="hit-badge"></div>' +
      '<div class="hit-main"><div class="hit-label"></div>' +
      '<div class="hit-meta"></div></div>' +
      '<div class="hit-band"><span class="band-chip"></span>' +
      '<div class="hit-rssi"></div></div>';
    card.querySelector(".hit-badge").textContent = h.id;
    card.querySelector(".hit-label").textContent = h.label;
    card.querySelector(".hit-meta").textContent = "Matched by " + h.via + " · seen " + ago;
    card.querySelector(".band-chip").textContent = h.band;
    card.querySelector(".hit-rssi").textContent = h.rssi + " dBm";
    wrap.appendChild(card);
  });
  $("hitsEmpty").classList.toggle("hidden", hits.length > 0);
}

window.__onScan = function (state) {
  scanState = state;
  renderScan();
};

window.__onLoc = function (lat, lng) {
  var first = !userLoc;
  userLoc = { lat: lat, lng: lng };
  if (mapInited) { renderMap(); renderCamList(); if (first) mapMsg("Your location is on the map"); }
};

/* ---------------- tabs ---------------- */

function showTab(map) {
  $("tabDetector").classList.toggle("active", !map);
  $("tabMap").classList.toggle("active", map);
  $("viewDetector").classList.toggle("hidden", map);
  $("viewMap").classList.toggle("hidden", !map);
  if (map && !locAsked && window.Bridge && Bridge.requestLocation) {
    locAsked = true;
    Bridge.requestLocation();
  }
  if (map && !mapInited) { initMap(); mapInited = true; }
  if (map && mapInited) { renderMap(); renderCamList(); }
}
$("tabDetector").addEventListener("click", function () { showTab(false); });
$("tabMap").addEventListener("click", function () { showTab(true); });

$("scanBtn").addEventListener("click", function () {
  if (!window.Bridge) return;
  if (!scanState.hasPerm && scanState.permBlocked) { Bridge.openAppSettings(); return; }
  Bridge.setScanning(!scanState.scanning);
});
$("permBtn").addEventListener("click", function () {
  if (!window.Bridge) return;
  if (scanState.permBlocked) Bridge.openAppSettings();
  else Bridge.requestAccess();
});
$("btBtn").addEventListener("click", function () {
  if (window.Bridge) Bridge.enableBluetooth();
});

/* ---------------- camera map ---------------- */

var mapC = { lat: 42.83, lng: -74.24, zoom: 8 };
var selectedCam = -1;
var searchText = "";
var mapTimer = null;

function project(lat, lng, z) {
  var size = 256 * Math.pow(2, z);
  var x = (lng + 180) / 360 * size;
  var s = Math.sin(lat * Math.PI / 180);
  s = Math.max(-0.9999, Math.min(0.9999, s));
  var y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * size;
  return { x: x, y: y };
}
function unproject(x, y, z) {
  var size = 256 * Math.pow(2, z);
  var lng = x / size * 360 - 180;
  var n = Math.PI - 2 * Math.PI * y / size;
  var lat = 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
  return { lat: lat, lng: lng };
}
function haversineMi(a, b, c, d) {
  var R = 3958.8, p = Math.PI / 180;
  var h = Math.sin((c - a) * p / 2) * Math.sin((c - a) * p / 2) +
          Math.cos(a * p) * Math.cos(c * p) * Math.sin((d - b) * p / 2) * Math.sin((d - b) * p / 2);
  return 2 * R * Math.asin(Math.sqrt(h));
}
function fmtDist(mi) {
  if (mi == null) return "";
  if (mi < 0.1) return "<0.1 mi";
  return mi.toFixed(1) + " mi";
}
function compass(degStr) {
  var d = parseFloat(degStr);
  if (isNaN(d)) return "";
  var dirs = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
  return dirs[Math.round(d / 45) % 8];
}

function renderMap() {
  var el = $("map");
  var w = el.clientWidth, hgt = el.clientHeight;
  if (!w || !hgt) return;
  var z = mapC.zoom;
  var c = project(mapC.lat, mapC.lng, z);
  var ox = c.x - w / 2, oy = c.y - hgt / 2;

  var tiles = $("mapTiles");
  tiles.innerHTML = "";
  var tx0 = Math.floor(ox / 256), tx1 = Math.floor((ox + w) / 256);
  var ty0 = Math.floor(oy / 256), ty1 = Math.floor((oy + hgt) / 256);
  var maxT = Math.pow(2, z) - 1;
  for (var tx = tx0; tx <= tx1; tx++) {
    for (var ty = ty0; ty <= ty1; ty++) {
      if (ty < 0 || ty > maxT) continue;
      var wx = ((tx % (maxT + 1)) + maxT + 1) % (maxT + 1);
      var img = document.createElement("img");
      img.src = "https://tile.openstreetmap.org/" + z + "/" + wx + "/" + ty + ".png";
      img.style.left = (tx * 256 - ox) + "px";
      img.style.top = (ty * 256 - oy) + "px";
      img.alt = "";
      tiles.appendChild(img);
    }
  }

  var marks = $("mapMarks");
  marks.innerHTML = "";
  CAMS.forEach(function (cam, i) {
    var p = project(cam[0], cam[1], z);
    var px = p.x - ox, py = p.y - oy;
    if (px < -24 || py < -24 || px > w + 24 || py > hgt + 24) return;
    var d = document.createElement("div");
    d.className = "mark " + (cam[3] === 1 ? "flock" : "other") + (i === selectedCam ? " sel" : "");
    d.style.left = px + "px";
    d.style.top = py + "px";
    d.addEventListener("click", function (ev) { ev.stopPropagation(); selectCam(i, false); });
    marks.appendChild(d);
  });
  if (userLoc) {
    var mp = project(userLoc.lat, userLoc.lng, z);
    var mx = mp.x - ox, my = mp.y - oy;
    if (mx >= -20 && my >= -20 && mx <= w + 20 && my <= hgt + 20) {
      var me = document.createElement("div");
      me.className = "mark me";
      me.style.left = mx + "px";
      me.style.top = my + "px";
      marks.appendChild(me);
    }
  }
}

function scheduleMapRender() {
  if (mapTimer) return;
  mapTimer = requestAnimationFrame(function () { mapTimer = null; renderMap(); });
}

function selectCam(i, center) {
  selectedCam = i;
  var cam = CAMS[i];
  if (center) { mapC.lat = cam[0]; mapC.lng = cam[1]; if (mapC.zoom < 12) mapC.zoom = 12; }
  var info = $("camInfo");
  var kind = cam[3] === 1 ? "Flock Safety plate reader" : "Plate reader";
  var bits = [];
  if (cam[4]) bits.push(cam[4]);
  if (cam[5]) bits.push(cam[5]);
  if (cam[6]) bits.push("faces " + compass(cam[6]) + " (" + cam[6] + "°)");
  var dist = userLoc ? haversineMi(userLoc.lat, userLoc.lng, cam[0], cam[1]) : null;
  info.innerHTML =
    '<div class="ci-title"></div><div class="ci-meta"></div>' +
    '<div style="margin-top:7px"><a class="ci-dir" target="_blank" rel="noopener">Directions</a>' +
    ' &nbsp;·&nbsp; <a class="ci-osm" target="_blank" rel="noopener">OpenStreetMap source</a></div>';
  info.querySelector(".ci-title").textContent = cam[2] + " — " + kind;
  info.querySelector(".ci-meta").textContent =
    bits.join(" · ") + (dist != null ? (bits.length ? " · " : "") + fmtDist(dist) + " from you" : "");
  info.querySelector(".ci-dir").href =
    "https://www.google.com/maps/dir/?api=1&destination=" + cam[0] + "," + cam[1];
  info.querySelector(".ci-osm").href = "https://www.openstreetmap.org/node/" + cam[7];
  info.classList.remove("hidden");
  renderMap();
}

function renderCamList() {
  var list = $("camList");
  list.innerHTML = "";
  var q = searchText.trim().toLowerCase();
  var rows = [];
  CAMS.forEach(function (cam, i) {
    if (q && (cam[2] + " " + cam[4]).toLowerCase().indexOf(q) === -1) return;
    var dist = userLoc ? haversineMi(userLoc.lat, userLoc.lng, cam[0], cam[1]) : null;
    rows.push({ i: i, cam: cam, dist: dist });
  });
  rows.sort(function (a, b) {
    if (a.dist != null && b.dist != null) return a.dist - b.dist;
    return a.cam[2].localeCompare(b.cam[2]);
  });
  rows.forEach(function (r) {
    var b = document.createElement("button");
    b.type = "button";
    b.className = "cam-row";
    var meta = (r.cam[3] === 1 ? "Flock Safety" : "Plate reader") +
      (r.cam[4] ? " · " + r.cam[4] : "") + (r.cam[5] ? " · " + r.cam[5] : "");
    b.innerHTML = '<div><div class="cr-town"></div><div class="cr-meta"></div></div><div class="cr-dist"></div>';
    b.querySelector(".cr-town").textContent = r.cam[2];
    b.querySelector(".cr-meta").textContent = meta;
    b.querySelector(".cr-dist").textContent = fmtDist(r.dist);
    b.addEventListener("click", function () {
      selectCam(r.i, true);
      $("map").scrollIntoView({ behavior: "smooth", block: "start" });
    });
    list.appendChild(b);
  });
  if (rows.length === 0) {
    var d = document.createElement("div");
    d.className = "empty";
    d.textContent = "No cameras match that search.";
    list.appendChild(d);
  }
}

function mapMsg(text) {
  var m = $("mapMsg");
  m.textContent = text;
  m.classList.remove("hidden");
  setTimeout(function () { m.classList.add("hidden"); }, 2600);
}

function initMap() {
  renderMap();
  renderCamList();
  var el = $("map");
  var drag = null;
  el.addEventListener("pointerdown", function (e) {
    if (e.target.closest(".map-zoom")) return;
    drag = { x: e.clientX, y: e.clientY };
    el.setPointerCapture(e.pointerId);
  });
  el.addEventListener("pointermove", function (e) {
    if (!drag) return;
    var dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    drag = { x: e.clientX, y: e.clientY };
    var c = project(mapC.lat, mapC.lng, mapC.zoom);
    var nc = unproject(c.x - dx, c.y - dy, mapC.zoom);
    mapC.lat = nc.lat; mapC.lng = nc.lng;
    scheduleMapRender();
  });
  ["pointerup", "pointercancel"].forEach(function (t) {
    el.addEventListener(t, function () { drag = null; });
  });
  el.addEventListener("dblclick", function () {
    mapC.zoom = Math.min(17, mapC.zoom + 1); renderMap();
  });
  $("zoomIn").addEventListener("click", function () { mapC.zoom = Math.min(17, mapC.zoom + 1); renderMap(); });
  $("zoomOut").addEventListener("click", function () { mapC.zoom = Math.max(6, mapC.zoom - 1); renderMap(); });
  $("nearMe").addEventListener("click", function () {
    if (userLoc) { mapC.lat = userLoc.lat; mapC.lng = userLoc.lng; mapC.zoom = 12; renderMap(); }
    else mapMsg("No location yet — grant location permission and give it a few seconds");
  });
  $("camSearch").addEventListener("input", function (e) { searchText = e.target.value; renderCamList(); });
  window.addEventListener("resize", scheduleMapRender);
}

/* ---------------- boot ---------------- */

renderScan();
if (window.Bridge) Bridge.ready();
