"use strict";

window.addEventListener('error', function (e) {
    const d = document.createElement('div');
    d.style.cssText = "z-index:10000;position:fixed;color:white;background:red;font-size:24px;left:10px;top:200px;padding:10px;";
    d.innerHTML = "GLOBAL ERROR:<br>" + e.message + "<br>" + e.filename + ":" + e.lineno;
    document.body.appendChild(d);
});

mermaid.initialize({
    startOnLoad: false,
    theme: 'dark',
    fontFamily: 'Inter',
    themeVariables: {
        primaryColor: 'transparent',
        primaryBorderColor: 'transparent',
        lineColor: '#64748b',
        fontFamily: 'Inter',
        fontSize: '18px'
    },
    flowchart: {
        padding: 24,
        curve: 'basis',
        htmlLabels: true
    }
});

const graphContainer = document.getElementById('graph-view');
const tlStatus = document.getElementById('timeline-status');
const epochValue = document.getElementById('epoch-value');

document.getElementById('btn-auto-track').addEventListener('click', () => {
    if (isScrubbing && tlStatus) {
        tlStatus.click(); // re-uses the established return-to-LIVE logic
    } else if (chartInstance) {
        chartInstance.timeScale().scrollToRealTime();
        const btnAutoTrack = document.getElementById('btn-auto-track');
        if (btnAutoTrack) btnAutoTrack.style.display = 'none';
    }
});

// Metrics DOM
const latAvg = document.getElementById('lat-avg');
const latLatest = document.getElementById('lat-latest');
const profileBody = document.getElementById('profile-body');
const nanBody = document.getElementById('nan-body');
const stabRate = document.getElementById('stab-rate');
const reactiveEff = document.getElementById('reactive-eff');

let isGraphRendered = false;
let sourceCodes = {};
// Map to keep track of previous values to know when to trigger the flash animation
const prevValues = new Map();
// Stores the direct children of each node: { "NodeA": ["NodeB", "NodeC"] }
let graphRouting = {};
let reverseGraphRouting = {}; // Stores direct parents: { "NodeB": ["NodeA"] }
// Array to track message arrival times for Stabilization Rate calculation
const messageTimes = [];
let panZoomInstance = null;
const graphElementsCache = new Map(); // O(1) memory bindings for static Graph SVG elements

/**
 * A zero-allocation fixed-size circular buffer.
 * Overwrites the oldest elements seamlessly instead of garbage collecting.
 */
class RingBuffer {
    constructor(capacity) {
        this.capacity = capacity;
        this.buffer = new Array(capacity);
        this.head = 0; // Where the next element will be written
        this.size = 0; // Current number of valid elements
    }

    push(item) {
        this.buffer[this.head++] = item;
        if (this.head === this.capacity) {
            this.head = 0;
        }
        if (this.size !== this.capacity) {
            this.size++;
        }
    }

    get(logicalIndex) {
        if (logicalIndex < 0 || logicalIndex >= this.size) {
            return undefined;
        }
        let physicalIndex = (this.size === this.capacity ? this.head : 0) + logicalIndex;
        if (physicalIndex >= this.capacity) {
            physicalIndex -= this.capacity;
        }
        return this.buffer[physicalIndex];
    }

    last() {
        if (this.size === 0) return undefined;
        return this.head === 0 ? this.buffer[this.capacity - 1] : this.buffer[this.head - 1];
    }

    // Export flat, chronologically sorted array specifically for charting engines
    toArray() {
        if (this.size === 0) return [];
        if (this.size < this.capacity) {
            return this.buffer.slice(0, this.size);
        }

        // When full, concat the oldest half (tail to end) with the newest half (0 to tail)
        const result = new Array(this.capacity);
        let dst = 0;
        for (let i = this.head; i < this.capacity; i++) {
            result[dst++] = this.buffer[i];
        }
        for (let i = 0; i < this.head; i++) {
            result[dst++] = this.buffer[i];
        }
        return result;
    }
}

// Settings Configs
let globalHistoryDepth = 3000;

// Lightweight Charts Globals
const nodeHistory = new Map(); // Maps NodeID -> Array of {time: timestamp, value: number}
const nodeNaNHistory = new Map(); // Tracks discrete NaN timestamps for red highlighting
const epochToRealTime = new Map(); // Maps integer epochs -> actual JS millisecond timestamps
let oldestEpochTracker = -1; // O(1) tracking for cleaning up old epochs
const snapshots = new RingBuffer(globalHistoryDepth); // Master timeline payload cache


// Alert Engine State
let activeAlerts = JSON.parse(localStorage.getItem('activeAlerts') || '[]');
let alertHistory = JSON.parse(localStorage.getItem('alertHistory') || '[]');
let alertIdCounter = parseInt(localStorage.getItem('alertIdCounter') || '0', 10);

function saveAlertState() {
    localStorage.setItem('activeAlerts', JSON.stringify(activeAlerts));
    localStorage.setItem('alertHistory', JSON.stringify(alertHistory));
    localStorage.setItem('alertIdCounter', alertIdCounter.toString());
}

let chartInstance = null;
const activeChartSeries = new Map(); // nodeId -> { lineSeries, nanSeries, color }
const CHART_COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899', '#06b6d4', '#eab308', '#ef4444'];
let chartColorIndex = 0;

// Global Context Menu Listeners
document.addEventListener('click', (e) => {
    const ctxMenu = document.getElementById('node-context-menu');
    if (ctxMenu && !ctxMenu.classList.contains('hidden')) {
        // If they click anywhere else, including the native Mermaid canvas, hide it
        ctxMenu.classList.add('hidden');
    }
});

const ctxAddChartBtn = document.getElementById('ctx-add-chart');
if (ctxAddChartBtn) {
    ctxAddChartBtn.addEventListener('click', (e) => {
        const ctxMenu = document.getElementById('node-context-menu');
        const nodeName = ctxMenu ? ctxMenu.dataset.contextNode : null;
        if (nodeName) {
            openChart(nodeName);
        }
    });
}

const ctxAddAlertBtn = document.getElementById('ctx-add-alert');
if (ctxAddAlertBtn) {
    ctxAddAlertBtn.addEventListener('click', () => {
        const ctxMenu = document.getElementById('node-context-menu');
        const nodeName = ctxMenu ? ctxMenu.dataset.contextNode : null;
        if (nodeName) {
            document.getElementById('add-alert-node-name').textContent = nodeName;
            document.getElementById('add-alert-value').value = '';
            document.getElementById('add-alert-modal').classList.remove('hidden');
        }
    });
}

function closeAddAlertModal() {
    document.getElementById('add-alert-modal').classList.add('hidden');
}

document.getElementById('add-alert-close')?.addEventListener('click', closeAddAlertModal);
document.getElementById('add-alert-cancel')?.addEventListener('click', closeAddAlertModal);

document.getElementById('add-alert-create')?.addEventListener('click', () => {
    const node = document.getElementById('add-alert-node-name').textContent;
    const condition = document.getElementById('add-alert-condition').value;
    const threshStr = document.getElementById('add-alert-value').value;

    if (!node || !condition || threshStr === '') {
        return;
    }

    const newAlert = {
        id: ++alertIdCounter,
        node: node,
        condition: condition,
        threshold: parseFloat(threshStr),
        lastNotifiedEpoch: -1
    };

    activeAlerts.push(newAlert);
    saveAlertState();
    renderActiveAlerts();
    closeAddAlertModal();
});

// Global Descriptions and Edge Labels Cache
let nodeDescriptions = {};
let edgeLabels = {};
let expandedGroups = new Set();
let activeDetailsNode = null;

document.getElementById('details-close').addEventListener('click', closeDetailsModal);

// Details Sidebar Resizing Logic
const detailsModalEl = document.querySelector('.details-modal');
const detailsResizeHandle = document.getElementById('details-resize-handle');
let isDetailsResizing = false;
let detailsResizeStartX = 0;
let detailsInitialWidth = 0;

if (detailsResizeHandle) {
    detailsResizeHandle.addEventListener('mousedown', (e) => {
        isDetailsResizing = true;
        detailsResizeStartX = e.clientX;
        detailsInitialWidth = detailsModalEl.offsetWidth;
        detailsResizeHandle.classList.add('active');
        document.body.style.cursor = 'ew-resize';
        e.preventDefault(); // Prevent text selection
    });
}

document.addEventListener('mousemove', (e) => {
    if (!isDetailsResizing) return;
    const dx = e.clientX - detailsResizeStartX;
    const newWidth = Math.max(300, Math.min(window.innerWidth * 0.9, detailsInitialWidth + dx));
    detailsModalEl.style.width = `${newWidth}px`;
});

document.addEventListener('mouseup', () => {
    if (isDetailsResizing) {
        isDetailsResizing = false;
        if (detailsResizeHandle) detailsResizeHandle.classList.remove('active');
        document.body.style.cursor = '';
    }
});

// Close modal when clicking outside of it
document.getElementById('node-details-modal').addEventListener('click', (e) => {
    if (e.target.id === 'node-details-modal') {
        closeDetailsModal();
    }
});

// Close modal on Escape key press
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        const modal = document.getElementById('node-details-modal');
        if (modal && !modal.classList.contains('hidden')) {
            closeDetailsModal();
        }
    }
});

let detailsVectorChart = null;
let detailsVectorSeries = null;

