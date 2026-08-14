// dates.js
// Calcule les noms courts/complets, numéros de mois relatifs et les années correspondantes
// Version hautement compatible (var)

var monthLabels = [
    "Janv.", "Févr.", "Mars", "Avr.", "Mai", "Juin",
    "Juil.", "Août", "Sept.", "Oct.", "Nov.", "Déc."
];

var monthLabelsFull = [
    "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
    "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"
];

function formatMonthNumber(monthIndex) {
    // monthIndex va de 0 à 11, on ajoute 1 pour avoir de 1 à 12
    var num = monthIndex + 1;
    return num < 10 ? "0" + num : "" + num;
}

function getRelativeDate(offsetMonths) {
    var d = new Date();
    d.setDate(15);
    d.setMonth(d.getMonth() + offsetMonths);

    var monthIndex = d.getMonth();

    return {
        month: monthLabels[monthIndex],
        monthFull: monthLabelsFull[monthIndex].toLowerCase(),
        monthNumber: formatMonthNumber(monthIndex),
        year: d.getFullYear()
    };
}

var prevDate = getRelativeDate(-1);
var currDate = getRelativeDate(0);
var nextDate = getRelativeDate(1);

// Exportation EXPLICITE via l'objet global output de Maestro
output.PREV_MONTH_NAME = prevDate.month;
output.PREV_MONTH_FULL_NAME = prevDate.monthFull;
output.PREV_MONTH_NUM = prevDate.monthNumber;
output.PREV_YEAR = prevDate.year;

output.CURRENT_MONTH_NAME = currDate.month;
output.CURRENT_MONTH_FULL_NAME = currDate.monthFull;
output.CURRENT_MONTH_NUM = currDate.monthNumber;
output.CURRENT_YEAR = currDate.year;

output.NEXT_MONTH_NAME = nextDate.month;
output.NEXT_MONTH_FULL_NAME = nextDate.monthFull;
output.NEXT_MONTH_NUM = nextDate.monthNumber;
output.NEXT_YEAR = nextDate.year;

// Log pour la console Maestro
console.log("Maestro Dates Initialized: Prev=" + output.PREV_MONTH_FULL_NAME + " / " + output.PREV_MONTH_NAME + " / " + output.PREV_MONTH_NUM + " (" + output.PREV_YEAR + ")");
console.log("Maestro Dates Initialized: Curr=" + output.CURRENT_MONTH_FULL_NAME + " / " + output.CURRENT_MONTH_NAME + " / " + output.CURRENT_MONTH_NUM + " (" + output.CURRENT_YEAR + ")");
console.log("Maestro Dates Initialized: Next=" + output.NEXT_MONTH_FULL_NAME + " / " + output.NEXT_MONTH_NAME + " / " + output.NEXT_MONTH_NUM + " (" + output.NEXT_YEAR + ")");