package hw3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;

/**
 * Legge query da console, interroga l'indice e stampa i risultati.
 *
 * Sintassi:  nome|contenuto  termine  "frase esatta"  +obbligatorio  -escluso
 * Comandi:   :help   :k N (numero risultati)   :explain R (spiega lo score del risultato R)   :quit
 *
 * Uso interattivo: bin/search.sh [--index index/english] [--analyzer english] [--k 10]
 * Uso non interattivo: bin/search.sh --query 'contenuto "query expansion" feedback' [--query ...]
 */
public final class Searcher implements AutoCloseable {

    /** Un risultato della ricerca. */
    public record Hit(int rank, int docId, float score, String fileName, String nome, String contenuto) {}

    /** Risultato completo di una query. */
    public record Result(HwQueryParser.Parsed parsed, long totalHits, double millis, List<Hit> hits) {}

    private final IndexReader reader;
    private final IndexSearcher searcher;
    private final HwQueryParser parser;

    public Searcher(Path indexDir, String variant) throws IOException {
        this.reader = DirectoryReader.open(FSDirectory.open(indexDir));
        this.searcher = new IndexSearcher(reader);          // Similarity di default: BM25
        this.parser = new HwQueryParser(Analyzers.perField(variant));
    }

    public int numDocs() { return reader.numDocs(); }

    public IndexSearcher indexSearcher() { return searcher; }

    public Result search(String line, int k) throws IOException {
        long t0 = System.nanoTime();
        HwQueryParser.Parsed parsed = parser.parse(line);
        TopDocs top = searcher.search(parsed.query(), k);
        long total = searcher.count(parsed.query());        // conteggio esatto dei documenti che matchano
        double ms = (System.nanoTime() - t0) / 1e6;
        StoredFields stored = searcher.storedFields();
        List<Hit> hits = new ArrayList<>(top.scoreDocs.length);
        for (int i = 0; i < top.scoreDocs.length; i++) {
            ScoreDoc sd = top.scoreDocs[i];
            Document d = stored.document(sd.doc);
            hits.add(new Hit(i + 1, sd.doc, sd.score, d.get(Analyzers.PATH),
                    d.get(Analyzers.NOME), d.get(Analyzers.CONTENUTO)));
        }
        return new Result(parsed, total, ms, hits);
    }

    public static void print(String line, Result r) {
        System.out.println("Query:        " + line);
        System.out.println("Query Lucene: " + r.parsed().query());
        for (String w : r.parsed().warnings()) {
            System.out.println("Avviso:       " + w);
        }
        System.out.printf(Locale.ROOT, "Risultati:    %d documenti (%.1f ms)%n", r.totalHits(), r.millis());
        for (Hit h : r.hits()) {
            System.out.printf(Locale.ROOT, "%3d. [%6.3f] %s%n", h.rank(), h.score(), h.fileName());
            System.out.println("      " + preview(h.contenuto(), 150));
        }
        System.out.println();
    }

    static String preview(String text, int max) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    @Override
    public void close() throws IOException { reader.close(); }

    private static void help() {
        System.out.println("""
                Sintassi:  nome|contenuto  termine  "frase esatta"  +obbligatorio  -escluso
                  nome       cerca nel nome del file (titolo dell'articolo)
                  contenuto  cerca nel contenuto del file (abstract)
                  senza prefisso = opzionale (piu' termini trovati => score piu' alto)
                Esempi:
                  contenuto "query expansion" pseudo relevance feedback
                  nome +recommender survey -music
                Comandi: :help  :k N  :explain R  :quit
                """);
    }

    public static void main(String[] args) throws IOException {
        String variant = "english";
        Path index = null;
        int k = 10;
        List<String> queries = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--index" -> index = Paths.get(args[++i]);
                case "--analyzer" -> variant = args[++i];
                case "--k" -> k = Integer.parseInt(args[++i]);
                case "--query" -> queries.add(args[++i]);
                default -> throw new IllegalArgumentException("argomento sconosciuto: " + args[i]);
            }
        }
        if (index == null) {
            index = Paths.get("index", variant);
        }

        try (Searcher s = new Searcher(index, variant)) {
            if (!queries.isEmpty()) {                       // modalita' non interattiva
                for (String q : queries) {
                    try {
                        print(q, s.search(q, k));
                    } catch (IllegalArgumentException e) {
                        System.out.println("Query:  " + q + "\nErrore: " + e.getMessage() + "\n");
                    }
                }
                return;
            }

            System.out.printf("Indice %s: %d documenti (analyzer contenuto: %s). Scrivi :help per la sintassi.%n",
                    index, s.numDocs(), variant);
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            Result last = null;
            while (true) {
                System.out.print("> ");
                System.out.flush();
                String line = in.readLine();
                if (line == null) break;
                line = line.strip();
                if (line.isEmpty()) continue;
                if (line.equals(":quit") || line.equals(":q") || line.equalsIgnoreCase("esci")) break;
                if (line.equals(":help")) { help(); continue; }
                if (line.startsWith(":k ")) {
                    k = Integer.parseInt(line.substring(3).strip());
                    System.out.println("Risultati mostrati: " + k);
                    continue;
                }
                if (line.startsWith(":explain ")) {
                    int rank = Integer.parseInt(line.substring(9).strip());
                    if (last == null || rank < 1 || rank > last.hits().size()) {
                        System.out.println("Nessun risultato con quel numero.");
                    } else {
                        Hit h = last.hits().get(rank - 1);
                        Query q = last.parsed().query();
                        System.out.println(s.indexSearcher().explain(q, h.docId()));
                    }
                    continue;
                }
                try {
                    last = s.search(line, k);
                    print(line, last);
                } catch (IllegalArgumentException e) {
                    System.out.println("Errore: " + e.getMessage());
                    help();
                }
            }
        }
    }
}
