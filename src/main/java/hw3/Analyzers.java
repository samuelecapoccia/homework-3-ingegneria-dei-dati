package hw3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * Analyzer usati per i due campi dell'indice.
 *
 * <ul>
 *   <li><b>nome</b> (nome del file, es. {@code Dense_Passage_Retrieval_for_Open-Domain_QA}):
 *       whitespace -> wordDelimiterGraph -> lowercase -> asciiFolding.
 *       WordDelimiterGraph spezza su '_' e '-' (i separatori tipici dei nomi di file);
 *       non spezza su cambio maiuscole/minuscole ne' tra lettere e cifre, cosi' sigle come
 *       ColBERT, BERT4Rec, T5, word2vec restano un unico termine. Niente stopword ne'
 *       stemming: un nome di file e' corto e ogni parola e' informativa.</li>
 *   <li><b>contenuto</b> (abstract in inglese): EnglishAnalyzer (StandardTokenizer,
 *       possessivi inglesi, lowercase, stopword inglesi, stemmer di Porter).
 *       Le varianti "standard" e "whitespace" servono solo per gli esperimenti di confronto.</li>
 * </ul>
 * Lo stesso analyzer viene usato in indicizzazione e in ricerca (PerFieldAnalyzerWrapper).
 */
public final class Analyzers {

    public static final String NOME = "nome";
    public static final String CONTENUTO = "contenuto";
    public static final String PATH = "path";

    private Analyzers() {}

    public static Analyzer nome() {
        try {
            return CustomAnalyzer.builder()
                    .withTokenizer("whitespace")
                    .addTokenFilter("wordDelimiterGraph",
                            "generateWordParts", "1",
                            "generateNumberParts", "1",
                            "splitOnCaseChange", "0",
                            "splitOnNumerics", "0",
                            "catenateWords", "0",
                            "preserveOriginal", "0",
                            "stemEnglishPossessive", "1")
                    // richiesto dopo un filtro "graph" quando si indicizza
                    .addTokenFilter("flattenGraph")
                    .addTokenFilter("lowercase")
                    .addTokenFilter("asciiFolding")
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Analyzer contenuto(String variant) {
        return switch (variant) {
            case "english" -> new EnglishAnalyzer();
            case "standard" -> new StandardAnalyzer();
            case "whitespace" -> new WhitespaceAnalyzer();
            default -> throw new IllegalArgumentException(
                    "analyzer sconosciuto: " + variant + " (usa english|standard|whitespace)");
        };
    }

    /** Analyzer per campo: "nome" ha il suo analyzer, tutto il resto usa quello del contenuto. */
    public static Analyzer perField(String contenutoVariant) {
        return new PerFieldAnalyzerWrapper(contenuto(contenutoVariant), Map.of(NOME, nome()));
    }
}
