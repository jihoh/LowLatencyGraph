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
            if (payload.metrics.nanStats) {
                updateNanTable(payload.metrics.nanStats);
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
            </tr>
        `;
    }
    profileBody.innerHTML = html;
}

// Update the Top NaN Stats table dynamically
function updateNanTable(nanArray) {
    if (!nanArray || nanArray.length === 0) {
        nanBody.innerHTML = '<tr><td colspan="2" style="text-align: center; color: var(--text-muted); font-style: italic;">No NaN occurrences</td></tr>';
        return;
    }
    let html = '';
    for (const stat of nanArray) {
        let displayName = stat.name;
        if (displayName.length > 20) {
            displayName = displayName.substring(0, 17) + '...';
        }
        html += `
            <tr>
                <td title="${stat.name}">${displayName}</td>
                <td class="right">${stat.count}</td>
            </tr>
        `;
    }
    nanBody.innerHTML = html;
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
        }
    }
}

// Boot
connect();
