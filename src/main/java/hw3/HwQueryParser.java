package hw3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.QueryBuilder;

/**
 * Parser della sintassi di query dell'homework.
 *
 * <pre>
 *   query   := campo elemento+
 *   campo   := "nome" | "contenuto"
 *   elemento:= [+|-] termine  |  [+|-] "frase esatta"
 * </pre>
 *
 * Semantica (la stessa del QueryParser classico di Lucene vista a lezione):
 * senza prefisso l'elemento e' opzionale (SHOULD, contribuisce al ranking BM25),
 * '+' lo rende obbligatorio (MUST), '-' lo esclude (MUST_NOT).
 * Il testo tra virgolette diventa una PhraseQuery. Termini e frasi vengono analizzati
 * con lo stesso analyzer usato in indicizzazione per quel campo.
 */
public final class HwQueryParser {

    /** Risultato del parsing: campo, query Lucene ed eventuali avvisi per l'utente. */
    public record Parsed(String field, Query query, List<String> warnings) {}

    private static final Pattern ELEMENT = Pattern.compile("([+-]?)\"([^\"]*)\"?|(\\S+)");

    private final QueryBuilder builder;

    public HwQueryParser(Analyzer perFieldAnalyzer) {
        this.builder = new QueryBuilder(perFieldAnalyzer);
    }

    public static String fieldFor(String keyword) {
        return switch (keyword.toLowerCase(Locale.ROOT)) {
            case "nome" -> Analyzers.NOME;
            case "contenuto", "contentuto" -> Analyzers.CONTENUTO;   // "contentuto": grafia della traccia
            default -> null;
        };
    }

    public Parsed parse(String line) {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("query vuota");
        }
        String[] parts = trimmed.split("\\s+", 2);
        String field = fieldFor(parts[0]);
        if (field == null) {
            throw new IllegalArgumentException(
                    "la query deve iniziare con 'nome' o 'contenuto' (trovato: '" + parts[0] + "')");
        }
        if (parts.length < 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException("nessun termine dopo '" + parts[0] + "'");
        }

        List<String> warnings = new ArrayList<>();
        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        StringBuilder optionalTerms = new StringBuilder();
        int positive = 0;

        Matcher m = ELEMENT.matcher(parts[1]);
        while (m.find()) {
            if (m.group(2) != null) {                       // "frase"
                Occur occur = occurFor(m.group(1));
                Query q = builder.createPhraseQuery(field, m.group(2));
                if (q == null) {
                    warnings.add("frase ignorata (solo stopword o vuota): \"" + m.group(2) + "\"");
                    continue;
                }
                bq.add(q, occur);
                if (occur != Occur.MUST_NOT) positive++;
            } else {                                        // termine
                String tok = m.group(3);
                Occur occur = Occur.SHOULD;
                if (tok.length() > 1 && (tok.charAt(0) == '+' || tok.charAt(0) == '-')) {
                    occur = occurFor(tok.substring(0, 1));
                    tok = tok.substring(1);
                }
                if (occur == Occur.SHOULD) {                // i termini opzionali vengono analizzati insieme
                    optionalTerms.append(tok).append(' ');
                    continue;
                }
                // un termine puo' produrre piu' token (es. "open-domain"): devono esserci tutti
                Query q = builder.createBooleanQuery(field, tok, Occur.MUST);
                if (q == null) {
                    warnings.add("termine ignorato (stopword): " + tok);
                    continue;
                }
                bq.add(q, occur);
                if (occur != Occur.MUST_NOT) positive++;
            }
        }

        if (!optionalTerms.isEmpty()) {
            Query q = builder.createBooleanQuery(field, optionalTerms.toString(), Occur.SHOULD);
            if (q == null) {
                warnings.add("termini ignorati (solo stopword): " + optionalTerms.toString().strip());
            } else if (q instanceof BooleanQuery inner) {
                for (BooleanClause c : inner.clauses()) {    // appiattisce: un unico livello di clausole
                    bq.add(c);
                    positive++;
                }
            } else {
                bq.add(q, Occur.SHOULD);
                positive++;
            }
        }

        if (positive == 0) {
            throw new IllegalArgumentException(
                    "nessun termine positivo da cercare" + (warnings.isEmpty() ? "" : " " + warnings));
        }
        return new Parsed(field, bq.build(), warnings);
    }

    private static Occur occurFor(String prefix) {
        return switch (prefix) {
            case "+" -> Occur.MUST;
            case "-" -> Occur.MUST_NOT;
            default -> Occur.SHOULD;
        };
    }
}
