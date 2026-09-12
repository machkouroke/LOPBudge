import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Génère le catalogue de devises embarqué (LOP-58, P-1).
 *
 *   java scripts/GenerateCurrencyCatalog.java
 *
 * La sortie est versionnée avec l'application et ne s'édite pas à la main : une génération
 * intégrale à l'exécution donnerait plus de 250 entrées — codes de fonds et devises mortes
 * compris — et son contenu varierait d'une version d'Android à l'autre.
 *
 * Filtre retenu : une devise est retenue si au moins un pays ISO l'utilise **aujourd'hui**.
 * Cela écarte d'un coup les devises retirées (DEM, ATS…) et les codes techniques (XAU, XDR,
 * XXX…), qu'aucune locale de pays ne désigne.
 */
public final class GenerateCurrencyCatalog {

    /** Ordre d'affichage des devises courantes (P-3). Le reste suit par code croissant. */
    private static final List<String> PREFERRED =
            List.of("EUR", "USD", "GBP", "CHF", "CAD", "JPY", "XOF", "MAD");

    /**
     * Pays de référence imposés (P-2), là où la règle générale ne tranche pas.
     * `EU` n'est pas un pays ISO mais ses deux lettres produisent bien 🇪🇺.
     */
    private static final Map<String, String> COUNTRY_OVERRIDES = Map.of("EUR", "EU");

    private static final Path OUTPUT = Path.of(
            "app/src/main/java/com/lop/budget/domain/model/CurrencyCatalogData.kt");

    public static void main(String[] args) throws IOException {
        Map<Currency, Set<String>> countriesByCurrency = new TreeMap<>(
                Comparator.comparing(Currency::getCurrencyCode));

        for (String country : Locale.getISOCountries()) {
            Currency currency;
            try {
                currency = Currency.getInstance(Locale.of("", country));
            } catch (IllegalArgumentException e) {
                continue; // Territoire sans devise propre (Antarctique…).
            }
            if (currency == null || currency.getDefaultFractionDigits() < 0) {
                continue; // Pseudo-devise : fonds, métal, code d'essai.
            }
            countriesByCurrency
                    .computeIfAbsent(currency, c -> new LinkedHashSet<>())
                    .add(country);
        }

        List<Currency> ordered = new ArrayList<>(countriesByCurrency.keySet());
        ordered.sort(Comparator
                .comparingInt((Currency c) -> {
                    int rank = PREFERRED.indexOf(c.getCurrencyCode());
                    return rank < 0 ? PREFERRED.size() : rank;
                })
                .thenComparing(Currency::getCurrencyCode));

        StringBuilder out = new StringBuilder();
        out.append("package com.lop.budget.domain.model\n")
                .append("\n")
                .append("// GÉNÉRÉ PAR scripts/GenerateCurrencyCatalog.java — NE PAS ÉDITER À LA MAIN.\n")
                .append("//\n")
                .append("// Devises en usage dans au moins un pays ISO, nom et symbole résolus pour la locale\n")
                .append("// française — la même que celle qui formate les montants (Format.money).\n")
                .append("// Ordre d'affichage figé ici (P-3) : un filtre de recherche le conserve sans le retrier.\n")
                .append("internal val CURRENCY_CATALOG: List<AppCurrency> = listOf(\n");

        for (Currency currency : ordered) {
            String code = currency.getCurrencyCode();
            Set<String> countries = countriesByCurrency.get(currency);
            out.append("    AppCurrency(")
                    .append(kotlinString(code)).append(", ")
                    .append(kotlinString(displayName(currency))).append(", ")
                    .append(kotlinString(currency.getSymbol(Locale.FRANCE))).append(", ")
                    .append(kotlinNullableString(referenceCountry(code, countries)))
                    .append("),\n");
        }

        out.append(")\n");

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, out.toString());
        System.out.println(ordered.size() + " devises écrites dans " + OUTPUT);
    }

    /**
     * Pays dont le drapeau représente la devise (P-2).
     *
     * Une devise partagée par plusieurs pays n'en a un que si ses deux premières lettres
     * désignent l'un d'eux — la convention ISO 4217 pour la devise principale d'un pays.
     * Sinon (`XOF`, `XAF`, `XCD`…) le catalogue porte `null` et la ligne reçoit le substitut.
     */
    private static String referenceCountry(String code, Set<String> countries) {
        String override = COUNTRY_OVERRIDES.get(code);
        if (override != null) {
            return override;
        }
        if (countries.size() == 1) {
            return countries.iterator().next();
        }
        String fromCode = code.substring(0, 2);
        return countries.contains(fromCode) ? fromCode : null;
    }

    /** « euro » → « Euro » : le libellé ouvre une ligne de liste. */
    private static String displayName(Currency currency) {
        String name = currency.getDisplayName(Locale.FRENCH);
        return name.isEmpty() ? currency.getCurrencyCode()
                : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Le `$` de « $US » ouvrirait un template Kotlin. */
    private static String kotlinString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$") + "\"";
    }

    private static String kotlinNullableString(String value) {
        return value == null ? "null" : kotlinString(value);
    }
}
