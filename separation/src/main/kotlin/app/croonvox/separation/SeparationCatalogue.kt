// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

import app.croonvox.core.model.SeparationModelId
import app.croonvox.separation.model.OrtTensorModel
import app.croonvox.separation.model.SpleeterSeparator
import java.io.File

/** A `.tar.bz2` holding a model's [files], pinned by its SHA-256 (ADR 15). */
data class ModelDownload(val url: String, val sha256: String, val bytes: Long, val files: List<String>)

/** One Separation Model of the catalogue: where its files come from and how to run it (ADR 3, ADR 15). */
class SeparationModel(
    val id: SeparationModelId,
    val name: String,
    /** Null when there is nothing to download. */
    val download: ModelDownload?,
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
        name = "Fast (Spleeter)",
        download = ModelDownload(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems.tar.bz2",
            sha256 = "e26401d9c1801f43c0229731d78d32c2e80085e3aceedeb25f29d2de5fa68ca2",
            bytes = 74_682_545,
            files = listOf("vocals.onnx", "accompaniment.onnx"),
        ),
    ) { dir ->
        SpleeterSeparator(OrtTensorModel(File(dir, "vocals.onnx")), OrtTensorModel(File(dir, "accompaniment.onnx")))
    }

    /** The model a newly downloaded Track is separated with. */
    val default: SeparationModel = spleeter2Stems

    val models: List<SeparationModel> = listOf(spleeter2Stems)
}
