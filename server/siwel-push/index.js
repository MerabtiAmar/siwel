// Démon Siwel — envoie une push FCM aux destinataires quand un appel est créé dans Firebase.
// Tourne 24/7 sur le serveur Oracle (systemd: siwel-push.service). Aucun coût : Oracle Always Free.

const admin = require("firebase-admin");
const serviceAccount = require(process.env.SIWEL_SERVICE_ACCOUNT || "./serviceAccountKey.json");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.SIWEL_DATABASE_URL,
});

const db = admin.database();
const callsRef = db.ref("siwel/calls");
const tokensRef = db.ref("siwel/tokens");

// callIds déjà traités → évite les doublons et les ré-envois au démarrage.
const processed = new Set();

async function notify(callId, call) {
  const initiator = call.initiator;
  const members = call.members || [];
  const recipients = members.filter((m) => m !== initiator);

  const tokensSnap = await tokensRef.once("value");
  const tokens = tokensSnap.val() || {};

  for (const name of recipients) {
    const token = tokens[name];
    if (!token) {
      console.log("[skip] pas de jeton pour", name);
      continue;
    }
    try {
      await admin.messaging().send({
        token,
        data: {
          type: "incoming_call",
          callId: String(callId),
          initiator: String(initiator),
          members: members.join(","),
        },
        android: { priority: "high" },
      });
      console.log("[push] envoyé à", name, "(appel " + callId + ")");
    } catch (e) {
      console.error("[err] échec push", name, e.code || e.message);
      // Jeton périmé → on le retire pour ne plus réessayer.
      if (e.code === "messaging/registration-token-not-registered") {
        tokensRef.child(name).remove();
      }
    }
  }
}

callsRef.once("value").then((snap) => {
  snap.forEach((c) => processed.add(c.key));
  console.log("Démon prêt. Appels existants ignorés:", processed.size);

  callsRef.on("child_added", async (childSnap) => {
    const callId = childSnap.key;
    if (processed.has(callId)) return;
    processed.add(callId);

    const call = childSnap.val();
    if (!call || call.status !== "ringing") return;

    console.log("[appel] nouveau:", callId, "de", call.initiator);
    try {
      await notify(callId, call);
    } catch (e) {
      console.error("[err] notify:", e);
    }
  });

  callsRef.on("child_removed", (childSnap) => processed.delete(childSnap.key));
});

process.on("uncaughtException", (e) => console.error("uncaught", e));
