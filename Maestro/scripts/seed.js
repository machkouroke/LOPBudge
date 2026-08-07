// seed.js
// Déclenche le seeding de la base de données via ADB
// Usage dans Maestro : - runScript: scripts/seed.js { scenario: 'TC_30' }

runShell('am broadcast -a com.lop.budget.ACTION_SEED --es scenario ' + scenario);
// Laisser un peu de temps pour que le seeding se termine
output.status = "Seeded " + scenario;
maestro.sleep(2000);
