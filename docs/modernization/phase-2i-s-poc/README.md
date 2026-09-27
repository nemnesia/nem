# Phase 2I-S production bootstrap smoke harness

This standalone test harness invokes the production `NemServerBootstrapper` and
`NemWebsockServerBootstrapper` classes from the locally built NIS artifact. It
uses mocked chain/network collaborators, the production NIS WebSocket
initializer, production ServletContext listeners and filters, and the
production Jetty 12 service-provider entry. It does not start a database or a
public peer network.

From the repository root, build/install the reactor artifact, then compile and
run the harness:

```bash
mvn -B -pl nis -am -DskipTests -Djacoco.skip=true install
npm install --prefix /tmp/nem-phase2i-sockjs sockjs-client@1.6.1
cd docs/modernization/phase-2i-s-poc
NODE_PATH=/tmp/nem-phase2i-sockjs/node_modules mvn -B compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=org.nem.specific.deploy.ProductionBootstrapSmoke \
  -Dexec.args=all
```

The Node client scenarios reuse the committed Phase 2I-H standard SockJS
client probes. The harness binds ephemeral loopback ports and verifies the
production server bootstrappers, REST heartbeat/404, `/w/messages/info`,
WebSocket and forced XHR polling STOMP exchanges, normal/abrupt cleanup, QTP
and active-request statistics, and clean shutdown. The mocked collaborators make this a production-bootstrap
integration smoke, not a full database-backed NIS node test.
