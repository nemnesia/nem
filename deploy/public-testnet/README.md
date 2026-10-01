# Public NEM Testnet node

This bundle runs the repository's NIS 0.6.102 code on Java 25 against the public NEM NIS1 Testnet. It does not reuse the Mainnet data directory, database, or node identity. Build and runtime state stay outside the repository.

## Runtime contract

- Testnet network ID: `0x98` (`-104` in the signed NIS peer metadata); Testnet addresses start with `T`.
- NIS peer and REST listener: TCP `7890`. The endpoint is HTTP. Jetty binds its connector on all interfaces; `nem.host` is the address advertised to peers, not a bind restriction.
- WebSocket listener: TCP `7778`; this node does not need it publicly exposed. Deny it at the host and edge firewall.
- HTTPS port `7891` is configured but the NIS server bootstrap currently creates only the HTTP connector on `7890`.
- NIS loads `peers-config_testnet.json` by the selected `nem.network` name. The shipped Testnet list seeds `libertalia.nemtest.net`, `ocracoke.nemtest.net`, and `tortuga.nemtest.net`.
- The live public Testnet activates the treasury reissuance, multisig, mosaics, both fee, remote account, and mosaic redefinition forks at height `1`. `config.properties.example` overrides the repository's generic historical fork defaults accordingly, following the [NEM Testnet node instructions](https://docs.nemtest.net/en/userbook/node/install/).
- H2 database: `/var/lib/nis-public-testnet/nis/data/nis5_testnet.mv.db` by default, from `jdbc.url` in `db.properties`. Never copy or point this path at a Mainnet database.
- NIS generates a random node identity if no boot key is set. `configure.sh` instead creates a persistent Testnet-only key outside the repository so its identity survives restarts. Auto-harvesting is disabled.
- Peer identity is exchanged in NIS's authenticated peer protocol over HTTP. Public `nem.host` must resolve or route to this machine's TCP `7890` from the Internet.

## Build a bundle

From the repository root, on Java 25:

```sh
deploy/public-testnet/build-bundle.sh /tmp/nis-public-testnet-bundle
```

The script runs `mvn -pl nis -am package` without skipping tests and refuses to overwrite a nonempty output directory. Install the resulting bundle at `/opt/nis-public-testnet` and keep configuration and state in the separate paths below. The source checkout itself is not a production working directory.

## Host installation

Create a dedicated unprivileged service account and directories:

```sh
sudo useradd --system --home-dir /var/lib/nis-public-testnet --shell /usr/sbin/nologin nis-testnet
sudo install -d -o root -g nis-testnet -m 0750 /opt/nis-public-testnet
sudo install -d -o root -g nis-testnet -m 0750 /etc/nis-public-testnet
sudo install -d -o nis-testnet -g nis-testnet -m 0750 /var/lib/nis-public-testnet
sudo cp -a /tmp/nis-public-testnet-bundle/. /opt/nis-public-testnet/
```

Set `NIS_TESTNET_PUBLIC_HOST` to the stable public DNS name or IPv4 address that external peers can reach on TCP `7890`, then run:

```sh
sudo env NIS_TESTNET_PUBLIC_HOST=YOUR_PUBLIC_HOST \
  NIS_TESTNET_CONFIG_DIR=/etc/nis-public-testnet \
  NIS_TESTNET_DATA_DIR=/var/lib/nis-public-testnet \
  NIS_TESTNET_BUNDLE_DIR=/opt/nis-public-testnet \
  /opt/nis-public-testnet/ops/configure.sh
sudo chown root:nis-testnet /etc/nis-public-testnet/* /var/lib/nis-public-testnet
sudo chmod 0640 /etc/nis-public-testnet/config-user.properties
```

`configure.sh` creates a new random 32-byte Testnet key only when none exists. It writes the boot key to a mode-0600 file and injects it from `config-user.properties`; neither file is part of the repository or bundle. Back up that file only in an encrypted secret store. Do not reuse any Mainnet key.

Install the service unit as `/etc/systemd/system/nis-public-testnet.service`, then:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now nis-public-testnet
sudo systemctl status nis-public-testnet
```

The unit runs as `nis-testnet`, limits memory to 8 GiB, sends SIGTERM on stop, and grants writes only below the Testnet data directory. The JVM uses G1, a 2 GiB initial heap, a 6 GiB maximum heap, and writes GC logs under that data directory.

## Network exposure

Before setting the public hostname in the NIS config, verify the exact same endpoint from an Internet-side host. Expose only inbound TCP `7890` for NIS peer/API traffic. Deny inbound `7778`, `7891`, SSH, and unrelated services unless another explicit service requires them. If the host is behind NAT, forward public TCP `7890` to this host's TCP `7890`; outbound-only connectivity and a local `LISTEN` socket do not establish public reachability. The NIS REST API shares port `7890` with peer protocol, so review API routes and retain NIS's local/trusted-request checks; this setup does not publish a separate management interface.

For a host firewall that uses UFW, the narrow host rule is:

```sh
sudo ufw allow 7890/tcp
```

Add the matching cloud security-group or router port-forward rule if applicable. Do not open the WebSocket port for this node.

## Health, synchronization, restart, and reconnect

Local health reports the network ID, advertised endpoint, chain height, and active peer count:

```sh
/opt/nis-public-testnet/check.sh
curl -fsS http://127.0.0.1:7890/chain/height
curl -fsS http://127.0.0.1:7890/status
```

From a separate Internet-side machine, verify the peer/API endpoint:

```sh
deploy/public-testnet/check-public.sh YOUR_PUBLIC_HOST
```

Compare local height to at least two peers and sample again after several minutes. The bundle's `monitor-tip.sh [SAMPLES=6] [INTERVAL_SECONDS=300] [LOCAL_URL=http://127.0.0.1:7890]` writes CSV samples for three known Testnet peers. For a persistence check, record height, gracefully stop the service, confirm the H2 file remains, restart the service, and verify the initial height is retained and advances. For reconnect validation, temporarily block this host's outbound TCP `7890` only, observe peer loss while the process stays alive, remove the block, then verify peers return and height progresses. Never disrupt a remote peer.

Useful diagnostics:

```sh
systemctl show nis-public-testnet -p ActiveState -p MainPID -p ActiveEnterTimestamp
journalctl -u nis-public-testnet --since '1 hour ago'
du -sh /var/lib/nis-public-testnet/nis/data
ps -o pid,etime,%cpu,%mem,nlwp,rss -p "$(systemctl show -p MainPID --value nis-public-testnet)"
ls /proc/"$(systemctl show -p MainPID --value nis-public-testnet)"/fd | wc -l
ss -lntp
```

The validation helper checks NIS network ID `-104`; a TCP connect or process liveness alone is not synchronization evidence. Record local and remote heights, their progression, peer count, restart heights, DB reuse, external inbound result, uptime, resource observations, and error/fatal logs without recording private-key contents.

## Stop and rollback

Graceful stop:

```sh
sudo systemctl stop nis-public-testnet
```

Rollback stops only this service and leaves its Testnet data untouched. To remove it, disable and stop the service, delete its unit and bundle/config directories, and archive or remove `/var/lib/nis-public-testnet` only after separately verifying the Testnet data path. Do not point cleanup at a generic `nem` directory.
