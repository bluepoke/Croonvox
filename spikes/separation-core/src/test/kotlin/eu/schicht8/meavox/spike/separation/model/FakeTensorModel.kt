// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.model

/** A [TensorModel] with one input and one output, computed by [compute]; remembers what it was fed. */
class FakeTensorModel(
    override val inputs: Map<String, LongArray> = mapOf("input" to longArrayOf(-1, -1, -1, -1)),
    override val outputs: Map<String, LongArray> = mapOf("output" to longArrayOf(-1, -1, -1, -1)),
    private val compute: (Tensor) -> Tensor,
) : TensorModel {
    var lastInputs: Map<String, Tensor> = emptyMap()
        private set

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        lastInputs = inputs
        return mapOf(outputs.keys.single() to compute(inputs.values.single()))
    }

    override fun close() {}
}

/** Element of a row-major tensor. */
operator fun Tensor.get(vararg index: Int): Float {
    require(index.size == shape.size) { "rank ${shape.size}, got ${index.size} indices" }
    var flat = 0L
    for (d in index.indices) flat = flat * shape[d] + index[d]
    return data[flat.toInt()]
}