function openDetailsModal(nodeName) {
    const modal = document.getElementById('node-details-modal');

    // Set Header and Description
    document.getElementById('details-node-name').textContent = nodeName;
    document.getElementById('details-node-desc').textContent = nodeDescriptions[nodeName] || "No description available for this calculation type.";

    // Set Lineage
    const parentsList = document.getElementById('details-parents-list');
    const childrenList = document.getElementById('details-children-list');
    parentsList.innerHTML = '';
    childrenList.innerHTML = '';

    const parents = reverseGraphRouting[nodeName] || [];
    if (parents.length === 0) {
        parentsList.innerHTML = '<li style="color: var(--text-secondary)">None (Source Node)</li>';
    } else {
        [].concat(parents).sort((a, b) => {
            const labelMap = edgeLabels[nodeName];
            if (!labelMap) return 0;

            // Extract the 'i0', 'i1' label into a comparable integer.
            const labelA = labelMap[a] || "";
            const labelB = labelMap[b] || "";

            const indexA = parseInt(labelA.replace('i', '')) || 0;
            const indexB = parseInt(labelB.replace('i', '')) || 0;

            return indexA - indexB;
        }).forEach(p => {
            // edgeLabels is populated as Child -> Parent -> Label
            const labelMap = edgeLabels[nodeName];
            const label = labelMap ? labelMap[p] : null;
            const li = document.createElement('li');
            li.innerHTML = `<strong class="clickable-node" onclick="openDetailsModal('${p}')">${p}</strong> ${label ? `<span style="color:var(--text-secondary); font-size: 0.8em; margin-left: 8px;">-> ${label}</span>` : ''} <span class="details-value-tick" data-node-id="${p}" style="float: right; font-family: monospace; color: var(--text-highlight);"></span>`;
            parentsList.appendChild(li);
        });
    }

    const children = graphRouting[nodeName] || [];
    if (children.length === 0) {
        childrenList.innerHTML = '<li style="color: var(--text-secondary)">None (Terminal Node)</li>';
    } else {
        [].concat(children).sort().forEach(c => {
            const labelMap = edgeLabels[nodeName];
            const label = labelMap ? labelMap[c] : null;
            const li = document.createElement('li');
            li.innerHTML = `<strong class="clickable-node" onclick="openDetailsModal('${c}')">${c}</strong> ${label ? `<span style="color:var(--text-secondary); font-size: 0.8em; margin-left: 8px;">-> ${label}</span>` : ''} <span class="details-value-tick" data-node-id="${c}" style="float: right; font-family: monospace; color: var(--text-highlight);"></span>`;
            childrenList.appendChild(li);
        });
    }

    // Set Properties
    const propsSection = document.getElementById('details-properties-section');
    const propsList = document.getElementById('details-properties-list');
    if (propsList) propsList.innerHTML = '';

    if (window.graphProperties && window.graphProperties[nodeName] && Object.keys(window.graphProperties[nodeName]).length > 0) {
        if (propsSection) propsSection.style.display = 'block';
        const props = window.graphProperties[nodeName];
        for (const [key, value] of Object.entries(props)) {
            const li = document.createElement('li');
            li.innerHTML = `<span style="color:var(--text-secondary); font-family: monospace; font-size: 0.9em; display:inline-block; width: 120px;">${key}</span> <strong style="color:var(--text-highlight); font-family: monospace; font-size: 0.9em;">${value}</strong>`;
            if (propsList) propsList.appendChild(li);
        }
    } else {
        if (propsSection) propsSection.style.display = 'none';
    }

    // Source Code
    const sourceSection = document.getElementById('details-source-section');
    const sourceCodePre = document.getElementById('details-source-code');
    if (sourceSection && sourceCodePre) {
        if (sourceCodes[nodeName] && sourceCodes[nodeName] !== "No underlying implementation class defined.") {
            sourceSection.style.display = 'block';
            sourceCodePre.textContent = sourceCodes[nodeName];
            if (window.Prism) {
                Prism.highlightElement(sourceCodePre);
            }
        } else {
            sourceSection.style.display = 'none';
        }
    }

    // Setup Vector Chart if applicable
    const vectorSection = document.getElementById('details-vector-section');
    const chartContainer = document.getElementById('tv-vector-chart');

    let isVector = false;
    let vectorData = [];
    let vectorHeaders = [];

    const latestVal = prevValues.get(nodeName);
    if (typeof latestVal === 'string' && latestVal.startsWith('[') && latestVal.endsWith(']')) {
        isVector = true;
        try {
            const arr = JSON.parse(latestVal);
            const lastSnap = snapshots.size > 0 ? snapshots.get(snapshots.size - 1) : null;
            let headersStr = lastSnap && lastSnap.values ? lastSnap.values[nodeName + "_headers"] : null;

            if (headersStr && headersStr.startsWith('[') && headersStr.endsWith(']')) {
                const headersArray = JSON.parse(headersStr);
                vectorHeaders = headersArray;
            }

            vectorData = arr.map((v, i) => {
                return { time: i + 1, value: Number.isNaN(Number(v)) || v === "NaN" || v === null ? NaN : Number(v) };
            });
        } catch (e) { }
    }

    if (isVector) {
        vectorSection.style.display = 'block';

        if (detailsVectorChart) {
            detailsVectorChart.remove();
            detailsVectorChart = null;
            detailsVectorSeries = null;
        }

        detailsVectorChart = LightweightCharts.createChart(chartContainer, {
            layout: {
                background: { type: 'solid', color: 'transparent' },
                textColor: '#94a3b8',
            },
            grid: {
                vertLines: { color: 'rgba(255, 255, 255, 0.05)' },
                horzLines: { color: 'rgba(255, 255, 255, 0.05)' },
            },
            crosshair: {
                mode: LightweightCharts.CrosshairMode.Normal,
            },
            rightPriceScale: {
                borderColor: 'rgba(255, 255, 255, 0.1)',
            },
            timeScale: {
                borderColor: 'rgba(255, 255, 255, 0.1)',
                timeVisible: false,
                tickMarkFormatter: (time, tickMarkType, locale) => {
                    const idx = Math.floor(time) - 1;
                    if (vectorHeaders && vectorHeaders.length > idx && idx >= 0) {
                        return vectorHeaders[idx];
                    }
                    return `${idx}`;
                }
            },
        });

        detailsVectorSeries = detailsVectorChart.addLineSeries({
            color: '#58a6ff',
            lineWidth: 2,
            crosshairMarkerVisible: true,
            lastValueVisible: false,
            priceLineVisible: false,
            pointMarkersVisible: true,
        });

        detailsVectorSeries.setData(vectorData);
        detailsVectorChart.timeScale().fitContent();

        const ro = new ResizeObserver(() => {
            if (detailsVectorChart && chartContainer.clientWidth > 0 && chartContainer.clientHeight > 0) {
                detailsVectorChart.resize(chartContainer.clientWidth, chartContainer.clientHeight);
                detailsVectorChart.timeScale().fitContent();
            }
        });
        ro.observe(chartContainer);
        chartContainer._ro = ro;

    } else {
        vectorSection.style.display = 'none';
        if (detailsVectorChart) {
            detailsVectorChart.remove();
            detailsVectorChart = null;
            detailsVectorSeries = null;
        }
    }

    // Reveal modal first so we can read its dimensions if needed
    modal.classList.remove('hidden');

    activeDetailsNode = nodeName;
}

function closeDetailsModal() {
    document.getElementById('node-details-modal').classList.add('hidden');
    activeDetailsNode = null;

    if (detailsVectorChart) {
        detailsVectorChart.remove();
        detailsVectorChart = null;
        detailsVectorSeries = null;
    }
}



// Global Z-Index tracker for draggable panels
let highestZIndex = 1000;
let isScrubbing = false;

// Heavily hit DOM elements hoisted for zero-allocation access loops
let domTimeScrubber = null;
let domTimelineEpoch = null;
let domAlertModal = null;
let domAlertModalText = null;

// The UI Zoom buttons bridge over to the active instance
document.getElementById('zoom-in').addEventListener('click', () => {
    if (panZoomInstance) panZoomInstance.zoomIn();
});

document.getElementById('zoom-out').addEventListener('click', () => {
    if (panZoomInstance) panZoomInstance.zoomOut();
});

document.getElementById('zoom-reset').addEventListener('click', () => {
    fitGraph();
});

// Helper for Graph Fit to zoom out slightly (2 clicks)
function fitGraph() {
    if (panZoomInstance) {
        panZoomInstance.resize();
        panZoomInstance.fit();
        panZoomInstance.center();
        panZoomInstance.zoomOut();
        panZoomInstance.zoomOut();
    }
}

let isDraggingMinimap = false;

function initMinimap(mainSvg) {
    const minimapSvg = document.getElementById('minimap-svg');
    const minimapViewport = document.getElementById('minimap-viewport');
    const minimapContainer = document.getElementById('minimap-container');

    if (!minimapSvg || !minimapViewport || !minimapContainer || !panZoomInstance) return;

    // Clone the static SVG content into the minimap
    minimapSvg.innerHTML = mainSvg.innerHTML;

    // Set the minimap viewBox to match the original graph's initial natural bounds
    const bbox = mainSvg.getBBox();
    const viewBoxStr = `${bbox.x} ${bbox.y} ${bbox.width} ${bbox.height}`;
    minimapSvg.setAttribute('viewBox', viewBoxStr);

    function updateViewport() {
        const sizes = panZoomInstance.getSizes();
        const pan = panZoomInstance.getPan();

        // Map logical viewBox coords to minimap container pixels
        const scale = Math.min(
            minimapContainer.clientWidth / sizes.viewBox.width,
            minimapContainer.clientHeight / sizes.viewBox.height
        );

        // Centering offsets due to preserveAspectRatio xMidYMid meet
        const offsetX = (minimapContainer.clientWidth - sizes.viewBox.width * scale) / 2;
        const offsetY = (minimapContainer.clientHeight - sizes.viewBox.height * scale) / 2;

        // Visible area in logical coordinates
        const viewWidth = sizes.width / sizes.realZoom;
        const viewHeight = sizes.height / sizes.realZoom;
        const viewX = -pan.x / sizes.realZoom;
        const viewY = -pan.y / sizes.realZoom;

        // Convert logical coordinates back to minimap container pixels
        const vpLeft = (viewX * scale) + offsetX;
        const vpTop = (viewY * scale) + offsetY;
        const vpWidth = viewWidth * scale;
        const vpHeight = viewHeight * scale;

        minimapViewport.style.left = `${vpLeft}px`;
        minimapViewport.style.top = `${vpTop}px`;
        minimapViewport.style.width = `${vpWidth}px`;
        minimapViewport.style.height = `${vpHeight}px`;
    }

    panZoomInstance.setOnPan(updateViewport);
    panZoomInstance.setOnZoom(updateViewport);
    setTimeout(updateViewport, 50);

    // Drag to pan map logic
    function handleMinimapDrag(e) {
        if (!isDraggingMinimap) return;
        const rect = minimapContainer.getBoundingClientRect();

        // Mouse position in minimap container
        const mouseX = e.clientX - rect.left;
        const mouseY = e.clientY - rect.top;

        const sizes = panZoomInstance.getSizes();
        const scale = Math.min(
            minimapContainer.clientWidth / sizes.viewBox.width,
            minimapContainer.clientHeight / sizes.viewBox.height
        );
        const offsetX = (minimapContainer.clientWidth - sizes.viewBox.width * scale) / 2;
        const offsetY = (minimapContainer.clientHeight - sizes.viewBox.height * scale) / 2;

        // Convert minimap mouse position back to logical graph coordinates
        const logicalX = (mouseX - offsetX) / scale;
        const logicalY = (mouseY - offsetY) / scale;

        // We want logical center of the screen to be at logicalX, logicalY
        const viewWidth = sizes.width / sizes.realZoom;
        const viewHeight = sizes.height / sizes.realZoom;

        const newViewX = logicalX - viewWidth / 2;
        const newViewY = logicalY - viewHeight / 2;

        panZoomInstance.pan({
            x: -newViewX * sizes.realZoom,
            y: -newViewY * sizes.realZoom
        });
    }

    minimapContainer.addEventListener('mousedown', (e) => {
        isDraggingMinimap = true;
        minimapContainer.style.cursor = 'grabbing';
        handleMinimapDrag(e);
        e.preventDefault();
    });

    document.addEventListener('mousemove', handleMinimapDrag);
    document.addEventListener('mouseup', () => {
        if (isDraggingMinimap) {
            isDraggingMinimap = false;
            minimapContainer.style.cursor = 'crosshair';
        }
    });
}

