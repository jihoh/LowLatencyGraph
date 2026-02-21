"use strict";

mermaid.initialize({
    startOnLoad: false,
    theme: 'dark',
    fontFamily: 'Inter'
});

const graphContainer = document.getElementById('graph-view');
const statusBadge = document.getElementById('connection-status');
const epochValue = document.getElementById('epoch-value');

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
// Array to track message arrival times for Stabilization Rate calculation
const messageTimes = [];
let panZoomInstance = null;

// Lightweight Charts Globals
const nodeHistory = new Map(); // Maps NodeID -> Array of {time: timestamp, value: number}
const epochToRealTime = new Map(); // Maps integer epochs -> actual JS millisecond timestamps
let oldestEpochTracker = -1; // O(1) tracking for cleaning up old epochs
let chartInstance = null;
let chartSeries = null;
let activeChartNodeId = null;

// The UI Zoom buttons bridge over to the active instance
document.getElementById('zoom-in').addEventListener('click', () => {
    if (panZoomInstance) panZoomInstance.zoomIn();
});

document.getElementById('zoom-out').addEventListener('click', () => {
    if (panZoomInstance) panZoomInstance.zoomOut();
});

document.getElementById('zoom-reset').addEventListener('click', () => {
    if (panZoomInstance) {
        panZoomInstance.resetZoom();
        panZoomInstance.center();
    }
});

