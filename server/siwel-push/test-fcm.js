// Test ponctuel : vérifie que l'API FCM accepte nos identifiants (envoi à un jeton bidon).
// Résultat attendu : erreur "invalid-argument" ou "registration-token-not-registered"
// = l'authentification fonctionne (seul le jeton est faux). Toute autre erreur = problème de droits.
const admin = require("firebase-admin");
const sa = require(process.env.SIWEL_SERVICE_ACCOUNT || "./serviceAccountKey.json");
admin.initializeApp({ credential: admin.credential.cert(sa) });
admin
  .messaging()
  .send({ token: "DUMMY_INVALID_TOKEN_FOR_TEST", data: { t: "x" } })
  .then((r) => console.log("INATTENDU_SUCCES", r))
  .catch((e) => console.log("ERRCODE:", e.code || e.message))
  .finally(() => process.exit(0));
