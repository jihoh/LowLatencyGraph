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
// Map to keep track of previous values to know when to trigger the flash animation
const prevValues = new Map();
// Stores the direct children of each node: { "NodeA": ["NodeB", "NodeC"] }
let graphRouting = {};
let reverseGraphRouting = {}; // Stores direct parents: { "NodeB": ["NodeA"] }
// Array to track message arrival times for Stabilization Rate calculation
const messageTimes = [];
let panZoomInstance = null;

// Lightweight Charts Globals
const nodeHistory = new Map(); // Maps NodeID -> Array of {time: timestamp, value: number}
const nodeNaNHistory = new Map(); // Tracks discrete NaN timestamps for red highlighting
const epochToRealTime = new Map(); // Maps integer epochs -> actual JS millisecond timestamps
let oldestEpochTracker = -1; // O(1) tracking for cleaning up old epochs
const snapshots = [];

// Alert Engine State
let activeAlerts = []; // Array of { id, node, condition, threshold, mode, lastNotifiedEpoch }
let alertHistory = []; // Array of { time: string, message: string }
let alertIdCounter = 0;
let chartInstance = null;
const activeChartSeries = new Map(); // nodeId -> { lineSeries, nanSeries, color }
const CHART_COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899', '#06b6d4', '#eab308', '#ef4444'];
let chartColorIndex = 0;

// Global Z-Index tracker for draggable panels
let highestZIndex = 1000;
let isScrubbing = false;


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
                // Cleanup old mappings
                while (payload.epoch - oldestEpochTracker > 1000) {
                    epochToRealTime.delete(oldestEpochTracker);
                    oldestEpochTracker++;
                }
            }

            // Cache historical state uniformly ensuring precise 1:1 timeline matches
            snapshots.push(payload);

            if (!isScrubbing) {
                const scrubber = document.getElementById('time-scrubber');
                if (scrubber) {
                    scrubber.max = snapshots.length - 1;
                    scrubber.value = snapshots.length - 1;
                }
                const timelineEpoch = document.getElementById('timeline-epoch');
                if (timelineEpoch) timelineEpoch.textContent = 'Epoch: ' + payload.epoch;
            }

            // Alert engine evaluation over latest data
            if (!isScrubbing && activeAlerts.length > 0) {
                let anyAlertTriggered = false;
                let alertsToRemove = [];

                for (let i = 0; i < activeAlerts.length; i++) {
                    const alert = activeAlerts[i];
                    const triggerValStr = payload.values[alert.node];

                    if (triggerValStr && triggerValStr !== 'NaN') {
                        const valNum = parseFloat(triggerValStr);
                        let triggered = false;

                        if (alert.condition === '<' && valNum < alert.threshold) triggered = true;
                        else if (alert.condition === '>' && valNum > alert.threshold) triggered = true;
                        else if (alert.condition === '=' && valNum === alert.threshold) triggered = true;

                        if (triggered) {
                            anyAlertTriggered = true;

                            // Emit Notification (deduplicated per alert)
                            if (Notification.permission === "granted" && (payload.epoch - alert.lastNotifiedEpoch > 20 || alert.lastNotifiedEpoch === -1)) {
                                new Notification("DRG Alert: Target Breached!", {
                                    body: `${alert.node} ${alert.condition} ${alert.threshold} (Val: ${valNum.toFixed(4)})`
                                });
                                alert.lastNotifiedEpoch = payload.epoch;

                                // Record to History
                                const timeStr = new Date().toLocaleTimeString();
                                const msg = `${alert.node} ${alert.condition} ${alert.threshold} (Hit: ${valNum.toFixed(4)})`;
                                alertHistory.unshift({ time: timeStr, epoch: payload.epoch, message: msg });
                                if (alertHistory.length > 50) alertHistory.pop(); // Cap history size
                                renderAlertHistory();
                            }

                            if (alert.mode === 'ONCE') {
                                alertsToRemove.push(alert.id);
                            }
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
                    // For continuous mode, just capture the active ones
                    if (triggeredMessages.length === 0) {
                        activeAlerts.forEach(a => {
                            const valStr = payload.values[a.node];
                            if (valStr !== 'NaN') {
                                const v = parseFloat(valStr);
                                if ((a.condition === '<' && v < a.threshold) || (a.condition === '>' && v > a.threshold) || (a.condition === '=' && v === a.threshold)) {
                                    triggeredMessages.push(`${a.node} ${a.condition} ${a.threshold} (Current: ${v.toFixed(4)})`);
                                }
                            }
                        });
                    }

                    const modal = document.getElementById('alert-modal');
                    const modalText = document.getElementById('alert-modal-text');

                    // Prevent overriding if already showing to allow manual dismissal
                    if (modal.classList.contains('hidden')) {
                        modalText.innerHTML = triggeredMessages.join('<br>');
                        modal.classList.remove('hidden');
                    }
                }

                // Prune ONCE alerts that fired
                if (alertsToRemove.length > 0) {
                    activeAlerts = activeAlerts.filter(a => !alertsToRemove.includes(a.id));
                    renderActiveAlerts();
                }

            }

            // Live History Tracking & Chart Updating
            // The Java backend pushes updates every epoch.
            for (const [key, val] of Object.entries(payload.values)) {
                if (!nodeHistory.has(key)) {
                    nodeHistory.set(key, []);
                    nodeNaNHistory.set(key, []);
                }
                const historyArr = nodeHistory.get(key);
                const nanArr = nodeNaNHistory.get(key);

                const isNaNVal = (val === 'NaN' || Number.isNaN(Number(val)));

                // Bridge the graph using the last known good value if current payload is NaN
                let plotVal = val;
                if (isNaNVal) {
                    plotVal = historyArr.length > 0 ? historyArr[historyArr.length - 1].value : 0;
                }

                const point = { time: payload.epoch, value: plotVal };
                historyArr.push(point);

                // Track continuous NaN statuses for the chart's red overlay background
                if (isNaNVal) {
                    const nanPoint = { time: payload.epoch, value: 1 };
                    nanArr.push(nanPoint);
                }

                // If this is the node currently being charted, explicitly push the live tick
                if (activeChartSeries.has(key)) {
                    try {
                        if (!isScrubbing) {
                            const cs = activeChartSeries.get(key);
                            if (cs.lineSeries) cs.lineSeries.update(point);
                            if (cs.nanSeries && isNaNVal) cs.nanSeries.update({ time: payload.epoch, value: 1 });
                        }
                    } catch (e) {
                        document.getElementById('chart-title').textContent = "UPD ERR: " + e.message;
                    }
                }
            }
        } catch (error) {
            console.error(error);
            document.body.innerHTML += '<div style="color:red; background:white; font-size: 24px; position:fixed; z-index:9999; top:10px; left:10px;">ERROR: ' + error.message + ' <br/> ' + error.stack + '</div>';
        }
    };
}

