const WebSocket = require('ws');
const ws = new WebSocket('ws://localhost:8080/ws/graph');
ws.on('message', function incoming(data) {
  const json = JSON.parse(data);
  console.log(JSON.stringify(json.metrics.profile, null, 2));
  process.exit(0);
});
ws.on('error', console.error);