// Text Scale controls
let textScale = 1.0;
// Set it immediately so it takes effect on load
document.documentElement.style.setProperty('--node-text-scale', textScale);

document.getElementById('text-in').addEventListener('click', () => {
    textScale = Math.min(2.5, textScale + 0.1);
    document.documentElement.style.setProperty('--node-text-scale', textScale);
});

document.getElementById('text-out').addEventListener('click', () => {
    textScale = Math.max(0.4, textScale - 0.1);
    document.documentElement.style.setProperty('--node-text-scale', textScale);
});

document.getElementById('text-reset').addEventListener('click', () => {
    textScale = 1.0;
    document.documentElement.style.setProperty('--node-text-scale', textScale);
});

function updateMetricsDOM(payload) {
    if (payload.epoch !== undefined) {
        epochValue.textContent = payload.epoch;
    }
    if (payload.graphName) {
        // Updated via init block now
    }
    if (payload.graphVersion) {
        document.getElementById('graph-version').textContent = 'v' + payload.graphVersion;
    }

    if (payload.metrics) {
        if (payload.metrics.eventsProcessed !== undefined) {
            document.getElementById('events-value').textContent = payload.metrics.eventsProcessed;
        }
        if (payload.metrics.epochEvents !== undefined) {
            const el = document.getElementById('events-epoch-value');
            if (el) el.textContent = payload.metrics.epochEvents;
        }
        if (payload.metrics.totalNodes !== undefined) {
            reactiveEff.textContent = `${payload.metrics.nodesUpdated} / ${payload.metrics.totalNodes}`;
            document.getElementById('graph-total-nodes').textContent = payload.metrics.totalNodes;
        }
        if (payload.metrics.totalSourceNodes !== undefined) {
            document.getElementById('graph-source-nodes').textContent = payload.metrics.totalSourceNodes;
        }
        if (payload.metrics.frontendHz !== undefined) {
            stabRate.textContent = payload.metrics.frontendHz;
        }
        if (payload.metrics.latency) {
            if (payload.metrics.latency.latest !== undefined) {
                latLatest.textContent = formatVal(payload.metrics.latency.latest, 2);
            }
            if (payload.metrics.latency.avg !== undefined) {
                latAvg.textContent = formatVal(payload.metrics.latency.avg, 2);
            }
        }
        if (payload.metrics.profile) {
            updateProfileTable(payload.metrics.profile);

            // Hydrate Live Metrics for the active Details modal if open
            if (activeDetailsNode) {
                const p = payload.metrics.profile.find(x => x.name === activeDetailsNode);
                if (p) {
                    const latEl = document.getElementById('details-metric-lat');
                    const updEl = document.getElementById('details-metric-update');
                    const avgEl = document.getElementById('details-metric-avg');
                    const nanEl = document.getElementById('details-metric-nan');
                    if (latEl) latEl.textContent = formatVal(p.latest, 2);
                    if (updEl) updEl.textContent = p.evaluations;
                    if (avgEl) avgEl.textContent = formatVal(p.avg, 2);
                    if (nanEl) nanEl.textContent = p.nans;
                }
            }
        }
    }

    // Re-hydrate internal state for the parsed node
    if (activeDetailsNode && payload.values) {


        // Update states
        if (payload.states && payload.states[activeDetailsNode]) {
            const stateObj = payload.states[activeDetailsNode];
            const stateSection = document.getElementById('details-state-section');
            const stateList = document.getElementById('details-state-list');

            if (stateSection && stateList) {
                stateSection.style.display = 'block';
                stateList.innerHTML = '';
                for (const [key, stateVal] of Object.entries(stateObj)) {
                    const li = document.createElement('li');
                    li.innerHTML = `<span style="color:var(--text-secondary); font-family: monospace; font-size: 0.9em; display:inline-block; width: 120px;">${key}</span> <strong style="color:var(--text-highlight); font-family: monospace; font-size: 0.9em;">${typeof stateVal === 'number' ? Number(stateVal).toFixed(4) : stateVal}</strong>`;
                    stateList.appendChild(li);
                }
            }
        } else {
            const stateSection = document.getElementById('details-state-section');
            if (stateSection) stateSection.style.display = 'none';
        }
    }
}

