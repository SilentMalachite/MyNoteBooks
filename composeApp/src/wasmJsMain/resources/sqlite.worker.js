import sqlite3InitModule from "./sqlite/index.mjs";

let db = null;

async function createDatabase() {
  const sqlite3 = await sqlite3InitModule();
  let mode = "memory";
  if ("opfs" in sqlite3 && sqlite3.oo1.OpfsDb) {
    db = new sqlite3.oo1.OpfsDb("/notebook.db");
    mode = "opfs";
  } else {
    db = new sqlite3.oo1.DB("/notebook.db", "ct");
  }
  // The app reads this with SELECT, so the stock driver needs no new message.
  db.createFunction("storage_mode", () => mode, { deterministic: true });
}

function resultRows(sql, params) {
  const values = db.exec({
    sql,
    bind: params,
    returnValue: "resultRows",
  });
  return { values: values ?? [] };
}

function handleMessage() {
  const data = this.data;
  const params = data && data.params == null ? [] : Array.from(data.params);
  switch (data && data.action) {
    case "exec":
      if (!data.sql) throw new Error("exec: Missing query string");
      return postMessage({ id: data.id, results: resultRows(data.sql, params) });
    case "begin_transaction":
      db.exec("BEGIN IMMEDIATE;");
      return postMessage({ id: data.id, results: { values: [] } });
    case "end_transaction":
      db.exec("END TRANSACTION;");
      return postMessage({ id: data.id, results: { values: [] } });
    case "rollback_transaction":
      db.exec("ROLLBACK TRANSACTION;");
      return postMessage({ id: data.id, results: { values: [] } });
    case "commit_batch":
      return postMessage({ id: data.id, results: commitBatch(data.statements || []) });
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

function commitBatch(statements) {
  try {
    db.exec("ROLLBACK;");
  } catch (err) {
    // There is no open transaction to clear.
  }
  db.exec("BEGIN IMMEDIATE;");
  try {
    for (const statement of statements) {
      db.exec({
        sql: statement.sql,
        bind: statement.params || [],
        returnValue: "resultRows",
      });
    }
    db.exec("COMMIT;");
  } catch (err) {
    try {
      db.exec("ROLLBACK;");
    } catch (rollbackErr) {
      // The failed batch is already being reported.
    }
    throw err;
  }
  return { values: [] };
}

function handleError(err) {
  const error = err instanceof Error
    ? { message: err.message, name: err.name }
    : { message: String(err), name: "Error" };
  postMessage({ id: this.data && this.data.id, error });
}

let ready = null;
self.onmessage = (event) => {
  ready = ready ?? createDatabase();
  ready.then(() => handleMessage.call(event)).catch((err) => handleError.call(event, err));
};
