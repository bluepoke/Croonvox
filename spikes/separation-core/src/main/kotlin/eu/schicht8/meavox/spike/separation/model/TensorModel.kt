// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.model

/** A dense float tensor, row-major. */
class Tensor(val shape: LongArray, val data: FloatArray) {
    init {
        require(shape.fold(1L) { a, d -> a * d } == data.size.toLong()) {
            "shape ${shape.contentToString()} does not hold ${data.size} values"
        }
    }

    val rank: Int get() = shape.size
}

/**
 * One inference model: named float tensors in, named float tensors out. [OrtTensorModel] runs it on
 * ONNX Runtime; the model adapters only see this interface.
 */
interface TensorModel : AutoCloseable {
    /** Declared input shapes; −1 marks a dynamic dimension. */
    val inputs: Map<String, LongArray>

    /** Declared output shapes; −1 marks a dynamic dimension. */
    val outputs: Map<String, LongArray>

    fun run(inputs: Map<String, Tensor>): Map<String, Tensor>
}

/** Fails early, with a readable message, when a model file doesn't fit its catalogue entry. */
internal fun checkShape(what: String, declared: LongArray, expected: LongArray) {
    val fits = declared.size == expected.size &&
        declared.indices.all { declared[it] < 0 || declared[it] == expected[it] }
    require(fits) { "$what: model declares ${declared.contentToString()}, expected ${expected.contentToString()}" }
}