function connect() {
    const ws = new WebSocket(`ws://${window.location.host}/ws/graph`);

    ws.onopen = () => {
        statusBadge.textContent = 'Connected';
        statusBadge.className = 'status connected';
    };

    ws.onclose = () => {
        statusBadge.textContent = 'Disconnected';
        statusBadge.className = 'status disconnected';
        isGraphRendered = false;
        setTimeout(connect, 2000); // Auto-reconnect
    };

    ws.onmessage = async (event) => {
        const payload = JSON.parse(event.data);

        // Update epoch counter
        epochValue.textContent = payload.epoch;

        // Update Metrics (Latency, Profile, Throguput, Efficiency)
        if (payload.metrics) {
            if (payload.metrics.eventsProcessed !== undefined) {
                document.getElementById('events-value').textContent = payload.metrics.eventsProcessed;
            }
            if (payload.metrics.totalNodes !== undefined) {
                reactiveEff.textContent = `${payload.metrics.nodesUpdated} / ${payload.metrics.totalNodes}`;
            }

            // Real-time Stabilizations / sec using a sliding window
            const now = performance.now();
            messageTimes.push(now);
            if (messageTimes.length > 50) {
                messageTimes.shift();
            }
            if (messageTimes.length > 1) {
                const elapsedSc = (now - messageTimes[0]) / 1000.0;
                const hz = (messageTimes.length - 1) / elapsedSc;
                stabRate.textContent = Math.round(hz);
            }

            if (payload.metrics.latency) {
                if (payload.metrics.latency.latest !== undefined) {
                    latLatest.textContent = formatVal(payload.metrics.latency.latest, 2);
                }
                latAvg.textContent = formatVal(payload.metrics.latency.avg, 2);
            }
            if (payload.metrics.profile) {
                updateProfileTable(payload.metrics.profile);
            }
        }

        if (!isGraphRendered) {
            // First time: Read layout from server
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
                        fit: true,
                        center: true,
                        minZoom: 0.1,
                        maxZoom: 50
                    });
                }

                // Store routing map from backend
                if (payload.routing) {
                    graphRouting = payload.routing;
                }

                // Store initial values
                for (const [key, val] of Object.entries(payload.values)) {
                    prevValues.set(key, val);
                }

                // Attach our custom hover listeners
                attachHoverListeners();
            }
        } else {
            // Hot Path: Do NOT re-render Mermaid. Just manipulate the DOM.
            updateGraphDOM(payload.values);
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

        // Live History Tracking & Chart Updating
        // The Java backend pushes updates every epoch.
        for (const [key, val] of Object.entries(payload.values)) {
            // We only trace double values, skip NaN
            if (val !== 'NaN' && typeof val === 'number') {
                if (!nodeHistory.has(key)) {
                    nodeHistory.set(key, []);
                }
                const historyArr = nodeHistory.get(key);
                // Use raw epoch integers for Lightweight Charts X-coords to ensure strictly increasing UTCTimestamps. 
                // We fake the real time inside the tickMarkFormatter and timeframe locators!
                const point = { time: payload.epoch, value: val };
                historyArr.push(point);

                // Cap memory buffer to 500 sliding window ticks
                if (historyArr.length > 500) {
                    historyArr.shift();
                }

                // If this is the node currently being charted, explicitly push the live tick
                if (activeChartNodeId === key && chartSeries) {
                    try {
                        chartSeries.update(point);
                    } catch (e) {
                        document.getElementById('chart-title').textContent = "UPD ERR: " + e.message;
                    }
                }
            }
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
      USDJPY(["USDJPY<br/><b>${formatVal(values['USDJPY'])}</b>"]);
      style USDJPY fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
      EURJPY(["EURJPY<br/><b>${formatVal(values['EURJPY'])}</b>"]);
      style EURJPY fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
      EURUSD(["EURUSD<br/><b>${formatVal(values['EURUSD'])}</b>"]);
      style EURUSD fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
      
      Arb_Spread["Arb.Spread<br/><b>${formatVal(values['Arb.Spread'])}</b>"];
      Arb_Spread_Ewma["Arb.Spread.Ewma<br/><b>${formatVal(values['Arb.Spread.Ewma'])}</b>"];
      
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

// Update the Top 5 Node Profile table dynamically
function updateProfileTable(profileArray) {
    let html = '';
    for (const node of profileArray) {
        let displayName = node.name;
        if (displayName.length > 20) {
            displayName = displayName.substring(0, 17) + '...';
        }

        html += `
            <tr>
                <td title="${node.name}">${displayName}</td>
                <td class="right">${formatVal(node.latest, 2)}</td>
                <td class="right">${formatVal(node.avg, 2)}</td>
                <td class="right">${node.evaluations}</td>
                <td class="right">${node.nans}</td>
            </tr>
        `;
    }
    profileBody.innerHTML = html;
}

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
                } else {
                    nodeGroup.classList.remove('nan-node');
                }

                // Find the existing bold tag we injected via <br/><b>...</b> in the mermaid string
                // Mermaid renders these inside a <foreignObject> or <text> group.
                const htmlContainer = nodeGroup.querySelector('div, span');
                const displayVal = isNaN ? "NaN" : formatVal(newVal);

                // The easiest DOM trick is to replace the innerText of the exact matched string
                if (htmlContainer) {
                    htmlContainer.innerHTML = htmlContainer.innerHTML.replace(
                        new RegExp(`<b>.*?</b>`),
                        `<b>${displayVal}</b>`
                    );
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

// Apply or remove hover CSS mathematically 
function highlightNodes(nodeNames, isHovered) {
    for (const name of nodeNames) {
        const svgNodeId = getMermaidNodeId(name);
        const nodeGroup = document.querySelector(`[id^="flowchart-${svgNodeId}-"]`);
        if (nodeGroup) {
            if (isHovered) {
                nodeGroup.classList.add('node-hover-highlight');
            } else {
                nodeGroup.classList.remove('node-hover-highlight');
            }
        }
    }
}

// Bind native browser pointer events onto the underlying DOM structure generated by Mermaid
function attachHoverListeners() {
    for (const nodeName of Object.keys(graphRouting)) {
        const svgNodeId = getMermaidNodeId(nodeName);
        const nodeGroup = document.querySelector(`[id^="flowchart-${svgNodeId}-"]`);

        if (nodeGroup) {
            nodeGroup.addEventListener('mouseenter', () => {
                const downstream = getDownstreamNodes(nodeName);
                highlightNodes(downstream, true);
            });
            nodeGroup.addEventListener('mouseleave', () => {
                const downstream = getDownstreamNodes(nodeName);
                highlightNodes(downstream, false);
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
            fixLeftEdge: true,
            fixRightEdge: true,
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

    document.getElementById('close-chart').addEventListener('click', closeChart);

    document.getElementById('toggle-chart').addEventListener('click', (e) => {
        const panel = document.getElementById('chart-panel');
        panel.classList.toggle('minimized');
        e.target.textContent = panel.classList.contains('minimized') ? '+' : '-';
    });

    // Dynamic Resizing Observer
    const chartPanel = document.getElementById('chart-panel');
    const resizeObserver = new ResizeObserver(entries => {
        if (chartInstance && chartContainer.clientWidth > 0 && chartContainer.clientHeight > 0) {
            chartInstance.resize(chartContainer.clientWidth, chartContainer.clientHeight);
        }
    });
    resizeObserver.observe(chartContainer);

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

function openChart(nodeId) {
    activeChartNodeId = nodeId;
    document.getElementById('chart-title').textContent = `${nodeId} - History`;

    if (chartSeries) {
        chartInstance.removeSeries(chartSeries);
    }

    chartSeries = chartInstance.addLineSeries({
        color: '#3b82f6',
        lineWidth: 2,
        crosshairMarkerVisible: true,
        lastValueVisible: true,
        priceLineVisible: true,
    });

    // Populate the existing history buffer instantly
    try {
        if (nodeHistory.has(nodeId)) {
            chartSeries.setData(nodeHistory.get(nodeId));
        } else {
            chartSeries.setData([]);
        }
    } catch (e) {
        document.getElementById('chart-title').textContent = "SET ERR: " + e.message;
    }

    document.getElementById('chart-panel').classList.remove('hidden');
    chartInstance.timeScale().fitContent();
}

function closeChart() {
    document.getElementById('chart-panel').classList.add('hidden');
    activeChartNodeId = null;
    if (chartSeries) {
        chartInstance.removeSeries(chartSeries);
        chartSeries = null;
    }
}

function setupMetricsPanelDrag() {
    const metricsPanel = document.getElementById('metrics-panel');
    const metricsHeader = document.getElementById('metrics-header');

    let isDragging = false;
    let dragStartX = 0;
    let dragStartY = 0;
    let initialLeft = 0;
    let initialTop = 0;

    metricsHeader.addEventListener('mousedown', (e) => {
        isDragging = true;
        dragStartX = e.clientX;
        dragStartY = e.clientY;

        const rect = metricsPanel.getBoundingClientRect();
        initialLeft = rect.left;
        initialTop = rect.top;

        metricsPanel.style.transition = 'none';
        metricsPanel.style.right = 'auto'; // Break the right-docking
        metricsPanel.style.left = `${initialLeft}px`;
        metricsPanel.style.top = `${initialTop}px`;
    });

    document.addEventListener('mousemove', (e) => {
        if (!isDragging) return;
        const dx = e.clientX - dragStartX;
        const dy = e.clientY - dragStartY;
        metricsPanel.style.left = `${initialLeft + dx}px`;
        metricsPanel.style.top = `${initialTop + dy}px`;
    });

    document.addEventListener('mouseup', () => {
        if (isDragging) {
            isDragging = false;
            metricsPanel.style.transition = 'opacity 0.3s ease';
        }
    });

    document.getElementById('toggle-metrics').addEventListener('click', (e) => {
        metricsPanel.classList.toggle('minimized');
        e.target.textContent = metricsPanel.classList.contains('minimized') ? '+' : '-';
    });
}

// Init Chart Context
window.addEventListener('DOMContentLoaded', () => {
    initChart();
    setupMetricsPanelDrag();
});

// Boot
connect();
