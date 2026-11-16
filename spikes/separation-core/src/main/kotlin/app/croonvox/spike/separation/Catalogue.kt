// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation

import app.croonvox.spike.separation.model.DemucsConfig
import app.croonvox.spike.separation.model.DemucsSeparator
import app.croonvox.spike.separation.model.MdxConfig
import app.croonvox.spike.separation.model.MdxSeparator
import app.croonvox.spike.separation.model.OrtTensorModel
import app.croonvox.spike.separation.model.PrimaryStem
import app.croonvox.spike.separation.model.RuntimeOptions
import app.croonvox.spike.separation.model.SpleeterSeparator
import java.io.File

/** The trade-off a candidate stands for (ADR 3). */
enum class Tier { Fast, Best }

/** Whether the weights may ship with, or be offered by, a GPL-3.0-or-later app (ADR 11). */
enum class LicenseStatus {
    /** A license file explicitly covers the weights. */
    Clear,

    /** The repo is permissively licensed, but no statement explicitly covers the weights. */
    NeedsConfirmation,

    /** No license at all: all rights reserved, not distributable. */
    None,
}

/** Where a candidate's files come from. [archive] is a `.tar.bz2` whose entries are flattened into the model dir. */
data class Download(val url: String, val bytes: Long, val archive: Boolean = false)

/**
 * One benchmark candidate. Its files live in `<models dir>/<id>/`. A null [download] means the
 * human converts the model (see `docs/spikes/separation-spike.md`) and imports the file.
 */
data class Candidate(
    val id: String,
    val name: String,
    val tier: Tier,
    val files: List<String>,
    val download: Download?,
    val weightsLicense: String,
    val licenseStatus: LicenseStatus,
    val notes: String,
    private val open: (dir: File, options: RuntimeOptions) -> SegmentSeparator,
) {
    fun isInstalled(dir: File) = files.all { File(dir, it).isFile }

    /** Bytes on disk of the installed files (what a user would download, uncompressed). */
    fun installedBytes(dir: File) = files.sumOf { File(dir, it).length() }

    fun open(dir: File, options: RuntimeOptions): SegmentSeparator = open.invoke(dir, options)
}

object Catalogue {
    private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models"
    private const val UVR = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models"

    private const val SPLEETER_LICENSE =
        "Spleeter repo: MIT; README says \"the code of Spleeter is MIT-licensed\" and is silent on the " +
            "pretrained weights (published in the same repo's releases). ONNX export by sherpa-onnx (Apache-2.0 tooling)."
    private const val UVR_LICENSE =
        "TRvlvr/model_repo has no license; the UVR GUI (MIT) does not cover the weights. Trained by the UVR team " +
            "(Anjok07, aufr33). All rights reserved by default."
    private const val DEMUCS_LICENSE =
        "facebookresearch/demucs: MIT (\"Demucs is released under the MIT license\"); weights are served by Meta " +
            "from dl.fbaipublicfiles.com without a separate license. ONNX export: sevagh/demucs.onnx (MIT)."

    private fun spleeter(id: String, name: String, suffix: String, download: Download, notes: String) = Candidate(
        id = id,
        name = name,
        tier = Tier.Fast,
        files = listOf("vocals$suffix.onnx", "accompaniment$suffix.onnx"),
        download = download,
        weightsLicense = SPLEETER_LICENSE,
        licenseStatus = LicenseStatus.NeedsConfirmation,
        notes = notes,
    ) { dir, options ->
        SpleeterSeparator(
            OrtTensorModel(File(dir, "vocals$suffix.onnx"), options),
            OrtTensorModel(File(dir, "accompaniment$suffix.onnx"), options),
        )
    }

    private fun mdx(id: String, name: String, tier: Tier, file: String, bytes: Long, config: MdxConfig, notes: String) = Candidate(
        id = id,
        name = name,
        tier = tier,
        files = listOf(file),
        download = Download("$UVR/$file", bytes),
        weightsLicense = UVR_LICENSE,
        licenseStatus = LicenseStatus.None,
        notes = notes,
    ) { dir, options -> MdxSeparator(OrtTensorModel(File(dir, file), options), config) }

    private fun demucs(id: String, name: String, file: String, notes: String) = Candidate(
        id = id,
        name = name,
        tier = Tier.Best,
        files = listOf(file),
        download = null,
        weightsLicense = DEMUCS_LICENSE,
        licenseStatus = LicenseStatus.NeedsConfirmation,
        notes = notes,
    ) { dir, options -> DemucsSeparator(OrtTensorModel(File(dir, file), options), DemucsConfig()) }

    val candidates: List<Candidate> = listOf(
        spleeter(
            "spleeter-2stems", "Spleeter 2-stems (fp32)", "",
            Download("$SHERPA/sherpa-onnx-spleeter-2stems.tar.bz2", 74_682_545, archive = true),
            "Two U-Nets (vocals, accompaniment), run on 11.9 s segments. Separates up to 11 kHz only; the " +
                "Instrumental keeps everything above (Instrumental = input − Vocal Stem).",
        ),
        spleeter(
            "spleeter-2stems-fp16", "Spleeter 2-stems (fp16 weights)", ".fp16",
            Download("$SHERPA/sherpa-onnx-spleeter-2stems-fp16.tar.bz2", 35_271_738, archive = true),
            "Same as fp32 with fp16 weights (float I/O). Half the download; CPU speed may differ either way.",
        ),
        mdx(
            "uvr-mdx-karaoke", "UVR MDX-Net Karaoke", Tier.Fast, "UVR_MDXNET_KARA.onnx", 29_704_436,
            MdxConfig(nFft = 6144, dimF = 2048, primary = PrimaryStem.Vocals, compensate = 1.035f),
            "Small MDX-Net, estimates the lead vocal only: backing vocals stay in the Instrumental. Separates up " +
                "to 14.7 kHz (dim_f 2048 of 3073 bins). 5.9 s segments.",
        ),
        mdx(
            "uvr-mdx-inst-hq3", "UVR MDX-Net Inst HQ 3", Tier.Best, "UVR-MDX-NET-Inst_HQ_3.onnx", 66_759_214,
            MdxConfig(nFft = 6144, dimF = 3072, primary = PrimaryStem.Instrumental, compensate = 1.022f),
            "Large MDX-Net, estimates the Instrumental (Vocal Stem = input − Instrumental). Full band " +
                "(dim_f 3072 of 3073 bins).",
        ),
        mdx(
            "uvr-mdx-inst-hq4", "UVR MDX-Net Inst HQ 4", Tier.Best, "UVR-MDX-NET-Inst_HQ_4.onnx", 59_074_342,
            MdxConfig(nFft = 5120, dimF = 2560, primary = PrimaryStem.Instrumental, compensate = 1.019f),
            "Successor of HQ 3 (n_fft 5120, dim_f 2560).",
        ),
        demucs(
            "htdemucs", "Demucs v4 htdemucs", "htdemucs.onnx",
            "Hybrid transformer, 4 sources (drums, bass, other, vocals); Vocal Stem = vocals, Instrumental = " +
                "input − vocals. 7.8 s segments. Convert with sevagh/demucs.onnx and import.",
        ),
        demucs(
            "htdemucs-ft-vocals", "Demucs v4 htdemucs_ft (vocals)", "htdemucs_ft_vocals.onnx",
            "The vocals specialist of the fine-tuned bag: same cost as htdemucs, better vocals. Convert with " +
                "`--ft-vocals` and import.",
        ),
    )

    fun byId(id: String) = candidates.first { it.id == id }
}