function connect() {
    const ws = new WebSocket(`ws://${window.location.host}/ws/graph`);

    ws.onopen = () => {
        tlStatus.textContent = isScrubbing ? 'REPLAY' : 'LIVE';
        tlStatus.className = isScrubbing ? 'status-badge replay' : 'status-badge live';
    };

    ws.onclose = () => {
        tlStatus.textContent = 'DISCONNECTED';
        tlStatus.className = 'status-badge disconnected';
        isGraphRendered = false;
        setTimeout(connect, 2000); // Auto-reconnect
    };

    ws.onmessage = async (event) => {
        try {
            const payload = JSON.parse(event.data);

            // Branch 1: Heavy Static Initializer Payload
            if (payload.type === 'init') {
                if (!isGraphRendered) {
                    window.graphProperties = payload.properties || {};
                    document.getElementById('graph-title').textContent = payload.graphName;
                    document.getElementById('graph-version').textContent = "v" + payload.graphVersion;
                    // Render layout from server once
                    if (payload.topology) {
                        const mermaidStr = payload.topology;
                        const { svg } = await mermaid.render('graph-svg', mermaidStr);
                        graphContainer.innerHTML = svg;
                        isGraphRendered = true;

                        // Hook into SVG and enable interactive vector tracking
                        const svgEl = graphContainer.querySelector('svg');
                        if (svgEl) {
                            svgEl.removeAttribute('width');
                            svgEl.removeAttribute('height');
                            svgEl.style.width = '100%';
                            svgEl.style.height = '100%';
                            svgEl.style.maxWidth = 'none';

                            panZoomInstance = svgPanZoom(svgEl, {
                                zoomEnabled: true,
                                controlIconsEnabled: false,
                                fit: false,
                                center: false,
                                minZoom: 0.1,
                                maxZoom: 50
                            });
                            initMinimap(svgEl);
                            setTimeout(fitGraph, 10);
                        }

                        // Store routing map from backend
                        if (payload.routing) {
                            graphRouting = payload.routing;
                            reverseGraphRouting = {};
                            for (const [parent, children] of Object.entries(graphRouting)) {
                                for (const child of children) {
                                    if (!reverseGraphRouting[child]) reverseGraphRouting[child] = [];
                                    reverseGraphRouting[child].push(parent);
                                }
                            }
                        }

                        // Populate Select Node dropdown dynamically
                        const alertNodeSelect = document.getElementById('alert-node');
                        let sortedNodes = Object.keys(graphRouting);

                        // Prefill prevValues so partial tick streams don't shatter the tracked struct
                        sortedNodes.forEach(nodeName => {
                            if (!prevValues.has(nodeName)) prevValues.set(nodeName, 'NaN');

                            // Compute cached DOM queries upfront for 0-allocation updates natively in WebSockets
                            const svgNodeId = getMermaidNodeId(nodeName);
                            const nodeGroups = document.querySelectorAll(`[id^="flowchart-${svgNodeId}-"]`);

                            const cachedInstances = [];

                            nodeGroups.forEach(nodeGroup => {
                                let nodeValueEl = nodeGroup.querySelector('.node-value');
                                let textNodesFallback = null;

                                if (!nodeValueEl) {
                                    textNodesFallback = nodeGroup.querySelectorAll('tspan');
                                }

                                cachedInstances.push({
                                    group: nodeGroup,
                                    valueEl: nodeValueEl,
                                    textNodesFallback: textNodesFallback
                                });
                            });

                            graphElementsCache.set(nodeName, {
                                instances: cachedInstances,
                                lsPaths: document.querySelectorAll(`.LS-${svgNodeId}`),
                                lePaths: document.querySelectorAll(`.LE-${svgNodeId}`)
                            });
                        });

                        if (alertNodeSelect) {
                            alertNodeSelect.innerHTML = '<option value="">Select Node</option>';
                            sortedNodes.sort().forEach(nodeName => {
                                const opt = document.createElement('option');
                                opt.value = nodeName;
                                opt.textContent = nodeName;
                                alertNodeSelect.appendChild(opt);
                            });
                        }

                        // Attach custom CSS hover bindings safely post SVG ingestion
                        attachHoverListeners();

                        // Restore persisted chart selections
                        try {
                            const persistedSeries = JSON.parse(localStorage.getItem('activeChartSeries') || '[]');
                            // Deduplicate and filter against valid routing nodes
                            const validSeries = persistedSeries.filter(nodeId => sortedNodes.includes(nodeId));
                            validSeries.forEach(nodeId => {
                                openChart(nodeId);
                            });
                        } catch (e) {
                            console.warn("Could not restore chart selections", e);
                        }
                    }

                    // Store descriptions and edge labels globally
                    if (payload.descriptions) {
                        nodeDescriptions = payload.descriptions;
                    }
                    if (payload.sourceCodes) {
                        sourceCodes = payload.sourceCodes;
                    }
                    if (payload.edgeLabels) {
                        edgeLabels = payload.edgeLabels;
                    }

                }
                return; // Initialization payload processed. Do not pass to tick logic.
            }

            // Branch 2: High Frequency Tick Logic (10Hz+)
            if (payload.type === 'tick' && isGraphRendered) {

                // Real-time Stabilizations / sec using a sliding window
                let currentHz = 0;
                const now = performance.now();
                messageTimes.push(now);
                if (messageTimes.length > 50) {
                    messageTimes.shift();
                }
                if (messageTimes.length > 1) {
                    const elapsedSc = (now - messageTimes[0]) / 1000.0;
                    currentHz = Math.round((messageTimes.length - 1) / elapsedSc);
                }

                if (!payload.metrics) payload.metrics = {};
                payload.metrics.frontendHz = currentHz;

                // Ensure updateMetricsDOM runs smoothly without `prevValues` locking
                if (!isScrubbing) {
                    updateMetricsDOM(payload);
                }

                // Hot Path: Do NOT re-render Mermaid. Just manipulate the DOM.
                if (!isScrubbing) {
                    updateGraphDOM(payload.values);
                }
            }

            // Map epoch to real world time
            if (!epochToRealTime.has(payload.epoch)) {
                epochToRealTime.set(payload.epoch, Date.now());
                if (oldestEpochTracker === -1) {
                    oldestEpochTracker = payload.epoch;
                }
                // Cleanup old mappings precisely matched to the historical timeline buffer
                while (payload.epoch - oldestEpochTracker > globalHistoryDepth) {
                    epochToRealTime.delete(oldestEpochTracker);
                    oldestEpochTracker++;
                }
            }

            // Cache historical state uniformly ensuring precise timeline matches using an amortized double buffer
            snapshots.push(payload);

            if (!isScrubbing) {
                if (domTimeScrubber) {
                    domTimeScrubber.max = snapshots.size - 1;
                    domTimeScrubber.value = snapshots.size - 1;
                }
                if (domTimelineEpoch) domTimelineEpoch.textContent = 'Epoch: ' + payload.epoch;
            }

            // Alert engine evaluation over latest data
            if (!isScrubbing && activeAlerts.length > 0) {
                let anyAlertTriggered = false;
                let alertsToRemove = [];
                // Fast-Path: Only parse numbers once per-node, per-tick, 
                // regardless of how many discrete alerts listen to the same node!
                const parsedValues = new Map();

                for (let i = 0; i < activeAlerts.length; i++) {
                    const alert = activeAlerts[i];
                    const triggerValStr = payload.values[alert.node];

                    if (triggerValStr && triggerValStr !== 'NaN') {
                        // Retrieve from cache or parse dynamically
                        let valNum = parsedValues.get(alert.node);
                        if (valNum === undefined) {
                            valNum = parseFloat(triggerValStr);
                            parsedValues.set(alert.node, valNum);
                        }

                        let triggered = false;

                        if (alert.condition === '<' && valNum < alert.threshold) triggered = true;
                        else if (alert.condition === '>' && valNum > alert.threshold) triggered = true;
                        else if (alert.condition === '=' && valNum === alert.threshold) triggered = true;

                        if (triggered) {
                            anyAlertTriggered = true;

                            // Emit Notification (deduplicated per alert)
                            if (Notification.permission === "granted" && (payload.epoch - alert.lastNotifiedEpoch > 20 || alert.lastNotifiedEpoch === -1)) {
                                new Notification("Alert: Target Breached!", {
                                    body: `${alert.node} ${alert.condition} ${alert.threshold} (Val: ${valNum.toFixed(4)})`
                                });
                                alert.lastNotifiedEpoch = payload.epoch;
                            }

                            // Always Record to History
                            const timeStr = new Date().toLocaleTimeString();
                            const msg = `${alert.node} ${alert.condition} ${alert.threshold} (Hit: ${valNum.toFixed(4)})`;
                            alertHistory.unshift({ id: `${Date.now()}-${alert.node}`, time: timeStr, epoch: payload.epoch, message: msg });
                            if (alertHistory.length > 50) alertHistory.pop(); // Cap history size

                            saveAlertState();
                            renderAlertHistory();

                            alertsToRemove.push(alert.id);
                        } else {
                            // Reset deduplication if price normalizes
                            alert.lastNotifiedEpoch = -1;
                        }
                    }
                }

                // Prepare modal text if any fired
                let triggeredMessages = [];

                if (anyAlertTriggered) {
                    for (let i = 0; i < alertsToRemove.length; i++) {
                        const dismissedId = alertsToRemove[i];
                        const a = activeAlerts.find(al => al.id === dismissedId);
                        if (a) {
                            triggeredMessages.push(`${a.node} ${a.condition} ${a.threshold}`);
                        }
                    }


                    // Prevent overriding if already showing to allow manual dismissal
                    if (domAlertModal && domAlertModal.classList.contains('hidden')) {
                        domAlertModalText.innerHTML = triggeredMessages.join('<br>');
                        domAlertModal.classList.remove('hidden');
                    }
                }

                // Prune ONCE alerts that fired
                if (alertsToRemove.length > 0) {
                    activeAlerts = activeAlerts.filter(a => !alertsToRemove.includes(a.id));
                    saveAlertState();
                    renderActiveAlerts();
                }

            }

            // Live History Tracking & Chart Updating
            // The Java backend pushes updates every epoch.
            for (const [key, cs] of activeChartSeries.entries()) {
                const val = payload.values[key];
                if (val === undefined) continue;

                if (!nodeHistory.has(key)) {
                    nodeHistory.set(key, new RingBuffer(globalHistoryDepth));
                    nodeNaNHistory.set(key, new RingBuffer(globalHistoryDepth));
                }
                const historyArr = nodeHistory.get(key);
                const nanArr = nodeNaNHistory.get(key);

                const isNaNVal = (val === 'NaN' || Number.isNaN(Number(val)));

                // Bridge the graph using the last known good value if current payload is NaN
                let plotVal = val;
                if (isNaNVal) {
                    plotVal = historyArr.size > 0 ? historyArr.last().value : 0;
                }

                const point = { time: payload.epoch, value: plotVal };

                historyArr.push(point);

                // Track continuous NaN statuses for the chart's red overlay background
                if (isNaNVal) {
                    const nanPoint = { time: payload.epoch, value: 1 };
                    nanArr.push(nanPoint);
                } else {
                    nanArr.push({ time: payload.epoch, value: NaN });
                }

                // Explicitly push the live tick
                try {
                    if (!isScrubbing) {
                        if (cs.lineSeries) cs.lineSeries.update(point);
                        if (cs.nanSeries && isNaNVal) cs.nanSeries.update({ time: payload.epoch, value: 1 });
                    }
                } catch (e) {
                    document.getElementById('chart-title').textContent = "UPD ERR: " + e.message;
                }
            }
        } catch (error) {
            console.error(error);
            document.body.innerHTML += '<div style="color:red; background:white; font-size: 24px; position:fixed; z-index:9999; top:10px; left:10px;">ERROR: ' + error.message + ' <br/> ' + error.stack + '</div>';
        }
    };
}

// Formats number to N decimal places
function formatVal(val, places = 4) {
    if (val === undefined || val === null) return "0.00";
    return Number(val).toFixed(places);
}

// Globals for Profile Table Sorting
let latestProfileData = [];
let profileSortCol = 'evaluations';
let profileSortAsc = false;

// Update the Top 5 Node Profile table dynamically
function updateProfileTable(profileArray) {
    latestProfileData = profileArray;
    renderProfileTable();
}

function renderProfileTable() {
    if (!latestProfileData || latestProfileData.length === 0) return;

    // Apply sorting
    const sortedData = [...latestProfileData].sort((a, b) => {
        let valA, valB;
        switch (profileSortCol) {
            case 'name':
                valA = a.name;
                valB = b.name;
                break;
            case 'latest':
                valA = Number(a.latest) || 0;
                valB = Number(b.latest) || 0;
                break;
            case 'avg':
                valA = Number(a.avg) || 0;
                valB = Number(b.avg) || 0;
                break;
            case 'updates':
                valA = Number(a.evaluations) || 0;
                valB = Number(b.evaluations) || 0;
                break;
            case 'nans':
                valA = Number(a.nans) || 0;
                valB = Number(b.nans) || 0;
                break;
            default:
                valA = Number(a.evaluations) || 0;
                valB = Number(b.evaluations) || 0;
        }

        if (valA < valB) return profileSortAsc ? -1 : 1;
        if (valA > valB) return profileSortAsc ? 1 : -1;
        return 0;
    });

    // Zero-Allocation Fast Path:
    // If the table already has the exact number of rows, just update the text nodes in-place.
    const rows = profileBody.children;
    if (rows.length === sortedData.length) {
        for (let i = 0; i < sortedData.length; i++) {
            const node = sortedData[i];
            const cells = rows[i].children;

            let displayName = node.name;
            if (displayName.length > 20) {
                displayName = displayName.substring(0, 17) + '...';
            }

            cells[0].textContent = displayName;
            cells[0].title = node.name;
            // Ensure click handler is attached
            cells[0].onclick = () => openDetailsModal(node.name);
            cells[0].className = 'clickable-node';

            cells[1].textContent = node.evaluations;
            cells[2].textContent = formatVal(node.latest, 2);
            cells[3].textContent = formatVal(node.avg, 2);
            cells[4].textContent = node.nans;
        }
    } else {
        // Initialization or length change: rebuild the DOM once
        let html = '';
        for (const node of sortedData) {
            let displayName = node.name;
            if (displayName.length > 20) {
                displayName = displayName.substring(0, 17) + '...';
            }

            html += `
                <tr>
                    <td title="${node.name}" class="clickable-node" onclick="openDetailsModal('${node.name}')">${displayName}</td>
                    <td class="right">${node.evaluations}</td>
                    <td class="right">${formatVal(node.latest, 2)}</td>
                    <td class="right">${formatVal(node.avg, 2)}</td>
                    <td class="right">${node.nans}</td>
                </tr>
            `;
        }
        profileBody.innerHTML = html;
    }

    // Recalculate accordion height so rows aren't clipped visually if it's open
    const content = document.getElementById('content-node-metrics');
    if (content.style.maxHeight && content.style.maxHeight !== '0px') {
        content.style.maxHeight = content.scrollHeight + 'px';
    }
}

