// dates.js
// Calcule les noms des mois relatifs et les années correspondantes
// Version hautement compatible (var)

var monthLabels = [
    "Janv.", "Févr.", "Mars", "Avr.", "Mai", "Juin",
    "Juil.", "Août", "Sept.", "Oct.", "Nov.", "Déc."
];

function getRelativeDate(offsetMonths) {
    var d = new Date();
    d.setDate(15);
    d.setMonth(d.getMonth() + offsetMonths);

    return {
        label: monthLabels[d.getMonth()],
        year: d.getFullYear()
    };
}

var prevDate = getRelativeDate(-1);
var currDate = getRelativeDate(0);
var nextDate = getRelativeDate(1);

// Exportation EXPLICITE via l'objet global output de Maestro
output.PREV_MONTH_NAME = prevDate.label;
output.PREV_YEAR = prevDate.year;

output.CURRENT_MONTH_NAME = currDate.label;
output.CURRENT_YEAR = currDate.year;

output.NEXT_MONTH_NAME = nextDate.label;
output.NEXT_YEAR = nextDate.year;

// Log pour la console Maestro
console.log("Maestro Dates Initialized: Prev=" + output.PREV_MONTH_NAME + " (" + output.PREV_YEAR + ")");
console.log("Maestro Dates Initialized: Curr=" + output.CURRENT_MONTH_NAME + " (" + output.CURRENT_YEAR + ")");
console.log("Maestro Dates Initialized: Next=" + output.NEXT_MONTH_NAME + " (" + output.NEXT_YEAR + ")");
