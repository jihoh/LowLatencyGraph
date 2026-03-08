# CoreGraph JSON Configuration Tutorial

Welcome to the comprehensive guide on configuring CoreGraph using JSON! 
CoreGraph allows you to define complex reactive acyclic graphs entirely through JSON files. This guide will walk you through the structural basics, node definitions, connections, and advanced features like templates and vector auto-expansion.

## 1. Overview and Basic Structure

Every CoreGraph configuration must follow a specific JSON structure. The root object contains a single `graph` property, which encapsulates the entire configuration including metadata, templates, and the node definitions themselves.

### The Skeleton
```json
{
    "graph": {
        "name": "My CoreGraph Config",
        "version": "1.0",
        "templates": [],
        "nodes": []
    }
}
```

- **`name`**: A readable string identifier for your graph.
- **`version`**: The version string of the configuration.
- **`templates`**: (Optional) A list of reusable sub-graph templates.
- **`nodes`**: A list of node objects that make up the graph topology.

---

## 2. Defining Nodes and Types

Nodes are the fundamental building blocks of a CoreGraph. Every object within the `nodes` array specifies a node and how it behaves. A standard node has four primary keys:

- **`name`**: The unique identifier for this node within the graph.
- **`type`**: The specific operation or functionality of the node, mapped directly to `NodeType` in the `NodeRegistry`.
- **`inputs`**: (Optional) A map linking required operational inputs to the names of other nodes in the graph.
- **`properties`**: (Optional) A map of static configuration parameters that dictate how the node is initialized (e.g., window sizes, offset limits, auto-expansion rules). *Note: The system recently reverted from using `state` to `properties`.*

### A. Source Nodes

Source nodes introduce data into the graph. They don't typically have `inputs` from other nodes but require starting `properties`.

#### Scalar Source
A single floating-point numeric source.
```json
{
    "name": "MarketPrice",
    "type": "SCALAR_SOURCE",
    "properties": {
        "value": 100.5
    }
}
```

#### Vector Source
A source holding an array of numeric values. Useful for order books or yield curves.
```json
{
    "name": "YieldCurve",
    "type": "VECTOR_SOURCE",
    "properties": {
        "size": 5,
        "tolerance": 1e-6
    }
}
```

### B. Calculation Nodes (Math Functions)

Calculation nodes take outputs from parent nodes (via `inputs`) and perform a mathematical or statistical operation. The JVM reflection maps your `inputs` keys directly to the argument names expected by the built-in functions (e.g., `input`, `a`, `b`, `leg1`, etc.).

#### Single Input Functions (Fn1)
Takes one scalar input. Examples include `EWMA`, `DIFF`, `MACD`, `RSI`, `SMA`.
```json
{
    "name": "PriceSMA",
    "type": "SMA",
    "inputs": {
        "input": "MarketPrice"
    },
    "properties": {
        "window": 10
    }
}
```

#### Two Input Functions (Fn2)
Takes two scalar inputs. Examples include `SPREAD`, `BETA`, `CORRELATION`.
```json
{
    "name": "AssetSpread",
    "type": "SPREAD",
    "inputs": {
        "a": "Asset1Price",
        "b": "Asset2Price"
    }
}
```

#### N-Input Functions (FnN)
Takes a variable number of scalar inputs. Examples include `AVERAGE`, `HARMONIC_MEAN`, `WEIGHTED_AVG`.
```json
{
    "name": "BasketAverage",
    "type": "AVERAGE",
    "inputs": {
        "1": "Asset1",
        "2": "Asset2",
        "3": "Asset3"
    }
}
```

### C. Structural and Routing Nodes

CoreGraph includes nodes for logic branching, condition evaluation, and event flow control.

#### Condition Node
Evaluates a boolean condition against an input. Returns a boolean value.
```json
{
    "name": "IsPriceHigh",
    "type": "CONDITION",
    "inputs": {
        "input": "MarketPrice"
    },
    "properties": {
        "operator": ">",
        "threshold": 150.0
    }
}
```

#### Switch Node
Acts as a router, propelling updates to specific subsequent branches based on a boolean condition.
```json
{
    "name": "PriceRouter",
    "type": "SWITCH",
    "inputs": {
        "input": "MarketPrice",
        "condition": "IsPriceHigh"
    },
    "properties": {
        "true_branch": "SellLogicNode",
        "false_branch": "BuyLogicNode"
    }
}
```

#### Timing Nodes (Throttle / Time Decay)
Used to regulate event frequency or smoothly decay values over time.
```json
{
    "name": "ThrottledPrice",
    "type": "THROTTLE",
    "inputs": {
        "input": "MarketPrice"
    },
    "properties": {
        "windowMs": 100.0
    }
}
```

---

## 3. Advanced Configuration: Templates

As graphs scale, you want to avoid duplicating JSON blocks. A **Template** allows you to define a parameterized cluster of nodes once and stamp it out multiple times.

### Defining a Template
Templates live under the `graph.templates` list. Use `{{variable_name}}` inside strings to mark a parameter for substitution.

```json
{
    "graph": {
        "templates": [
            {
                "name": "MovingAverageCrossover",
                "nodes": [
                    {
                        "name": "{{prefix}}.short_sma",
                        "type": "SMA",
                        "properties": { "window": "{{short_win}}" },
                        "inputs": { "input": "{{source}}" }
                    },
                    {
                        "name": "{{prefix}}.long_sma",
                        "type": "SMA",
                        "properties": { "window": "{{long_win}}" },
                        "inputs": { "input": "{{source}}" }
                    },
                    {
                        "name": "{{prefix}}.spread",
                        "type": "SPREAD",
                        "inputs": {
                            "a": "{{prefix}}.short_sma",
                            "b": "{{prefix}}.long_sma"
                        }
                    }
                ]
            }
        ],
        "nodes": [ ... ]
    }
}
```

### Instantiating a Template
To use a template in the active `nodes` array, create a node with `type: "TEMPLATE"` and pass the required variables through `properties`. Notice that you also need to set the magical `"template": "TemplateName"` property.

```json
{
    "name": "BTC_Strategy_Template",
    "type": "TEMPLATE",
    "properties": {
        "template": "MovingAverageCrossover",
        "prefix": "BTC",
        "source": "BTC_PRICE_NODE",
        "short_win": 10,
        "long_win": 50
    }
}
```

During compilation, CoreGraph will replace the `TEMPLATE` node with the 3 nodes defined inside `MovingAverageCrossover`, expanding `{{prefix}}`, `{{source}}`, `{{short_win}}`, and `{{long_win}}` correctly.

---

## 4. Advanced Configuration: Vector Auto-Expansion

When dealing with a `VECTOR_SOURCE`, creating individual scalar nodes for every element of the vector manually is tedious. Setting `auto_expand: true` allows the engine to generate child nodes for each element automatically.

```json
{
    "name": "YieldCurve",
    "type": "VECTOR_SOURCE",
    "properties": {
        "size": 3,
        "auto_expand": true,
        "auto_expand_labels": ["1M", "3M", "6M"]
    }
}
```

By doing this, the `JsonGraphCompiler` automatically registers three `VECTOR_ELEMENT` nodes into your graph named:
- `YieldCurve.1M`
- `YieldCurve.3M`
- `YieldCurve.6M`

You can immediately use these auto-generated names as inputs for other math functions!

```json
{
    "name": "Spread3M1M",
    "type": "SPREAD",
    "inputs": {
        "a": "YieldCurve.3M",
        "b": "YieldCurve.1M"
    }
}
```
