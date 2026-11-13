package hw3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Indicizza tutti i file .txt di una directory (ricorsivamente).
 *
 * Per ogni file crea un Document con:
 *   nome      - TextField (memorizzato): nome del file senza estensione, analyzer "nome"
 *   contenuto - TextField (memorizzato, per mostrare un'anteprima): testo del file
 *   path      - StringField (memorizzato): nome del file completo, identificativo esatto
 *
 * Uso: bin/index.sh [--docs data/docs] [--index index/english] [--analyzer english] [--limit N]
 */
public final class Indexer {

    /** Tempi (nanosecondi) e dimensioni di una indicizzazione. */
    public record Stats(int docs, long bytesRead, long readNanos, long addNanos,
                        long commitNanos, long indexBytes) {
        public long totalNanos() { return readNanos + addNanos + commitNanos; }
        public double docsPerSecond() { return docs / (totalNanos() / 1e9); }
    }

    private Indexer() {}

    /** Tutti i .txt sotto dir, in ordine alfabetico (ordine deterministico). */
    public static List<Path> listTxt(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    /**
     * Costruisce l'indice. La lettura dei file e' separata dall'indicizzazione vera e propria
     * per poter misurare i due tempi in modo distinto.
     */
    public static Stats build(Path docsDir, Path indexDir, String variant, int limit) throws IOException {
        List<Path> files = listTxt(docsDir);
        if (limit > 0 && limit < files.size()) {
            files = files.subList(0, limit);
        }

        // 1) lettura dei file
        long t0 = System.nanoTime();
        List<String> names = new ArrayList<>(files.size());
        List<String> texts = new ArrayList<>(files.size());
        long bytes = 0;
        for (Path p : files) {
            byte[] raw = Files.readAllBytes(p);
            bytes += raw.length;
            names.add(p.getFileName().toString());
            texts.add(new String(raw, StandardCharsets.UTF_8));
        }
        long t1 = System.nanoTime();

        // 2) analisi + indicizzazione
        IndexWriterConfig config = new IndexWriterConfig(Analyzers.perField(variant));
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);   // ricrea l'indice da zero
        long t2, t3;
        try (Directory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir, config)) {
            for (int i = 0; i < names.size(); i++) {
                String fileName = names.get(i);
                Document doc = new Document();
                doc.add(new TextField(Analyzers.NOME, stripExtension(fileName), Field.Store.YES));
                doc.add(new TextField(Analyzers.CONTENUTO, texts.get(i), Field.Store.YES));
                doc.add(new StringField(Analyzers.PATH, fileName, Field.Store.YES));
                writer.addDocument(doc);
            }
            t2 = System.nanoTime();
            // 3) commit: rende visibili i documenti alla ricerca
            writer.commit();
            t3 = System.nanoTime();
        }
        return new Stats(names.size(), bytes, t1 - t0, t2 - t1, t3 - t2, directorySize(indexDir));
    }

    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    static long directorySize(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    public static void main(String[] args) throws IOException {
        Path docs = Paths.get("data/docs");
        Path index = null;
        String variant = "english";
        int limit = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docs" -> docs = Paths.get(args[++i]);
                case "--index" -> index = Paths.get(args[++i]);
                case "--analyzer" -> variant = args[++i];
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("argomento sconosciuto: " + args[i]);
            }
        }
        if (index == null) {
            index = Paths.get("index", variant);
        }
        System.out.printf("Indicizzo %s -> %s (analyzer contenuto: %s, nome: custom)%n", docs, index, variant);
        Stats s = build(docs, index, variant, limit);
        System.out.printf(Locale.ROOT,
                "Documenti indicizzati: %d (%.1f MB di testo)%n"
                        + "Lettura file:   %8.0f ms%n"
                        + "Indicizzazione: %8.0f ms%n"
                        + "Commit:         %8.0f ms%n"
                        + "Totale:         %8.0f ms  (%.0f doc/s)%n"
                        + "Dimensione indice: %.1f MB%n",
                s.docs(), s.bytesRead() / 1e6, s.readNanos() / 1e6, s.addNanos() / 1e6,
                s.commitNanos() / 1e6, s.totalNanos() / 1e6, s.docsPerSecond(), s.indexBytes() / 1e6);
    }
}
