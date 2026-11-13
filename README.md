# Homework 3 – Ingegneria dei Dati 2026/2027

Motore di ricerca su file `.txt` realizzato con **Apache Lucene 10.6.0** (Java 21+).

Il dataset è costituito da **28.162 articoli arXiv della categoria cs.IR** (Information Retrieval).
Ogni articolo è un file `.txt`: il **nome del file** è il titolo dell'articolo, il **contenuto** è l'abstract.

## Struttura

| Percorso | Contenuto |
|---|---|
| `scripts/build_dataset.py` | Scarica gli articoli da arXiv e crea `data/docs/*.txt`, `data/docs_map.tsv` e `data/dataset_stats.json` |
| `src/main/java/hw3/Analyzers.java` | Gli analyzer dei due campi (`nome`, `contenuto`) |
| `src/main/java/hw3/Indexer.java` | Indicizza una directory di file `.txt` |
| `src/main/java/hw3/HwQueryParser.java` | Il parser della sintassi di query |
| `src/main/java/hw3/Searcher.java` | Legge le query da console e stampa i risultati |
| `src/main/java/hw3/IndexingBenchmark.java` | Misura i tempi di indicizzazione |
| `src/main/java/hw3/Evaluate.java` | Pooling, metriche (P@k, Recall, MAP, MRR, nDCG) e valutazione known-item |
| `scripts/judge.py` | Strumento da terminale per i giudizi di rilevanza |
| `eval/` | Le query di valutazione, il pool e i giudizi (`qrels.tsv`) |
| `results/` | I risultati degli esperimenti in formato CSV |

## Come riprodurre

Prerequisiti: Java 21 o superiore, Maven, Python 3 (va bene anche solo la libreria standard).

```bash
python3 scripts/build_dataset.py          # ~2 minuti: scarica arXiv cs.IR e crea data/docs/
mvn -q package                            # compila
bin/index.sh --analyzer english           # crea index/english (indice principale)
bin/search.sh                             # ricerca interattiva
```

Esperimenti:

```bash
bin/index.sh --analyzer standard && bin/index.sh --analyzer whitespace   # indici di confronto
bin/benchmark.sh                          # tempi di indicizzazione  -> results/indexing_*.csv
bin/evaluate.sh pool                      # pool da giudicare         -> eval/pool.tsv
python3 scripts/judge.py                  # giudizi di rilevanza      -> eval/qrels.tsv
bin/evaluate.sh metrics                   # metriche                  -> results/metrics_*.csv
bin/evaluate.sh knownitem                 # known-item search         -> results/knownitem.csv
bin/analyze.sh "Open-Domain_Question_Answering"   # mostra i token prodotti dagli analyzer
```

La relazione è in [`relazione/Relazione_Homework3.pdf`](relazione/Relazione_Homework3.pdf).

## Sintassi delle query

```
nome|contenuto  termine  "frase esatta"  +obbligatorio  -escluso
```

- `nome` cerca nel nome del file, `contenuto` cerca nel testo.
- Le parole tra virgolette formano una **phrase query**.
- Gli elementi senza prefisso sono opzionali: più ne trova un documento, più alto è il suo punteggio (BM25).
- `+` rende un elemento obbligatorio, `-` lo esclude.

Esempi:

```
contenuto "query expansion" pseudo relevance feedback
nome +recommender survey -music
```

Comandi della console: `:help`, `:k N` (numero di risultati), `:explain R` (spiega il punteggio del risultato R), `:quit`.
