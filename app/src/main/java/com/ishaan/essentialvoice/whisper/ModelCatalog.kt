package com.ishaan.essentialvoice.whisper

import android.content.Context
import com.ishaan.essentialvoice.Prefs
import java.io.File

/**
 * One downloadable file: the pair of a name and the exact size it must end up.
 *
 * The size is not decoration. [ModelDownloader] resumes with a Range request
 * and treats "the file is this many bytes" as the only proof a download
 * finished, so a wrong number here is a model that can never install.
 */
data class ModelVariant(val fileName: String, val bytes: Long, val downloadUrl: String? = null) {

    val sizeMb: Int get() = ((bytes + 500_000) / 1_000_000).toInt()

    val url: String get() = downloadUrl ?: "${ModelCatalog.BASE_URL}$fileName"

    fun file(context: Context): File = File(ModelCatalog.dir(context), fileName)

    fun isInstalled(context: Context): Boolean {
        val f = file(context)
        return f.isFile && f.length() == bytes
    }
}

/**
 * The quality toggle, expressed as settings that were measured on this
 * phone rather than guessed.
 *
 * [millisPer10s] is the wall time whisper.cpp took on a CMF Phone 2 Pro for an
 * eleven-second clip of clear speech at four threads — the number the tier card
 * shows, so a tier's cost in waiting is as visible as its cost in bytes.
 *
 * Anything larger than `small` was tried and rejected on this hardware:
 * `medium.en-q5_0` spends 19.6s in the encoder alone and `large-v3-turbo-q5_0`
 * spends 32.6s, both fixed costs that no thread count or beam setting moves.
 * Neither is a dictation tool. Quantised `small.en-q5_1` was also slower than
 * fp16 here (7.1s against 5.8s): the Dimensity does fp16 natively, so
 * dequantising costs time and buys nothing but disk.
 */
data class QualityTier(
    val id: String,
    val label: String,
    val sub: String,
    /** The model file to load. */
    val model: ModelVariant,
    /** >1 selects beam search; 1 means greedy sampling. */
    val beamSize: Int,
    /** Candidates the sampler keeps. */
    val bestOf: Int,
    val millisPer10s: Int,
    val isHinglish: Boolean = false,
) {
    /** Human reading of [millisPer10s]: "1.5s", "6s". */
    val waitLabel: String
        get() {
            val s = millisPer10s / 1000f
            return if (s < 3f) "%.1fs".format(s) else "${s.toInt()}s"
        }

    fun file(context: Context): File = model.file(context)
    fun isInstalled(context: Context): Boolean = model.isInstalled(context)
    val sizeMb: Int get() = model.sizeMb
}

object ModelCatalog {

    const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    const val DEFAULT_TIER_ID = "hinglish_fast"

    val tiers = listOf(
        QualityTier(
            id = "hinglish_fast",
            label = "Hinglish Fast",
            sub = "Whisper Small Multilingual (465 MB). High accuracy English & Hinglish in ~3-4 seconds.",
            model = ModelVariant(
                fileName = "ggml-small.bin",
                bytes = 487_601_967L,
                downloadUrl = "${BASE_URL}ggml-small.bin",
            ),
            beamSize = 1,
            bestOf = 1,
            millisPer10s = 3_000,
            isHinglish = true,
        ),
        QualityTier(
            id = "hinglish_instant",
            label = "Hinglish Instant",
            sub = "Whisper Base Multilingual (141 MB). Ultra-fast 1.5s transcription for everyday voice.",
            model = ModelVariant(
                fileName = "ggml-base.bin",
                bytes = 147_951_465L,
                downloadUrl = "${BASE_URL}ggml-base.bin",
            ),
            beamSize = 1,
            bestOf = 1,
            millisPer10s = 1_500,
            isHinglish = true,
        ),
        QualityTier(
            id = "balanced",
            label = "Balanced (EN)",
            sub = "The everyday English setting. Clean punctuation on ordinary speech.",
            model = ModelVariant("ggml-base.en.bin", 147_964_211L),
            beamSize = 1,
            bestOf = 1,
            millisPer10s = 1_800,
        ),
        QualityTier(
            id = "accurate",
            label = "Accurate (EN)",
            sub = "English Small model. Holds up to accents, background noise and technical words.",
            model = ModelVariant("ggml-small.en.bin", 487_614_201L),
            beamSize = 1,
            bestOf = 1,
            millisPer10s = 3_500,
        ),
        QualityTier(
            id = "fast",
            label = "Fast (EN)",
            sub = "Short commands and notes to self. English tiny model.",
            model = ModelVariant("ggml-tiny.en.bin", 77_704_715L),
            beamSize = 1,
            bestOf = 1,
            millisPer10s = 1_200,
        ),
    )

    fun byId(id: String): QualityTier = tiers.firstOrNull { it.id == id } ?: tiers.first { it.id == DEFAULT_TIER_ID }

    fun dir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /**
     * Everything on disk. Deduplicated by file, because two tiers share one
     * model — Maximum is Accurate searched harder, not a second download.
     */
    fun installedBytes(context: Context): Long =
        tiers.map { it.model }
            .distinctBy { it.fileName }
            .filter { it.isInstalled(context) }
            .sumOf { it.bytes }
}
