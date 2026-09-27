'use strict';

// Repeats standard SockJS sessions in a bounded-concurrency batch. Each session
// reaches NIS, receives a MESSAGE, then sends a receipt-bearing STOMP DISCONNECT.
const SockJS = require('sockjs-client');
const endpoint = process.argv[2];
const transport = process.argv[3];
const expected = Number(process.argv[4] || 100);
const concurrency = Number(process.argv[5] || 10);
let started = 0;
let connected = 0;
let messages = 0;
let receipts = 0;
let completed = 0;
let failed = false;
const timeout = setTimeout(() => {
  console.error(`NORMAL_BATCH_TIMEOUT started=${started} connected=${connected} messages=${messages} receipts=${receipts} completed=${completed} expected=${expected}`);
  process.exit(2);
}, 240000);

function fail(index, reason) {
  if (failed) return;
  failed = true;
  console.error(`NORMAL_BATCH_FAILURE index=${index} reason=${reason} started=${started} connected=${connected} messages=${messages} receipts=${receipts} completed=${completed}`);
  process.exit(3);
}

function launch() {
  if (failed || started >= expected) return;
  const index = started++;
  const sock = new SockJS(endpoint, null, { transports: [transport], timeout: 10000 });
  let didConnect = false;
  let didMessage = false;
  let disconnectSent = false;
  sock.onopen = () => sock.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000');
  sock.onmessage = (event) => {
    const frame = event.data;
    if (frame.startsWith('CONNECTED') && !didConnect) {
      didConnect = true;
      connected++;
      const subscriptionId = `node-info-${index}`;
      sock.send(`SUBSCRIBE\nid:${subscriptionId}\ndestination:/node/info\nack:auto\n\n\u0000`);
      sock.send('SEND\ndestination:/w/api/node/info\ncontent-length:0\n\n\u0000');
    } else if (frame.startsWith('MESSAGE') && !didMessage) {
      didMessage = true;
      messages++;
      disconnectSent = true;
      sock.send(`UNSUBSCRIBE\nid:node-info-${index}\nreceipt:unsubscribe-${index}\n\n\u0000`);
      setTimeout(() => sock.send(`DISCONNECT\nreceipt:disconnect-${index}\n\n\u0000`), 250);
    } else if (frame.startsWith('RECEIPT') && frame.includes(`receipt-id:disconnect-${index}`)) {
      receipts++;
      completed++;
      if (completed === expected || completed % 10 === 0) {
        console.log(`NORMAL_BATCH_PROGRESS transport=${transport} completed=${completed}/${expected}`);
      }
      sock.close();
      if (completed === expected) {
        clearTimeout(timeout);
        console.log(`NORMAL_BATCH_COMPLETE=${completed} transport=${transport} connected=${connected} messages=${messages} receipts=${receipts} concurrency=${concurrency}`);
        process.exit(0);
      }
      launch();
    }
  };
  sock.onerror = (error) => fail(index, error && error.message || String(error));
  sock.onclose = (event) => {
    if (completed < expected && disconnectSent && !event.wasClean) fail(index, `closed:${event.code}:${event.reason}`);
  };
}

for (let i = 0; i < Math.min(concurrency, expected); i++) launch();
