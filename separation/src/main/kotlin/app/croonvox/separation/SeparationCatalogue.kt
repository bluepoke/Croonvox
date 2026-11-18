// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

import app.croonvox.core.model.SeparationModelId
import app.croonvox.separation.model.MdxConfig
import app.croonvox.separation.model.MdxSeparator
import app.croonvox.separation.model.OrtTensorModel
import app.croonvox.separation.model.PrimaryStem
import app.croonvox.separation.model.SpleeterSeparator
import java.io.File

/**
 * Where a model's [files] come from, pinned by its SHA-256 (ADR 15): a `.tar.bz2` holding them,
 * or, if not [archive], the model's single file itself.
 */
data class ModelDownload(val url: String, val sha256: String, val bytes: Long, val files: List<String>, val archive: Boolean = true) {
    init {
        require(archive || files.size == 1) { "a plain download is exactly one file" }
    }
}

/**
 * One Separation Model of the catalogue: where its files come from, how to run it, and what the
 * model picker tells about it (ADR 3, ADR 15, ADR 19).
 */
class SeparationModel(
    val id: SeparationModelId,
    val name: String,
    /** Null when there is nothing to download. */
    val download: ModelDownload?,
    /** Separation time per second of audio on the weakest measured device (ADR 15): the picker's expected duration. */
    val realTimeFactor: Float = 1f,
    /** Backing vocals stay in the Instrumental; otherwise they are removed with the lead vocal. */
    val keepsBackingVocals: Boolean = true,
    private val open: (dir: File) -> SegmentSeparator,
) {
    /** Loads the model from its installed files in [dir]. */
    fun open(dir: File): SegmentSeparator = open.invoke(dir)
}

/** The Separation Models the app offers (ADR 15). */
object SeparationCatalogue {
    /**
     * Fast: Deezer Spleeter 2-stems (fp32), as exported to ONNX by sherpa-onnx. Separates up to
     * 11 kHz; backing vocals stay in the Instrumental.
     *
     * Downloaded from the sherpa-onnx release until the project hosts its own copy (ADR 15, #21);
     * the checksum keeps the file from changing under the app.
     */
    val spleeter2Stems = SeparationModel(
        id = SeparationModelId("spleeter-2stems"),
        name = "Fast",
        download = ModelDownload(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems.tar.bz2",
            sha256 = "e26401d9c1801f43c0229731d78d32c2e80085e3aceedeb25f29d2de5fa68ca2",
            bytes = 74_682_545,
            files = listOf("vocals.onnx", "accompaniment.onnx"),
        ),
        realTimeFactor = 0.16f,
        keepsBackingVocals = true,
    ) { dir ->
        SpleeterSeparator(OrtTensorModel(File(dir, "vocals.onnx")), OrtTensorModel(File(dir, "accompaniment.onnx")))
    }

    /**
     * Medium: UVR MDX-Net Karaoke (ADR 19). Estimates the lead vocal only, so backing vocals stay
     * in the Instrumental; separates up to 14.7 kHz. Its STFT parameters aren't in the ONNX file.
     *
     * Downloaded from the UVR model repository until the project hosts its own copy, though its
     * weights have no license yet (ADR 19, note of #17); the checksum keeps the file from changing.
     */
    val uvrKaraoke = SeparationModel(
        id = SeparationModelId("uvr-mdx-karaoke"),
        name = "Medium",
        download = uvrDownload("UVR_MDXNET_KARA.onnx", "e3167c87333a48548413e972a286bf40bf5694001d2853861eb1435953f02d63", 29_704_436),
        realTimeFactor = 1.34f,
        keepsBackingVocals = true,
    ) { dir ->
        MdxSeparator(
            OrtTensorModel(File(dir, "UVR_MDXNET_KARA.onnx")),
            MdxConfig(nFft = 6144, dimF = 2048, primary = PrimaryStem.Vocals, compensate = 1.035f),
        )
    }

    /**
     * Best: UVR MDX-Net Inst HQ 4 (ADR 15). Estimates the Instrumental without any vocals, so
     * backing vocals are removed too; full band. Downloaded like [uvrKaraoke], with the same caveat.
     */
    val uvrInstHq4 = SeparationModel(
        id = SeparationModelId("uvr-mdx-inst-hq4"),
        name = "Best",
        download = uvrDownload("UVR-MDX-NET-Inst_HQ_4.onnx", "3c4b5b9b05090fdf238f38ba5046813982d50e2a652e9cb3324ea79720c3c9c8", 59_074_342),
        realTimeFactor = 3.4f,
        keepsBackingVocals = false,
    ) { dir ->
        MdxSeparator(
            OrtTensorModel(File(dir, "UVR-MDX-NET-Inst_HQ_4.onnx")),
            MdxConfig(nFft = 5120, dimF = 2560, primary = PrimaryStem.Instrumental, compensate = 1.019f),
        )
    }

    /** The model Separation falls back to when no Default Separation Model is set, or it left the catalogue (ADR 19). */
    val default: SeparationModel = spleeter2Stems

    /** Fastest first, as the model picker lists them. */
    val models: List<SeparationModel> = listOf(spleeter2Stems, uvrKaraoke, uvrInstHq4)

    fun byId(id: SeparationModelId): SeparationModel? = models.firstOrNull { it.id == id }

    private fun uvrDownload(file: String, sha256: String, bytes: Long) = ModelDownload(
        url = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/$file",
        sha256 = sha256,
        bytes = bytes,
        files = listOf(file),
        archive = false,
    )
}
