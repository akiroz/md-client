const { readFileSync } = require("fs");
const server = require("server");
const { post } = server.router;

const ca = readFileSync("ca.pem", "utf8");
const cert = readFileSync("node.pem", "utf8");
const private_key = readFileSync("node-key.pem", "utf8");

const created_at = new Date().toISOString();

server({ port: 3001, security: false }, [
    post("/ping", ({ data: { secret, port, tls_created_at, requested_shard_count }}) => {
        console.log(`POST /ping - ${secret}`);
        return  (tls_created_at === created_at ? {} : {
            image_server: "https://mangadex.org",
            tls: {
                created_at,
                private_key,
                certificate: cert + ca,
            }
        }); 
    }),
    post("/stop", ({ data: { secret }}) => {
        console.log(`POST /stop - ${secret}`);
        return {};
    }),
]);
