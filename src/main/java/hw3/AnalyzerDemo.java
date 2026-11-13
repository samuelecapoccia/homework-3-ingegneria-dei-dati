package hw3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Mostra i token prodotti dagli analyzer per un testo (utile per verificare le scelte).
 * Uso: bin/analyze.sh "Open-Domain_Question_Answering" "Neural models improve retrieval."
 */
public final class AnalyzerDemo {

    static List<String> tokens(Analyzer a, String field, String text) throws IOException {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = a.tokenStream(field, text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(term.toString());
            ts.end();
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        for (String text : args) {
            System.out.println("Testo: " + text);
            System.out.println("  nome (custom)        " + tokens(Analyzers.nome(), Analyzers.NOME, text));
            for (String v : new String[] {"english", "standard", "whitespace"}) {
                System.out.printf("  contenuto (%-10s) %s%n", v, tokens(Analyzers.contenuto(v), Analyzers.CONTENUTO, text));
            }
        }
    }
}
