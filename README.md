## md-client

Unofficial clojure-based M@H node.

### Quickstart

1. Download `md-client-x.x.x-standalone.jar` from [releases](https://github.com/akiroz/md-client/releases)
1. Download `config.edn` from this repo and place it in the same directory
1. Edit `config.edn` as necessary
1. Run `java -jar md-client-x.x.x-standalone.jar config.edn`.

This will create a `data` directory and `log` directory (if file logging is enabled)

### Development

```
$ lein run
$ lein uberjar
```
