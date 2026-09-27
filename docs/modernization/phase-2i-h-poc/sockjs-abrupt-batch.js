'use strict';

// Opens N standard SockJS sessions, delivers STOMP CONNECT to each, then
// exits the Node process without STOMP DISCONNECT or SockJS close.
const SockJS = require('sockjs-client');
const endpoint = process.argv[2];
const transport = process.argv[3];
const expected = Number(process.argv[4] || 100);
const sessions = [];
let connected = 0;
const timeout = setTimeout(() => {
  console.error(`ABRUPT_BATCH_TIMEOUT connected=${connected} expected=${expected}`);
  process.exit(2);
}, 30000);

for (let i = 0; i < expected; i++) {
  const sock = new SockJS(endpoint, null, { transports: [transport], timeout: 10000 });
  sessions.push(sock);
  sock.onopen = () => sock.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000');
  sock.onmessage = (event) => {
    if (event.data.startsWith('CONNECTED')) {
      connected++;
      if (connected === expected) {
        clearTimeout(timeout);
        console.log(`ABRUPT_BATCH_CONNECTED=${connected} transport=${transport}; exiting without close/DISCONNECT`);
        process.exit(0);
      }
    }
  };
  sock.onerror = (error) => console.error(`sockjs_error ${i}: ${error && error.message || error}`);
}
