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
const latMin = document.getElementById('lat-min');
const latMax = document.getElementById('lat-max');
const profileBody = document.getElementById('profile-body');

let isGraphRendered = false;
// Map to keep track of previous values to know when to trigger the flash animation
const prevValues = new Map();

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

        // Update Metrics (Latency & Profile)
        if (payload.metrics) {
            if (payload.metrics.latency) {
                latAvg.textContent = formatVal(payload.metrics.latency.avg, 2);
                latMin.textContent = formatVal(payload.metrics.latency.min, 2);
                latMax.textContent = formatVal(payload.metrics.latency.max, 2);
            }
            if (payload.metrics.profile) {
                updateProfileTable(payload.metrics.profile);
            }
        }

        if (!isGraphRendered) {
            // First time: Ask Mermaid to render the graph structure we synthesize from the payload
            const mermaidStr = buildMermaidString(payload.values);
            const { svg } = await mermaid.render('graph-svg', mermaidStr);
            graphContainer.innerHTML = svg;
            isGraphRendered = true;

            // Store initial values
            for (const [key, val] of Object.entries(payload.values)) {
                prevValues.set(key, val);
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
                <td class="right">${formatVal(node.avg, 2)}</td>
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
                // Find the existing bold tag we injected via <br/><b>...</b> in the mermaid string
                // Mermaid renders these inside a <foreignObject> or <text> group.
                const htmlContainer = nodeGroup.querySelector('div, span');
                // The easiest DOM trick is to replace the innerText of the exact matched string
                if (htmlContainer) {
                    htmlContainer.innerHTML = htmlContainer.innerHTML.replace(
                        new RegExp(`<b>.*?</b>`),
                        `<b>${formatVal(newVal)}</b>`
                    );
                } else {
                    // Fallback for strict SVG text nodes (if htmlLabels: false)
                    const textNodes = nodeGroup.querySelectorAll('tspan');
                    if (textNodes.length > 1) {
                        textNodes[textNodes.length - 1].textContent = formatVal(newVal);
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

// Boot
connect();
