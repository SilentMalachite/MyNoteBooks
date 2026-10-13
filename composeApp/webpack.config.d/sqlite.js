const fs = require("fs");
const path = require("path");
const CopyWebpackPlugin = require("copy-webpack-plugin");

function findSqliteDist() {
  const direct = [
    path.resolve(__dirname, "../../node_modules/@sqlite.org/sqlite-wasm/dist"),
    path.resolve(__dirname, "../node_modules/@sqlite.org/sqlite-wasm/dist"),
    path.resolve(__dirname, "../../../composeApp/build/wasm/node_modules/@sqlite.org/sqlite-wasm/dist"),
  ];
  for (const dist of direct) {
    if (fs.existsSync(path.join(dist, "index.mjs"))) return dist;
  }
  const buildDir = path.resolve(__dirname, "../..");
  const stack = [buildDir];
  while (stack.length > 0) {
    const dir = stack.pop();
    let entries = [];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch (error) {
      continue;
    }
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const full = path.join(dir, entry.name);
      if (entry.name === "node_modules") {
        const dist = path.join(full, "@sqlite.org", "sqlite-wasm", "dist");
        if (fs.existsSync(path.join(dist, "index.mjs"))) return dist;
      } else {
        stack.push(full);
      }
    }
  }
  throw new Error("Cannot find @sqlite.org/sqlite-wasm. Compile the wasmJs target so npm installs it.");
}

const sqliteDist = findSqliteDist();
// sqlite.worker.js ships from wasmJsMain/resources. Copying it here too breaks wasmJsBrowserDistribution.
config.plugins.push(new CopyWebpackPlugin({
  patterns: [
    { from: path.join(sqliteDist, "index.mjs"), to: "sqlite/index.mjs" },
    { from: path.join(sqliteDist, "sqlite3.wasm"), to: "sqlite/sqlite3.wasm" },
    { from: path.join(sqliteDist, "sqlite3-opfs-async-proxy.js"), to: "sqlite/sqlite3-opfs-async-proxy.js" },
  ],
}));

// OPFS keeps the database after the tab closes only when the page sends these headers.
const coop = {
  "Cross-Origin-Opener-Policy": "same-origin",
  "Cross-Origin-Embedder-Policy": "require-corp",
};
config.devServer = config.devServer || {};
config.devServer.headers = Object.assign({}, config.devServer.headers, coop);
