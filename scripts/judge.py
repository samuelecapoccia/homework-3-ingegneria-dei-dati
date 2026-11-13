#!/usr/bin/env python3
"""
Strumento da terminale per i giudizi di rilevanza (pooling).

Per ogni query mostra il bisogno informativo e, uno alla volta, i documenti del pool
(titolo + abstract) in ordine casuale: chi giudica non sa quale configurazione li ha
restituiti ne' in che posizione (giudizio "alla cieca").

Tasti: 0 = non rilevante, 1 = parzialmente rilevante, 2 = molto rilevante,
       b = torna indietro (annulla l'ultimo giudizio), q = salva ed esci.
I giudizi vengono salvati subito in eval/qrels.tsv: si puo' interrompere e riprendere.

Uso: python3 scripts/judge.py
"""
import os
import random
import shutil
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUERIES, POOL = ROOT / "eval/queries.tsv", ROOT / "eval/pool.tsv"
QRELS, MAP, DOCS = ROOT / "eval/qrels.tsv", ROOT / "data/docs_map.tsv", ROOT / "data/docs"


def read_tsv(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    return [l.split("\t") for l in lines[1:] if l.strip()]


def main():
    queries = {r[0]: (r[1], r[2]) for r in read_tsv(QUERIES)}
    titles = {r[0]: r[5] for r in read_tsv(MAP)}
    pool = {}
    for qid, fname, *_ in read_tsv(POOL):
        pool.setdefault(qid, []).append(fname)
    todo = []
    for qid in sorted(pool):
        docs = sorted(pool[qid])
        random.Random(f"judge-{qid}").shuffle(docs)
        todo += [(qid, d) for d in docs]

    judged = {}
    if QRELS.exists():
        for qid, fname, rel in read_tsv(QRELS):
            judged[(qid, fname)] = int(rel)

    def save():
        with QRELS.open("w", encoding="utf-8") as f:
            f.write("qid\tfilename\trel\n")
            for (qid, fname), rel in sorted(judged.items()):
                f.write(f"{qid}\t{fname}\t{rel}\n")

    width = min(shutil.get_terminal_size((100, 30)).columns, 110) - 2
    history = []
    i = 0
    while i < len(todo):
        qid, fname = todo[i]
        if (qid, fname) in judged and (qid, fname) not in history:
            i += 1
            continue
        query, need = queries[qid]
        q_docs = [t for t in todo if t[0] == qid]
        pos = q_docs.index((qid, fname)) + 1
        abstract = (DOCS / fname).read_text(encoding="utf-8").strip()
        os.system("clear")
        print(f"[{qid}  documento {pos}/{len(q_docs)}  |  giudicati {len(judged)}/{len(todo)}]")
        print("=" * width)
        print(f"QUERY: {query}")
        print(textwrap.fill("BISOGNO: " + need, width))
        print("=" * width)
        print(textwrap.fill("TITOLO: " + titles.get(fname, fname), width))
        print()
        # vista compatta: inizio dell'abstract; 'a' mostra il testo completo
        short = abstract if len(abstract) <= 280 else abstract[:280].rsplit(" ", 1)[0] + " [...]"
        print(textwrap.fill(short, width))
        print("-" * width)
        if (qid, fname) in judged:
            print(f"(giudizio attuale: {judged[(qid, fname)]})")
        prompt = "0 = no | 1 = in parte | 2 = si' | a = abstract completo | b = indietro | q = esci > "
        ans = input(prompt).strip().lower()
        if ans == "a":
            print()
            print(textwrap.fill(abstract, width))
            print("-" * width)
            ans = input(prompt).strip().lower()
        if ans in ("0", "1", "2"):
            judged[(qid, fname)] = int(ans)
            save()
            history.append((qid, fname))
            i += 1
        elif ans == "b" and history:
            prev = history.pop()
            judged.pop(prev, None)
            save()
            i = todo.index(prev)
        elif ans == "q":
            save()
            print(f"Salvato. Giudicati {len(judged)}/{len(todo)}. Rilancia lo script per continuare.")
            return
    save()
    print(f"Finito! {len(judged)} giudizi salvati in {QRELS.relative_to(ROOT)}.")


if __name__ == "__main__":
    main()
