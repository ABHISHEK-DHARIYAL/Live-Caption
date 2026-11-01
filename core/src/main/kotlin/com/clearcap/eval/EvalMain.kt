package com.clearcap.eval

import java.io.File

/**
 * Command-line scorer: `EvalMainKt <reference.txt> <hypothesis.txt>` prints WER and CER.
 * Or: `EvalMainKt --dir <refDir> <hypDir>` scores every matching file name and prints totals.
 * Used by docs/EVALUATION.md so measured numbers can be reproduced from the same transcripts.
 */
fun main(args: Array<String>) {
    if (args.size == 3 && args[0] == "--dir") {
        var w = EditStats(0, 0, 0, 0); var c = EditStats(0, 0, 0, 0)
        val refs = File(args[1]).listFiles { f -> f.extension == "txt" }?.sortedBy { it.name } ?: emptyList()
        for (r in refs) {
            val h = File(args[2], r.name)
            if (!h.exists()) { println("MISSING hypothesis for ${r.name}"); continue }
            val ref = r.readText(); val hyp = h.readText()
            val ws = ErrorRates.wer(ref, hyp); val cs = ErrorRates.cer(ref, hyp)
            println("%-30s WER %s  CER %s".format(r.name, pct(ws), pct(cs)))
            w = EditStats(w.substitutions + ws.substitutions, w.deletions + ws.deletions, w.insertions + ws.insertions, w.refLength + ws.refLength)
            c = EditStats(c.substitutions + cs.substitutions, c.deletions + cs.deletions, c.insertions + cs.insertions, c.refLength + cs.refLength)
        }
        println("TOTAL (${refs.size} files)   WER ${pct(w)}  CER ${pct(c)}   [words=${w.refLength}, chars=${c.refLength}]")
        return
    }
    if (args.size != 2) { println("usage: EvalMainKt <reference.txt> <hypothesis.txt> | --dir <refDir> <hypDir>"); return }
    val ref = File(args[0]).readText(); val hyp = File(args[1]).readText()
    println("WER ${pct(ErrorRates.wer(ref, hyp))}  CER ${pct(ErrorRates.cer(ref, hyp))}")
}

private fun pct(s: EditStats): String = s.rate?.let { "%.1f%% (S${s.substitutions} D${s.deletions} I${s.insertions} / ${s.refLength})".format(it * 100) } ?: "n/a"
