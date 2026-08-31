# Cluster Verification Topology

Run the topology from the repository root after setting non-empty local secrets:

```powershell
$env:JWT_SECRET = 'replace-with-a-local-secret'
$env:GM_TOKEN = 'replace-with-a-local-management-token'
$env:GRAFANA_ADMIN_PASSWORD = 'replace-with-a-local-grafana-password'
docker compose -f docker-compose.yml -f deploy/cluster/docker-compose.yml -f deploy/observability/docker-compose.yml up --build
```

The topology starts one Login node, two Gateway nodes, two Logic nodes, Redis,
PostgreSQL, Prometheus, and Grafana. Pekko uses `gateway-1` and `logic-1` as
stable seeds; `logic-2` validates cross-node shard placement and rebalancing.

The initial verification sequence is:

1. Confirm all `up` metrics targets in Prometheus at `http://localhost:9091`.
2. Build the load-test project with `dotnet build xs-net-sdk-unity/LoadTest/XsNet.LoadTest.csproj`.
3. Run the login load test against Gateway 1, then repeat through Gateway 2:
   `dotnet run --project xs-net-sdk-unity/LoadTest/XsNet.LoadTest.csproj -- --host 127.0.0.1 --protocol tcp --port 8082 --count 1000 --concurrency 200 --secret "$env:JWT_SECRET"`
4. Repeat with `--protocol udp --port 2002`, then compare throughput and p99 latency with `docs/登录压测报告.md`.
5. Stop `logic-2`, observe shard recovery and `dbsync_*` metrics, then restore it.

The Compose files intentionally contain no credentials. Do not use development
secrets for an environment accessible outside the local machine.