// Bind sorting listeners on initial load
document.addEventListener('DOMContentLoaded', () => {
    const tableHeaders = document.querySelectorAll('#profile-table th');
    tableHeaders.forEach(th => {
        // Add pointer cursor to indicate clickability
        th.style.cursor = 'pointer';
        th.title = 'Click to sort';

        th.addEventListener('click', () => {
            const colName = th.getAttribute('data-col');
            if (profileSortCol === colName) {
                profileSortAsc = !profileSortAsc;
            } else {
                profileSortCol = colName;
                profileSortAsc = false; // default to descending for new columns (makes sense for latency/count)
            }

            // Update header UI carets
            tableHeaders.forEach(h => h.innerHTML = h.innerHTML.replace(/ [▲▼]/g, ''));
            th.innerHTML += profileSortAsc ? ' ▲' : ' ▼';

            renderProfileTable();
        });
    });

    const modalCloseBtn = document.getElementById('alert-modal-close');
    if (modalCloseBtn) {
        modalCloseBtn.addEventListener('click', () => {
            const modal = document.getElementById('alert-modal');
            modal.classList.add('hidden');
        });
    }
});

// Sanitize name to match Mermaid's internal ID generator
function getMermaidNodeId(nodeName) {
    if (!nodeName) return "";
    return nodeName.replace(/[^a-zA-Z0-9_]/g, "_");
}

// Fast path DOM update
function updateGraphDOM(newValues) {
    for (const [nodeName, newVal] of Object.entries(newValues)) {
        const oldVal = prevValues.get(nodeName);

        // Only touch the DOM if the value ACTUALLY changed contextually
        if (oldVal !== newVal) {
            const cachedDOM = graphElementsCache.get(nodeName);
            if (cachedDOM && cachedDOM.instances && cachedDOM.instances.length > 0) {
                const isValNaN = newVal === "NaN";

                if (isValNaN) {
                    cachedDOM.lsPaths.forEach(e => e.dataset.sourceNan = "true");
                    cachedDOM.lePaths.forEach(e => e.dataset.targetNan = "true");
                } else {
                    cachedDOM.lsPaths.forEach(e => e.dataset.sourceNan = "false");
                    cachedDOM.lePaths.forEach(e => e.dataset.targetNan = "false");
                }

                // Determine if it's an Array representation like "[4.7, 4.8]"
                let displayVal = "NaN";
                if (!isValNaN) {
                    if (typeof newVal === 'string' && newVal.startsWith('[') && newVal.endsWith(']')) {
                        try {
                            const arr = JSON.parse(newVal);
                            const headersStr = newValues[nodeName + "_headers"];

                            if (headersStr && headersStr.startsWith('[') && headersStr.endsWith(']')) {
                                const headersArray = JSON.parse(headersStr);
                                const maxItems = 2;
                                const items = arr.slice(0, maxItems).map((v, i) => {
                                    const h = (i < headersArray.length) ? headersArray[i] : i;
                                    return `${h}: ${formatVal(v)}`;
                                });
                                if (arr.length > maxItems) {
                                    displayVal = "[" + items.join(", ") + ", ...]";
                                } else {
                                    displayVal = "[" + items.join(", ") + "]";
                                }
                            } else {
                                const maxItems = 2;
                                const items = arr.slice(0, maxItems).map((v, i) => `${i}: ${formatVal(v)}`);
                                if (arr.length > maxItems) {
                                    displayVal = "[" + items.join(", ") + ", ...]";
                                } else {
                                    displayVal = "[" + items.join(", ") + "]";
                                }
                            }
                        } catch (e) {
                            displayVal = newVal;
                        }
                    } else {
                        displayVal = formatVal(newVal);
                    }
                }

                if (activeDetailsNode === nodeName && detailsVectorSeries) {
                    try {
                        const arr = typeof newVal === 'string' ? JSON.parse(newVal) : newVal;
                        if (Array.isArray(arr)) {
                            const pointData = arr.map((v, i) => {
                                return { time: i + 1, value: Number.isNaN(Number(v)) || v === "NaN" || v === null ? NaN : Number(v) };
                            });
                            detailsVectorSeries.setData(pointData);
                        }
                    } catch (e) { }
                }

                cachedDOM.instances.forEach(instance => {
                    const nodeGroup = instance.group;

                    if (isValNaN) {
                        nodeGroup.classList.add('nan-node');
                    } else {
                        nodeGroup.classList.remove('nan-node');
                    }

                    if (instance.valueEl) {
                        instance.valueEl.textContent = displayVal;
                    } else if (instance.textNodesFallback && instance.textNodesFallback.length > 1) {
                        instance.textNodesFallback[instance.textNodesFallback.length - 1].textContent = displayVal;
                    }

                    // Trigger CSS flash animation
                    nodeGroup.classList.remove('node-flash');
                    // Force reflow to restart animation
                    void nodeGroup.offsetWidth;
                    nodeGroup.classList.add('node-flash');
                });
            }

            // Also update any details panel ticks
            const detailsTicks = document.querySelectorAll(`.details-value-tick[data-node-id="${nodeName}"]`);
            if (detailsTicks.length > 0) {
                // Formatting for display in sidebar - we can just use the DOM string if parsed, or format newVal
                let tickVal = newVal;
                if (typeof tickVal === 'string' && tickVal.startsWith('[') && tickVal.endsWith(']')) {
                    tickVal = "[Vector]"; // Simplify for sidebar view
                } else if (tickVal !== "NaN" && !Number.isNaN(Number(tickVal))) {
                    tickVal = Number(tickVal).toFixed(4);
                }
                detailsTicks.forEach(span => span.textContent = tickVal);
            }

            prevValues.set(nodeName, newVal);
        }
    }
}

// Recursively find all downstream components
function getDownstreamNodes(nodeName, visited = new Set()) {
    if (visited.has(nodeName)) return [];
    visited.add(nodeName);
    const children = graphRouting[nodeName] || [];
    let allDownstream = [nodeName, ...children]; // Includes the node itself
    for (const child of children) {
        allDownstream = allDownstream.concat(getDownstreamNodes(child, visited));
    }
    return Array.from(new Set(allDownstream));
}

// Recursively find all upstream components
function getUpstreamNodes(nodeName, visited = new Set()) {
    if (visited.has(nodeName)) return [];
    visited.add(nodeName);
    const parents = reverseGraphRouting[nodeName] || [];
    let allUpstream = [nodeName, ...parents];
    for (const parent of parents) {
        allUpstream = allUpstream.concat(getUpstreamNodes(parent, visited));
    }
    return Array.from(new Set(allUpstream));
}

// Apply or remove hover CSS mathematically 
function highlightNodes(nodeNames, isHovered, type = 'downstream', lineageSet = null) {
    for (const name of nodeNames) {
        const cachedDOM = graphElementsCache.get(name);
        if (!cachedDOM) continue;

        if (cachedDOM.instances) {
            cachedDOM.instances.forEach(instance => {
                const nodeGroup = instance.group;
                if (nodeGroup) {
                    if (isHovered) {
                        nodeGroup.classList.add(`node-hover-${type}`);
                    } else {
                        nodeGroup.classList.remove(`node-hover-${type}`);
                    }
                }
            });
        }

        cachedDOM.lsPaths.forEach(e => {
            let shouldHighlight = isHovered;
            if (isHovered && lineageSet) {
                let targetInLineage = false;
                for (const lineageNode of lineageSet) {
                    if (e.classList.contains('LE-' + getMermaidNodeId(lineageNode))) {
                        targetInLineage = true;
                        break;
                    }
                }
                shouldHighlight = targetInLineage;
            }
            e.dataset[`sourceHover${type.charAt(0).toUpperCase() + type.slice(1)}`] = shouldHighlight ? "true" : "false";
        });
        cachedDOM.lePaths.forEach(e => {
            let shouldHighlight = isHovered;
            if (isHovered && lineageSet) {
                let sourceInLineage = false;
                for (const lineageNode of lineageSet) {
                    if (e.classList.contains('LS-' + getMermaidNodeId(lineageNode))) {
                        sourceInLineage = true;
                        break;
                    }
                }
                shouldHighlight = sourceInLineage;
            }
            e.dataset[`targetHover${type.charAt(0).toUpperCase() + type.slice(1)}`] = shouldHighlight ? "true" : "false";
        });
    }
}