// Builds exactly what GraphExplain does in Java, allowing us to bootstrap the initial render
function buildMermaidString(values) {
    let str = "graph TD;\n";
    // We infer topology loosely here just for fallback, but ideally in a production
    // scenario, the server would send the topology *once* on connect, and then only deltas.
    // For this prototype, we'll hardcode the Tri-Arb topology since it's known, 
    // or rely on a simpler top-down flow if we just list nodes.

    // Hardcoded Tri-Arb layout for absolute structural perfection
    str += `
      USDJPY["<div class='node-inner'><span class='node-title source-node'>USDJPY</span><b class='node-value'>${formatVal(values['USDJPY'])}</b></div>"];
      style USDJPY fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
      EURJPY["<div class='node-inner'><span class='node-title source-node'>EURJPY</span><b class='node-value'>${formatVal(values['EURJPY'])}</b></div>"];
      style EURJPY fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
      EURUSD["<div class='node-inner'><span class='node-title source-node'>EURUSD</span><b class='node-value'>${formatVal(values['EURUSD'])}</b></div>"];
      style EURUSD fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
      
      Arb_Spread["<div class='node-inner'><span class='node-title'>Arb.Spread</span><b class='node-value'>${formatVal(values['Arb.Spread'])}</b></div>"];
      Arb_Spread_Ewma["<div class='node-inner'><span class='node-title'>Arb.Spread.Ewma</span><b class='node-value'>${formatVal(values['Arb.Spread.Ewma'])}</b></div>"];
      
      USDJPY --> Arb_Spread;
      EURJPY --> Arb_Spread;
      EURUSD --> Arb_Spread;
      Arb_Spread --> Arb_Spread_Ewma;
    `;

    return str;
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

    let html = '';
    for (const node of sortedData) {
        let displayName = node.name;
        if (displayName.length > 20) {
            displayName = displayName.substring(0, 17) + '...';
        }

        html += `
            <tr>
                <td title="${node.name}">${displayName}</td>
                <td class="right">${node.evaluations}</td>
                <td class="right">${formatVal(node.latest, 2)}</td>
                <td class="right">${formatVal(node.avg, 2)}</td>
                <td class="right">${node.nans}</td>
            </tr>
        `;
    }
    profileBody.innerHTML = html;

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
            const svgNodeId = getMermaidNodeId(nodeName);
            // Mermaid assigns IDs like 'flowchart-Arb_Spread-xxx' but the <g id="Arb_Spread"> is wrapped around it
            const nodeGroup = document.querySelector(`[id^="flowchart-${svgNodeId}-"]`);

            if (nodeGroup) {
                const isNaN = newVal === "NaN";
                if (isNaN) {
                    nodeGroup.classList.add('nan-node');
                    document.querySelectorAll(`.LS-${svgNodeId}`).forEach(e => e.dataset.sourceNan = "true");
                    document.querySelectorAll(`.LE-${svgNodeId}`).forEach(e => e.dataset.targetNan = "true");
                } else {
                    nodeGroup.classList.remove('nan-node');
                    document.querySelectorAll(`.LS-${svgNodeId}`).forEach(e => e.dataset.sourceNan = "false");
                    document.querySelectorAll(`.LE-${svgNodeId}`).forEach(e => e.dataset.targetNan = "false");
                }

                const displayVal = isNaN ? "NaN" : formatVal(newVal);

                // Directly target the new semantic class we injected in GraphExplain.java
                const valueEl = nodeGroup.querySelector('.node-value');
                if (valueEl) {
                    valueEl.textContent = displayVal;
                } else {
                    // Fallback for strict SVG text nodes (if htmlLabels: false)
                    const textNodes = nodeGroup.querySelectorAll('tspan');
                    if (textNodes.length > 1) {
                        textNodes[textNodes.length - 1].textContent = displayVal;
                    }
                }

                // Trigger CSS flash animation
                nodeGroup.classList.remove('node-flash');
                // Force reflow to restart animation
                void nodeGroup.offsetWidth;
                nodeGroup.classList.add('node-flash');
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
function highlightNodes(nodeNames, isHovered, type = 'downstream') {
    for (const name of nodeNames) {
        const svgNodeId = getMermaidNodeId(name);
        const nodeGroup = document.querySelector(`[id^="flowchart-${svgNodeId}-"]`);
        if (nodeGroup) {
            if (isHovered) {
                nodeGroup.classList.add(`node-hover-${type}`);
            } else {
                nodeGroup.classList.remove(`node-hover-${type}`);
            }
        }

        document.querySelectorAll(`.LS-${svgNodeId}`).forEach(e => {
            e.dataset[`sourceHover${type.charAt(0).toUpperCase() + type.slice(1)}`] = isHovered ? "true" : "false";
        });
        document.querySelectorAll(`.LE-${svgNodeId}`).forEach(e => {
            e.dataset[`targetHover${type.charAt(0).toUpperCase() + type.slice(1)}`] = isHovered ? "true" : "false";
        });
    }
}

// Bind native browser pointer events onto the underlying DOM structure generated by Mermaid
function attachHoverListeners() {
    for (const nodeName of prevValues.keys()) {
        const svgNodeId = getMermaidNodeId(nodeName);
        const nodeGroup = document.querySelector(`[id^="flowchart-${svgNodeId}-"]`);

        if (nodeGroup) {
            nodeGroup.addEventListener('mouseenter', () => {
                const downstream = getDownstreamNodes(nodeName).filter(n => n !== nodeName);
                const upstream = getUpstreamNodes(nodeName).filter(n => n !== nodeName);

                highlightNodes(downstream, true, 'downstream');
                highlightNodes(upstream, true, 'upstream');
                highlightNodes([nodeName], true, 'self');

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
            nodeGroup.addEventListener('click', () => {
                openChart(nodeName);
            });
            // Make it obviously clickable
            nodeGroup.style.cursor = 'pointer';
        }
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
    const chartPanel = document.getElementById('chart-panel');
    const resizeObserver = new ResizeObserver(entries => {
        if (chartInstance && chartContainer.clientWidth > 0 && chartContainer.clientHeight > 0) {
            chartInstance.resize(chartContainer.clientWidth, chartContainer.clientHeight);
        }
    });
    resizeObserver.observe(chartContainer);

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

        const modeTd = document.createElement('td');
        modeTd.textContent = alert.mode;
        if (alert.mode === 'ONCE') modeTd.style.color = "var(--text-secondary)";
        else modeTd.style.color = "var(--accent-blue)";

        const actionTd = document.createElement('td');
        const delBtn = document.createElement('button');
        delBtn.className = 'delete-alert-btn';
        delBtn.innerHTML = '×';
        delBtn.title = 'Delete Alert';
        delBtn.onclick = () => {
            activeAlerts = activeAlerts.filter(a => a.id !== alert.id);
            renderActiveAlerts();
        };
        actionTd.appendChild(delBtn);

        tr.appendChild(ruleTd);
        tr.appendChild(modeTd);
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

    alertHistory.forEach(hist => {
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

        tr.appendChild(timeTd);
        tr.appendChild(epochTd);
        tr.appendChild(msgTd);
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
            const mode = document.getElementById('alert-mode')?.value || 'ONCE';

            if (!node || !condition || threshStr === '') {
                return;
            }

            const newAlert = {
                id: ++alertIdCounter,
                node: node,
                condition: condition,
                threshold: parseFloat(threshStr),
                mode: mode,
                lastNotifiedEpoch: -1
            };

            activeAlerts.push(newAlert);
            renderActiveAlerts();

            // Clear input box and reset mode to default
            document.getElementById('alert-threshold').value = '';
            const modeSelect = document.getElementById('alert-mode');
            if (modeSelect) modeSelect.value = 'ONCE';

            // Request Notification permission
            if ("Notification" in window && Notification.permission !== "granted" && Notification.permission !== "denied") {
                Notification.requestPermission();
            }
        });
    }
});
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

        // Populate the existing history buffers instantly
        try {
            const arr = nodeHistory.get(nodeId) || [];
            const nanArr = nodeNaNHistory.get(nodeId) || [];

            if (isScrubbing) {
                const scrubberIdx = parseInt(document.getElementById('time-scrubber').value);
                lineS.setData(arr.slice(0, scrubberIdx + 1));
                if (snapshots[scrubberIdx]) {
                    const maxEpoch = snapshots[scrubberIdx].epoch;
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
}

function closeChart() {
    document.getElementById('chart-panel').classList.add('hidden');
    for (const [id, cs] of activeChartSeries.entries()) {
        if (cs.lineSeries) chartInstance.removeSeries(cs.lineSeries);
        if (cs.nanSeries) chartInstance.removeSeries(cs.nanSeries);
    }
    activeChartSeries.clear();
    document.getElementById('chart-title').innerHTML = '';
}

// ============================================================================
// Historical Scrubbing Logic
// ============================================================================

if (tlStatus) {
    tlStatus.addEventListener('click', () => {
        if (!isScrubbing || snapshots.length === 0) return;

        // Jump back to Live unconditionally
        isScrubbing = false;
        tlStatus.textContent = "LIVE";
        tlStatus.className = "status-badge live";
        const btnAutoTrack = document.getElementById('btn-auto-track');
        if (btnAutoTrack) btnAutoTrack.style.display = 'none';

        const scrubber = document.getElementById('time-scrubber');
        if (scrubber) {
            scrubber.value = snapshots.length - 1;
        }

        const lastPayload = snapshots[snapshots.length - 1];
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

        if (idx >= snapshots.length - 1) {
            // Returned to Live
            isScrubbing = false;
            if (tlStatus) {
                tlStatus.textContent = "LIVE";
                tlStatus.className = "status-badge live";
            }
            const btnAutoTrack = document.getElementById('btn-auto-track');
            if (btnAutoTrack) btnAutoTrack.style.display = 'none';

            const lastPayload = snapshots[snapshots.length - 1];
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

            const targetPayload = snapshots[idx];
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
            cs.lineSeries.setData(historyArr.slice(0, idx + 1));
        }
        const nanArr = nodeNaNHistory.get(nodeId);
        if (cs.nanSeries && nanArr && snapshots[idx]) {
            const maxEpoch = snapshots[idx].epoch;
            cs.nanSeries.setData(nanArr.filter(p => p.time <= maxEpoch));
        }
    }
}

function renderScrubbedChartLive() {
    if (activeChartSeries.size === 0) return;
    for (const [nodeId, cs] of activeChartSeries.entries()) {
        const historyArr = nodeHistory.get(nodeId);
        if (historyArr && cs.lineSeries) {
            cs.lineSeries.setData(historyArr);
        }
        const nanArr = nodeNaNHistory.get(nodeId);
        if (cs.nanSeries && nanArr) {
            cs.nanSeries.setData(nanArr);
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
            if (newHeight < 100) newHeight = 100;
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
    initChart();
    setupOmnidirectionalResize('chart-panel');
    setupOmnidirectionalResize('metrics-panel');
    setupDockingControls();
    setupDockResizers();

    // Boot WebSocket strictly after DOM stabilizes
    connect();
});
