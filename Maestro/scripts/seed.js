// seed.js
// Déclenche le seeding de la base de données via Deep Link (compatible Local et Cloud)
// Usage dans Maestro : - runScript: scripts/seed.js { scenario: 'TC_30' }

// Utilisation d'un deep link car plus fiable sur toutes les plateformes Maestro
maestro.openApp("lopbudge://seed?scenario=" + scenario);

// Laisser un peu de temps pour que le seeding se termine
output.status = "Seeded via Deep Link: " + scenario;
maestro.sleep(2000);
