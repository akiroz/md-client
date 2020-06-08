## md-client

Unofficial clojure-based M@H node.

### Quickstart

1. Download `md-client-x.x.x-standalone.jar` from [releases](https://github.com/akiroz/md-client/releases)
1. Download `config.edn` from this repo and place it in the same directory
1. Edit `config.edn` as necessary
1. Run `java -jar md-client-x.x.x-standalone.jar config.edn`.

This will create a `data` directory and `log` directory (if file logging is enabled)

### Grafana Dashboard

This client supports sending metrics to influxdb which you can visualize with grafana.

To enable influxdb metrics, enter the HTTP URL in `config.edn`.
- `:influx-metrics "http://localhost:8086"`

The influxdb database is not created automatically, it can be created using the `influx` CLI tool.
- `influx -execute 'CREATE DATABASE mangadex WITH DURATION 7d'` (Change the retention policy as necessary)

The client publishes a single measurement (`mangadex`) at 1Hz with the following fields:
- `cacheSize` Disk space used by the cache in bytes
- `connection` Number of active downstream HTTP connections (TCP sockets)
- `egress` Total number of bytes sent downstream (Since client started)
- `hit` Total number of cache hits (Since client started)
- `request` Total number of HTTP requests (Since client started)
- `throughput` Downstream throughput in bytes/second

In Grafana, create a datasource called `InfluxDB` and point it to your influxdb instance with the `mangadex` database and import the `Mangadex@Home-1591605284442.json` Dashboard. Customize as necessary.

### Development

```
$ lein run
$ lein uberjar
```
