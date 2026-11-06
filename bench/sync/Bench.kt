// Sync bench: runs the app's own LyricsWordAlign.placeLinesDetailed and RefinePolicy.compute
// (compiled unmodified from shared/) on one song's heard words. No app code is changed.
//
// args: <dir> <offsetMs>...
// <dir>/lines.tsv : "<groundTruthStartMs>\t<line text>"
// <dir>/heard.tsv : "<word>\t<startMs>\t<endMs>"
// writes <dir>/out.tsv : P rows (placement) and R rows (refine on a sheet shifted by offsetMs)
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.lyrics.RefinePolicy
import java.io.File

fun main(args: Array<String>) {
    val dir = File(args[0])
    val offsets = args.drop(1).map { it.toLong() }
    val gt = File(dir, "lines.tsv").readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map {
        val tab = it.indexOf('\t')
        it.substring(0, tab).toLong() to it.substring(tab + 1)
    }
    val heard = File(dir, "heard.tsv").readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map {
        val p = it.split('\t')
        LyricsWordAlign.Heard(p[0], p[1].toLong(), p[2].toLong())
    }
    val out = StringBuilder()
    val placed = LyricsWordAlign.placeLinesDetailed(gt.map { it.second }, heard)
    out.append("A\t").append(placed.attempted).append('\n')
    placed.starts.forEachIndexed { i, s ->
        out.append("P\t").append(i).append('\t').append(s ?: "null").append('\t')
            .append(placed.matchedShare.getOrNull(i) ?: 0.0).append('\n')
    }
    for (off in offsets) {
        val sheet = gt.map { LyricLine(timeMs = it.first + off, text = it.second) }
        val r = RefinePolicy.compute(sheet, heard)
        out.append("R\t").append(off).append('\t').append(r.applied).append('\t').append(r.medianMs)
            .append('\t').append(r.madMs).append('\t').append(r.model).append('\t').append(r.lines)
            .append('\t').append(r.slopePerMin).append('\n')
    }
    File(dir, "out.tsv").writeText(out.toString(), Charsets.UTF_8)
}
