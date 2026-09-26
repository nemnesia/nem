'use strict';

const SockJS = require('sockjs-client');

const endpoint = process.argv[2];
const transport = process.argv[3];
const timeoutMs = Number(process.argv[4] || 15000);
const expectNisMessage = process.argv[5] === 'expect-nis-message';
const events = [];
let stompSessionReady = false;
let normalDisconnectSent = false;
let selectedTransport = null;
const sock = new SockJS(endpoint, null, {
  transports: [transport],
  timeout: 5000
});

let finished = false;
const timeout = setTimeout(() => finish('timeout'), timeoutMs);

function finish(reason) {
  if (finished) return;
  finished = true;
  clearTimeout(timeout);
  console.log(JSON.stringify({
    requestedTransport: transport,
    selectedTransport,
    readyState: sock.readyState,
    protocol: sock.protocol,
    events,
    reason,
    connected: events.some((event) => event.type === 'message' && event.data.startsWith('CONNECTED')),
    nisMessage: events.some((event) => event.type === 'message' && event.data.startsWith('MESSAGE')),
    disconnectReceipt: events.some((event) => event.type === 'message' && event.data.startsWith('RECEIPT\nreceipt-id:disconnect-1')),
    normalDisconnectSent
  }));
  if (expectNisMessage && (!events.some((event) => event.type === 'message' && event.data.startsWith('CONNECTED'))
      || !events.some((event) => event.type === 'message' && event.data.startsWith('MESSAGE'))
      || !events.some((event) => event.type === 'message' && event.data.startsWith('RECEIPT\nreceipt-id:disconnect-1')))) {
    process.exitCode = 2;
  }
  try { sock.close(); } catch (_) { /* cleanup after a failed connection */ }
}

sock.onopen = () => {
  events.push({ type: 'open' });
  selectedTransport = sock._transport && sock._transport.transportName || null;
  console.log('SOCKJS_OPEN ' + selectedTransport);
  sock.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000');
};

sock.onmessage = (event) => {
  events.push({ type: 'message', data: event.data });
  if (event.data.startsWith('CONNECTED') && !stompSessionReady) {
    stompSessionReady = true;
    sock.send('SUBSCRIBE\nid:node-info\ndestination:/node/info\nack:auto\n\n\u0000');
    setTimeout(() => sock.send('SEND\ndestination:/w/api/node/info\ncontent-length:0\n\n\u0000'), 500);
  }
  if (event.data.startsWith('MESSAGE') && !normalDisconnectSent) {
    normalDisconnectSent = true;
    sock.send('UNSUBSCRIBE\nid:node-info\nreceipt:unsubscribe-1\n\n\u0000');
    setTimeout(() => sock.send('DISCONNECT\nreceipt:disconnect-1\n\n\u0000'), 250);
  }
  if (event.data.startsWith('RECEIPT\nreceipt-id:disconnect-1')) finish('nis-message-disconnect-receipt');
};

sock.onerror = (event) => {
  events.push({ type: 'error', message: event && event.message || String(event) });
};

sock.onclose = (event) => {
  events.push({ type: 'close', code: event.code, reason: event.reason, wasClean: event.wasClean });
  finish('close');
};