// Bind native browser pointer events onto the underlying DOM structure generated by Mermaid
function attachHoverListeners() {
    for (const nodeName of prevValues.keys()) {
        const cachedDOM = graphElementsCache.get(nodeName);
        if (!cachedDOM || !cachedDOM.instances) continue;

        cachedDOM.instances.forEach(instance => {
            const nodeGroup = instance.group;
            if (!nodeGroup) return;

            nodeGroup.addEventListener('mouseenter', () => {
                const downstream = getDownstreamNodes(nodeName).filter(n => n !== nodeName);
                const upstream = getUpstreamNodes(nodeName).filter(n => n !== nodeName);
                const lineage = new Set([...downstream, ...upstream, nodeName]);

                highlightNodes(downstream, true, 'downstream', lineage);
                highlightNodes(upstream, true, 'upstream', lineage);
                highlightNodes([nodeName], true, 'self', lineage);

                const graphView = document.getElementById('graph-view');
                if (graphView) graphView.classList.add('graph-hover-active');
            });
            nodeGroup.addEventListener('mouseleave', () => {
                const downstream = getDownstreamNodes(nodeName).filter(n => n !== nodeName);
                const upstream = getUpstreamNodes(nodeName).filter(n => n !== nodeName);

                highlightNodes(downstream, false, 'downstream');
                highlightNodes(upstream, false, 'upstream');
                highlightNodes([nodeName], false, 'self');

                const graphView = document.getElementById('graph-view');
                if (graphView) graphView.classList.remove('graph-hover-active');
            });
            nodeGroup.addEventListener('contextmenu', (e) => {
                e.preventDefault(); // Prevent browser right-click menu
                const ctxMenu = document.getElementById('node-context-menu');
                if (ctxMenu) {
                    const addChartBtn = document.getElementById('ctx-add-chart');
                    if (addChartBtn) {
                        if (activeChartSeries.has(nodeName)) {
                            addChartBtn.textContent = 'Remove Chart';
                        } else {
                            addChartBtn.textContent = 'Add Chart';
                        }
                    }
                    ctxMenu.style.left = `${e.clientX}px`;
                    ctxMenu.style.top = `${e.clientY}px`;
                    ctxMenu.classList.remove('hidden');
                    ctxMenu.dataset.contextNode = nodeName;
                }
            });
            // We use mouseup instead of click, because svg-pan-zoom intercepts click loops
            // if it thinks a pan or drag happened, but mouseup is cleaner.
            nodeGroup.addEventListener('mouseup', (e) => {
                if (e.button !== 0) return; // Only trigger for primary (left) button

                // Also explicitly hide the context menu if they left-click the node itself
                const ctxMenu = document.getElementById('node-context-menu');
                if (ctxMenu) ctxMenu.classList.add('hidden');
                openDetailsModal(nodeName);
            });
            // Make it obviously clickable
            nodeGroup.style.cursor = 'pointer';
        });
    }
}

// ============================================================================
// Lightweight Charts Logic
// ============================================================================

function initChart() {
    const chartContainer = document.getElementById('tv-chart');
    if (!chartContainer) return;

    chartInstance = LightweightCharts.createChart(chartContainer, {
        layout: {
            background: { type: 'solid', color: 'transparent' },
            textColor: '#94a3b8',
        },
        grid: {
            vertLines: { color: 'rgba(255, 255, 255, 0.05)' },
            horzLines: { color: 'rgba(255, 255, 255, 0.05)' },
        },
        crosshair: {
            mode: LightweightCharts.CrosshairMode.Normal,
        },
        rightPriceScale: {
            borderColor: 'rgba(255, 255, 255, 0.1)',
            visible: true,
            autoScale: true,
        },
        timeScale: {
            borderColor: 'rgba(255, 255, 255, 0.1)',
            visible: true,
            timeVisible: true,
            secondsVisible: true,
            rightOffset: 12,
            barSpacing: 15,
            tickMarkFormatter: (time, tickMarkType, locale) => {
                const realTimeMs = epochToRealTime.get(time) || Date.now();
                const d = new Date(realTimeMs);
                const hh = d.getHours().toString().padStart(2, '0');
                const mm = d.getMinutes().toString().padStart(2, '0');
                const ss = d.getSeconds().toString().padStart(2, '0');
                return `${hh}:${mm}:${ss}`;
            }
        },
        localization: {
            timeFormatter: (time) => {
                const realTimeMs = epochToRealTime.get(time) || Date.now();
                const d = new Date(realTimeMs);
                const hh = d.getHours().toString().padStart(2, '0');
                const mm = d.getMinutes().toString().padStart(2, '0');
                const ss = d.getSeconds().toString().padStart(2, '0');
                return `${hh}:${mm}:${ss}`;
            }
        }
    });




    // Track manual panning
    chartInstance.timeScale().subscribeVisibleTimeRangeChange(range => {
        if (!range || isScrubbing) return;
        const maxEpoch = snapshots.length > 0 ? snapshots[snapshots.length - 1].epoch : 0;
        const btnAutoTrack = document.getElementById('btn-auto-track');
        if (btnAutoTrack) {
            // Show the button if the user has scrolled left such that the latest point is off screen
            if (range.to < maxEpoch) {
                btnAutoTrack.style.display = 'flex';
            } else {
                btnAutoTrack.style.display = 'none';
            }
        }
    });

    // Dynamic Resizing Observer
    const chartContent = document.getElementById('chart-content');
    const chartPanel = document.getElementById('chart-panel');
    const resizeObserver = new ResizeObserver(entries => {
        window.requestAnimationFrame(() => {
            if (chartInstance && chartContent.clientWidth > 0 && chartContent.clientHeight > 0) {
                chartInstance.resize(chartContent.clientWidth, chartContent.clientHeight);
            }
        });
    });
    resizeObserver.observe(chartContent);

    // Bring to front on click
    chartPanel.addEventListener('mousedown', () => {
        highestZIndex++;
        chartPanel.style.zIndex = highestZIndex;
    });

    // Draggable Logic
    const chartHeader = document.querySelector('.chart-header');
    let isDragging = false;
    let dragStartX = 0;
    let dragStartY = 0;
    let initialLeft = 0;
    let initialTop = 0;

    chartHeader.addEventListener('mousedown', (e) => {
        if (e.target.id === 'close-chart') return; // Ignore close button
        isDragging = true;
        dragStartX = e.clientX;
        dragStartY = e.clientY;

        const rect = chartPanel.getBoundingClientRect();
        initialLeft = rect.left;
        initialTop = rect.top;

        // Strip transition animation so dragging is 1-to-1 crisp
        chartPanel.style.transition = 'none';
        chartPanel.style.transform = 'none';
        chartPanel.style.left = `${initialLeft}px`;
        chartPanel.style.top = `${initialTop}px`;
    });

    document.addEventListener('mousemove', (e) => {
        if (!isDragging) return;
        const dx = e.clientX - dragStartX;
        const dy = e.clientY - dragStartY;
        chartPanel.style.left = `${initialLeft + dx}px`;
        chartPanel.style.top = `${initialTop + dy}px`;
    });

    document.addEventListener('mouseup', () => {
        if (isDragging) {
            isDragging = false;
            chartPanel.style.transition = 'opacity 0.3s ease';
        }
    });
}

document.getElementById('toggle-metrics').addEventListener('click', () => {
    const metricsContent = document.getElementById('metrics-content');
    const panel = document.getElementById('metrics-panel');
    const btn = document.getElementById('toggle-metrics');

    if (metricsContent.style.display === 'none') {
        metricsContent.style.display = '';
        btn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>';
        btn.title = 'Minimize';
    } else {
        metricsContent.style.display = 'none';
        btn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg>';
        btn.title = 'Expand';
    }
});

// Setup Dashboard Header Collapsibility
document.querySelectorAll('.metrics-panel h3[id^="header-"]').forEach(header => {
    header.addEventListener('click', () => {
        const contentId = header.id.replace('header-', 'content-');
        const contentDiv = document.getElementById(contentId);
        if (contentDiv) {
            if (contentDiv.style.display === 'none') {
                contentDiv.style.display = '';
                header.querySelector('span').textContent = '▼';
            } else {
                contentDiv.style.display = 'none';
                header.querySelector('span').textContent = '▶';
            }
        }
    });
});

// Support code moved to top of file inside the combined DOMContentLoaded block.

// Setup Alerting UI Handlers
function renderActiveAlerts() {
    const tbody = document.getElementById('active-alerts-body');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (activeAlerts.length === 0) {
        tbody.innerHTML = `<tr><td colspan="3" style="text-align: center; color: var(--text-secondary); py-2">No active alerts</td></tr>`;
        return;
    }

    activeAlerts.forEach(alert => {
        const tr = document.createElement('tr');

        const ruleTd = document.createElement('td');
        ruleTd.innerHTML = `<span style="color:var(--text-primary)">${alert.node}</span> ${alert.condition} ${alert.threshold}`;



        const actionTd = document.createElement('td');
        const delBtn = document.createElement('button');
        delBtn.className = 'delete-alert-btn';
        delBtn.innerHTML = '×';
        delBtn.title = 'Delete Alert';
        delBtn.onclick = () => {
            activeAlerts = activeAlerts.filter(a => a.id !== alert.id);
            saveAlertState();
            renderActiveAlerts();
        };
        actionTd.appendChild(delBtn);

        tr.appendChild(ruleTd);
        tr.appendChild(actionTd);
        tbody.appendChild(tr);
    });
}

function renderAlertHistory() {
    const tbody = document.getElementById('alert-history-body');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (alertHistory.length === 0) {
        tbody.innerHTML = `<tr><td colspan="3" style="text-align: center; color: var(--text-secondary); py-2">No trigger history</td></tr>`;
        return;
    }

    alertHistory.forEach((hist, index) => {
        const tr = document.createElement('tr');
        const timeTd = document.createElement('td');
        timeTd.textContent = hist.time;
        timeTd.style.color = "var(--text-secondary)";
        timeTd.style.whiteSpace = "nowrap";

        const epochTd = document.createElement('td');
        epochTd.textContent = hist.epoch;
        epochTd.style.color = "var(--text-secondary)";
        epochTd.style.fontFamily = "monospace";

        const msgTd = document.createElement('td');
        msgTd.textContent = hist.message;
        msgTd.style.color = "var(--accent-red)";

        const actionTd = document.createElement('td');
        const delBtn = document.createElement('button');
        delBtn.className = 'delete-alert-btn';
        delBtn.innerHTML = '×';
        delBtn.title = 'Delete History Record';
        delBtn.onclick = () => {
            if (hist.id) {
                alertHistory = alertHistory.filter(h => h.id !== hist.id);
            } else {
                // Fallback for older localStorage records without IDs
                alertHistory.splice(index, 1);
            }
            saveAlertState();
            renderAlertHistory();
        };
        actionTd.appendChild(delBtn);

        tr.appendChild(timeTd);
        tr.appendChild(epochTd);
        tr.appendChild(msgTd);
        tr.appendChild(actionTd);
        tbody.appendChild(tr);
    });
}

document.addEventListener('DOMContentLoaded', () => {
    renderActiveAlerts();
    renderAlertHistory();

    const alertBtn = document.getElementById('alert-set-btn');
    if (alertBtn) {
        alertBtn.addEventListener('click', () => {
            const node = document.getElementById('alert-node').value;
            const condition = document.getElementById('alert-condition').value;
            const threshStr = document.getElementById('alert-threshold').value;

            if (!node || !condition || threshStr === '') {
                return;
            }

            const newAlert = {
                id: ++alertIdCounter,
                node: node,
                condition: condition,
                threshold: parseFloat(threshStr),
                lastNotifiedEpoch: -1
            };

            activeAlerts.push(newAlert);
            saveAlertState();
            renderActiveAlerts();

            // Clear input box
            document.getElementById('alert-threshold').value = '';

            // Request Notification permission
            if ("Notification" in window && Notification.permission !== "granted" && Notification.permission !== "denied") {
                Notification.requestPermission();
            }
        });
    }
});
function saveChartSelection() {
    localStorage.setItem('activeChartSeries', JSON.stringify(Array.from(activeChartSeries.keys())));
}

function openChart(nodeId) {
    const chartPanel = document.getElementById('chart-panel');
    const bottomDock = document.getElementById('bottom-dock');
    const toggleBtn = document.getElementById('toggle-chart');
    if (chartPanel && chartPanel.classList.contains('minimized')) {
        chartPanel.classList.remove('minimized');
        if (bottomDock) bottomDock.classList.remove('minimized');
        if (toggleBtn) toggleBtn.textContent = '-';
        setTimeout(() => {
            if (chartInstance) chartInstance.timeScale().fitContent();
        }, 50);
    }

    if (activeChartSeries.has(nodeId)) {
        // Toggle OFF if already active
        const cs = activeChartSeries.get(nodeId);
        if (cs.lineSeries) chartInstance.removeSeries(cs.lineSeries);
        if (cs.nanSeries) chartInstance.removeSeries(cs.nanSeries);
        activeChartSeries.delete(nodeId);

        // Free memory immediately
        nodeHistory.delete(nodeId);
        nodeNaNHistory.delete(nodeId);

        if (activeChartSeries.size === 0) {
            closeChart();
            return;
        }
    } else {
        // Toggle ON
        const color = CHART_COLORS[chartColorIndex % CHART_COLORS.length];
        chartColorIndex++;

        const nanS = chartInstance.addHistogramSeries({
            color: 'rgba(239, 68, 68, 0.2)',
            priceFormat: { type: 'volume' },
            priceScaleId: '', // overlay without attaching to a visible scale
            scaleMargins: { top: 0, bottom: 0 },
        });

        const lineS = chartInstance.addLineSeries({
            color: color,
            lineWidth: 2,
            crosshairMarkerVisible: true,
            lastValueVisible: true,
            priceLineVisible: true,
        });

        activeChartSeries.set(nodeId, { lineSeries: lineS, nanSeries: nanS, color: color });

        // Lazy Hydration: Build the history array from the snapshots master cache 
        // ONLY when a user opens the chart, saving massive background memory.
        if (!nodeHistory.has(nodeId)) {
            const histBuf = new RingBuffer(globalHistoryDepth);
            const nanBuf = new RingBuffer(globalHistoryDepth);

            for (let i = 0; i < snapshots.size; i++) {
                const snap = snapshots.get(i);
                if (!snap || !snap.values) continue;

                const val = snap.values[nodeId];
                if (val === undefined) continue;

                const isNaNVal = (val === 'NaN' || Number.isNaN(Number(val)));
                let plotVal = val;
                if (isNaNVal) {
                    plotVal = histBuf.size > 0 ? histBuf.last().value : 0;
                }

                histBuf.push({ time: snap.epoch, value: plotVal });

                if (isNaNVal) {
                    nanBuf.push({ time: snap.epoch, value: 1 });
                } else {
                    nanBuf.push({ time: snap.epoch, value: NaN });
                }
            }
            nodeHistory.set(nodeId, histBuf);
            nodeNaNHistory.set(nodeId, nanBuf);
        }

        try {
            const arr = nodeHistory.get(nodeId).toArray();
            const nanArr = nodeNaNHistory.get(nodeId).toArray();

            if (isScrubbing) {
                const scrubberIdx = parseInt(document.getElementById('time-scrubber').value);
                lineS.setData(arr.slice(0, scrubberIdx + 1));
                if (snapshots.get(scrubberIdx)) {
                    const maxEpoch = snapshots.get(scrubberIdx).epoch;
                    nanS.setData(nanArr.filter(p => p.time <= maxEpoch));
                }
            } else {
                lineS.setData(arr);
                nanS.setData(nanArr);
            }
        } catch (e) {
            document.getElementById('chart-title').textContent = "SET ERR: " + e.message;
        }
    }

    // Update title to show all active charts (with colored blocks) and add click-to-remove listener
    const chartTitleContainer = document.getElementById('chart-title');
    chartTitleContainer.innerHTML = '';
    for (const [id, cs] of activeChartSeries.entries()) {
        const span = document.createElement('span');
        span.style.color = cs.color;
        span.style.marginRight = '10px';
        span.style.cursor = 'pointer';
        span.title = `Click to remove ${id} from chart`;
        span.innerHTML = `&#9632; ${id}`;
        span.onclick = () => openChart(id);
        chartTitleContainer.appendChild(span);
    }

    document.getElementById('chart-panel').classList.remove('hidden');
    chartInstance.timeScale().fitContent();
    saveChartSelection();
}

function closeChart() {
    const chartPanel = document.getElementById('chart-panel');
    const bottomDock = document.getElementById('bottom-dock');
    if (chartPanel) chartPanel.classList.add('minimized');
    if (bottomDock) bottomDock.classList.add('minimized');

    // Wipe all chart series and free the lazy memory
    for (const [id, cs] of activeChartSeries.entries()) {
        if (cs.lineSeries) chartInstance.removeSeries(cs.lineSeries);
        if (cs.nanSeries) chartInstance.removeSeries(cs.nanSeries);
        nodeHistory.delete(id);
        nodeNaNHistory.delete(id);
    }

    activeChartSeries.clear();
    document.getElementById('chart-title').innerHTML = '';
    saveChartSelection();
}

// ============================================================================
// Historical Scrubbing Logic
// ============================================================================

if (tlStatus) {
    tlStatus.addEventListener('click', () => {
        if (!isScrubbing || snapshots.size === 0) return;

        // Jump back to Live unconditionally
        isScrubbing = false;
        tlStatus.textContent = "LIVE";
        tlStatus.className = "status-badge live";
        const btnAutoTrack = document.getElementById('btn-auto-track');
        if (btnAutoTrack) btnAutoTrack.style.display = 'none';

        const scrubber = document.getElementById('time-scrubber');
        if (scrubber) {
            scrubber.value = snapshots.size - 1;
        }

        const lastPayload = snapshots.last();
        document.getElementById('timeline-epoch').textContent = 'Epoch: ' + lastPayload.epoch;
        updateGraphDOM(lastPayload.values);
        updateMetricsDOM(lastPayload);
        renderScrubbedChartLive();
    });
}

const timeScrub = document.getElementById('time-scrubber');
if (timeScrub) {
    timeScrub.addEventListener('input', (e) => {
        isScrubbing = true;
        const idx = parseInt(e.target.value);

        if (idx >= snapshots.size - 1) {
            // Returned to Live
            isScrubbing = false;
            if (tlStatus) {
                tlStatus.textContent = "LIVE";
                tlStatus.className = "status-badge live";
            }
            const btnAutoTrack = document.getElementById('btn-auto-track');
            if (btnAutoTrack) btnAutoTrack.style.display = 'none';

            const lastPayload = snapshots.last();
            if (lastPayload) {
                document.getElementById('timeline-epoch').textContent = 'Epoch: ' + lastPayload.epoch;
                updateGraphDOM(lastPayload.values);
                updateMetricsDOM(lastPayload);
                renderScrubbedChartLive();
            }
        } else {
            // Scrubbing Mode
            document.getElementById('timeline-status').textContent = "REPLAY";
            document.getElementById('timeline-status').className = "status-badge replay";
            const btnAutoTrack = document.getElementById('btn-auto-track');
            if (btnAutoTrack) btnAutoTrack.style.display = 'flex';

            const targetPayload = snapshots.get(idx);
            if (targetPayload) {
                document.getElementById('timeline-epoch').textContent = 'Epoch: ' + targetPayload.epoch;
                updateGraphDOM(targetPayload.values);
                updateMetricsDOM(targetPayload);
                renderScrubbedChartScrubbed(idx);
            }
        }
    });
}

function renderScrubbedChartScrubbed(idx) {
    if (activeChartSeries.size === 0) return;
    for (const [nodeId, cs] of activeChartSeries.entries()) {
        const historyArr = nodeHistory.get(nodeId);
        if (historyArr && cs.lineSeries) {
            cs.lineSeries.setData(historyArr.toArray().slice(0, idx + 1));
        }
        const nanArr = nodeNaNHistory.get(nodeId);
        if (cs.nanSeries && nanArr && snapshots.get(idx)) {
            const maxEpoch = snapshots.get(idx).epoch;
            cs.nanSeries.setData(nanArr.toArray().filter(p => p.time <= maxEpoch));
        }
    }
}

function renderScrubbedChartLive() {
    if (activeChartSeries.size === 0) return;
    for (const [nodeId, cs] of activeChartSeries.entries()) {
        const historyArr = nodeHistory.get(nodeId);
        if (historyArr && cs.lineSeries) {
            cs.lineSeries.setData(historyArr.toArray());
        }
        const nanArr = nodeNaNHistory.get(nodeId);
        if (cs.nanSeries && nanArr) {
            cs.nanSeries.setData(nanArr.toArray());
        }
    }
}

// --- Omnidirectional Resizing Hook ---
function setupOmnidirectionalResize(panelId) {
    const panel = document.getElementById(panelId);
    if (!panel) return;

    const resizers = ['top', 'right', 'bottom', 'left', 'top-left', 'top-right', 'bottom-left', 'bottom-right'];

    resizers.forEach(pos => {
        const resizer = document.createElement('div');
        resizer.className = `resizer resizer-${pos}`;
        panel.appendChild(resizer);

        resizer.addEventListener('mousedown', function (e) {
            e.preventDefault();

            // Pop to front natively on drag start
            highestZIndex++;
            panel.style.zIndex = highestZIndex;

            // Lock dimensions and absolute positions
            const rect = panel.getBoundingClientRect();
            panel.style.transition = 'none';
            panel.style.transform = 'none'; // Overrides centered transform safely during JS tracking

            if (panel.style.right !== 'auto') panel.style.right = 'auto'; // Break CSS relative constraints
            if (panel.style.bottom !== 'auto') panel.style.bottom = 'auto';

            // Set explicit pixels
            panel.style.left = rect.left + 'px';
            panel.style.top = rect.top + 'px';
            panel.style.width = rect.width + 'px';
            panel.style.height = rect.height + 'px';

            const startX = e.clientX;
            const startY = e.clientY;
            const startWidth = rect.width;
            const startHeight = rect.height;
            const startLeft = rect.left;
            const startTop = rect.top;

            // Compute actual current minimum bounds securely from computed CSS
            const computedStyle = window.getComputedStyle(panel);
            const minWidth = parseInt(computedStyle.minWidth, 10) || 200;
            const minHeight = parseInt(computedStyle.minHeight, 10) || 150;

            function onMouseMove(moveEvent) {
                const dx = moveEvent.clientX - startX;
                const dy = moveEvent.clientY - startY;

                let newWidth = startWidth;
                let newHeight = startHeight;
                let newLeft = startLeft;
                let newTop = startTop;

                // Evaluate mapped offset calculations based on handle dragged natively
                if (pos.includes('right')) newWidth = startWidth + dx;
                if (pos.includes('left')) {
                    newWidth = startWidth - dx;
                    newLeft = startLeft + dx;
                }

                if (pos.includes('bottom')) newHeight = startHeight + dy;
                if (pos.includes('top')) {
                    newHeight = startHeight - dy;
                    newTop = startTop + dy;
                }

                // Minimum constraint anchoring mapping algorithms bounding the drag behavior natively
                if (newWidth < minWidth) {
                    if (pos.includes('left')) newLeft = startLeft + (startWidth - minWidth);
                    newWidth = minWidth;
                }

                if (newHeight < minHeight) {
                    if (pos.includes('top')) newTop = startTop + (startHeight - minHeight);
                    newHeight = minHeight;
                }

                panel.style.width = newWidth + 'px';
                panel.style.height = newHeight + 'px';
                panel.style.left = newLeft + 'px';
                panel.style.top = newTop + 'px';
            }

            function onMouseUp() {
                document.removeEventListener('mousemove', onMouseMove);
                document.removeEventListener('mouseup', onMouseUp);
                panel.style.transition = 'opacity 0.3s ease';
            }

            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        });
    });
}

// --- SNAPSHOT EXPORT ---
const btnSnapshot = document.getElementById('btn-snapshot');
if (btnSnapshot) {
    btnSnapshot.addEventListener('click', () => {
        window.open('/api/snapshot', '_blank');
    });
}

// --- IDE Split-Pane Docking Logic ---
function setupDockingControls() {
    const metricsPanel = document.getElementById('metrics-panel');
    const chartPanel = document.getElementById('chart-panel');
    const rightDock = document.getElementById('right-dock');
    const bottomDock = document.getElementById('bottom-dock');
    const workspace = document.querySelector('.workspace');

    // Dashboard Toggle Hook
    document.getElementById('toggle-metrics').addEventListener('click', (e) => {
        const isMinimized = metricsPanel.classList.toggle('minimized');

        if (metricsPanel.classList.contains('docked')) {
            rightDock.classList.toggle('minimized', isMinimized);
            // Clear explicit drag-resizer boundaries so CSS !important collapse kicks in natively
            if (isMinimized) {
                rightDock.style.width = '';
            }
        }

        const svgExpand = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg>';
        const svgMinimize = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>';
        e.target.innerHTML = isMinimized ? svgExpand : svgMinimize;
        e.target.title = isMinimized ? 'Expand' : 'Minimize';
    });

    // Chart VSCode Terminal Minimization Hook
    document.getElementById('toggle-chart').addEventListener('click', (e) => {
        chartPanel.classList.toggle('minimized');
        const isMinimized = chartPanel.classList.contains('minimized');
        bottomDock.classList.toggle('minimized', isMinimized);

        // Clear explicit drag-resizer boundaries 
        if (isMinimized) {
            bottomDock.style.height = '';
        }

        const svgExpand = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="18 15 12 9 6 15"></polyline></svg>';
        const svgMinimize = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg>';
        e.target.innerHTML = isMinimized ? svgExpand : svgMinimize;
        e.target.title = isMinimized ? 'Expand' : 'Minimize';
        if (chartInstance && !isMinimized) {
            setTimeout(() => chartInstance.timeScale().fitContent(), 10);
        }
    });
}

function setupDockResizers() {
    const rightDock = document.getElementById('right-dock');
    const rightResizer = document.getElementById('right-dock-resizer');
    const bottomDock = document.getElementById('bottom-dock');
    const bottomResizer = document.getElementById('bottom-dock-resizer');

    // Right Dock Resizing (Left Edge drag)
    if (rightResizer && rightDock) {
        let isResizingRight = false;
        rightResizer.addEventListener('mousedown', (e) => {
            if (rightDock.classList.contains('minimized')) return;
            isResizingRight = true;
            rightResizer.classList.add('active');
            document.body.style.cursor = 'col-resize';
            e.preventDefault();
        });
        document.addEventListener('mousemove', (e) => {
            if (!isResizingRight) return;
            const containerRect = document.body.getBoundingClientRect();
            let newWidth = containerRect.right - e.clientX;
            if (newWidth < 200) newWidth = 200;
            if (newWidth > 800) newWidth = 800;
            rightDock.style.width = newWidth + 'px';
        });
        document.addEventListener('mouseup', () => {
            if (isResizingRight) {
                isResizingRight = false;
                rightResizer.classList.remove('active');
                document.body.style.cursor = '';
                if (chartInstance) chartInstance.timeScale().fitContent();
            }
        });
    }

    // Bottom Dock Resizing (Top Edge drag)
    if (bottomResizer && bottomDock) {
        let isResizingBottom = false;
        bottomResizer.addEventListener('mousedown', (e) => {
            if (bottomDock.classList.contains('minimized')) return;
            isResizingBottom = true;
            bottomResizer.classList.add('active');
            document.body.style.cursor = 'row-resize';
            e.preventDefault();
        });
        document.addEventListener('mousemove', (e) => {
            if (!isResizingBottom) return;
            const dockRect = bottomDock.getBoundingClientRect();
            // Since bottom dock pushes up, height bounds equals its current bottom edge minus clientY.
            let newHeight = dockRect.bottom - e.clientY;
            if (newHeight < 300) newHeight = 300;
            if (newHeight > window.innerHeight * 0.8) newHeight = window.innerHeight * 0.8;
            bottomDock.style.height = newHeight + 'px';
        });
        document.addEventListener('mouseup', () => {
            if (isResizingBottom) {
                isResizingBottom = false;
                bottomResizer.classList.remove('active');
                document.body.style.cursor = '';
                if (chartInstance) chartInstance.timeScale().fitContent();
            }
        });
    }
}

// Init Context
window.addEventListener('DOMContentLoaded', () => {
    domTimeScrubber = document.getElementById('time-scrubber');
    domTimelineEpoch = document.getElementById('timeline-epoch');
    domAlertModal = document.getElementById('alert-modal');
    domAlertModalText = document.getElementById('alert-modal-text');

    initChart();
    setupOmnidirectionalResize('chart-panel');
    setupOmnidirectionalResize('metrics-panel');
    setupDockingControls();
    setupDockResizers();



    // Boot WebSocket strictly after DOM stabilizes
    connect();
});

// Graph Search functionality
document.addEventListener('DOMContentLoaded', () => {
    const searchInput = document.getElementById('node-search-input');
    const searchClear = document.getElementById('node-search-clear');

    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            const query = e.target.value.trim().toLowerCase();
            const svgEl = document.getElementById('graph-view');

            if (searchClear) {
                searchClear.style.display = query.length > 0 ? 'block' : 'none';
            }

            if (!query) {
                svgEl.classList.remove('graph-search-active');
                const matchEls = svgEl.querySelectorAll('.node-search-match');
                matchEls.forEach(el => el.classList.remove('node-search-match'));
                return;
            }

            svgEl.classList.add('graph-search-active');

            for (const [nodeName, cache] of graphElementsCache.entries()) {
                let typeStr = "";
                const titleStr = nodeName.toLowerCase();
                const props = window.graphProperties ? window.graphProperties[nodeName] : {};

                if (props && props.type) {
                    typeStr = String(props.type).toLowerCase();
                } else if (cache.instances && cache.instances.length > 0) {
                    const typeEl = cache.instances[0].group.querySelector('.node-type');
                    if (typeEl && typeEl.textContent) {
                        typeStr = typeEl.textContent.trim().toLowerCase();
                    }
                }

                const isMatch = titleStr.includes(query) || (typeStr && typeStr.includes(query));

                if (cache.instances) {
                    cache.instances.forEach(inst => {
                        if (isMatch) {
                            inst.group.classList.add('node-search-match');
                        } else {
                            inst.group.classList.remove('node-search-match');
                        }
                    });
                }
            }
        });

        if (searchClear) {
            searchClear.addEventListener('click', () => {
                searchInput.value = '';
                searchInput.dispatchEvent(new Event('input'));
                searchInput.focus();
            });
        }

        // Blur the search input if the user clicks outside of it
        // We use true for capture phase because svg-pan-zoom stops bubble propagation on SVG clicks
        document.addEventListener('mousedown', (e) => {
            if (document.activeElement === searchInput && !searchInput.contains(e.target) && (!searchClear || !searchClear.contains(e.target))) {
                searchInput.blur();
            }
        }, true);

        // Blur the search input if the user presses ESC
        searchInput.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' || e.keyCode === 27) {
                searchInput.blur();
            }
        });
    }
});
